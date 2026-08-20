package com.cixingji.backend.service.traffic;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.cixingji.backend.config.TrafficProperties;
import com.cixingji.backend.mapper.TrafficBusinessLogMapper;
import com.cixingji.backend.mapper.TrafficDeadLetterMapper;
import com.cixingji.backend.mapper.TrafficPlayEventMapper;
import com.cixingji.backend.pojo.entity.TrafficBusinessLog;
import com.cixingji.backend.pojo.entity.TrafficDeadLetter;
import com.cixingji.backend.pojo.entity.TrafficPlayEvent;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

@Service
public class TrafficAdminService {
    private final TrafficProperties properties;
    private final TrafficPlayEventMapper playMapper;
    private final TrafficBusinessLogMapper logMapper;
    private final TrafficDeadLetterMapper deadLetterMapper;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final TrafficMetrics metrics;

    public TrafficAdminService(TrafficProperties properties, TrafficPlayEventMapper playMapper,
                               TrafficBusinessLogMapper logMapper, TrafficDeadLetterMapper deadLetterMapper,
                               @Qualifier("syncKafkaTemplate") KafkaTemplate<String, String> kafkaTemplate,
                               TrafficMetrics metrics) {
        this.properties = properties;
        this.playMapper = playMapper;
        this.logMapper = logMapper;
        this.deadLetterMapper = deadLetterMapper;
        this.kafkaTemplate = kafkaTemplate;
        this.metrics = metrics;
    }

    public Map<String, Object> overview() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("enabled", properties.isEnabled());
        data.put("kafkaEnabled", properties.getKafka().isEnabled());
        data.put("playTopic", properties.getKafka().getPlayTopic());
        data.put("businessLogTopic", properties.getKafka().getBusinessLogTopic());
        data.put("pending", countPlay("PENDING"));
        data.put("retry", countPlay("RETRY"));
        data.put("dead", countPlay("DEAD"));
        data.put("success", countPlay("SUCCESS"));
        Date today = Date.from(LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant());
        data.put("logsToday", logMapper.selectCount(new QueryWrapper<TrafficBusinessLog>().ge("created_at", today)));
        data.put("openDeadLetters", deadLetterMapper.selectCount(new QueryWrapper<TrafficDeadLetter>().eq("status", "OPEN")));
        data.put("runtime", metrics.snapshot());
        return data;
    }

    public List<TrafficBusinessLog> logs(String action, int page, int size) {
        QueryWrapper<TrafficBusinessLog> query = new QueryWrapper<>();
        if (action != null && !action.trim().isEmpty()) query.eq("action", action.trim().toUpperCase(Locale.ROOT));
        return logMapper.selectList(query.orderByDesc("id").last(limit(page, size)));
    }

    public long logCount(String action) {
        QueryWrapper<TrafficBusinessLog> query = new QueryWrapper<>();
        if (action != null && !action.trim().isEmpty()) query.eq("action", action.trim().toUpperCase(Locale.ROOT));
        Long count = logMapper.selectCount(query);
        return count == null ? 0 : count;
    }

    public List<TrafficPlayEvent> playEvents(String status, int page, int size) {
        QueryWrapper<TrafficPlayEvent> query = new QueryWrapper<>();
        if (status != null && !status.trim().isEmpty()) query.eq("status", status.trim().toUpperCase(Locale.ROOT));
        return playMapper.selectList(query.orderByDesc("id").last(limit(page, size)));
    }

    public long playCount(String status) {
        QueryWrapper<TrafficPlayEvent> query = new QueryWrapper<>();
        if (status != null && !status.trim().isEmpty()) query.eq("status", status.trim().toUpperCase(Locale.ROOT));
        Long count = playMapper.selectCount(query);
        return count == null ? 0 : count;
    }

    public List<TrafficDeadLetter> deadLetters(int page, int size) {
        return deadLetterMapper.selectList(new QueryWrapper<TrafficDeadLetter>()
                .orderByDesc("id").last(limit(page, size)));
    }

    public long deadLetterCount() {
        Long count = deadLetterMapper.selectCount(null);
        return count == null ? 0 : count;
    }

    public int replayDeadPlayEvents(Long id) {
        UpdateWrapper<TrafficPlayEvent> update = new UpdateWrapper<>();
        if (id == null) update.eq("status", "DEAD"); else update.eq("id", id).eq("status", "DEAD");
        update.set("status", "PENDING").set("attempts", 0).set("next_retry_at", null).set("error_message", null);
        return playMapper.update(null, update);
    }

    public boolean replayDeadLetter(Long id) {
        TrafficDeadLetter deadLetter = deadLetterMapper.selectById(id);
        if (deadLetter == null || !"OPEN".equals(deadLetter.getStatus())) return false;
        try {
            kafkaTemplate.send(deadLetter.getSourceTopic(), deadLetter.getMessageKey(), deadLetter.getPayload())
                    .get(5, java.util.concurrent.TimeUnit.SECONDS);
            deadLetterMapper.update(null, new UpdateWrapper<TrafficDeadLetter>().eq("id", id)
                    .eq("status", "OPEN").set("status", "REPLAYED").set("replayed_at", new Date()));
            return true;
        } catch (Exception publishFailure) {
            return false;
        }
    }

    private long countPlay(String status) {
        Long count = playMapper.selectCount(new QueryWrapper<TrafficPlayEvent>().eq("status", status));
        return count == null ? 0 : count;
    }

    private String limit(int page, int size) {
        int safeSize = Math.max(1, Math.min(100, size));
        int offset = Math.max(0, page - 1) * safeSize;
        return "LIMIT " + offset + "," + safeSize;
    }
}
