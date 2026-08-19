package com.cixingji.backend.service.sync;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.cixingji.backend.config.DataSyncProperties;
import com.cixingji.backend.mapper.CanalSyncCheckpointMapper;
import com.cixingji.backend.mapper.SearchSyncEventMapper;
import com.cixingji.backend.pojo.dto.sync.CanalSyncEvent;
import com.cixingji.backend.pojo.entity.SearchSyncEvent;
import com.cixingji.backend.utils.ESUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.time.Instant;
import java.util.*;

@Slf4j
@Service
public class SearchSyncEventService {
    private final SearchSyncEventMapper eventMapper;
    private final CanalSyncCheckpointMapper checkpointMapper;
    private final DataSyncProperties properties;
    private final ESUtil esUtil;
    private final ObjectMapper objectMapper;
    private final KafkaTemplate<String, String> kafkaTemplate;

    public SearchSyncEventService(SearchSyncEventMapper eventMapper, CanalSyncCheckpointMapper checkpointMapper,
                                  DataSyncProperties properties, ESUtil esUtil, ObjectMapper objectMapper,
                                  @Qualifier("syncKafkaTemplate") KafkaTemplate<String, String> kafkaTemplate) {
        this.eventMapper = eventMapper; this.checkpointMapper = checkpointMapper; this.properties = properties;
        this.esUtil = esUtil; this.objectMapper = objectMapper; this.kafkaTemplate = kafkaTemplate;
    }

    @Transactional
    @KafkaListener(topics = "${sync.kafka.topic:video-index-sync}", groupId = "teriteri-search-sync-v1",
            autoStartup = "${sync.kafka.enabled:false}", containerFactory = "syncKafkaListenerContainerFactory")
    public void receive(String payload) throws IOException {
        CanalSyncEvent incoming = objectMapper.readValue(payload, CanalSyncEvent.class);
        SearchSyncEvent event = new SearchSyncEvent();
        event.setEventId(incoming.getEventId()); event.setSourceTable(incoming.getSourceTable());
        event.setEventType(incoming.getEventType()); event.setAggregateType(incoming.getAggregateType());
        event.setAggregateKey(incoming.getAggregateKey()); event.setBinlogFile(incoming.getBinlogFile());
        event.setBinlogPosition(incoming.getBinlogPosition()); event.setExecuteTime(incoming.getExecuteTime());
        event.setPayload(payload); event.setStatus("PENDING"); event.setAttempts(0);
        event.setCreatedAt(new Date()); event.setUpdatedAt(new Date());
        try { eventMapper.insert(event); }
        catch (DuplicateKeyException duplicate) { log.debug("Ignoring duplicate Canal event {}", incoming.getEventId()); }
    }

    @Scheduled(fixedDelay = 500)
    public void processPending() {
        if (!properties.isEnabled()) return;
        Date now = new Date();
        Date cutoff = Date.from(Instant.now().minusMillis(properties.getCoalesceMillis()));
        List<SearchSyncEvent> events;
        try {
            events = eventMapper.selectList(new QueryWrapper<SearchSyncEvent>()
                    .in("status", Arrays.asList("PENDING", "RETRY"))
                    .and(w -> w.isNull("next_retry_at").or().le("next_retry_at", now))
                    .le("updated_at", cutoff).orderByAsc("id").last("LIMIT 500"));
        } catch (Exception e) {
            log.warn("Search sync event polling failed; verify database migration", e);
            return;
        }
        Map<String, List<SearchSyncEvent>> groups = new LinkedHashMap<>();
        for (SearchSyncEvent event : events) groups.computeIfAbsent(event.getAggregateType() + ":" + event.getAggregateKey(), key -> new ArrayList<>()).add(event);
        for (List<SearchSyncEvent> group : groups.values()) processGroup(group);
    }

