/*
 * Copyright 2024 OceanBase.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.oceanbase.spark.writer

import com.oceanbase.spark.cfg.{ConnectionOptions, SparkSettings}
import com.oceanbase.spark.directload.{DirectLoader, DirectLoadUtils}

import com.alipay.oceanbase.rpc.direct_load.ObDirectLoadBucket
import com.alipay.oceanbase.rpc.protocol.payload.impl.ObObj
import org.apache.commons.lang.StringUtils
import org.apache.spark.sql.{DataFrame, Row}

import java.util.Objects

import scala.collection.mutable.ArrayBuffer

class DirectLoadWriter(settings: SparkSettings) extends Serializable {

  private val bufferSize =
    settings.getIntegerProperty(ConnectionOptions.BATCH_SIZE, ConnectionOptions.BATCH_SIZE_DEFAULT)
  private val sinkTaskPartitionSize: Integer =
    settings.getIntegerProperty(ConnectionOptions.SINK_TASK_PARTITION_SIZE)
  private val sinkTaskUseRepartition: Boolean = settings
    .getProperty(
      ConnectionOptions.SINK_TASK_USE_REPARTITION,
      ConnectionOptions.SINK_TASK_USE_REPARTITION_DEFAULT.toString)
    .toBoolean

  def write(dataFrame: DataFrame): Unit = {
    assert(StringUtils.isNotBlank(settings.getProperty(ConnectionOptions.EXECUTION_ID)))

    var resultDataFrame = dataFrame
    if (Objects.nonNull(sinkTaskPartitionSize)) {
      resultDataFrame =
        if (sinkTaskUseRepartition) dataFrame.repartition(sinkTaskPartitionSize)
        else dataFrame.coalesce(sinkTaskPartitionSize)
    }

    resultDataFrame.foreachPartition(
      (partition: Iterator[Row]) => {
        val directLoader: DirectLoader = DirectLoadUtils.buildDirectLoaderFromSetting(settings)
        directLoader.begin()
        val buffer = ArrayBuffer[Row]()
        partition.foreach(
          row => {
            buffer += row
            if (buffer.length >= bufferSize) {
              flush(buffer, directLoader)
            }
          })
        flush(buffer, directLoader)
      })
  }

  private def flush(buffer: ArrayBuffer[Row], directLoader: DirectLoader): Unit = {
    val bucket = new ObDirectLoadBucket()
    buffer.foreach(
      row => {
        val array = new Array[ObObj](row.size)
        for (i <- 0 until (row.size)) {
          array(i) = DirectLoader.createObObj(row.get(i))
        }
        bucket.addRow(array)
      })

    directLoader.write(bucket)
    buffer.clear()
  }
}
