/*
 * Copyright (2021) The Delta Lake Project Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.delta

import java.io.File
import java.util.UUID

import org.apache.spark.sql.delta.actions.{Action, AddFile, DeletionVectorDescriptor, RemoveFile}
import org.apache.spark.sql.delta.deletionvectors.{RoaringBitmapArray, RoaringBitmapArrayFormat}
import org.apache.spark.sql.delta.sources.DeltaSQLConf
import org.apache.spark.sql.delta.storage.dv.DeletionVectorStore
import org.apache.spark.sql.delta.util.PathWithFileSystem
import org.apache.hadoop.fs.Path

import org.apache.spark.sql.{DataFrame, QueryTest}
import org.apache.spark.sql.test.SharedSparkSession

/** Collection of test utilities related with persistent Deletion Vectors. */
trait DeletionVectorsTestUtils extends QueryTest with SharedSparkSession {

  /** Run a thunk with Deletion Vectors enabled/disabled. */
  def withDeletionVectorsEnabled(enabled: Boolean = true)(thunk: => Unit): Unit = {
    val enabledStr = enabled.toString
    withSQLConf(
      DeltaConfigs.ENABLE_DELETION_VECTORS_CREATION.defaultTablePropertyKey -> enabledStr
    ) {
      thunk
    }
  }

  /** Helper to run 'fn' with a temporary Delta table. */
  def withTempDeltaTable(
      dataDF: DataFrame,
      partitionBy: Seq[String] = Seq.empty,
      enableDVs: Boolean = true)(fn: (io.delta.tables.DeltaTable, DeltaLog) => Unit): Unit = {
    withTempPath { path =>
      val tablePath = new Path(path.getAbsolutePath)
      dataDF.write
        .option(DeltaConfigs.ENABLE_DELETION_VECTORS_CREATION.key, enableDVs.toString)
        .partitionBy(partitionBy: _*)
        .format("delta")
        .save(tablePath.toString)
      val targetTable = io.delta.tables.DeltaTable.forPath(tablePath.toString)
      val targetLog = DeltaLog.forTable(spark, tablePath)
      fn(targetTable, targetLog)
    }
  }

  /** Helper that verifies whether a defined number of DVs exist */
  def verifyDVsExist(targetLog: DeltaLog, filesWithDVsSize: Int): Unit = {
    val filesWithDVs = getFilesWithDeletionVectors(targetLog)
    assert(filesWithDVs.size === filesWithDVsSize)
    assertDeletionVectorsExist(targetLog, filesWithDVs)
  }

  /** Returns all [[AddFile]] actions of a Delta table that contain Deletion Vectors. */
  def getFilesWithDeletionVectors(log: DeltaLog): Seq[AddFile] =
    log.unsafeVolatileSnapshot.allFiles.collect().filter(_.deletionVector != null).toSeq

  /** Helper to check that the Deletion Vectors of the provided file actions exist on disk. */
  def assertDeletionVectorsExist(log: DeltaLog, filesWithDVs: Seq[AddFile]): Unit = {
    val tablePath = new Path(log.dataPath.toUri.getPath)
    for (file <- filesWithDVs) {
      val dv = file.deletionVector
      assert(dv != null)
      assert(dv.isOnDisk && !dv.isInline)
      assert(dv.offset.isDefined)

      // Check that DV exists.
      val dvPath = dv.absolutePath(tablePath)
      val dvPathStr = DeletionVectorStore.pathToString(dvPath)
      assert(new File(dvPathStr).exists(), s"DV not found $dvPath")

      // Check that cardinality is correct.
      val bitmap = newDVStore.read(dvPath, dv.offset.get, dv.sizeInBytes)
      assert(dv.cardinality === bitmap.cardinality)
    }
  }

  // ======== HELPER METHODS TO WRITE DVs ==========
  protected def serializeRoaringBitmapArrayWithDefaultFormat(
      dv: RoaringBitmapArray): Array[Byte] = {
    val serializationFormat = RoaringBitmapArrayFormat.Portable
    dv.serializeAsByteArray(serializationFormat)
  }

