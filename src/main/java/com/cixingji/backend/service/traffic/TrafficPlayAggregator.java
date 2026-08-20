package com.cixingji.backend.service.traffic;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.cixingji.backend.config.TrafficProperties;
import com.cixingji.backend.mapper.TrafficPlayEventMapper;
import com.cixingji.backend.pojo.entity.TrafficPlayEvent;
import com.cixingji.backend.utils.ESUtil;
import com.cixingji.backend.utils.RedisUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.*;

@Slf4j
@Service
public class TrafficPlayAggregator {
    private final TrafficProperties properties;
    private final TrafficPlayEventMapper mapper;
    private final TransactionTemplate transactionTemplate;
    private final RedisUtil redisUtil;
    private final ESUtil esUtil;
    private final TrafficMetrics metrics;

    public TrafficPlayAggregator(TrafficProperties properties, TrafficPlayEventMapper mapper,
                                 TransactionTemplate transactionTemplate, RedisUtil redisUtil,
                                 ESUtil esUtil, TrafficMetrics metrics) {
        this.properties = properties;
        this.mapper = mapper;
        this.transactionTemplate = transactionTemplate;
        this.redisUtil = redisUtil;
        this.esUtil = esUtil;
        this.metrics = metrics;
    }

    @Scheduled(fixedDelayString = "${traffic.aggregate-delay-ms:2000}")
    public void aggregatePending() {
        if (!properties.isEnabled() || !properties.getKafka().isEnabled()) return;
        List<TrafficPlayEvent> events;
        try {
            events = mapper.selectList(new QueryWrapper<TrafficPlayEvent>()
                    .and(query -> query.in("status", Arrays.asList("PENDING", "RETRY"))
                            .and(due -> due.isNull("next_retry_at").or().le("next_retry_at", new Date()))
                            .or(expired -> expired.eq("status", "PROCESSING").le("next_retry_at", new Date())))
                    .orderByAsc("id").last("LIMIT " + Math.max(1, properties.getAggregateBatchSize())));
        } catch (Exception e) {
            log.warn("Playback aggregation polling failed; verify traffic_kafka.sql", e);
            return;
        }
        Map<Integer, List<TrafficPlayEvent>> groups = new LinkedHashMap<>();
        for (TrafficPlayEvent event : events) groups.computeIfAbsent(event.getVid(), key -> new ArrayList<>()).add(event);
        for (Map.Entry<Integer, List<TrafficPlayEvent>> group : groups.entrySet()) aggregate(group.getKey(), group.getValue());
    }

    private void aggregate(Integer vid, List<TrafficPlayEvent> events) {
        if (!claim(events)) return;
        try {
            transactionTemplate.executeWithoutResult(status -> {
                if (mapper.incrementPlay(vid, events.size()) != 1) throw new IllegalStateException("Video stats not found: " + vid);
                List<Long> ids = new ArrayList<>();
                for (TrafficPlayEvent event : events) ids.add(event.getId());
                mapper.update(null, new UpdateWrapper<TrafficPlayEvent>().in("id", ids)
                        .set("status", "SUCCESS").set("processed_at", new Date())
                        .set("next_retry_at", null).set("error_message", null));
            });
            metrics.aggregated(events.size());
            redisUtil.delValue("videoStats:" + vid);
            try { esUtil.updateVideoById(vid); }
            catch (Exception searchFailure) { log.warn("Playback aggregated but Elasticsearch refresh failed for video {}", vid, searchFailure); }
        } catch (Exception failure) {
            for (TrafficPlayEvent event : events) scheduleRetry(event, failure);
        }
    }

    /** 用带租约的 PROCESSING 状态避免多个后端实例重复聚合同一批播放。 */
    private boolean claim(List<TrafficPlayEvent> events) {
        List<Long> ids = new ArrayList<>();
        for (TrafficPlayEvent event : events) ids.add(event.getId());
        Date now = new Date();
        int claimed = mapper.update(null, new UpdateWrapper<TrafficPlayEvent>().in("id", ids)
                .and(query -> query.in("status", Arrays.asList("PENDING", "RETRY"))
                        .or(expired -> expired.eq("status", "PROCESSING").le("next_retry_at", now)))
                .set("status", "PROCESSING")
                .set("next_retry_at", Date.from(Instant.now().plusSeconds(60))));
        return claimed == events.size();
    }

    private void scheduleRetry(TrafficPlayEvent event, Exception failure) {
        int attempts = event.getAttempts() == null ? 1 : event.getAttempts() + 1;
        boolean dead = attempts >= Math.max(1, properties.getMaxAttempts());
        long delay = Math.min(300, 2L * (1L << Math.min(7, attempts - 1)));
        String message = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
        if (message.length() > 1900) message = message.substring(message.length() - 1900);
        mapper.update(null, new UpdateWrapper<TrafficPlayEvent>().eq("id", event.getId())
                .set("status", dead ? "DEAD" : "RETRY")
                .set("attempts", attempts)
                .set("next_retry_at", dead ? null : Date.from(Instant.now().plusSeconds(delay)))
                .set("error_message", message));
    }
}
