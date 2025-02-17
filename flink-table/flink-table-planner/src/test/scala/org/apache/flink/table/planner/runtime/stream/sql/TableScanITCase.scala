/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.flink.table.planner.runtime.stream.sql

import org.apache.flink.api.common.typeinfo.TypeInformation
import org.apache.flink.api.java.typeutils.RowTypeInfo
import org.apache.flink.api.scala._
import org.apache.flink.streaming.api.functions.ProcessFunction
import org.apache.flink.table.api._
import org.apache.flink.table.api.bridge.scala._
import org.apache.flink.table.api.internal.TableEnvironmentInternal
import org.apache.flink.table.planner.runtime.utils.{StreamingTestBase, TestingAppendSink}
import org.apache.flink.table.planner.utils.{TestPreserveWMTableSource, TestTableSourceWithTime, WithoutTimeAttributesTableSource}
import org.apache.flink.types.Row
import org.apache.flink.util.Collector

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

import java.lang.{Integer => JInt, Long => JLong}

class TableScanITCase extends StreamingTestBase {

  @Test
  def testTableSourceWithoutTimeAttribute(): Unit = {
    val tableName = "MyTable"
    WithoutTimeAttributesTableSource.createTemporaryTable(tEnv, tableName)
    val sqlQuery = s"SELECT * from $tableName"
    val result = tEnv.sqlQuery(sqlQuery).toAppendStream[Row]
    val sink = new TestingAppendSink
    result.addSink(sink)
    env.execute()

    val expected = Seq("Mary,1,1", "Bob,2,3")
    assertThat(sink.getAppendResults.sorted).isEqualTo(expected.sorted)
  }

  @Test
  def testProctimeTableSource(): Unit = {
    val tableName = "MyTable"

    val data = Seq("Mary", "Peter", "Bob", "Liz")

    val schema = new TableSchema(Array("name", "ptime"), Array(Types.STRING, Types.LOCAL_DATE_TIME))
    val returnType = Types.STRING

    val tableSource = new TestTableSourceWithTime(false, schema, returnType, data, null, "ptime")
    tEnv.asInstanceOf[TableEnvironmentInternal].registerTableSourceInternal(tableName, tableSource)

    val sqlQuery = s"SELECT name FROM $tableName"
    val result = tEnv.sqlQuery(sqlQuery).toAppendStream[Row]
    val sink = new TestingAppendSink
    result.addSink(sink)
    env.execute()

    val expected = Seq("Mary", "Peter", "Bob", "Liz")
    assertThat(sink.getAppendResults.sorted).isEqualTo(expected.sorted)
  }

  @Test
  def testRowtimeTableSourceWithFieldReMapping(): Unit = {
    val tableName = "MyTable"

    val data: Seq[Row] = Seq(
      Row.of(Int.box(1), Long.box(11), "Mary"),
      Row.of(Int.box(2), Long.box(12), "Peter"),
      Row.of(Int.box(3), Long.box(13), "Bob"),
      Row.of(Int.box(4), Long.box(14), "Liz")
    )

    val schema = new TableSchema(
      Array("key", "rowtime", "payload"),
      Array(Types.INT(), Types.SQL_TIMESTAMP(), Types.STRING()))
    val returnType = Types.ROW(Types.INT(), Types.LONG(), Types.STRING())
    val mapping = Map("key" -> "f0", "ts" -> "f1", "payload" -> "f2")
    val tableSource = new TestTableSourceWithTime(
      false,
      schema,
      returnType,
      data,
      rowtime = "rowtime",
      mapping = mapping,
      existingTs = "ts")
    tEnv.asInstanceOf[TableEnvironmentInternal].registerTableSourceInternal(tableName, tableSource)

    val sqlQuery =
      s"""
         |SELECT
         |  CAST(TUMBLE_START(rowtime, INTERVAL '0.005' SECOND) AS VARCHAR),
         |  COUNT(payload)
         |FROM $tableName
         |GROUP BY TUMBLE(rowtime, INTERVAL '0.005' SECOND)
       """.stripMargin
    val result = tEnv.sqlQuery(sqlQuery).toAppendStream[Row]
    val sink = new TestingAppendSink
    result.addSink(sink)
    env.execute()

    val expected = Seq("1970-01-01 00:00:00.010,4")
    assertThat(sink.getAppendResults.sorted).isEqualTo(expected.sorted)
  }

  @Test
  def testRowtimeTableSourcePreserveWatermarks(): Unit = {
    val tableName = "MyTable"

    // rows with timestamps and watermarks
    val data = Seq(
      Right(1L),
      Left(5L, Row.of(new JInt(1), new JLong(5), "A")),
      Left(2L, Row.of(new JInt(2), new JLong(1), "B")),
      Right(10L),
      Left(8L, Row.of(new JInt(6), new JLong(8), "C")),
      Right(20L),
      Left(21L, Row.of(new JInt(6), new JLong(21), "D")),
      Right(30L)
    )

    val fieldNames = Array("id", "rtime", "name")
    val schema = new TableSchema(fieldNames, Array(Types.INT, Types.LOCAL_DATE_TIME, Types.STRING))
    val rowType = new RowTypeInfo(
      Array(Types.INT, Types.LONG, Types.STRING).asInstanceOf[Array[TypeInformation[_]]],
      fieldNames)

    val tableSource = new TestPreserveWMTableSource(schema, rowType, data, "rtime")
    tEnv.asInstanceOf[TableEnvironmentInternal].registerTableSourceInternal(tableName, tableSource)
    val sqlQuery = s"SELECT id, name FROM $tableName"
    val sink = new TestingAppendSink

    tEnv
      .sqlQuery(sqlQuery)
      .toAppendStream[Row]
      // append current watermark to each row to verify that original watermarks were preserved
      .process(new ProcessFunction[Row, Row] {

        override def processElement(
            value: Row,
            ctx: ProcessFunction[Row, Row]#Context,
            out: Collector[Row]): Unit = {
          val res = new Row(3)
          res.setField(0, value.getField(0))
          res.setField(1, value.getField(1))
          res.setField(2, ctx.timerService().currentWatermark())
          out.collect(res)
        }
      })
      .addSink(sink)
    env.execute()

    val expected = Seq("1,A,1", "2,B,1", "6,C,10", "6,D,20")
    assertThat(sink.getAppendResults.sorted).isEqualTo(expected.sorted)
  }

}
