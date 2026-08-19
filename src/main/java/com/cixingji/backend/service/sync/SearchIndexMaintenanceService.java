package com.cixingji.backend.service.sync;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.cixingji.backend.config.DataSyncProperties;
import com.cixingji.backend.mapper.SearchIndexJobMapper;
import com.cixingji.backend.mapper.VideoMapper;
import com.cixingji.backend.pojo.entity.SearchIndexJob;
import com.cixingji.backend.pojo.entity.Video;
import com.cixingji.backend.utils.ESUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import javax.annotation.PostConstruct;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

@Slf4j
@Service
public class SearchIndexMaintenanceService {
    private final SearchIndexJobMapper jobMapper;
    private final VideoMapper videoMapper;
    private final ESUtil esUtil;
    private final DataSyncProperties properties;
    private final Executor taskExecutor;

    public SearchIndexMaintenanceService(SearchIndexJobMapper jobMapper, VideoMapper videoMapper, ESUtil esUtil,
                                         DataSyncProperties properties, @Qualifier("taskExecutor") Executor taskExecutor) {
        this.jobMapper = jobMapper; this.videoMapper = videoMapper; this.esUtil = esUtil;
        this.properties = properties; this.taskExecutor = taskExecutor;
    }

    @PostConstruct
    public void recoverInterruptedJobs() {
        if (!properties.isEnabled()) return;
        jobMapper.update(null, new UpdateWrapper<SearchIndexJob>().in("status", "QUEUED", "RUNNING")
                .set("status", "FAILED").set("error_message", "后端重启中断任务，请重新发起")
                .set("finished_at", new Date()));
    }

    public synchronized SearchIndexJob startRebuild() {
        ensureNoActiveJob();
        Long total = videoMapper.selectCount(new QueryWrapper<Video>().eq("status", 1));
        SearchIndexJob job = create("REBUILD", total == null ? 0 : total.intValue());
        taskExecutor.execute(() -> runRebuild(job.getId()));
        return job;
    }

    public synchronized SearchIndexJob startConsistencyCheck(boolean repair) {
        ensureNoActiveJob();
        SearchIndexJob job = create(repair ? "CONSISTENCY_REPAIR" : "CONSISTENCY_CHECK", 0);
        taskExecutor.execute(() -> runConsistency(job.getId(), repair));
        return job;
    }

    @Scheduled(cron = "0 40 2 * * ?")
    public void dailyConsistencyRepair() {
        if (!properties.isEnabled()) return;
        try { startConsistencyCheck(true); }
        catch (IllegalStateException active) { log.info("Skipping daily consistency repair because another index job is running"); }
    }

    public List<SearchIndexJob> jobs(int limit) {
        return jobMapper.selectList(new QueryWrapper<SearchIndexJob>().orderByDesc("id").last("LIMIT " + Math.max(1, Math.min(100, limit))));
    }

    private void runRebuild(Long jobId) {
        start(jobId);
        try {
            int total = esUtil.rebuildVideoSearchIndexNow(processed -> jobMapper.update(null,
                    new UpdateWrapper<SearchIndexJob>().eq("id", jobId).set("processed", processed)));
            jobMapper.update(null, new UpdateWrapper<SearchIndexJob>().eq("id", jobId)
                    .set("status", "SUCCESS").set("total", total).set("processed", total).set("finished_at", new Date()));
        } catch (Exception e) { fail(jobId, e); }
    }

    private void runConsistency(Long jobId, boolean repair) {
        start(jobId);
        try {
            Map<String, Integer> report = esUtil.reconcileVideoSearchIndex(repair);
            jobMapper.update(null, new UpdateWrapper<SearchIndexJob>().eq("id", jobId)
                    .set("status", "SUCCESS").set("total", report.get("mysql")).set("processed", report.get("mysql"))
                    .set("missing_count", report.get("missing")).set("stale_count", report.get("stale"))
                    .set("extra_count", report.get("extra")).set("repaired_count", report.get("repaired"))
                    .set("finished_at", new Date()));
        } catch (Exception e) { fail(jobId, e); }
    }

    private SearchIndexJob create(String type, int total) {
        Date now = new Date();
        SearchIndexJob job = new SearchIndexJob();
        job.setJobType(type); job.setStatus("QUEUED"); job.setTotal(total); job.setProcessed(0);
        job.setMissingCount(0); job.setStaleCount(0); job.setExtraCount(0); job.setRepairedCount(0); job.setCreatedAt(now);
        jobMapper.insert(job);
        return job;
    }

    private void start(Long id) {
        jobMapper.update(null, new UpdateWrapper<SearchIndexJob>().eq("id", id)
                .set("status", "RUNNING").set("started_at", new Date()));
    }

    private void fail(Long id, Exception e) {
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        if (message.length() > 1900) message = message.substring(message.length() - 1900);
        jobMapper.update(null, new UpdateWrapper<SearchIndexJob>().eq("id", id)
                .set("status", "FAILED").set("error_message", message).set("finished_at", new Date()));
        log.error("Search index maintenance job {} failed", id, e);
    }

    private void ensureNoActiveJob() {
        Long active = jobMapper.selectCount(new QueryWrapper<SearchIndexJob>().in("status", "QUEUED", "RUNNING"));
        if (active != null && active > 0) throw new IllegalStateException("已有索引维护任务正在执行");
    }
}
