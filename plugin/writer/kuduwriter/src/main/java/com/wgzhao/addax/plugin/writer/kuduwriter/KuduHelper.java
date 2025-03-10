/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.wgzhao.addax.plugin.writer.kuduwriter;

import com.wgzhao.addax.common.exception.AddaxException;
import com.wgzhao.addax.common.util.Configuration;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.apache.commons.lang3.StringUtils;
import org.apache.kudu.ColumnSchema;
import org.apache.kudu.Schema;
import org.apache.kudu.Type;
import org.apache.kudu.client.CreateTableOptions;
import org.apache.kudu.client.KuduClient;
import org.apache.kudu.client.KuduException;
import org.apache.kudu.client.KuduTable;
import org.apache.kudu.client.PartialRow;
import org.apache.kudu.shaded.com.google.common.collect.Lists;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class KuduHelper
{

    private static final Logger LOG = LoggerFactory.getLogger(KuduHelper.class);

    public static KuduClient getKuduClient(Configuration configuration)
    {
        try {
            String masterAddress = (String) configuration.get(KuduKey.KUDU_MASTER_ADDRESSES);
            return new KuduClient.KuduClientBuilder(masterAddress)
                    .defaultAdminOperationTimeoutMs(
                            Long.parseLong(configuration.getString(
                                    KuduKey.KUDU_ADMIN_TIMEOUT, "60")) * 1000L)
                    .defaultOperationTimeoutMs(
                            Long.parseLong(configuration.getString(
                                    KuduKey.KUDU_SESSION_TIMEOUT, "100"))* 1000L)
                    .build();
        }
        catch (Exception e) {
            throw AddaxException.asAddaxException(KuduWriterErrorCode.GET_KUDU_CONNECTION_ERROR, e);
        }
    }

    public static KuduTable getKuduTable(KuduClient kuduClient, String tableName)
    {
        try {
            return kuduClient.openTable(tableName);
        }
        catch (Exception e) {
            throw AddaxException.asAddaxException(KuduWriterErrorCode.GET_KUDU_TABLE_ERROR, e);
        }
    }

    public static ThreadPoolExecutor createRowAddThreadPool(int coreSize)
    {
        return new ThreadPoolExecutor(coreSize,
                coreSize,
                60L,
                TimeUnit.SECONDS,
                new SynchronousQueue<Runnable>(),
                new ThreadFactory()
                {
                    private final ThreadGroup group = System.getSecurityManager() == null ?
                            Thread.currentThread().getThreadGroup() : System.getSecurityManager().getThreadGroup();
                    private final AtomicInteger threadNumber = new AtomicInteger(1);

                    @Override
                    public Thread newThread(Runnable r)
                    {
                        Thread t = new Thread(group, r,
                                "pool-kudu_rows_add-thread-" + threadNumber.getAndIncrement(),
                                0);
                        if (t.isDaemon()) {
                            t.setDaemon(false);
                        }
                        if (t.getPriority() != Thread.NORM_PRIORITY) {
                            t.setPriority(Thread.NORM_PRIORITY);
                        }
                        return t;
                    }
                }, new ThreadPoolExecutor.CallerRunsPolicy());
    }

    public static List<List<Configuration>> getColumnLists(List<Configuration> columns)
    {
        int quota = 8;
        int num = (columns.size() - 1) / quota + 1;
        int gap = columns.size() / num;
        List<List<Configuration>> columnLists = new ArrayList<>(num);
        for (int j = 0; j < num - 1; j++) {
            List<Configuration> destList = new ArrayList<>(columns.subList(j * gap, (j + 1) * gap));
            columnLists.add(destList);
        }
        List<Configuration> destList = new ArrayList<>(columns.subList(gap * (num - 1), columns.size()));
        columnLists.add(destList);
        return columnLists;
    }

    public static boolean isTableExists(Configuration configuration)
    {
        String tableName = configuration.getString(KuduKey.KUDU_TABLE_NAME);
        String kuduConfig = configuration.getString(KuduKey.KUDU_CONFIG);
        KuduClient kuduClient =KuduHelper.getKuduClient(configuration);
        try {
            return kuduClient.tableExists(tableName);
        }
        catch (Exception e) {
            throw AddaxException.asAddaxException(KuduWriterErrorCode.GET_KUDU_CONNECTION_ERROR, e);
        }
        finally {
            KuduHelper.closeClient(kuduClient);
        }
    }

    public static void closeClient(KuduClient kuduClient)
    {
        try {
            if (kuduClient != null) {
                kuduClient.close();
            }
        }
        catch (KuduException e) {
            LOG.warn("The \"kudu client\" was not stopped gracefully. !");
        }
    }

    public static Schema getSchema(Configuration configuration)
    {
        List<Configuration> columns = configuration.getListConfiguration(KuduKey.COLUMN);
        List<ColumnSchema> columnSchemas = new ArrayList<>();
        Schema schema = null;
        if (columns == null || columns.isEmpty()) {
            throw AddaxException.asAddaxException(KuduWriterErrorCode.REQUIRED_VALUE,
                    "column is not defined，eg：column:[{\"name\": \"cf0:column0\",\"type\": \"string\"},{\"name\": \"cf1:column1\",\"type\": \"long\"}]");
        }
        try {
            for (Configuration column : columns) {

                String type = "BIGINT".equalsIgnoreCase(column.getNecessaryValue(KuduKey.TYPE, KuduWriterErrorCode.REQUIRED_VALUE)) ||
                        "LONG".equalsIgnoreCase(column.getNecessaryValue(KuduKey.TYPE, KuduWriterErrorCode.REQUIRED_VALUE)) ?
                        "INT64" : "INT".equalsIgnoreCase(column.getNecessaryValue(KuduKey.TYPE, KuduWriterErrorCode.REQUIRED_VALUE)) ?
                        "INT32" : column.getNecessaryValue(KuduKey.TYPE, KuduWriterErrorCode.REQUIRED_VALUE).toUpperCase();
                String name = column.getNecessaryValue(KuduKey.NAME, KuduWriterErrorCode.REQUIRED_VALUE);
                Boolean key = column.getBool(KuduKey.PRIMARY_KEY, false);
                String encoding = column.getString(KuduKey.ENCODING, KuduConstant.ENCODING).toUpperCase();
                String compression = column.getString(KuduKey.COMPRESSION, KuduConstant.COMPRESSION).toUpperCase();
                String comment = column.getString(KuduKey.COMMENT, "");

                columnSchemas.add(new ColumnSchema.ColumnSchemaBuilder(name, Type.getTypeForName(type))
                        .key(key)
                        .encoding(ColumnSchema.Encoding.valueOf(encoding))
                        .compressionAlgorithm(ColumnSchema.CompressionAlgorithm.valueOf(compression))
                        .comment(comment)
                        .build());
            }
            schema = new Schema(columnSchemas);
        }
        catch (Exception e) {
            throw AddaxException.asAddaxException(KuduWriterErrorCode.REQUIRED_VALUE, e);
        }
        return schema;
    }

    public static Integer getPrimaryKeyIndexUntil(List<Configuration> columns)
    {
        int i = 0;
        while (i < columns.size()) {
            Configuration col = columns.get(i);
            if (!col.getBool(KuduKey.PRIMARY_KEY, false)) {
                break;
            }
            i++;
        }
        return i;
    }

    public static void setTablePartition(Configuration configuration,
            CreateTableOptions tableOptions,
            Schema schema)
    {
        Configuration partition = configuration.getConfiguration(KuduKey.PARTITION);
        if (partition == null) {
            ColumnSchema columnSchema = schema.getColumns().get(0);
            tableOptions.addHashPartitions(Collections.singletonList(columnSchema.getName()), 3);
            return;
        }
        //range分区
        Map<String, Object> range = partition.getMap(KuduKey.RANGE);
        if (range != null && !range.isEmpty()) {
            Set<Map.Entry<String, Object>> rangeColumns = range.entrySet();
            for (Map.Entry<String, Object> rangeColumn : rangeColumns) {
                JSONArray lowerAndUppers = (JSONArray) rangeColumn.getValue();
                Iterator<Object> iterator = lowerAndUppers.iterator();
                String column = rangeColumn.getKey();
                if (StringUtils.isBlank(column)) {
                    throw AddaxException.asAddaxException(KuduWriterErrorCode.REQUIRED_VALUE,
                            "range partition column is empty, please check the configuration parameters.");
                }
                while (iterator.hasNext()) {
                    JSONObject lowerAndUpper = (JSONObject) iterator.next();
                    String lowerValue = lowerAndUpper.getString(KuduKey.LOWER);
                    String upperValue = lowerAndUpper.getString(KuduKey.UPPER);
                    if (StringUtils.isBlank(lowerValue) || StringUtils.isBlank(upperValue)) {
                        throw AddaxException.asAddaxException(KuduWriterErrorCode.REQUIRED_VALUE,
                                "\"lower\" or \"upper\" is empty, please check the configuration parameters.");
                    }
                    PartialRow lower = schema.newPartialRow();
                    PartialRow upper = schema.newPartialRow();
                    lower.addString(column, lowerValue);
                    upper.addString(column, upperValue);
                    tableOptions.addRangePartition(lower, upper);
                }
            }
            LOG.info("Set range partition complete!");
        }

        // 设置Hash分区
        Configuration hash = partition.getConfiguration(KuduKey.HASH);
        if (hash != null) {
            List<String> hashColumns = hash.getList(KuduKey.COLUMN, String.class);
            Integer hashPartitionNum = configuration.getInt(KuduKey.HASH_NUM, 3);
            tableOptions.addHashPartitions(hashColumns, hashPartitionNum);
            LOG.info("Set hash partition complete!");
        }
    }

    public static void validateParameter(Configuration configuration)
    {
        LOG.info("Start validating parameters！");
        // configuration.getNecessaryValue(Key.KUDU_CONFIG, KuduWriterErrorCode.REQUIRED_VALUE);
        configuration.getNecessaryValue(KuduKey.KUDU_TABLE_NAME, KuduWriterErrorCode.REQUIRED_VALUE);
        configuration.getNecessaryValue(KuduKey.KUDU_MASTER_ADDRESSES, KuduWriterErrorCode.REQUIRED_VALUE);
//        String encoding = configuration.getString(Key.ENCODING, Constant.DEFAULT_ENCODING);
//        if (!Charset.isSupported(encoding)) {
//            throw DataXException.asAddaxException(KuduWriterErrorCode.ILLEGAL_VALUE,
//                    String.format("Encoding is not supported:[%s] .", encoding));
//        }
//        configuration.set(Key.ENCODING, encoding);
        String insertMode = configuration.getString(KuduKey.WRITE_MODE, KuduConstant.INSERT_MODE);
        try {
            InsertModeType.getByTypeName(insertMode);
        }
        catch (Exception e) {
            insertMode = KuduConstant.INSERT_MODE;
        }
        configuration.set(KuduKey.WRITE_MODE, insertMode);

        Long writeBufferSize = configuration.getLong(KuduKey.WRITE_BATCH_SIZE, KuduConstant.DEFAULT_WRITE_BATCH_SIZE);
        configuration.set(KuduKey.WRITE_BATCH_SIZE, writeBufferSize);

        Long mutationBufferSpace = configuration.getLong(KuduKey.MUTATION_BUFFER_SPACE, KuduConstant.DEFAULT_MUTATION_BUFFER_SPACE);
        configuration.set(KuduKey.MUTATION_BUFFER_SPACE, mutationBufferSpace);

        Boolean isSkipFail = configuration.getBool(KuduKey.SKIP_FAIL, false);
        configuration.set(KuduKey.SKIP_FAIL, isSkipFail);
        List<Configuration> columns = configuration.getListConfiguration(KuduKey.COLUMN);
        List<Configuration> goalColumns = new ArrayList<>();
        //column参数验证
        int indexFlag = 0;
        boolean primaryKey = true;
        int primaryKeyFlag = 0;
        for (int i = 0; i < columns.size(); i++) {
            Configuration col = columns.get(i);
            String index = col.getString(KuduKey.INDEX);
            if (index == null) {
                index = String.valueOf(i);
                col.set(KuduKey.INDEX, index);
                indexFlag++;
            }
//            if (primaryKey != col.getBool(Key.PRIMARY_KEY, false)) {
//                primaryKey = col.getBool(Key.PRIMARY_KEY, false);
//                primaryKeyFlag++;
//            }
            goalColumns.add(col);
        }
        if (indexFlag != 0 && indexFlag != columns.size()) {
            throw AddaxException.asAddaxException(KuduWriterErrorCode.ILLEGAL_VALUE,
                    "\"index\" either has values for all of them, or all of them are null!");
        }
//        if (primaryKeyFlag > 1) {
//            throw DataXException.asAddaxException(KuduWriterErrorCode.ILLEGAL_VALUE,
//                    "\"primaryKey\" must be written in the front！");
//        }
        configuration.set(KuduKey.COLUMN, goalColumns);
//        LOG.info("------------------------------------");
//        LOG.info(configuration.toString());
//        LOG.info("------------------------------------");
        LOG.info("validate parameter complete！");
    }

    public static void truncateTable(Configuration configuration)
    {
        String kuduConfig = configuration.getString(KuduKey.KUDU_CONFIG);
        String userTable = configuration.getString(KuduKey.KUDU_TABLE_NAME);
        LOG.info(String.format("Because you have configured truncate is true,KuduWriter begins to truncate table %s .", userTable));
        KuduClient kuduClient = KuduHelper.getKuduClient(configuration);

        try {
            if (kuduClient.tableExists(userTable)) {
                kuduClient.deleteTable(userTable);
                LOG.info(String.format("table  %s has been deleted.", userTable));
            }
        }
        catch (KuduException e) {
            throw AddaxException.asAddaxException(KuduWriterErrorCode.DELETE_KUDU_ERROR, e);
        }
        finally {
            KuduHelper.closeClient(kuduClient);
        }
    }

    public static List<String> getColumnNames(List<Configuration> columns)
{
    List<String> columnNames = Lists.newArrayList();
    for (Configuration eachColumnConf : columns) {
        columnNames.add(eachColumnConf.getString(KuduKey.NAME));
    }
    return columnNames;
}

}
