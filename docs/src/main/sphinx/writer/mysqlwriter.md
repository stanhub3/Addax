# MysqlWriter 

MysqlWriter 插件实现了写入数据到 Mysql 主库的目的表的功能。在底层实现上， MysqlWriter 通过 JDBC 连接远程 Mysql 数据库，并执行相应的 insert into ... 或者 ( replace into ...) 的 sql 语句将数据写入 Mysql，内部会分批次提交入库，需要数据库本身采用 innodb 引擎。

MysqlWriter 面向ETL开发工程师，他们使用 MysqlWriter 从数仓导入数据到 Mysql。同时 MysqlWriter 亦可以作为数据迁移工具为DBA等用户提供服务。

MysqlWriter 通过 Addax 框架获取 Reader 生成的协议数据，根据你配置的 `writeMode` 生成 `insert into...`(当主键/唯一性索引冲突时会写不进去冲突的行)
或者 `replace into...`(没有遇到主键/唯一性索引冲突时，与 insert into 行为一致，冲突时会用新行替换原有行所有字段) 的语句写入数据到 Mysql。出于性能考虑，采用了 `PreparedStatement + Batch`，并且设置了：`rewriteBatchedStatements=true`，将数据缓冲到线程上下文 Buffer 中，当 Buffer
累计到预定阈值时，才发起写入请求。

## 示例

假定要写入的 MySQL 表建表语句如下：

```sql
create table test.addax_tbl 
(
col1 varchar(20) ,
col2 int(4),
col3 datetime,
col4 boolean,
col5 binary
) default charset utf8;
```

这里使用一份从内存产生到 Mysql 导入的数据。

```json
{
  "job": {
    "setting": {
      "speed": {
        "channel": 1,
        "bytes": -1
      }
    },
    "content": [
      {
        "reader": {
          "name": "streamreader",
          "parameter": {
            "column": [
              {
                "value": "Addax",
                "type": "string"
              },
              {
                "value": 19880808,
                "type": "long"
              },
              {
                "value": "1988-08-08 08:08:08",
                "type": "date"
              },
              {
                "value": true,
                "type": "bool"
              },
              {
                "value": "test",
                "type": "bytes"
              }
            ],
            "sliceRecordCount": 1000
          }
        },
        "writer": {
          "name": "mysqlwriter",
          "parameter": {
            "writeMode": "insert",
            "username": "root",
            "password": "",
            "column": [
              "*"
            ],
            "session": [
              "set session sql_mode='ANSI'"
            ],
            "preSql": [
              "delete from @table"
            ],
            "connection": [
              {
                "jdbcUrl": "jdbc:mysql://127.0.0.1:3306/test?useSSL=false",
                "table": [
                  "addax_tbl"
                ],
                "driver": "com.mysql.jdbc.Driver"
              }
            ]
          }
        }
      }
    ]
  }
}
```

将上述配置文件保存为  `job/stream2mysql.json`

### 执行采集命令

执行以下命令进行数据采集

```shell
bin/addax.py job/stream2mysql.json
```

## 参数说明

| 配置项          | 是否必须 | 类型  | 默认值 |         描述   |
| :-------------- | :------: | ------ |------------- |-------|
| jdbcUrl         |    是    | list | 无     | 对端数据库的JDBC连接信息，jdbcUrl按照RDBMS官方规范，并可以填写连接[附件控制信息](http://dev.mysql.com/doc/connector-j/en/connector-j-reference-configuration-properties.html) ｜
| driver          |   否     |  string   | 无      | 自定义驱动类名，解决兼容性问题，详见下面描述 |
| username        |    是    | string | 无     | 数据源的用户名 |
| password        |    否    | string | 无     | 数据源指定用户名的密码 |
| table           |    是    | list | 无     | 所选取的需要同步的表名,使用JSON数据格式，当配置为多张表时，用户自己需保证多张表是同一表结构 |
| column          |    是    | list | 无     |  所配置的表中需要同步的列名集合，详细描述见[rdbmswriter](rdbmswriter.md) ｜
| session         | 否      | list | 空  | Addax在获取Mysql连接时，执行session指定的SQL语句，修改当前connection session属性 |
| preSql         |    否    | list  | 无     | 数据写入钱先执行的sql语句，例如清除旧数据,如果 Sql 中有你需要操作到的表名称，可用 `@table` 表示 |
| postSql        |   否      | list | 无    | 数据写入完成后执行的sql语句，例如加上某一个时间戳|
| writeMode       | 是 |     string | insert | 数据写入表的方式, `insert` 表示采用 `insert into` , `replace`表示采用`replace into`方式 `update` 表示采用 `ON DUPLICATE KEY UPDATE` 语句 |
| batchSize       |    否    | int | 1024   | 定义了插件和数据库服务器端每次批量数据获取条数，调高该值可能导致 Addax 出现OOM或者目标数据库事务提交失败导致挂起 |

### driver

当前 Addax 采用的 MySQL JDBC 驱动为 8.0 以上版本，驱动类名使用的 `com.mysql.cj.jdbc.Driver`，而不是 `com.mysql.jdbc.Driver`。
如果你需要采集的 MySQL 服务低于 `5.6`，需要使用到 `Connector/J 5.1` 驱动，则可以采取下面的步骤：

**替换插件内置的驱动**

`rm -f plugin/writer/mysqlwriter/lib/mysql-connector-java-*.jar`

**拷贝老的驱动到插件目录**

`cp mysql-connector-java-5.1.48.jar plugin/writer/mysqlwriter/lib/`

**指定驱动类名称**

在你的 json 文件类，配置 `"driver": "com.mysql.jdbc.Driver"`

## 类型转换

目前 MysqlWriter 支持大部分 Mysql 类型，但也存在部分个别类型没有支持的情况，请注意检查你的类型。

下面列出 MysqlWriter 针对 Mysql 类型转换列表:

| Addax 内部类型| Mysql 数据类型    |
| -------- | -----  |
| Long     |int, tinyint, smallint, mediumint, int, bigint, year|
| Double   |float, double, decimal|
| String   |varchar, char, tinytext, text, mediumtext, longtext    |
| Date     |date, datetime, timestamp, time    |
| Boolean  |bit, bool   |
| Bytes    |tinyblob, mediumblob, blob, longblob, varbinary    |

bit类型目前是未定义类型转换
