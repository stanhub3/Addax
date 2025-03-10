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

package com.wgzhao.addax.plugin.writer.hbase11xsqlwriter;

import com.wgzhao.addax.common.base.HBaseConstant;
import com.wgzhao.addax.common.element.Column;
import com.wgzhao.addax.common.element.Record;
import com.wgzhao.addax.common.exception.AddaxException;
import com.wgzhao.addax.common.plugin.RecordReceiver;
import com.wgzhao.addax.common.plugin.TaskPluginCollector;
import com.wgzhao.addax.common.util.Configuration;
import org.apache.phoenix.schema.PTable;
import org.apache.phoenix.schema.types.PDataType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * @author yanghan.y
 */
public class HbaseSQLWriterTask
{
    private static final Logger LOG = LoggerFactory.getLogger(HbaseSQLWriterTask.class);
    private final HbaseSQLWriterConfig cfg;
    private TaskPluginCollector taskPluginCollector;
    private Connection connection = null;
    private PreparedStatement ps = null;
    // 需要向 hbase 写入的列的数量,即用户配置的column参数中列的个数。时间戳不包含在内
    private int numberOfColumnsToWrite;
    // 期待从源头表的Record中拿到多少列
    private int numberOfColumnsToRead;
    private int[] columnTypes;

    public HbaseSQLWriterTask(Configuration configuration)
    {
        // 这里仅解析配置，不访问远端集群，配置的合法性检查在writer的init过程中进行
        cfg = HbaseSQLHelper.parseConfig(configuration);
    }

    public void startWriter(RecordReceiver lineReceiver, TaskPluginCollector taskPluginCollector)
    {
        this.taskPluginCollector = taskPluginCollector;
        Record record;
        try {
            // 准备阶段
            prepare();

            List<Record> buffer = new ArrayList<>(cfg.getBatchSize());
            while ((record = lineReceiver.getFromReader()) != null) {
                // 校验列数量是否符合预期
                if (record.getColumnNumber() != numberOfColumnsToRead) {
                    throw AddaxException.asAddaxException(HbaseSQLWriterErrorCode.ILLEGAL_VALUE,
                            "The number of fields(" + record.getColumnNumber()
                                    + ") in the source and the number of fields(" + numberOfColumnsToRead + ") you configured in 'column' are not the same.");
                }

                buffer.add(record);
                if (buffer.size() > cfg.getBatchSize()) {
                    doBatchUpsert(buffer);
                    buffer.clear();
                }
            } // end while loop

            // 处理剩余的record
            if (!buffer.isEmpty()) {
                doBatchUpsert(buffer);
                buffer.clear();
            }
        }
        catch (Throwable t) {
            // 确保所有异常都转化为DataXException
            throw AddaxException.asAddaxException(HbaseSQLWriterErrorCode.PUT_HBASE_ERROR, t);
        }
        finally {
            close();
        }
    }

    private void prepare()
            throws SQLException
    {
        if (connection == null) {
            connection = HbaseSQLHelper.getJdbcConnection(cfg);
            connection.setAutoCommit(false);    // 批量提交
        }

        if (ps == null) {
            // 一个Task的生命周期中只使用一个PreparedStatement对象，所以，在
            ps = createPreparedStatement();
            columnTypes = getColumnSqlType(cfg.getColumns());
        }
    }

    private void close()
    {
        if (ps != null) {
            try {
                ps.close();
            }
            catch (SQLException e) {
                // 不会出错
                LOG.error("Failed to close PreparedStatement", e);
            }
        }
        if (connection != null) {
            try {
                connection.close();
            }
            catch (SQLException e) {
                // 不会出错
                LOG.error("Failed to close Connection", e);
            }
        }
    }

