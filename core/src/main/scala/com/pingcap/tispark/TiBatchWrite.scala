/*
 * Copyright 2019 PingCAP, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.pingcap.tispark

import java.sql.SQLException
import java.util

import com.google.protobuf.ByteString
import com.pingcap.tikv.allocator.RowIDAllocator
import com.pingcap.tikv.codec.{CodecDataOutput, KeyUtils, TableCodec}
import com.pingcap.tikv.exception.TiBatchWriteException
import com.pingcap.tikv.key.{IndexKey, Key, RowKey}
import com.pingcap.tikv.meta.{TiColumnInfo, TiDBInfo, TiTableInfo, _}
import com.pingcap.tikv.region.TiRegion
import com.pingcap.tikv.row.ObjectRowImpl
import com.pingcap.tikv.types.DataType.EncodeType
import com.pingcap.tikv.types.IntegerType
import com.pingcap.tikv.util.{BackOffer, ConcreteBackOffer}
import com.pingcap.tikv.{TTLManager, TiBatchWriteUtils, TiDBJDBCClient, _}
import com.pingcap.tispark.TiBatchWrite.TiRow
import com.pingcap.tispark.utils.TiUtil
import org.apache.spark.{Partitioner, SparkConf}
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.catalyst.analysis.NoSuchTableException
import org.apache.spark.sql.functions.lit
import org.apache.spark.sql.{DataFrame, Row, TiContext}
import org.slf4j.LoggerFactory

import scala.collection.JavaConverters._
import scala.collection.mutable

object TiBatchWrite {
  // Milliseconds
  private val MIN_DELAY_CLEAN_TABLE_LOCK = 60000
  private val DELAY_CLEAN_TABLE_LOCK_AND_COMMIT_BACKOFF_DELTA = 30000
  private val PRIMARY_KEY_COMMIT_BACKOFF = MIN_DELAY_CLEAN_TABLE_LOCK - DELAY_CLEAN_TABLE_LOCK_AND_COMMIT_BACKOFF_DELTA

  type SparkRow = org.apache.spark.sql.Row
  type TiRow = com.pingcap.tikv.row.Row
  type TiDataType = com.pingcap.tikv.types.DataType

  @throws(classOf[NoSuchTableException])
  @throws(classOf[TiBatchWriteException])
  def writeToTiDB(df: DataFrame, tiContext: TiContext, options: TiDBOptions): Unit =
    new TiBatchWrite(df, tiContext, options).write()
}

class TiBatchWrite(@transient val df: DataFrame,
                   @transient val tiContext: TiContext,
                   options: TiDBOptions)
    extends Serializable {
  private final val logger = LoggerFactory.getLogger(getClass.getName)

  import TiBatchWrite._

  private var tiConf: TiConfiguration = _
  @transient private var tiSession: TiSession = _

  private var tiTableRef: TiTableReference = _
  private var tiDBInfo: TiDBInfo = _
  private var tiTableInfo: TiTableInfo = _

  private var tableColSize: Int = _

  private var colsMapInTiDB: Map[String, TiColumnInfo] = _

  private var colsInDf: List[String] = _

  private var uniqueIndices: List[TiIndexInfo] = _
  private var handleCol: TiColumnInfo = _

  @transient private var tiDBJDBCClient: TiDBJDBCClient = _
  private var useTableLock: Boolean = _
  private var isEnableSplitRegion: Boolean = _
  private var tableLocked: Boolean = false

  @transient private var ttlManager: TTLManager = _
  private var isTTLUpdate: Boolean = _
  private var lockTTLSeconds: Long = _

  @throws(classOf[NoSuchTableException])
  @throws(classOf[TiBatchWriteException])
  private def write(): Unit = {
    try {
      doWrite()
    } finally {
      close()
    }
  }

  private def close(): Unit = {
    try {
      unlockTable()
    } catch {
      case _: Throwable =>
    }

    try {
      if (ttlManager != null) {
        ttlManager.close()
      }
    } catch {
      case _: Throwable =>
    }

    try {
      if (tiDBJDBCClient != null) {
        tiDBJDBCClient.close()
      }
    } catch {
      case _: Throwable =>
    }
  }

  private def mergeSparkConfWithDataSourceConf(conf: SparkConf,
                                               options: TiDBOptions): TiConfiguration = {
    val clonedConf = conf.clone()
    // priority: data source config > spark config
    clonedConf.setAll(options.parameters)
    TiUtil.sparkConfToTiConf(clonedConf)
  }

  @throws(classOf[NoSuchTableException])
  @throws(classOf[TiBatchWriteException])
  private def doWrite(): Unit = {
    // check if write enable
    if (!tiContext.tiConf.isWriteEnable) {
      throw new TiBatchWriteException(
        "tispark batch write is disabled! set spark.tispark.write.enable to enable."
      )
    }

    // initialize
    tiConf = mergeSparkConfWithDataSourceConf(tiContext.conf, options)
    tiSession = tiContext.tiSession
    tiTableRef = options.getTiTableRef(tiConf)
    tiDBInfo = tiSession.getCatalog.getDatabase(tiTableRef.databaseName)
    tiTableInfo = tiSession.getCatalog.getTable(tiTableRef.databaseName, tiTableRef.tableName)

    if (tiTableInfo == null) {
      throw new NoSuchTableException(tiTableRef.databaseName, tiTableRef.tableName)
    }

    colsMapInTiDB = tiTableInfo.getColumns.asScala.map(col => col.getName -> col).toMap
    colsInDf = df.columns.toList.map(_.toLowerCase())
    uniqueIndices = tiTableInfo.getIndices.asScala.filter(index => index.isUnique).toList
    handleCol = tiTableInfo.getPKIsHandleColumn
    tableColSize = tiTableInfo.getColumns.size()

    val tikvSupportUpdateTTL = StoreVersion.minTiKVVersion("3.0.5", tiSession.getPDClient)
    isTTLUpdate = options.isTTLUpdate(tikvSupportUpdateTTL)
    lockTTLSeconds = options.getLockTTLSeconds(tikvSupportUpdateTTL)

    // check unsupported
    checkUnsupported()

    // cache data
    df.persist(org.apache.spark.storage.StorageLevel.MEMORY_AND_DISK)

    // check empty
    if (TiUtil.isDataFrameEmpty(df)) {
      logger.warn("data is empty!")
      return
    }

    // lock table
    tiDBJDBCClient = new TiDBJDBCClient(TiDBUtils.createConnectionFactory(options.url)())
    useTableLock = {
      if (!options.useTableLock) {
        false
      } else {
        if (tiDBJDBCClient.isEnableTableLock) {
          if (tiDBJDBCClient.getDelayCleanTableLock >= MIN_DELAY_CLEAN_TABLE_LOCK) {
            true
          } else {
            logger.warn(
              s"table lock disabled! to enable table lock, please set tidb config: delay-clean-table-lock >= $MIN_DELAY_CLEAN_TABLE_LOCK"
            )
            false
          }
        } else {
          false
        }
      }
    }
    if (!useTableLock) {
      logger.warn(
        s"table lock disabled! to enable table lock, please set tidb config: enable-table-lock = true"
      )
    }

    isEnableSplitRegion = tiDBJDBCClient.isEnableSplitRegion
    lockTable()

    // check schema
    checkColumnNumbers()

    // auto increment
    val rdd = if (tiTableInfo.hasAutoIncrementColumn) {
      val isProvidedID = tableColSize == colsInDf.length
      val autoIncrementColName = tiTableInfo.getAutoIncrementColInfo.getName

      // when auto increment column is provided but the corresponding column in df contains null,
      // we need throw exception
      if (isProvidedID) {
        if (!df.columns.contains(autoIncrementColName)) {
          throw new TiBatchWriteException(
            "Column size is matched but cannot find auto increment column by name"
          )
        }

        val colOffset =
          colsInDf.zipWithIndex.find(col => autoIncrementColName.equals(col._1)).get._2
        val hasNullValue = df
          .filter(row => row.get(colOffset) == null)
          .count() > 0
        if (hasNullValue) {
          throw new TiBatchWriteException(
            "cannot allocate id on the condition of having null value " +
              "and valid value on auto increment column"
          )
        }
        df.rdd
      } else {
        // if auto increment column is not provided, we need allocate id for it.
        // adding an auto increment column to df
        val newDf = df.withColumn(autoIncrementColName, lit(null).cast("long"))
        val start = getAutoTableIdStart(df.count)

        // update colsInDF since we just add one column in df
        colsInDf = newDf.columns.toList
        // last one is auto increment column
        newDf.rdd.zipWithIndex.map { row =>
          val rowSep = row._1.toSeq.zipWithIndex.map { data =>
            val colOffset = data._2
            if (colsMapInTiDB.contains(colsInDf(colOffset))) {
              if (colsMapInTiDB(colsInDf(colOffset)).isAutoIncrement) {
                row._2 + start
              } else {
                data._1
              }
            }
          }
          Row.fromSeq(rowSep)
        }
      }
    } else {
      df.rdd
    }

    // spark row -> tikv row
    val tiRowRdd = rdd.map(row => sparkRow2TiKVRow(row))

    // check value not null
    checkValueNotNull(tiRowRdd)

    // get timestamp as start_ts
    val startTimeStamp = tiSession.getTimestamp
    val startTs = startTimeStamp.getVersion
    logger.info(s"startTS: $startTs")

    // for partition table, we need calculate each row and tell which physical table
    // that row is belong to.
    // currently we only support replace and insert.
    val constraintCheckIsNeeded = handleCol != null || uniqueIndices.nonEmpty

    val encodedTiRowRDD = if (constraintCheckIsNeeded) {
      val wrappedRowRdd = if (tiTableInfo.isPkHandle) {
        tiRowRdd.map { row =>
          WrappedRow(row, extractHandleId(row))
        }
      } else {
        val start = getAutoTableIdStart(tiRowRdd.count)
        tiRowRdd.zipWithIndex.map { data =>
          WrappedRow(data._1, data._2 + start)
        }
      }

      val distinctWrappedRowRdd = deduplicate(wrappedRowRdd)

      val deletion = generateDataToBeRemovedRdd(distinctWrappedRowRdd, startTimeStamp)
      if (!options.replace && !deletion.isEmpty()) {
        throw new TiBatchWriteException("data to be inserted has conflicts with TiKV data")
      }

      val wrappedEncodedRdd = generateKV(distinctWrappedRowRdd, remove = false)
      splitTableRegion(wrappedEncodedRdd.filter(r => !r.isIndex))
      splitIndexRegion(wrappedEncodedRdd.filter(r => r.isIndex))

      val mergedRDD = wrappedEncodedRdd ++ generateKV(deletion, remove = true)
      mergedRDD
        .map(wrappedEncodedRow => (wrappedEncodedRow.encodedKey, wrappedEncodedRow))
        .groupByKey()
        .map {
          case (key, iterable) =>
            // if rdd contains same key, it means we need first delete the old value and insert the new value associated the
            // key. We can merge the two operation into one update operation.
            // Note: the deletion operation's value of kv pair is empty.
            iterable.find(value => value.encodedValue.nonEmpty) match {
              case Some(wrappedEncodedRow) =>
                WrappedEncodedRow(
                  wrappedEncodedRow.row,
                  wrappedEncodedRow.handle,
                  wrappedEncodedRow.encodedKey,
                  wrappedEncodedRow.encodedValue,
                  isIndex = wrappedEncodedRow.isIndex,
                  wrappedEncodedRow.indexId,
                  remove = false
                )
              case None =>
                WrappedEncodedRow(
                  iterable.head.row,
                  iterable.head.handle,
                  key,
                  new Array[Byte](0),
                  isIndex = iterable.head.isIndex,
                  iterable.head.indexId,
                  remove = true
                )
            }
        }
    } else {
      val start = getAutoTableIdStart(tiRowRdd.count)
      val wrappedRowRdd = tiRowRdd.zipWithIndex.map { row =>
        WrappedRow(row._1, row._2 + start)
      }

      val wrappedEncodedRdd = generateKV(wrappedRowRdd, remove = false)
      splitTableRegion(wrappedEncodedRdd.filter(r => !r.isIndex))
      splitIndexRegion(wrappedEncodedRdd.filter(r => r.isIndex))

      wrappedEncodedRdd
    }

    val encodedKVPairRDD = encodedTiRowRDD.map(
      row => EncodedKVPair(row.encodedKey, row.encodedValue)
    )
    // shuffle data in same task which belong to same region
    val shuffledRDD = shuffleKeyToSameRegion(encodedKVPairRDD).cache()

    // take one row as primary key
    val (primaryKey: SerializableKey, primaryRow: Array[Byte]) = {
      val takeOne = shuffledRDD.take(1)
      if (takeOne.length == 0) {
        logger.warn("there is no data in source rdd")
        return
      } else {
        takeOne(0)
      }
    }

    logger.info(s"primary key: $primaryKey")

    // filter primary key
    val secondaryKeysRDD = shuffledRDD.filter { keyValue =>
      !keyValue._1.equals(primaryKey)
    }

    // driver primary pre-write
    val ti2PCClient = new TwoPhaseCommitter(tiConf, startTs, lockTTLSeconds * 1000)
    val prewritePrimaryBackoff =
      ConcreteBackOffer.newCustomBackOff(BackOffer.BATCH_PREWRITE_BACKOFF)
    logger.info("start to prewritePrimaryKey")
    ti2PCClient.prewritePrimaryKey(prewritePrimaryBackoff, primaryKey.bytes, primaryRow)
    logger.info("prewritePrimaryKey success")

    // for test
    if (options.sleepAfterPrewritePrimaryKey > 0) {
      logger.info(s"sleep ${options.sleepAfterPrewritePrimaryKey} ms for test")
      Thread.sleep(options.sleepAfterPrewritePrimaryKey)
    }

    // start primary key ttl update
    if (isTTLUpdate) {
      ttlManager = new TTLManager(tiConf, startTs, primaryKey.bytes)
      ttlManager.keepAlive()
    }

    // executors secondary pre-write
    logger.info("start to prewriteSecondaryKeys")
    secondaryKeysRDD.foreachPartition { iterator =>
      val ti2PCClientOnExecutor =
        new TwoPhaseCommitter(tiConf, startTs, lockTTLSeconds * 1000)

      val pairs = iterator.map { keyValue =>
        new TwoPhaseCommitter.BytePairWrapper(
          keyValue._1.bytes,
          keyValue._2
        )
      }.asJava

      ti2PCClientOnExecutor.prewriteSecondaryKeys(primaryKey.bytes, pairs)

      try {
        ti2PCClientOnExecutor.close()
      } catch {
        case _: Throwable =>
      }
    }
    logger.info("prewriteSecondaryKeys success")

    // for test
    if (options.sleepAfterPrewriteSecondaryKey > 0) {
      logger.info(s"sleep ${options.sleepAfterPrewriteSecondaryKey} ms for test")
      Thread.sleep(options.sleepAfterPrewriteSecondaryKey)
    }

    // driver primary commit
    val commitTs = tiSession.getTimestamp.getVersion
    // check commitTS
    if (commitTs <= startTs) {
      throw new TiBatchWriteException(
        s"invalid transaction tso with startTs=$startTs, commitTs=$commitTs"
      )
    }

    if (!useTableLock && isSchemaChanged()) {
      throw new TiBatchWriteException("schema has changed during prewrite!")
    }

    // for test
    if (options.sleepAfterGetCommitTS > 0) {
      logger.info(s"sleep ${options.sleepAfterGetCommitTS} ms for test")
      Thread.sleep(options.sleepAfterGetCommitTS)
    }

    val commitPrimaryBackoff = ConcreteBackOffer.newCustomBackOff(PRIMARY_KEY_COMMIT_BACKOFF)

    if (connectionLost()) {
      throw new TiBatchWriteException("tidb's jdbc connection is lost!")
    }
    logger.info("start to commitPrimaryKey")
    ti2PCClient.commitPrimaryKey(commitPrimaryBackoff, primaryKey.bytes, commitTs)
    logger.info("commitPrimaryKey success")

    // stop primary key ttl update
    if (isTTLUpdate) {
      ttlManager.close()
    }

    // unlock table
    unlockTable()

    // executors secondary commit
    if (!options.skipCommitSecondaryKey) {
      logger.info("start to commitSecondaryKeys")
      secondaryKeysRDD.foreachPartition { iterator =>
        val ti2PCClientOnExecutor = new TwoPhaseCommitter(tiConf, startTs)

        val keys = iterator.map { keyValue =>
          new TwoPhaseCommitter.ByteWrapper(keyValue._1.bytes)
        }.asJava

        try {
          ti2PCClientOnExecutor.commitSecondaryKeys(keys, commitTs)
        } catch {
          case e: TiBatchWriteException =>
            // ignored
            logger.warn(s"commit secondary key error", e)
        }
      }
      logger.info("commitSecondaryKeys finish")
    } else {
      logger.info("skipping commit secondary key")
    }
  }

  private def isSchemaChanged(): Boolean = {
    val newTableInfo = tiSession.getCatalog.getTable(tiTableRef.databaseName, tiTableRef.tableName)
    tiTableInfo.getUpdateTimestamp < newTableInfo.getUpdateTimestamp
  }

  private def getAutoTableIdStart(step: Long): Long = {
    RowIDAllocator
      .create(
        tiDBInfo.getId,
        tiTableInfo.getId,
        tiConf,
        tiTableInfo.isAutoIncColUnsigned,
        step
      )
      .getStart
  }

  @throws(classOf[TiBatchWriteException])
  private def lockTable(): Unit = {
    if (useTableLock) {
      if (!tableLocked) {
        tiDBJDBCClient.lockTableWriteLocal(options.database, options.table)
        tableLocked = true
      } else {
        logger.warn("table already locked!")
      }
    } else {
      if (tiContext.tiConf.isWriteWithoutLockTable) {
        logger.warn("write without lock table enabled! only for test!")
      } else {
        throw new TiBatchWriteException("current tidb does not support LockTable or is disabled!")
      }
    }
  }

  @throws(classOf[TiBatchWriteException])
  private def unlockTable(): Unit = {
    if (useTableLock) {
      if (tableLocked) {
        tiDBJDBCClient.unlockTables()
        tableLocked = false
      }
    } else {
      if (tiContext.tiConf.isWriteWithoutLockTable) {
        logger.warn("write without lock table enabled! only for test!")
      } else {
        throw new TiBatchWriteException("current tidb does not support LockTable or is disabled!")
      }
    }
  }

  private def generateDataToBeRemovedRdd(rdd: RDD[WrappedRow],
                                         startTs: TiTimestamp): RDD[WrappedRow] = {
    rdd.mapPartitions { wrappedRows =>
      val snapshot = TiSession.getInstance(tiConf).createSnapshot(startTs)
      var rowBuf = mutable.ListBuffer.empty[WrappedRow]
      var rowBufIndex = 0

      new Iterator[WrappedRow] {
        override def hasNext: Boolean = {
          while (true) {
            if (!wrappedRows.hasNext && !(rowBufIndex < rowBuf.size)) {
              return false
            }

            if (rowBufIndex < rowBuf.size) {
              return true
            }

            processNextBatch()
          }
          assert(false)
          false
        }

        override def next(): WrappedRow = {
          if (hasNext) {
            rowBufIndex = rowBufIndex + 1
            rowBuf(rowBufIndex - 1)
          } else {
            null
          }
        }

        def getNextBatch(itor: Iterator[WrappedRow]): List[WrappedRow] = {
          val buf = mutable.ListBuffer.empty[WrappedRow]
          var i = 0
          while (itor.hasNext && i < options.snapshotBatchGetSize) {
            buf.append(itor.next())
            i = i + 1
          }
          buf.toList
        }

        def genNextHandleBatch(
          batch: List[WrappedRow]
        ): (util.List[Array[Byte]], util.Map[ByteString, Long]) = {
          val list = new util.ArrayList[Array[Byte]]()
          val map = new util.HashMap[ByteString, Long]()
          batch.foreach { wrappedRow =>
            val bytes = buildRowKey(wrappedRow.row, wrappedRow.handle).bytes
            val key = ByteString.copyFrom(bytes)
            list.add(bytes)
            map.put(key, wrappedRow.handle)
          }
          (list, map)
        }

        def genNextIndexBatch(
          batch: List[WrappedRow],
          index: TiIndexInfo
        ): (util.List[Array[Byte]], util.Map[ByteString, TiRow]) = {
          val list = new util.ArrayList[Array[Byte]]()
          val map = new util.HashMap[ByteString, TiRow]()
          batch.foreach { wrappedRow =>
            val bytes = buildUniqueIndexKey(wrappedRow.row, index).bytes
            val key = ByteString.copyFrom(bytes)
            list.add(bytes)
            map.put(key, wrappedRow.row)
          }
          (list, map)
        }

        def processNextBatch(): Unit = {
          rowBuf = mutable.ListBuffer.empty[WrappedRow]
          rowBufIndex = 0

          val batch = getNextBatch(wrappedRows)

          if (handleCol != null) {
            val (batchHandle, handleMap) = genNextHandleBatch(batch)
            val oldValueList = snapshot.batchGet(batchHandle)
            (0 until oldValueList.size()).foreach { i =>
              val oldValuePair = oldValueList.get(i)
              val oldValue = oldValuePair.getValue.toByteArray
              val key = oldValuePair.getKey
              val handle = handleMap.get(key)

              val oldRow = TableCodec.decodeRow(oldValue, handle, tiTableInfo)
              rowBuf += WrappedRow(oldRow, handle)
            }
          }

          val oldIndicesBatch: util.List[Array[Byte]] = new util.ArrayList[Array[Byte]]()
          val oldIndicesMap: mutable.HashMap[SerializableKey, Long] = new mutable.HashMap()
          uniqueIndices.foreach { index =>
            val (batchIndices, rowMap) = genNextIndexBatch(batch, index)
            val oldValueList = snapshot.batchGet(batchIndices)
            (0 until oldValueList.size()).foreach { i =>
              val oldValuePair = oldValueList.get(i)
              val oldValue = oldValuePair.getValue.toByteArray
              val key = oldValuePair.getKey
              val oldHandle = TableCodec.decodeHandle(oldValue)
              val tiRow = rowMap.get(key)

              oldIndicesBatch.add(buildRowKey(tiRow, oldHandle).bytes)
              oldIndicesMap.put(new SerializableKey(buildRowKey(tiRow, oldHandle).bytes), oldHandle)
            }
          }

          val oldIndicesRowPairs = snapshot.batchGet(oldIndicesBatch)
          (0 until oldIndicesRowPairs.size()).foreach { i =>
            val oldIndicesRowPair = oldIndicesRowPairs.get(i)
            val oldRowKey = oldIndicesRowPair.getKey.toByteArray
            val oldRowValue = oldIndicesRowPair.getValue.toByteArray
            val oldHandle = oldIndicesMap.get(new SerializableKey(oldRowKey)).get
            val oldRow = TableCodec.decodeRow(oldRowValue, oldHandle, tiTableInfo)
            rowBuf += WrappedRow(oldRow, oldHandle)
          }
        }
      }
    }
  }

  private def connectionLost(): Boolean = {
    if (useTableLock) {
      tiDBJDBCClient.isClosed
    } else {
      // TODO: what if version of tidb does not support lock table
      false
    }
  }

  @throws(classOf[TiBatchWriteException])
  private def checkUnsupported(): Unit = {
    // write to partition table
    if (tiTableInfo.isPartitionEnabled) {
      throw new TiBatchWriteException(
        "tispark currently does not support write data to partition table!"
      )
    }

    // write to table with generated column
    if (tiTableInfo.hasGeneratedColumn) {
      throw new TiBatchWriteException(
        "tispark currently does not support write data to table with generated column!"
      )
    }
  }

  private def checkColumnNumbers(): Unit = {
    if (!tiTableInfo.hasAutoIncrementColumn && colsInDf.length != tableColSize) {
      throw new TiBatchWriteException(
        s"table without auto increment column, but data col size ${colsInDf.length} != table column size $tableColSize"
      )
    }

    if (tiTableInfo.hasAutoIncrementColumn && colsInDf.length != tableColSize && colsInDf.length != tableColSize - 1) {
      throw new TiBatchWriteException(
        s"table with auto increment column, but data col size ${colsInDf.length} != table column size $tableColSize and table column size - 1 ${tableColSize - 1} "
      )
    }
  }

  private def checkValueNotNull(rdd: RDD[TiRow]): Unit = {
    val nullRowCount = rdd
      .filter { row =>
        colsMapInTiDB.exists {
          case (_, v) =>
            if (v.getType.isNotNull && row.get(v.getOffset, v.getType) == null) {
              true
            } else {
              false
            }
        }
      }
      .count()

    if (nullRowCount > 0) {
      throw new TiBatchWriteException(
        s"Insert null value to not null column! $nullRowCount rows contain illegal null values!"
      )
    }
  }

  @throws(classOf[TiBatchWriteException])
  private def deduplicate(rdd: RDD[WrappedRow]): RDD[WrappedRow] = {
    //1 handle key
    var mutableRdd = rdd
    if (handleCol != null) {
      mutableRdd = mutableRdd
        .map { wrappedRow =>
          val rowKey = buildRowKey(wrappedRow.row, wrappedRow.handle)
          (rowKey, wrappedRow)
        }
        .groupByKey()
        .map(_._2.head)
    }
    uniqueIndices.foreach { index =>
      {
        mutableRdd = mutableRdd
          .map { wrappedRow =>
            val indexKey = buildUniqueIndexKey(wrappedRow.row, index)
            (indexKey, wrappedRow)
          }
          .groupByKey()
          .map(_._2.head)
      }
    }
    mutableRdd
  }

  @throws(classOf[NoSuchTableException])
  private def shuffleKeyToSameRegion(
    rdd: RDD[EncodedKVPair]
  ): RDD[(SerializableKey, Array[Byte])] = {
    val regions = getRegions
    assert(regions.size() > 0)
    val tiRegionPartitioner = new TiRegionPartitioner(regions, options.writeConcurrency)

    rdd
      .map(obj => (obj.encodedKey, obj.encodedValue))
      // remove duplicate rows if key equals (should not happen, cause already deduplicated)
      .reduceByKey(
        tiRegionPartitioner,
        (a: Array[Byte], _: Array[Byte]) => a
      )
  }

  private def getRegions: util.List[TiRegion] = {
    val regions = TiBatchWriteUtils.getRegionsByTable(tiSession, tiTableInfo)
    logger.info(
      s"find ${regions.size} regions in $tiTableRef tableId: ${tiTableInfo.getId}"
    )
    regions
  }

  private def extractHandleId(row: TiRow): Long =
    // If handle ID is changed when update, update will remove the old record first,
    // and then call `AddRecord` to add a new record.
    // Currently, only insert can set _tidb_rowid, update can not update _tidb_rowid.
    if (tiTableInfo.isPkHandle) {
      row
        .get(handleCol.getOffset, handleCol.getType)
        .asInstanceOf[java.lang.Long]
    } else {
      throw new TiBatchWriteException("cannot extract handle non pk is handle table")
    }

  // convert spark's row to tikv row. We do not allocate handle for no pk case.
  // allocating handle id will be finished after we check conflict.
  private def sparkRow2TiKVRow(sparkRow: SparkRow): TiRow = {
    val fieldCount = sparkRow.size
    val tiRow = ObjectRowImpl.create(fieldCount)
    for (i <- 0 until fieldCount) {
      // TODO: add tiDataType back
      tiRow.set(
        colsMapInTiDB(colsInDf(i)).getOffset,
        null,
        colsMapInTiDB(colsInDf(i)).getType.convertToTiDBType(sparkRow(i))
      )
    }
    tiRow
  }

  @throws(classOf[TiBatchWriteException])
  private def encodeTiRow(tiRow: TiRow): Array[Byte] = {
    val colSize = tiRow.fieldCount()

    if (colSize > tableColSize) {
      throw new TiBatchWriteException(
        s"data col size $colSize > table column size $tableColSize"
      )
    }

    // TODO: ddl state change
    // pending: https://internal.pingcap.net/jira/browse/TISPARK-82
    val convertedValues = new Array[AnyRef](colSize)
    for (i <- 0 until colSize) {
      // pk is handle can be skipped
      val columnInfo = tiTableInfo.getColumn(i)
      val value = tiRow.get(i, columnInfo.getType)
      convertedValues.update(i, value)
    }

    TableCodec.encodeRow(tiTableInfo.getColumns, convertedValues, tiTableInfo.isPkHandle)
  }

  // construct unique index and non-unique index and value to be inserted into TiKV
  // NOTE:
  //      pk is not handle case is equivalent to unique index.
  //      for non-unique index, handle will be encoded as part of index key. In contrast, unique
  //      index encoded handle to value.
  private def generateUniqueIndexKey(row: TiRow,
                                     handle: Long,
                                     index: TiIndexInfo,
                                     remove: Boolean): (SerializableKey, Array[Byte]) = {
    val indexKey = buildUniqueIndexKey(row, index)
    val value = if (remove) {
      new Array[Byte](0)
    } else {
      val cdo = new CodecDataOutput()
      cdo.writeLong(handle)
      cdo.toBytes
    }

    (indexKey, value)
  }

  private def generateSecondaryIndexKey(row: TiRow,
                                        handle: Long,
                                        index: TiIndexInfo,
                                        remove: Boolean): (SerializableKey, Array[Byte]) = {
    val keys = IndexKey.encodeIndexDataValues(row, index.getIndexColumns, tiTableInfo)
    val cdo = new CodecDataOutput()
    cdo.write(
      IndexKey.toIndexKey(locatePhysicalTable(row), index.getId, keys: _*).getBytes
    )
    IntegerType.BIGINT.encode(cdo, EncodeType.KEY, handle)
    val value: Array[Byte] = if (remove) {
      new Array[Byte](0)
    } else {
      val value = new Array[Byte](1)
      value(0) = '0'
      value
    }
    (new SerializableKey(cdo.toBytes), value)
  }

  private def buildRowKey(row: TiRow, handle: Long): SerializableKey = {
    new SerializableKey(RowKey.toRowKey(locatePhysicalTable(row), handle).getBytes)
  }

  private def buildUniqueIndexKey(row: TiRow, index: TiIndexInfo): SerializableKey = {
    val keys =
      IndexKey.encodeIndexDataValues(row, index.getIndexColumns, tiTableInfo)
    val indexKey =
      IndexKey.toIndexKey(locatePhysicalTable(row), index.getId, keys: _*)
    new SerializableKey(indexKey.getBytes)
  }

  private def generateRowKey(row: TiRow,
                             handle: Long,
                             remove: Boolean): (SerializableKey, Array[Byte]) = {
    if (remove) {
      (
        buildRowKey(row, handle),
        new Array[Byte](0)
      )
    } else {
      (
        new SerializableKey(RowKey.toRowKey(locatePhysicalTable(row), handle).getBytes),
        encodeTiRow(row)
      )
    }
  }

  private def generateKV(rdd: RDD[WrappedRow], remove: Boolean): RDD[WrappedEncodedRow] = {
    rdd
      .map { row =>
        {
          val kvBuf = mutable.ListBuffer.empty[WrappedEncodedRow]
          val (encodedKey, encodedValue) = generateRowKey(row.row, row.handle, remove)
          kvBuf += WrappedEncodedRow(
            row.row,
            row.handle,
            encodedKey,
            encodedValue,
            isIndex = false,
            -1,
            remove
          )
          tiTableInfo.getIndices.asScala.foreach { index =>
            if (index.isUnique) {
              val (encodedKey, encodedValue) =
                generateUniqueIndexKey(row.row, row.handle, index, remove)
              kvBuf += WrappedEncodedRow(
                row.row,
                row.handle,
                encodedKey,
                encodedValue,
                isIndex = true,
                index.getId,
                remove
              )
            } else {
              val (encodedKey, encodedValue) =
                generateSecondaryIndexKey(row.row, row.handle, index, remove)
              kvBuf += WrappedEncodedRow(
                row.row,
                row.handle,
                encodedKey,
                encodedValue,
                isIndex = true,
                index.getId,
                remove
              )
            }
          }
          kvBuf
        }
      }
      .flatMap(identity)
  }

  // TODO: support physical table later. Need use partition info and row value to
  // calculate the real physical table.
  private def locatePhysicalTable(row: TiRow): Long = {
    tiTableInfo.getId
  }

  private def estimateRegionSplitNum(wrappedEncodedRdd: RDD[WrappedEncodedRow]): Long = {
    val totalSize =
      wrappedEncodedRdd.map(r => r.encodedKey.bytes.length + r.encodedValue.length).sum()

    //TODO: replace 96 with actual value read from pd https://github.com/pingcap/tispark/issues/890
    Math.ceil(totalSize / (tiContext.tiConf.getTikvRegionSplitSizeInMB * 1024 * 1024)).toLong
  }

  private def checkTidbRegionSplitContidion(minHandle: Long,
                                            maxHandle: Long,
                                            regionSplitNum: Long): Boolean = {
    maxHandle - minHandle > regionSplitNum * 1000
  }

  private def splitIndexRegion(wrappedEncodedRdd: RDD[WrappedEncodedRow]): Unit = {
    if (options.enableRegionSplit && isEnableSplitRegion) {
      val indices = tiTableInfo.getIndices.asScala

      indices.foreach { index =>
        val colName = index.getIndexColumns.get(0).getName
        val tiColumn = tiTableInfo.getColumn(colName)
        val colOffset = tiColumn.getOffset
        val dataType = tiColumn.getType

        val ordering = new Ordering[WrappedEncodedRow] {
          override def compare(x: WrappedEncodedRow, y: WrappedEncodedRow): Int = {
            val xIndex = x.row.get(colOffset, dataType)
            val yIndex = y.row.get(colOffset, dataType)
            xIndex.toString.compare(yIndex.toString)
          }
        }

        val rdd = wrappedEncodedRdd.filter(_.indexId == index.getId)
        val regionSplitNum = if (options.regionSplitNum != 0) {
          options.regionSplitNum
        } else {
          estimateRegionSplitNum(rdd)
        }

        // region split
        if (regionSplitNum > 1) {
          val minIndexValue = rdd.min()(ordering).row.get(colOffset, dataType).toString
          val maxIndexValue = rdd.max()(ordering).row.get(colOffset, dataType).toString
          logger.info(
            s"index region split, regionSplitNum=$regionSplitNum, indexName=${index.getName}"
          )
          try {
            tiDBJDBCClient
              .splitIndexRegion(
                options.database,
                options.table,
                index.getName,
                minIndexValue,
                maxIndexValue,
                regionSplitNum
              )
          } catch {
            case e: SQLException =>
              if (options.isTest) {
                throw e
              }
          }
        } else {
          logger.warn(
            s"skip index split index, regionSplitNum=$regionSplitNum, indexName=${index.getName}"
          )
        }
      }
    }
  }

  // when data to be inserted is too small to do region split, we check is user set region split num.
  // If so, we do region split as user's intention. This is also useful for writing test case.
  // We assume the data to be inserted is ruled by normal distribution.
  private def splitTableRegion(wrappedRowRdd: RDD[WrappedEncodedRow]): Unit = {
    if (options.enableRegionSplit && isEnableSplitRegion) {
      if (options.regionSplitNum != 0) {
        try {
          tiDBJDBCClient
            .splitTableRegion(
              options.database,
              options.table,
              0,
              Int.MaxValue,
              options.regionSplitNum
            )
        } catch {
          case e: SQLException =>
            if (options.isTest) {
              throw e
            }
        }
      } else {
        val regionSplitNum = if (options.regionSplitNum != 0) {
          options.regionSplitNum
        } else {
          estimateRegionSplitNum(wrappedRowRdd)
        }
        // region split
        if (regionSplitNum > 1) {
          val minHandle = wrappedRowRdd.min().handle
          val maxHandle = wrappedRowRdd.max().handle
          if (checkTidbRegionSplitContidion(minHandle, maxHandle, regionSplitNum)) {
            logger.info(s"table region split is enabled, regionSplitNum=$regionSplitNum")
            try {
              tiDBJDBCClient
                .splitTableRegion(
                  options.database,
                  options.table,
                  minHandle,
                  maxHandle,
                  regionSplitNum
                )
            } catch {
              case e: SQLException =>
                if (options.isTest) {
                  throw e
                }
            }
          } else {
            logger.warn("table region split is skipped")
          }
        }
      }
    }
  }
}

class TiRegionPartitioner(regions: util.List[TiRegion], writeConcurrency: Int) extends Partitioner {
  def binarySearch(key: Key): Int = {
    if (regions.get(0).contains(key)) {
      return 0
    }
    var l = 0
    var r = regions.size()
    while (l < r) {
      val mid = l + (r - l) / 2
      val region = regions.get(mid)
      if (Key.toRawKey(region.getEndKey).compareTo(key) <= 0) {
        l = mid + 1
      } else {
        r = mid
      }
    }
    assert(regions.get(l).contains(key))
    l
  }

  override def numPartitions: Int = if (writeConcurrency <= 0) regions.size() else writeConcurrency

  override def getPartition(key: Any): Int = {
    val serializableKey = key.asInstanceOf[SerializableKey]
    val rawKey = Key.toRawKey(serializableKey.bytes)

    binarySearch(rawKey) % numPartitions
  }
}

case class WrappedRow(row: TiRow, handle: Long)

case class WrappedEncodedRow(row: TiRow,
                             handle: Long,
                             encodedKey: SerializableKey,
                             encodedValue: Array[Byte],
                             isIndex: Boolean,
                             indexId: Long,
                             remove: Boolean)
    extends Ordered[WrappedEncodedRow] {
  override def compare(that: WrappedEncodedRow): Int = this.handle.toInt - that.handle.toInt

  override def hashCode(): Int = encodedKey.hashCode()
}

// EncodedKVPair is used at last stage if write process.
// At this stage, sorting should not be happened anymore.
case class EncodedKVPair(encodedKey: SerializableKey, encodedValue: Array[Byte]) {
  override def hashCode(): Int = encodedKey.hashCode()
}

class SerializableKey(val bytes: Array[Byte]) extends Serializable {
  override def toString: String = KeyUtils.formatBytes(bytes)

  override def equals(that: Any): Boolean =
    that match {
      case that: SerializableKey => this.bytes.sameElements(that.bytes)
      case _                     => false
    }

  override def hashCode(): Int =
    util.Arrays.hashCode(bytes)
}