    private void processGroup(List<SearchSyncEvent> group) {
        SearchSyncEvent latest = group.get(group.size() - 1);
        try {
            synchronize(latest);
            List<Long> ids = new ArrayList<>();
            for (SearchSyncEvent event : group) ids.add(event.getId());
            eventMapper.update(null, new UpdateWrapper<SearchSyncEvent>().in("id", ids)
                    .set("status", "SUCCESS").set("processed_at", new Date()).set("updated_at", new Date())
                    .set("next_retry_at", null).set("error_message", null));
        } catch (Exception e) {
            for (SearchSyncEvent event : group) scheduleRetry(event, e);
        }
    }

    private void synchronize(SearchSyncEvent event) throws Exception {
        switch (event.getAggregateType()) {
            case "VIDEO": esUtil.synchronizeVideoNow(Integer.valueOf(event.getAggregateKey())); break;
            case "USER": esUtil.synchronizeVideosByUserNow(Integer.valueOf(event.getAggregateKey())); break;
            case "CATEGORY":
                String[] category = event.getAggregateKey().split("\\|", 2);
                if (category.length == 2) esUtil.synchronizeVideosByCategoryNow(category[0], category[1]);
                break;
            default: throw new IllegalArgumentException("Unsupported aggregate " + event.getAggregateType());
        }
    }

    private void scheduleRetry(SearchSyncEvent event, Exception failure) {
        int attempts = event.getAttempts() + 1;
        boolean dead = attempts >= properties.getMaxAttempts();
        String message = abbreviate(failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage());
        long delay = Math.min(300, 5L * (1L << Math.min(6, attempts - 1)));
        eventMapper.update(null, new UpdateWrapper<SearchSyncEvent>().eq("id", event.getId())
                .set("status", dead ? "DEAD" : "RETRY").set("attempts", attempts)
                .set("next_retry_at", dead ? null : Date.from(Instant.now().plusSeconds(delay)))
                .set("error_message", message).set("updated_at", new Date()));
        if (dead && properties.getKafka().isEnabled()) {
            try { kafkaTemplate.send(properties.getKafka().getDeadLetterTopic(), event.getAggregateType() + ":" + event.getAggregateKey(), event.getPayload()); }
            catch (Exception publishFailure) { log.error("Could not publish dead-letter event {}", event.getEventId(), publishFailure); }
        }
    }

    public Map<String, Object> overview() {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String status : Arrays.asList("PENDING", "RETRY", "SUCCESS", "DEAD")) {
            result.put(status.toLowerCase(Locale.ROOT), eventMapper.selectCount(new QueryWrapper<SearchSyncEvent>().eq("status", status)));
        }
        result.put("checkpoint", checkpointMapper.selectById(properties.getCanal().getDestination()));
        result.put("enabled", properties.isEnabled());
        return result;
    }

    public List<SearchSyncEvent> list(String status, int page, int size) {
        QueryWrapper<SearchSyncEvent> query = new QueryWrapper<>();
        if (status != null && !status.trim().isEmpty()) query.eq("status", status.trim().toUpperCase(Locale.ROOT));
        int safeSize = Math.max(1, Math.min(100, size));
        return eventMapper.selectList(query.orderByDesc("id").last("LIMIT " + Math.max(0, page - 1) * safeSize + "," + safeSize));
    }

    public long count(String status) {
        QueryWrapper<SearchSyncEvent> query = new QueryWrapper<>();
        if (status != null && !status.trim().isEmpty()) query.eq("status", status.trim().toUpperCase(Locale.ROOT));
        Long count = eventMapper.selectCount(query);
        return count == null ? 0 : count;
    }

    public int replay(Long id) {
        UpdateWrapper<SearchSyncEvent> update = new UpdateWrapper<>();
        if (id != null) update.eq("id", id); else update.eq("status", "DEAD");
        update.set("status", "PENDING").set("attempts", 0).set("next_retry_at", null)
                .set("error_message", null).set("updated_at", new Date());
        return eventMapper.update(null, update);
    }

    private String abbreviate(String value) { return value.length() <= 1900 ? value : value.substring(value.length() - 1900); }
}