    /*
     * 批量提交一组数据，如果失败，则尝试一行行提交，如果仍然失败，抛错给用户
     */
    private void doBatchUpsert(List<Record> records)
            throws SQLException
    {
        try {
            // 将所有record提交到connection缓存
            for (Record r : records) {
                setupStatement(r);
                ps.executeUpdate();
            }

            // 将缓存的数据提交到hbase
            connection.commit();
        }
        catch (SQLException e) {
            LOG.error("Failed to batch commit {} records", records.size(), e);

            // 批量提交失败，则一行行重试，以确定那一行出错
            connection.rollback();
            doSingleUpsert(records);
        }
        catch (Exception e) {
            throw AddaxException.asAddaxException(HbaseSQLWriterErrorCode.PUT_HBASE_ERROR, e);
        }
    }

    /*
     * 单行提交，将出错的行记录到脏数据中。由脏数据收集模块判断任务是否继续
     */
    private void doSingleUpsert(List<Record> records)
    {
        for (Record r : records) {
            try {
                setupStatement(r);
                ps.executeUpdate();
                connection.commit();
            }
            catch (SQLException e) {
                //出错了，记录脏数据
                LOG.error("Failed to write hbase", e);
                this.taskPluginCollector.collectDirtyRecord(r, e);
            }
        }
    }

    /*
     * 生成sql模板，并根据模板创建PreparedStatement
     */
    private PreparedStatement createPreparedStatement()
            throws SQLException
    {
        // 生成列名集合，列之间用逗号分隔： col1,col2,col3,...
        String columnNames = String.join(",", cfg.getColumns());

        numberOfColumnsToWrite = cfg.getColumns().size();
        numberOfColumnsToRead = numberOfColumnsToWrite;   // 开始的时候，要读的列数娱要写的列数相等

        // 生成UPSERT模板
        String tableName = cfg.getTableName();
        String sql = String.format("UPSERT INTO %s ( %s ) VALUES ( %s )", tableName, columnNames, String.join(",", Collections.nCopies(cfg.getColumns().size(), "?")));
        PreparedStatement ps = connection.prepareStatement(sql);
        LOG.debug("SQL template generated: {}", sql);
        return ps;
    }

    /*
     * 根据列名来从数据库元数据中获取这一列对应的SQL类型
     */
    private int[] getColumnSqlType(List<String> columnNames)
            throws SQLException
    {
        int[] types = new int[numberOfColumnsToWrite];
        PTable ptable = HbaseSQLHelper
                .getTableSchema(connection, cfg.getNamespace(), cfg.getTableName(), cfg.isThinClient());

        for (int i = 0; i < columnNames.size(); i++) {
            String name = columnNames.get(i);
            PDataType type = ptable.getColumnForColumnName(name).getDataType();
            types[i] = type.getSqlType();
            LOG.debug("Column name : {}, sql type = {} {} ", name, type.getSqlType(), type.getSqlTypeName());
        }
        return types;
    }

    private void setupStatement(Record record)
            throws SQLException
    {
        // 一开始的时候就已经校验过record中的列数量与ps中需要的值数量相等
        for (int i = 0; i < numberOfColumnsToWrite; i++) {
            Column col = record.getColumn(i);
            int sqlType = columnTypes[i];
            // PreparedStatement中的索引从1开始，所以用i+1
            setupColumn(i + 1, sqlType, col);
        }
    }

