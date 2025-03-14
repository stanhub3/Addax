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

package com.wgzhao.addax.plugin.reader.sqlserverreader;

import com.wgzhao.addax.rdbms.reader.CommonRdbmsReader;
import com.wgzhao.addax.rdbms.util.DBUtil;
import com.wgzhao.addax.rdbms.util.DataBaseType;

public class SqlServerRdbmsReader
        extends CommonRdbmsReader
{
    public static class Job
            extends CommonRdbmsReader.Job
    {
        public Job(DataBaseType dataBaseType)
        {
            super(dataBaseType);
        }
    }

    public static class Task
            extends CommonRdbmsReader.Task
    {

        public Task(DataBaseType dataBaseType)
        {
            super(dataBaseType);
        }

        public Task(DataBaseType dataBaseType, int taskGroupId, int taskId)
        {
            super(dataBaseType, taskGroupId, taskId);
        }
    }

    static {
        DBUtil.loadDriverClass("reader", "rdbms");
    }
}
