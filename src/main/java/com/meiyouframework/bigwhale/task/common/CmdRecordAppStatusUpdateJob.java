package com.meiyouframework.bigwhale.task.common;

import com.meiyouframework.bigwhale.common.Constant;
import com.meiyouframework.bigwhale.common.pojo.HttpYarnApp;
import com.meiyouframework.bigwhale.entity.*;
import com.meiyouframework.bigwhale.service.*;
import com.meiyouframework.bigwhale.task.AbstractCmdRecordTask;
import com.meiyouframework.bigwhale.util.SpringContextUtils;
import com.meiyouframework.bigwhale.util.YarnApiUtils;
import org.apache.commons.lang.StringUtils;
import org.quartz.*;
import org.springframework.util.CollectionUtils;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Collection;

/**
 * @author Suxy
 * @date 2020/4/23
 * @description file description
 */
@DisallowConcurrentExecution
public class CmdRecordAppStatusUpdateJob extends AbstractCmdRecordTask implements InterruptableJob {

    private DateFormat dateFormat = new SimpleDateFormat("yyyyMMddHHmmss");
    private Thread thread;
    private volatile boolean interrupted = false;

    private ScriptService scriptService;

    public CmdRecordAppStatusUpdateJob() {
        scriptService = SpringContextUtils.getBean(ScriptService.class);
    }

    @Override
    public void interrupt() {
        if (!interrupted) {
            interrupted = true;
            thread.interrupt();
        }
    }

    @Override
    public void execute(JobExecutionContext jobExecutionContext) {
        thread = Thread.currentThread();
        Collection<CmdRecord> records = cmdRecordService.findByQuery("jobFinalStatus=UNDEFINED");
        if (CollectionUtils.isEmpty(records)) {
            return;
        }
        ClusterService clusterService = SpringContextUtils.getBean(ClusterService.class);
        for (CmdRecord cmdRecord : records) {
            Cluster cluster = clusterService.findById(cmdRecord.getClusterId());
            Script script = scriptService.findById(cmdRecord.getScriptId());
            HttpYarnApp httpYarnApp;
            if (cmdRecord.getJobId() != null) {
                httpYarnApp = YarnApiUtils.getApp(cluster.getYarnUrl(), cmdRecord.getJobId());
                if (httpYarnApp != null && "UNDEFINED".equals(httpYarnApp.getFinalStatus())) {
                    continue;
                }
                updateStatus(cmdRecord, script, httpYarnApp);
            } else {
                httpYarnApp = YarnApiUtils.getActiveApp(cluster.getYarnUrl(), script.getUser(), script.getQueue(), script.getApp() + "_instance" + dateFormat.format(cmdRecord.getStartTime()), 3);
                if (httpYarnApp != null) {
                    continue;
                }
                httpYarnApp = YarnApiUtils.getLastNoActiveApp(cluster.getYarnUrl(), script.getUser(), script.getQueue(), script.getApp(), 3);
                updateStatus(cmdRecord, script, httpYarnApp);
            }
        }
    }

    private void updateStatus(CmdRecord cmdRecord, Script script, HttpYarnApp httpYarnApp) {
        if (httpYarnApp != null) {
            Scheduling scheduling = StringUtils.isNotBlank(cmdRecord.getSchedulingId()) ? schedulingService.findById(cmdRecord.getSchedulingId()) : null;
            String finalStatus = httpYarnApp.getFinalStatus();
            cmdRecord.setJobFinalStatus(finalStatus);
            if ("SUCCEEDED".equals(finalStatus)) {
                //提交子任务
                AgentService agentService = SpringContextUtils.getBean(AgentService.class);
                submitNextCmdRecord(cmdRecord, scheduling, agentService, scriptService);
            } else {
                if (script.getType() == Constant.SCRIPT_TYPE_SPARK_BATCH) {
                    notice(cmdRecord, null, scheduling, httpYarnApp.getId(), String.format(Constant.ERROR_TYPE_SPARK_BATCH_UNUSUAL, finalStatus));
                } else {
                    notice(cmdRecord, null, scheduling, httpYarnApp.getId(), String.format(Constant.ERROR_TYPE_FLINK_BATCH_UNUSUAL, finalStatus));
                }
            }
        } else {
            cmdRecord.setJobFinalStatus("UNKNOWN");
        }
        cmdRecordService.save(cmdRecord);
    }
}