    private void setupColumn(int pos, int sqlType, Column col)
            throws SQLException
    {
        if (col.getRawData() != null) {
            switch (sqlType) {
                case Types.CHAR:
                case Types.VARCHAR:
                    ps.setString(pos, col.asString());
                    break;

                case Types.BINARY:
                case Types.VARBINARY:
                    ps.setBytes(pos, col.asBytes());
                    break;

                case Types.BOOLEAN:
                    ps.setBoolean(pos, col.asBoolean());
                    break;

                case Types.TINYINT:
                case HBaseConstant.TYPE_UNSIGNED_TINYINT:
                    ps.setByte(pos, col.asLong().byteValue());
                    break;

                case Types.SMALLINT:
                case HBaseConstant.TYPE_UNSIGNED_SMALLINT:
                    ps.setShort(pos, col.asLong().shortValue());
                    break;

                case Types.INTEGER:
                case HBaseConstant.TYPE_UNSIGNED_INTEGER:
                    ps.setInt(pos, col.asLong().intValue());
                    break;

                case Types.BIGINT:
                case HBaseConstant.TYPE_UNSIGNED_LONG:
                    ps.setLong(pos, col.asLong());
                    break;

                case Types.FLOAT:
                    ps.setFloat(pos, col.asDouble().floatValue());
                    break;

                case Types.DOUBLE:
                    ps.setDouble(pos, col.asDouble());
                    break;

                case Types.DECIMAL:
                    ps.setBigDecimal(pos, col.asBigDecimal());
                    break;

                case Types.DATE:
                case HBaseConstant.TYPE_UNSIGNED_DATE:
                    ps.setDate(pos, new java.sql.Date(col.asDate().getTime()));
                    break;

                case Types.TIME:
                case HBaseConstant.TYPE_UNSIGNED_TIME:
                    ps.setTime(pos, new java.sql.Time(col.asDate().getTime()));
                    break;

                case Types.TIMESTAMP:
                case HBaseConstant.TYPE_UNSIGNED_TIMESTAMP:
                    ps.setTimestamp(pos, new java.sql.Timestamp(col.asDate().getTime()));
                    break;

                default:
                    throw AddaxException.asAddaxException(HbaseSQLWriterErrorCode.ILLEGAL_VALUE,
                            "The data type " + sqlType + "is unsupported.");
            } // end switch
        }
        else {
            // 没有值，按空值的配置情况处理
            switch (cfg.getNullMode()) {
                case SKIP:
                    // 跳过空值，则不插入该列,
                    ps.setNull(pos, sqlType);
                    break;

                case EMPTY:
                    // 插入"空值"，请注意不同类型的空值不同
                    // 另外，对SQL来说，空值本身是有值的，这与直接操作HBASE Native API时的空值完全不同
                    ps.setObject(pos, getEmptyValue(sqlType));
                    break;

                default:
                    // nullMode的合法性在初始化配置的时候已经校验过，这里一定不会出错
                    throw AddaxException.asAddaxException(HbaseSQLWriterErrorCode.ILLEGAL_VALUE,
                            "The nullMode type " + cfg.getNullMode() + " is unsupported, here are available nullMode:" + Arrays.asList(NullModeType.values()));
            }
        }
    }

    /**
     * 根据类型获取"空值"
     * 值类型的空值都是0，bool是false，String是空字符串
     *
     * @param sqlType sql数据类型，定义于{@link Types}
     * @return Object
     */
    private Object getEmptyValue(int sqlType)
    {
        switch (sqlType) {
            case Types.CHAR:
            case Types.VARCHAR:
                return "";

            case Types.BOOLEAN:
                return false;

            case Types.TINYINT:
            case HBaseConstant.TYPE_UNSIGNED_TINYINT:
                return (byte) 0;

            case Types.SMALLINT:
            case HBaseConstant.TYPE_UNSIGNED_SMALLINT:
                return (short) 0;

            case Types.INTEGER:
            case HBaseConstant.TYPE_UNSIGNED_INTEGER:
                return 0;

            case Types.BIGINT:
            case HBaseConstant.TYPE_UNSIGNED_LONG:
                return (long) 0;

            case Types.FLOAT:
                return (float) 0.0;

            case Types.DOUBLE:
                return 0.0;

            case Types.DECIMAL:
                return new BigDecimal(0);

            case Types.DATE:
            case HBaseConstant.TYPE_UNSIGNED_DATE:
                return new java.sql.Date(0);

            case Types.TIME:
            case HBaseConstant.TYPE_UNSIGNED_TIME:
                return new java.sql.Time(0);

            case Types.TIMESTAMP:
            case HBaseConstant.TYPE_UNSIGNED_TIMESTAMP:
                return new java.sql.Timestamp(0);

            case Types.BINARY:
            case Types.VARBINARY:
                return new byte[0];

            default:
                throw AddaxException.asAddaxException(HbaseSQLWriterErrorCode.ILLEGAL_VALUE,
                        "The data type " + sqlType + " is unsupported yet.");
        }
    }
}
