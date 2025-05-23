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
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.iotdb.subscription.it.triple.treemodel.regression.pullconsumer.pattern;

import org.apache.iotdb.it.framework.IoTDBTestRunner;
import org.apache.iotdb.itbase.category.MultiClusterIT2SubscriptionTreeRegressionConsumer;
import org.apache.iotdb.rpc.IoTDBConnectionException;
import org.apache.iotdb.rpc.StatementExecutionException;
import org.apache.iotdb.session.subscription.consumer.tree.SubscriptionTreePullConsumer;
import org.apache.iotdb.subscription.it.triple.treemodel.regression.AbstractSubscriptionTreeRegressionIT;

import org.apache.thrift.TException;
import org.apache.tsfile.enums.TSDataType;
import org.apache.tsfile.file.metadata.enums.CompressionType;
import org.apache.tsfile.file.metadata.enums.TSEncoding;
import org.apache.tsfile.write.record.Tablet;
import org.apache.tsfile.write.schema.IMeasurementSchema;
import org.apache.tsfile.write.schema.MeasurementSchema;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@RunWith(IoTDBTestRunner.class)
@Category({MultiClusterIT2SubscriptionTreeRegressionConsumer.class})
public class IoTDBMiddleMatchPatternPullConsumerDataSetIT
    extends AbstractSubscriptionTreeRegressionIT {
  private static final String database = "root.test.MiddleMatchPatternPullConsumerDataSet";
  private static final String database2 = "root.MiddleMatchPatternPullConsumerDataSet";
  private static final String device = database + ".d_0";
  private static final String device2 = database + ".d_1";
  private static final String topicName = "topicMiddleMatchPatternPullConsumerDataSet";
  private static List<IMeasurementSchema> schemaList = new ArrayList<>();

  private String pattern = "root.**.d_*.s_0";
  public static SubscriptionTreePullConsumer consumer;

  @Override
  @Before
  public void setUp() throws Exception {
    super.setUp();
    createDB(database);
    createDB(database2);
    createTopic_s(topicName, pattern, null, "now", false);
    session_src.createTimeseries(
        device + ".s_0", TSDataType.INT64, TSEncoding.GORILLA, CompressionType.LZ4);
    session_src.createTimeseries(
        device + ".s_1", TSDataType.DOUBLE, TSEncoding.TS_2DIFF, CompressionType.LZMA2);
    session_dest.createTimeseries(
        device + ".s_0", TSDataType.INT64, TSEncoding.GORILLA, CompressionType.LZ4);
    session_dest.createTimeseries(
        device + ".s_1", TSDataType.DOUBLE, TSEncoding.TS_2DIFF, CompressionType.LZMA2);
    session_src.executeNonQueryStatement(
        "create aligned timeseries " + database + ".d_1(s_0 int64,s_1 double);");
    session_dest.executeNonQueryStatement(
        "create aligned timeseries " + database + ".d_1(s_0 int64,s_1 double);");
    session_src.executeNonQueryStatement("create timeseries " + database2 + ".d_2.s_0 int32;");
    session_dest.executeNonQueryStatement("create timeseries " + database2 + ".d_2.s_0 int32;");
    session_src.executeNonQueryStatement("create timeseries " + database2 + ".d_2.s_1 float;");
    session_dest.executeNonQueryStatement("create timeseries " + database2 + ".d_2.s_1 float;");
    session_src.executeNonQueryStatement(
        "insert into " + database2 + ".d_2(time,s_0,s_1)values(1000,132,4567.89);");
    session_src.executeNonQueryStatement(
        "insert into " + database + ".d_1(time,s_0,s_1)values(2000,232,567.891);");
    schemaList.add(new MeasurementSchema("s_0", TSDataType.INT64));
    schemaList.add(new MeasurementSchema("s_1", TSDataType.DOUBLE));
    subs.getTopics().forEach((System.out::println));
    assertTrue(subs.getTopic(topicName).isPresent(), "Create show topics");
  }

  @Override
  @After
  public void tearDown() throws Exception {
    consumer.close();
    subs.dropTopic(topicName);
    dropDB(database);
    dropDB(database2);
    super.tearDown();
  }

  private void insert_data(long timestamp)
      throws IoTDBConnectionException, StatementExecutionException {
    Tablet tablet = new Tablet(device, schemaList, 10);
    int rowIndex = 0;
    for (int row = 0; row < 5; row++) {
      rowIndex = tablet.getRowSize();
      tablet.addTimestamp(rowIndex, timestamp);
      tablet.addValue("s_0", rowIndex, row * 20L + row);
      tablet.addValue("s_1", rowIndex, row + 2.45);
      timestamp += 2000;
    }
    session_src.insertTablet(tablet);
  }

  @Test
  public void do_test()
      throws InterruptedException,
          TException,
          IoTDBConnectionException,
          IOException,
          StatementExecutionException {
    consumer =
        create_pull_consumer("pull_pattern", "MiddleMatchPatternHistory_DataSet", false, null);
    // Write data before subscribing
    insert_data(1706659200000L); // 2024-01-31 08:00:00+08:00
    // Subscribe
    consumer.subscribe(topicName);
    assertEquals(subs.getSubscriptions().size(), 1, "show subscriptions after subscription");
    insert_data(System.currentTimeMillis() - 30000L);
    // Consumption data
    consume_data(consumer, session_dest);
    String sql = "select count(s_0) from " + device;
    System.out.println("src " + database + ".d_0.s_0: " + getCount(session_src, sql));
    System.out.println(
        "src "
            + database
            + ".d_0.s_1: "
            + getCount(session_src, "select count(s_1) from " + device));
    System.out.println(
        "src "
            + database
            + ".d_1.s_0: "
            + getCount(session_src, "select count(s_0) from " + database + ".d_1"));
    System.out.println(
        "src "
            + database2
            + ".d_2.s_0: "
            + getCount(session_src, "select count(s_0) from " + database2 + ".d_2"));
    System.out.println("dest " + database + ".d_0.s_0: " + getCount(session_dest, sql));
    System.out.println(
        "dest "
            + database
            + ".d_0.s_1: "
            + getCount(session_dest, "select count(s_1) from " + device));
    System.out.println(
        "dest "
            + database
            + ".d_1.s_0: "
            + getCount(session_dest, "select count(s_0) from " + database + ".d_1"));
    System.out.println(
        "dest "
            + database2
            + ".s_0: "
            + getCount(session_dest, "select count(s_0) from " + database2 + ".d_2"));
    check_count(10, sql, "Consumption Data:s_0");
    check_count(0, "select count(s_1) from " + device, "Consumption Data: s_1");
    check_count(1, "select count(s_0) from " + database + ".d_1", "Consumption Data:d_1");
    check_count(1, "select count(s_0) from " + database2 + ".d_2", "Consumption data:d_2");
    insert_data(System.currentTimeMillis());
    // Unsubscribe
    consumer.unsubscribe(topicName);
    // Subscribe and then write data
    consumer.subscribe(topicName);
    assertEquals(subs.getSubscriptions().size(), 1, "show subscriptions after re-subscribing");
    insert_data(1707782400000L); // 2024-02-13 08:00:00+08:00
    System.out.println("src: " + getCount(session_src, sql));
    // Consumption data: Progress is not preserved if you unsubscribe and then resubscribe. Full
    // synchronization.
    consume_data(consumer, session_dest);
    check_count(15, "select count(s_0) from " + device, "Consume data again:s_0");
    check_count(0, "select count(s_1) from " + device, "Consumption data: s_1");
  }
}