  /**
   * Produce a new [[AddFile]] that will store `dv` in the log using default settings for choosing
   * inline or on-disk storage.
   *
   * Also returns the corresponding [[RemoveFile]] action for `currentFile`.
   *
   * TODO: Always on-disk for now. Inline support comes later.
   */
  protected def writeFileWithDV(
      log: DeltaLog,
      currentFile: AddFile,
      dv: RoaringBitmapArray): Seq[Action] = {
    writeFileWithDVOnDisk(log, currentFile, dv)
  }

  /**
   * Produce a new [[AddFile]] that will reference the `dv` in the log while storing it on-disk.
   *
   * Also returns the corresponding [[RemoveFile]] action for `currentFile`.
   */
  protected def writeFileWithDVOnDisk(
      log: DeltaLog,
      currentFile: AddFile,
      dv: RoaringBitmapArray): Seq[Action] = writeFilesWithDVsOnDisk(log, Seq((currentFile, dv)))

  protected def withDVWriter[T](
      log: DeltaLog,
      dvFileID: UUID)(fn: DeletionVectorStore.Writer => T): T = {
    val dvStore = newDVStore
    // scalastyle:off deltahadoopconfiguration
    val conf = spark.sessionState.newHadoopConf()
    // scalastyle:on deltahadoopconfiguration
    val tableWithFS = PathWithFileSystem.withConf(log.dataPath, conf)
    val dvPath =
      DeletionVectorStore.assembleDeletionVectorPathWithFileSystem(tableWithFS, dvFileID)
    val writer = dvStore.createWriter(dvPath)
    try {
      fn(writer)
    } finally {
      writer.close()
    }
  }

  /**
   * Produce new [[AddFile]] actions that will reference associated DVs in the log while storing
   * all DVs in the same file on-disk.
   *
   * Also returns the corresponding [[RemoveFile]] actions for the original file entries.
   */
  protected def writeFilesWithDVsOnDisk(
      log: DeltaLog,
      filesWithDVs: Seq[(AddFile, RoaringBitmapArray)]): Seq[Action] = {
    val dvFileId = UUID.randomUUID()
    withDVWriter(log, dvFileId) { writer =>
      filesWithDVs.flatMap { case (currentFile, dv) =>
        val range = writer.write(serializeRoaringBitmapArrayWithDefaultFormat(dv))
        val dvData = DeletionVectorDescriptor.onDiskWithRelativePath(
          id = dvFileId,
          sizeInBytes = range.length,
          cardinality = dv.cardinality,
          offset = Some(range.offset))
        val (add, remove) = currentFile.removeRows(
          dvData
        )
        Seq(add, remove)
      }
    }
  }

  /**
   * Removes the `numRowsToRemovePerFile` from each file via DV.
   * Returns the total number of rows removed.
   */
  protected def removeRowsFromAllFilesInLog(
      log: DeltaLog,
      numRowsToRemovePerFile: Long): Long = {
    var numFiles: Option[Int] = None
    // This is needed to make the manual commit work correctly, since we are not actually
    // running a command that produces metrics.
    withSQLConf(DeltaSQLConf.DELTA_HISTORY_METRICS_ENABLED.key -> "false") {
      val txn = log.startTransaction()
      val allAddFiles = txn.snapshot.allFiles.collect()
      numFiles = Some(allAddFiles.length)
      val bitmap = RoaringBitmapArray(0L until numRowsToRemovePerFile: _*)
      val actions = allAddFiles.flatMap { file =>
        if (file.numPhysicalRecords.isDefined) {
          // Only when stats are enabled. Can't check when stats are disabled
          assert(file.numPhysicalRecords.get > numRowsToRemovePerFile)
        }
        writeFileWithDV(log, file, bitmap)
      }
      txn.commit(actions, DeltaOperations.Delete(predicate = Seq.empty))
    }
    numFiles.get * numRowsToRemovePerFile
  }

  def newDVStore(): DeletionVectorStore = {
    // scalastyle:off deltahadoopconfiguration
    DeletionVectorStore.createInstance(spark.sessionState.newHadoopConf())
    // scalastyle:on deltahadoopconfiguration
  }
}
