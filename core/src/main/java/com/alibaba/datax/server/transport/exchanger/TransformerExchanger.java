package com.alibaba.datax.server.transport.exchanger;

import com.alibaba.datax.common.element.Record;
import com.alibaba.datax.common.exception.DataXException;
import com.alibaba.datax.common.plugin.TaskPluginCollector;
import com.alibaba.datax.server.communicator.communication.Communication;
import com.alibaba.datax.server.transport.transformer.TransformerErrorCode;
import com.alibaba.datax.server.transport.transformer.TransformerExecution;
import com.alibaba.datax.server.util.ClassLoaderSwapper;
import com.alibaba.datax.server.util.CommunicationTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * no comments.
 * Created by liqiang on 16/3/9.
 */
public abstract class TransformerExchanger {

    private static final Logger LOG = LoggerFactory.getLogger(TransformerExchanger.class);
    protected final TaskPluginCollector pluginCollector;

    protected final int taskGroupId;
    protected final int taskId;
    protected final Communication currentCommunication;

    private long totalExaustedTime = 0;
    private long totalFilterRecords = 0;
    private long totalSuccessRecords = 0;
    private long totalFailedRecords = 0;

    //用于分别统计每个transformer的过滤和失败信息
    //数组对应transformerExecs列表
    private long[] filterRecordsPerTransform=null;
    private long[] failedRecordsPerTransform=null;


    private List<TransformerExecution> transformerExecs;

    private ClassLoaderSwapper classLoaderSwapper = ClassLoaderSwapper
            .newCurrentThreadClassLoaderSwapper();


    public TransformerExchanger(int taskGroupId, int taskId, Communication communication,
                                List<TransformerExecution> transformerExecs,
                                final TaskPluginCollector pluginCollector) {

        this.transformerExecs = transformerExecs;
        this.pluginCollector = pluginCollector;
        this.taskGroupId = taskGroupId;
        this.taskId = taskId;
        this.currentCommunication = communication;

        this.filterRecordsPerTransform=new long[transformerExecs.size()];
        this.failedRecordsPerTransform=new long[transformerExecs.size()];
    }


    public Record doTransformer(Record record) {
        if (transformerExecs == null || transformerExecs.size() == 0) {
            return record;
        }

        Record result = record;

        long diffExaustedTime = 0;
        String errorMsg = null;
        boolean failed = false;
        int idx=0;
        for (TransformerExecution transformerInfoExec : transformerExecs) {
            long startTs = System.nanoTime();

            if (transformerInfoExec.getClassLoader() != null) {
                classLoaderSwapper.setCurrentThreadClassLoader(transformerInfoExec.getClassLoader());
            }

            /**
             * 延迟检查transformer参数的有效性，直接抛出异常，不作为脏数据
             * 不需要在插件中检查参数的有效性。但参数的个数等和插件相关的参数，在插件内部检查
             */
            if (!transformerInfoExec.isChecked()) {

                if (transformerInfoExec.getColumnIndex() != null && transformerInfoExec.getColumnIndex() >= record.getColumnNumber()) {
                    throw DataXException.asDataXException(TransformerErrorCode.TRANSFORMER_ILLEGAL_PARAMETER,
                            String.format("columnIndex[%s] out of bound[%s]. name=%s",
                                    transformerInfoExec.getColumnIndex(), record.getColumnNumber(),
                                    transformerInfoExec.getTransformerName()));
                }
                transformerInfoExec.setIsChecked(true);
            }

            try {
                result = transformerInfoExec.getTransformer().evaluate(result, transformerInfoExec.gettContext(), transformerInfoExec.getFinalParas());
            } catch (Throwable throwable) {
                errorMsg = String.format("transformer(%s) has Exception(%s)", transformerInfoExec.getTransformerName(),
                        throwable.getMessage());
                failed = true;
                failedRecordsPerTransform[idx]++;
                //LOG.error(errorMsg, e);
                // transformerInfoExec.addFailedRecords(1);
                //脏数据不再进行后续transformer处理，按脏数据处理，并过滤该record。
                break;

            } finally {
                if (transformerInfoExec.getClassLoader() != null) {
                    classLoaderSwapper.restoreCurrentThreadClassLoader();
                }
            }

            if (result == null) {
                /**
                 * 这个null不能传到writer，必须消化掉
                 */
                totalFilterRecords++;
                filterRecordsPerTransform[idx]++;
                //transformerInfoExec.addFilterRecords(1);
                break;
            }

            long diff = System.nanoTime() - startTs;
            //transformerInfoExec.addExaustedTime(diff);
            diffExaustedTime += diff;
            //transformerInfoExec.addSuccessRecords(1);

            idx++;
        }

        totalExaustedTime += diffExaustedTime;

        if (failed) {
            totalFailedRecords++;
            this.pluginCollector.collectDirtyRecord(record, errorMsg);
            return null;
        } else {
            totalSuccessRecords++;
            return result;
        }
    }

    public void doStat() {

        /**
         * todo 对于多个transformer时，各个transformer的单独统计进行显示。最后再汇总整个transformer的时间消耗.
         * 暂时不统计。
         */
//        if (transformers.size() > 1) {
//            for (ransformerInfoExec transformerInfoExec : transformers) {
//                currentCommunication.setLongCounter(CommunicationTool.TRANSFORMER_NAME_PREFIX + transformerInfoExec.getTransformerName(), transformerInfoExec.getExaustedTime());
//            }
//        }
        currentCommunication.setLongCounter(CommunicationTool.TRANSFORMER_SUCCEED_RECORDS, totalSuccessRecords);
        currentCommunication.setLongCounter(CommunicationTool.TRANSFORMER_FAILED_RECORDS, totalFailedRecords);
        currentCommunication.setLongCounter(CommunicationTool.TRANSFORMER_FILTER_RECORDS, totalFilterRecords);
        currentCommunication.setLongCounter(CommunicationTool.TRANSFORMER_USED_TIME, totalExaustedTime);

        for(int idx=0; idx<filterRecordsPerTransform.length; idx++)
        {
            currentCommunication.setLongCounter("filterRecordsTransform_"+idx, filterRecordsPerTransform[idx]);
            currentCommunication.setLongCounter("failedRecordsTransform_"+idx, failedRecordsPerTransform[idx]);
        }
    }


}
