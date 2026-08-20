package com.cixingji.backend.service.traffic;

import com.cixingji.backend.config.TrafficProperties;
import com.cixingji.backend.mapper.TrafficPlayEventMapper;
import com.cixingji.backend.pojo.dto.traffic.PlayEvent;
import com.cixingji.backend.pojo.entity.TrafficPlayEvent;
import com.cixingji.backend.service.video.VideoStatsService;
import com.cixingji.backend.utils.RedisUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class TrafficEventService {
    private final TrafficProperties properties;
    private final RedisUtil redisUtil;
    private final VideoStatsService videoStatsService;
    private final TrafficPlayEventMapper playEventMapper;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final TrafficMetrics metrics;
    private final Cache<String, Boolean> localDedupe;

    public TrafficEventService(TrafficProperties properties, RedisUtil redisUtil,
                               VideoStatsService videoStatsService, TrafficPlayEventMapper playEventMapper,
                               @Qualifier("syncKafkaTemplate") KafkaTemplate<String, String> kafkaTemplate,
                               ObjectMapper objectMapper, TrafficMetrics metrics) {
        this.properties = properties;
        this.redisUtil = redisUtil;
        this.videoStatsService = videoStatsService;
        this.playEventMapper = playEventMapper;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
        this.localDedupe = CacheBuilder.newBuilder()
                .maximumSize(500_000)
                .expireAfterWrite(Math.max(1, properties.getPlayDeduplicateSeconds()), TimeUnit.SECONDS)
                .build();
    }

    /** 返回 true 表示本次播放被接受，false 表示处于同一去重窗口。 */
    public boolean publishPlay(Integer vid, String actorType, String actorIdentity) {
        long windowSeconds = Math.max(1, properties.getPlayDeduplicateSeconds());
        String actorKey = TrafficIdentity.hash(actorType + ":" + actorIdentity);
        long occurredAt = System.currentTimeMillis();
        long window = occurredAt / (windowSeconds * 1000L);
        String eventId = TrafficIdentity.hash(actorKey + ":" + vid + ":" + window);
        if (!firstInWindow(eventId, windowSeconds)) return false;

        if (!properties.isEnabled() || !properties.getKafka().isEnabled()) {
            videoStatsService.updateStats(vid, "play", true, 1);
            return true;
        }

        PlayEvent event = new PlayEvent(eventId, vid, actorType, actorKey, occurredAt);
        try {
            String payload = objectMapper.writeValueAsString(event);
            kafkaTemplate.send(properties.getKafka().getPlayTopic(), String.valueOf(vid), payload)
                    .addCallback(result -> metrics.published(), failure -> {
                        metrics.publishFailed();
                        log.error("Kafka play publish failed, storing durable inbox event {}", eventId, failure);
                        try { storeInbox(event); }
                        catch (Exception databaseFailure) {
                            log.error("Could not persist failed Kafka play event {}", eventId, databaseFailure);
                        }
                    });
            return true;
        } catch (Exception failure) {
            metrics.publishFailed();
            storeInbox(event);
            return true;
        }
    }

    private boolean firstInWindow(String eventId, long windowSeconds) {
        try {
            return redisUtil.setIfAbsent("traffic:play:dedupe:" + eventId, "1", windowSeconds, TimeUnit.SECONDS);
        } catch (Exception redisFailure) {
            log.warn("Redis play deduplication unavailable, using local cache", redisFailure);
            return localDedupe.asMap().putIfAbsent(eventId, Boolean.TRUE) == null;
        }
    }

    public void storeInbox(PlayEvent event) {
        TrafficPlayEvent entity = new TrafficPlayEvent();
        entity.setEventId(event.getEventId());
        entity.setVid(event.getVid());
        entity.setActorType(event.getActorType());
        entity.setActorKey(event.getActorKey());
        entity.setStatus("PENDING");
        entity.setAttempts(0);
        entity.setOccurredAt(new Date(event.getOccurredAt()));
        entity.setCreatedAt(new Date());
        try {
            playEventMapper.insert(entity);
        } catch (DuplicateKeyException duplicate) {
            log.debug("Ignoring duplicate play event {}", event.getEventId());
        }
    }
}
