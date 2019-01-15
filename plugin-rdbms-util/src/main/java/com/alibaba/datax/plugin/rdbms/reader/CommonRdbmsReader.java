package com.alibaba.datax.plugin.rdbms.reader;

import com.alibaba.datax.common.element.BoolColumn;
import com.alibaba.datax.common.element.BytesColumn;
import com.alibaba.datax.common.element.DateColumn;
import com.alibaba.datax.common.element.DoubleColumn;
import com.alibaba.datax.common.element.LongColumn;
import com.alibaba.datax.common.element.Record;
import com.alibaba.datax.common.element.StringColumn;
import com.alibaba.datax.common.exception.DataXException;
import com.alibaba.datax.common.plugin.RecordSender;
import com.alibaba.datax.common.plugin.TaskPluginCollector;
import com.alibaba.datax.common.statistics.PerfRecord;
import com.alibaba.datax.common.statistics.PerfTrace;
import com.alibaba.datax.common.util.Configuration;
import com.alibaba.datax.plugin.rdbms.reader.util.OriginalConfPretreatmentUtil;
import com.alibaba.datax.plugin.rdbms.reader.util.PreCheckTask;
import com.alibaba.datax.plugin.rdbms.reader.util.ReaderSplitUtil;
import com.alibaba.datax.plugin.rdbms.reader.util.SingleTableSplitUtil;
import com.alibaba.datax.plugin.rdbms.util.DBUtil;
import com.alibaba.datax.plugin.rdbms.util.DBUtilErrorCode;
import com.alibaba.datax.plugin.rdbms.util.DataBaseType;
import com.alibaba.datax.plugin.rdbms.util.RdbmsException;
import com.alibaba.fastjson.JSONArray;
import com.google.common.collect.Lists;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class CommonRdbmsReader {

    public static class Job {
        private static final Logger LOG = LoggerFactory
                .getLogger(Job.class);

        public Job(DataBaseType dataBaseType) {
            OriginalConfPretreatmentUtil.DATABASE_TYPE = dataBaseType;
            SingleTableSplitUtil.DATABASE_TYPE = dataBaseType;
        }

        public void init(Configuration originalConfig) {

            OriginalConfPretreatmentUtil.doPretreatment(originalConfig);

            LOG.debug("After job init(), job config now is:[\n{}\n]",
                    originalConfig.toJSON());
        }

        public void preCheck(Configuration originalConfig,DataBaseType dataBaseType) {
            /*检查每个表是否有读权限，以及querySql跟splik Key是否正确*/
            Configuration queryConf = ReaderSplitUtil.doPreCheckSplit(originalConfig);
            String splitPK = queryConf.getString(Key.SPLIT_PK);
            List<Object> connList = queryConf.getList(Constant.CONN_MARK, Object.class);
            String username = queryConf.getString(Key.USERNAME);
            String password = queryConf.getString(Key.PASSWORD);
            ExecutorService exec;
            if (connList.size() < 10){
                exec = Executors.newFixedThreadPool(connList.size());
            }else{
                exec = Executors.newFixedThreadPool(10);
            }
            Collection<PreCheckTask> taskList = new ArrayList<PreCheckTask>();
            for (int i = 0, len = connList.size(); i < len; i++){
                Configuration connConf = Configuration.from(connList.get(i).toString());
                PreCheckTask t = new PreCheckTask(username,password,connConf,dataBaseType,splitPK);
                taskList.add(t);
            }
            List<Future<Boolean>> results = Lists.newArrayList();
            try {
                results = exec.invokeAll(taskList);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            for (Future<Boolean> result : results){
                try {
                    result.get();
                } catch (ExecutionException e) {
                    DataXException de = (DataXException) e.getCause();
                    throw de;
                }catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            exec.shutdownNow();
        }


        public List<Configuration> split(Configuration originalConfig,
                                         int adviceNumber) {
            List<Configuration> splitList=ReaderSplitUtil.doSplit(originalConfig, adviceNumber);
            LOG.info("Split完成,建议数量为: "+adviceNumber+", 实际数量为: "+splitList.size());
            return splitList;
        }

        public void post(Configuration originalConfig) {
            // do nothing
        }

        public void destroy(Configuration originalConfig) {
            // do nothing
        }

    }

    public static class Task {
        private static final Logger LOG = LoggerFactory
                .getLogger(Task.class);
        private static final boolean IS_DEBUG = LOG.isDebugEnabled();
        protected final byte[] EMPTY_CHAR_ARRAY = new byte[0];

        private DataBaseType dataBaseType;
        private int taskGroupId = -1;
        private int taskId=-1;

        private String username;
        private String password;
        private String jdbcUrl;
        private String mandatoryEncoding;

        // 作为日志显示信息时，需要附带的通用信息。比如信息所对应的数据库连接等信息，针对哪个表做的操作
        private String basicMsg;

        public Task(DataBaseType dataBaseType) {
            this(dataBaseType, -1, -1);
        }

        public Task(DataBaseType dataBaseType,int taskGropuId, int taskId) {
            this.dataBaseType = dataBaseType;
            this.taskGroupId = taskGropuId;
            this.taskId = taskId;
        }

        public void init(Configuration readerSliceConfig) {

			/* for database connection */

            this.username = readerSliceConfig.getString(Key.USERNAME);
            this.password = readerSliceConfig.getString(Key.PASSWORD);
            this.jdbcUrl = readerSliceConfig.getString(Key.JDBC_URL);

            //ob10的处理
            if (this.jdbcUrl.startsWith(com.alibaba.datax.plugin.rdbms.writer.Constant.OB10_SPLIT_STRING) && this.dataBaseType == DataBaseType.MySql) {
                String[] ss = this.jdbcUrl.split(com.alibaba.datax.plugin.rdbms.writer.Constant.OB10_SPLIT_STRING_PATTERN);
                if (ss.length != 3) {
                    throw DataXException
                            .asDataXException(
                                    DBUtilErrorCode.JDBC_OB10_ADDRESS_ERROR, "JDBC OB10格式错误，请联系askdatax");
                }
                LOG.info("this is ob1_0 jdbc url.");
                this.username = ss[1].trim() +":"+this.username;
                this.jdbcUrl = ss[2];
                LOG.info("this is ob1_0 jdbc url. user=" + this.username + " :url=" + this.jdbcUrl);
            }

            this.mandatoryEncoding = readerSliceConfig.getString(Key.MANDATORY_ENCODING, "");

            basicMsg = String.format("jdbcUrl:[%s]", this.jdbcUrl);

        }

        public void startRead(Configuration readerSliceConfig,
                              RecordSender recordSender,
                              TaskPluginCollector taskPluginCollector, int fetchSize) {
            String querySql = readerSliceConfig.getString(Key.QUERY_SQL);
            String table = readerSliceConfig.getString(Key.TABLE);
            String bindVarsStr=readerSliceConfig.getString(Key.QUERY_SQL_BIND_VARS);
            //Key.QUERY_SQL_BIND_VAR是二维数组,第一维对应Statement，第二维对应SQL里多个绑定变量,第一维数量不定,第二维数量由参数指定
            JSONArray bindVarsArr=new JSONArray();
            int singleBindValsCnt=0;
            if(bindVarsStr!=null && !bindVarsStr.trim().equals(""))
            {
                singleBindValsCnt=readerSliceConfig.getInt(Key.BIND_VAL_CNT,0);
                try {
                    bindVarsArr=(JSONArray)JSONArray.parse(bindVarsStr);
                    if(bindVarsArr==null || bindVarsArr.isEmpty())
                    {
                        throw DataXException
                                .asDataXException(
                                        DBUtilErrorCode.ILLEGAL_VALUE,
                                        String.format(
                                                "您配置了绑定变量 [{}], 但是解析失败, 必须是JSON数组格式.",
                                                bindVarsStr));
                    }
                }
                catch(Exception e)
                {
                    throw DataXException
                            .asDataXException(
                                    DBUtilErrorCode.ILLEGAL_VALUE,
                                    String.format(
                                            "您配置了绑定变量 [{}], 但是解析失败, 必须是JSON数组格式.",
                                            bindVarsStr));
                }
            }

            PerfTrace.getInstance().addTaskDetails(taskId, table + "," + basicMsg);

            //如果有绑定变量则不打印SQL，SQL太长了
            if(bindVarsArr.isEmpty())
            {
                LOG.info("Begin to read record by Sql: [{}\n] {}.",
                        querySql, basicMsg);
            }

            PerfRecord queryPerfRecord = new PerfRecord(taskGroupId,taskId, PerfRecord.PHASE.SQL_QUERY);

            Connection conn = DBUtil.getConnection(this.dataBaseType, jdbcUrl,
                    username, password);

            // session config .etc related
            DBUtil.dealWithSessionConfig(conn, readerSliceConfig,
                    this.dataBaseType, basicMsg);

            PerfRecord allResultPerfRecord = new PerfRecord(taskGroupId, taskId, PerfRecord.PHASE.RESULT_NEXT_ALL);

            int columnNumber = 0;
            ResultSetMetaData metaData=null;
            //for cursor close
            PreparedStatement ps=null;
            ResultSet rs = null;
            int idx=0;
            try {
                do {
                    //没有绑定变量
                    if(bindVarsArr.isEmpty())
                    {
                        LOG.info("Query without bind variables.");
                        queryPerfRecord.start();
                        rs = DBUtil.query(conn, querySql, fetchSize);
                        queryPerfRecord.end();
                    }
                    //有绑定变量,处理中...
                    else if(idx<bindVarsArr.size())
                    {
                        queryPerfRecord.start();
                        //long startTimestamp=System.currentTimeMillis();
                        //LOG.info("Query with bind variables: [{}] ",bindVarsArr.getJSONArray(idx));
                        //一次绑定变量的数量
                        if(singleBindValsCnt==0)
                        {
                            throw DataXException
                                    .asDataXException(
                                            DBUtilErrorCode.ILLEGAL_VALUE,
                                            String.format(
                                                    "有绑定变量,一次绑定变量的数量却是0,应该是程序bug,请联系管理员."));
                        }
                        try {
                            rs = DBUtil.query(conn, querySql, bindVarsArr.getJSONArray(idx),singleBindValsCnt, fetchSize);
                        }
                        catch(Exception e)
                        {
                            LOG.error("Error in prepareStatement: "+bindVarsArr.getJSONArray(idx),e);
                            throw DataXException
                                    .asDataXException(
                                            DBUtilErrorCode.ORACLE_QUERY_SQL_ERROR,
                                            String.format(
                                                    "有绑定变量,执行SQL语句出错."));
                        }
                        //LOG.info("查询结束,耗时: "+(System.currentTimeMillis()-startTimestamp));
                        queryPerfRecord.end();
                    }
                    //有绑定变量,处理完
                    else
                    {
                        break;
                    }

                    if(metaData==null)
                    {
                        metaData = rs.getMetaData();
                        columnNumber = metaData.getColumnCount();
                    }

                    //这个统计干净的result_Next时间
                    allResultPerfRecord.start();

                    long rsNextUsedTime = 0;
                    long lastTime = System.nanoTime();

                    //long startNextTime=System.currentTimeMillis();
                    //long nextTimeSum=0;
                    //long startTransportTime=0;
                    //long transTimeSum=0;

                    //int cnt=1;
                    LOG.info("Begin to transport records...");
                    while (rs.next()) {
                        //nextTimeSum+=(System.currentTimeMillis()-startNextTime);
                        //if(cnt>=10000)
                        //{
                            //LOG.info("next平均耗时: "+(1.0*nextTimeSum/10000.0));
                            //nextTimeSum=0;
                        //}

                        rsNextUsedTime += (System.nanoTime() - lastTime);
                        //startTransportTime=System.currentTimeMillis();
                        this.transportOneRecord(recordSender, rs,
                                metaData, columnNumber, mandatoryEncoding, taskPluginCollector);
                        //transTimeSum+=System.currentTimeMillis()-startTransportTime;
                        //if(cnt>=10000)
                        //{
                            //LOG.info("trans平均耗时: "+(1.0*transTimeSum/10000.0));
                            //transTimeSum=0;
                            //cnt=0;
                        //}
                        lastTime = System.nanoTime();
                        //startNextTime=System.currentTimeMillis();
                        //cnt++;
                    }

                    allResultPerfRecord.end(rsNextUsedTime);

                    //目前大盘是依赖这个打印，而之前这个Finish read record是包含了sql查询和result next的全部时间
                    LOG.info("Finished read record...");
                    //LOG.info("Finished read record by Sql: [{}\n] [{}\n] {}.",
                            //querySql, (bindVarsArr.isEmpty()?"":bindVarsArr.getJSONArray(idx)),basicMsg);

                    if(bindVarsArr.isEmpty())
                    {
                        break;
                    }
                    else
                    {
                        //关闭游标
                        DBUtil.closeDBResources(rs, null, null);
                        idx++;
                    }
                }
                while(true);
            }catch (Exception e) {
                throw RdbmsException.asQueryException(this.dataBaseType, e, querySql, table, username);
            } finally {
                DBUtil.closeDBResources(null, conn);
            }
        }

        public void post(Configuration originalConfig) {
            // do nothing
        }

        public void destroy(Configuration originalConfig) {
            // do nothing
        }
        
        protected Record transportOneRecord(RecordSender recordSender, ResultSet rs, 
                ResultSetMetaData metaData, int columnNumber, String mandatoryEncoding, 
                TaskPluginCollector taskPluginCollector) {
            Record record = buildRecord(recordSender,rs,metaData,columnNumber,mandatoryEncoding,taskPluginCollector); 
            recordSender.sendToWriter(record);
            return record;
        }
        protected Record buildRecord(RecordSender recordSender,ResultSet rs, ResultSetMetaData metaData, int columnNumber, String mandatoryEncoding,
        		TaskPluginCollector taskPluginCollector) {
        	Record record = recordSender.createRecord();

            try {
                for (int i = 1; i <= columnNumber; i++) {
                    switch (metaData.getColumnType(i)) {

                    case Types.CHAR:
                    case Types.NCHAR:
                    case Types.VARCHAR:
                    case Types.LONGVARCHAR:
                    case Types.NVARCHAR:
                    case Types.LONGNVARCHAR:
                        String rawData;
                        if(StringUtils.isBlank(mandatoryEncoding)){
                            rawData = rs.getString(i);
                        }else{
                            rawData = new String((rs.getBytes(i) == null ? EMPTY_CHAR_ARRAY : 
                                rs.getBytes(i)), mandatoryEncoding);
                        }
                        record.addColumn(new StringColumn(rawData));
                        break;

                    case Types.CLOB:
                    case Types.NCLOB:
                        record.addColumn(new StringColumn(rs.getString(i)));
                        break;

                    case Types.SMALLINT:
                    case Types.TINYINT:
                    case Types.INTEGER:
                    case Types.BIGINT:
                        record.addColumn(new LongColumn(rs.getString(i)));
                        break;

                    case Types.NUMERIC:
                    case Types.DECIMAL:
                        record.addColumn(new DoubleColumn(rs.getString(i)));
                        break;

                    case Types.FLOAT:
                    case Types.REAL:
                    case Types.DOUBLE:
                        record.addColumn(new DoubleColumn(rs.getString(i)));
                        break;

                    case Types.TIME:
                        record.addColumn(new DateColumn(rs.getTime(i)));
                        break;

                    // for mysql bug, see http://bugs.mysql.com/bug.php?id=35115
                    case Types.DATE:
                        if (metaData.getColumnTypeName(i).equalsIgnoreCase("year")) {
                            record.addColumn(new LongColumn(rs.getInt(i)));
                        } else {
                            record.addColumn(new DateColumn(rs.getDate(i)));
                        }
                        break;

                    case Types.TIMESTAMP:
                        record.addColumn(new DateColumn(rs.getTimestamp(i)));
                        break;

                    case Types.BINARY:
                    case Types.VARBINARY:
                    case Types.BLOB:
                    case Types.LONGVARBINARY:
                        record.addColumn(new BytesColumn(rs.getBytes(i)));
                        break;

                    // warn: bit(1) -> Types.BIT 可使用BoolColumn
                    // warn: bit(>1) -> Types.VARBINARY 可使用BytesColumn
                    case Types.BOOLEAN:
                    case Types.BIT:
                        record.addColumn(new BoolColumn(rs.getBoolean(i)));
                        break;

                    case Types.NULL:
                        String stringData = null;
                        if(rs.getObject(i) != null) {
                            stringData = rs.getObject(i).toString();
                        }
                        record.addColumn(new StringColumn(stringData));
                        break;

                    default:
                        throw DataXException
                                .asDataXException(
                                        DBUtilErrorCode.UNSUPPORTED_TYPE,
                                        String.format(
                                                "您的配置文件中的列配置信息有误. 因为DataX 不支持数据库读取这种字段类型. 字段名:[%s], 字段名称:[%s], 字段Java类型:[%s]. 请尝试使用数据库函数将其转换datax支持的类型 或者不同步该字段 .",
                                                metaData.getColumnName(i),
                                                metaData.getColumnType(i),
                                                metaData.getColumnClassName(i)));
                    }
                }
            } catch (Exception e) {
                if (IS_DEBUG) {
                    LOG.debug("read data " + record.toString()
                            + " occur exception:", e);
                }
                //TODO 这里识别为脏数据靠谱吗？
                taskPluginCollector.collectDirtyRecord(record, e);
                if (e instanceof DataXException) {
                    throw (DataXException) e;
                }
            }
            return record;
        }
    }

}
