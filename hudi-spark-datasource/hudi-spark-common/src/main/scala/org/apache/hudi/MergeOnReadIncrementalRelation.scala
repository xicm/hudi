/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hudi

import org.apache.hadoop.fs.{GlobPattern, Path}
import org.apache.hudi.HoodieConversionUtils.toScalaOption
import org.apache.hudi.common.model.{FileSlice, HoodieRecord}
import org.apache.hudi.common.table.HoodieTableMetaClient
import org.apache.hudi.common.table.timeline.{HoodieInstant, HoodieTimeline}
import org.apache.hudi.common.table.view.HoodieTableFileSystemView
import org.apache.hudi.common.util.StringUtils
import org.apache.hudi.exception.HoodieException
import org.apache.hudi.hadoop.utils.HoodieInputFormatUtils.{getCommitMetadata, getWritePartitionPaths, listAffectedFilesForCommits}
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.SQLContext
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions.Expression
import org.apache.spark.sql.sources._
import org.apache.spark.sql.types.StructType

import scala.collection.JavaConverters._
import scala.collection.immutable

/**
 * @Experimental
 */
class MergeOnReadIncrementalRelation(sqlContext: SQLContext,
                                     optParams: Map[String, String],
                                     userSchema: Option[StructType],
                                     metaClient: HoodieTableMetaClient)
  extends MergeOnReadSnapshotRelation(sqlContext, optParams, userSchema, Seq(), metaClient) with HoodieIncrementalRelationTrait {

  override type FileSplit = HoodieMergeOnReadFileSplit

  override def imbueConfigs(sqlContext: SQLContext): Unit = {
    super.imbueConfigs(sqlContext)
    sqlContext.sparkSession.sessionState.conf.setConfString("spark.sql.parquet.enableVectorizedReader", "false")
  }

  override protected def timeline: HoodieTimeline = {
    val startTimestamp = optParams(DataSourceReadOptions.BEGIN_INSTANTTIME.key)
    val endTimestamp = optParams.getOrElse(DataSourceReadOptions.END_INSTANTTIME.key, super.timeline.lastInstant().get.getTimestamp)
    super.timeline.findInstantsInRange(startTimestamp, endTimestamp)
  }

  protected override def composeRDD(fileSplits: Seq[HoodieMergeOnReadFileSplit],
                                    tableSchema: HoodieTableSchema,
                                    requiredSchema: HoodieTableSchema,
                                    requestedColumns: Array[String],
                                    filters: Array[Filter]): RDD[InternalRow] = {
    // The only required filters are ones that make sure we're only fetching records that
    // fall into incremental span of the timeline being queried
    val requiredFilters = incrementalSpanRecordFilters
    val optionalFilters = filters
    val readers = createBaseFileReaders(tableSchema, requiredSchema, requestedColumns, requiredFilters, optionalFilters)

    val hoodieTableState = getTableState
    // TODO(HUDI-3639) implement incremental span record filtering w/in RDD to make sure returned iterator is appropriately
    //                 filtered, since file-reader might not be capable to perform filtering
    new HoodieMergeOnReadRDD(
      sqlContext.sparkContext,
      config = jobConf,
      fileReaders = readers,
      tableSchema = tableSchema,
      requiredSchema = requiredSchema,
      tableState = hoodieTableState,
      mergeType = mergeType,
      fileSplits = fileSplits)
  }

  override protected def collectFileSplits(partitionFilters: Seq[Expression], dataFilters: Seq[Expression]): List[HoodieMergeOnReadFileSplit] = {
    if (includedCommits.isEmpty) {
      List()
    } else {
      val latestCommit = includedCommits.last.getTimestamp
      val commitsMetadata = includedCommits.map(getCommitMetadata(_, timeline)).asJava

      val modifiedFiles = listAffectedFilesForCommits(conf, new Path(metaClient.getBasePath), commitsMetadata)
        .filter(file => metaClient.getFs.exists(file.getPath))
      val fsView = new HoodieTableFileSystemView(metaClient, timeline, modifiedFiles)

      val modifiedPartitions = getWritePartitionPaths(commitsMetadata)

      val fileSlices = modifiedPartitions.asScala.flatMap { relativePartitionPath =>
        fsView.getLatestMergedFileSlicesBeforeOrOn(relativePartitionPath, latestCommit).iterator().asScala
      }.toSeq

      buildSplits(filterFileSlices(fileSlices, globPattern))
    }
  }

  private def filterFileSlices(fileSlices: Seq[FileSlice], pathGlobPattern: String): Seq[FileSlice] = {
    val filteredFileSlices = if (!StringUtils.isNullOrEmpty(pathGlobPattern)) {
      val globMatcher = new GlobPattern("*" + pathGlobPattern)
      fileSlices.filter(fileSlice => {
        val path = toScalaOption(fileSlice.getBaseFile).map(_.getPath)
          .orElse(toScalaOption(fileSlice.getLatestLogFile).map(_.getPath.toString))
          .get
        globMatcher.matches(path)
      })
    } else {
      fileSlices
    }
    filteredFileSlices
  }
}

trait HoodieIncrementalRelationTrait extends HoodieBaseRelation {

  // Validate this Incremental implementation is properly configured
  validate()

  protected lazy val includedCommits: immutable.Seq[HoodieInstant] = timeline.getInstants.iterator().asScala.toList

  // Record filters making sure that only records w/in the requested bounds are being fetched as part of the
  // scan collected by this relation
  protected lazy val incrementalSpanRecordFilters: Seq[Filter] = {
    val isNotNullFilter = IsNotNull(HoodieRecord.COMMIT_TIME_METADATA_FIELD)
    val largerThanFilter = GreaterThanOrEqual(HoodieRecord.COMMIT_TIME_METADATA_FIELD, includedCommits.head.getTimestamp)
    val lessThanFilter = LessThanOrEqual(HoodieRecord.COMMIT_TIME_METADATA_FIELD, includedCommits.last.getTimestamp)

    Seq(isNotNullFilter, largerThanFilter, lessThanFilter)
  }

  override lazy val mandatoryFields: Seq[String] = {
    // NOTE: This columns are required for Incremental flow to be able to handle the rows properly, even in
    //       cases when no columns are requested to be fetched (for ex, when using {@code count()} API)
    Seq(HoodieRecord.RECORD_KEY_METADATA_FIELD, HoodieRecord.COMMIT_TIME_METADATA_FIELD) ++
      preCombineFieldOpt.map(Seq(_)).getOrElse(Seq())
  }

  protected def validate(): Unit = {
    if (super.timeline.empty()) {
      throw new HoodieException("No instants to incrementally pull")
    }

    if (!this.optParams.contains(DataSourceReadOptions.BEGIN_INSTANTTIME.key)) {
      throw new HoodieException(s"Specify the begin instant time to pull from using " +
        s"option ${DataSourceReadOptions.BEGIN_INSTANTTIME.key}")
    }

    if (!this.tableConfig.populateMetaFields()) {
      throw new HoodieException("Incremental queries are not supported when meta fields are disabled")
    }
  }

  protected def globPattern: String =
    optParams.getOrElse(DataSourceReadOptions.INCR_PATH_GLOB.key, DataSourceReadOptions.INCR_PATH_GLOB.defaultValue)

}

