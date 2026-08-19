package com.cixingji.backend.component;

import com.alibaba.otter.canal.client.CanalConnector;
import com.alibaba.otter.canal.client.CanalConnectors;
import com.alibaba.otter.canal.protocol.CanalEntry;
import com.alibaba.otter.canal.protocol.Message;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.cixingji.backend.config.DataSyncProperties;
import com.cixingji.backend.mapper.CanalSyncCheckpointMapper;
import com.cixingji.backend.pojo.dto.sync.CanalSyncEvent;
import com.cixingji.backend.pojo.entity.CanalSyncCheckpoint;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

@Slf4j
@Component
public class CanalListener {
    private final DataSyncProperties properties;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final CanalSyncCheckpointMapper checkpointMapper;
    private final ObjectMapper objectMapper;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "canal-binlog-listener");
        thread.setDaemon(true);
        return thread;
    });
    private volatile boolean running;
    private volatile CanalConnector connector;

    public CanalListener(DataSyncProperties properties, @Qualifier("syncKafkaTemplate") KafkaTemplate<String, String> kafkaTemplate,
                         CanalSyncCheckpointMapper checkpointMapper, ObjectMapper objectMapper) {
        this.properties = properties; this.kafkaTemplate = kafkaTemplate;
        this.checkpointMapper = checkpointMapper; this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void start() {
        if (!properties.isEnabled() || !properties.getCanal().isEnabled() || !properties.getKafka().isEnabled()) {
            log.info("Canal search synchronization is disabled");
            return;
        }
        running = true;
        executor.execute(this::listenLoop);
    }

    @PreDestroy
    public void stop() {
        running = false;
        if (connector != null) {
            try { connector.disconnect(); } catch (Exception ignored) { }
        }
        executor.shutdownNow();
    }

    private void listenLoop() {
        while (running) {
            try {
                connect();
                poll();
            } catch (Exception e) {
                if (running) log.error("Canal listener disconnected; retrying in 5 seconds", e);
                disconnect();
                sleep(5000);
            }
        }
    }

    private void connect() {
        DataSyncProperties.Canal config = properties.getCanal();
        connector = CanalConnectors.newSingleConnector(new InetSocketAddress(config.getHost(), config.getPort()),
                config.getDestination(), empty(config.getUsername()), empty(config.getPassword()));
        connector.connect();
        connector.subscribe(config.getSubscription());
        // Canal Server persists the last acknowledged cursor for this destination.
        connector.rollback();
        log.info("Connected to Canal {}:{} destination {}", config.getHost(), config.getPort(), config.getDestination());
    }

    private void poll() throws Exception {
        DataSyncProperties.Canal config = properties.getCanal();
        while (running) {
            Message message = connector.getWithoutAck(config.getBatchSize(), (long) config.getPollTimeoutMillis(), TimeUnit.MILLISECONDS);
            long batchId = message.getId();
            if (batchId == -1 || message.getEntries().isEmpty()) continue;
            try {
                List<CanalSyncEvent> events = parse(message.getEntries());
                for (CanalSyncEvent event : events) {
                    kafkaTemplate.send(properties.getKafka().getTopic(), event.getAggregateType() + ":" + event.getAggregateKey(), objectMapper.writeValueAsString(event))
                            .get(properties.getKafka().getSendTimeoutSeconds(), TimeUnit.SECONDS);
                }
                saveCheckpoint(batchId, message.getEntries());
                connector.ack(batchId);
            } catch (Exception e) {
                connector.rollback(batchId);
                throw e;
            }
        }
    }

    List<CanalSyncEvent> parse(List<CanalEntry.Entry> entries) throws Exception {
        List<CanalSyncEvent> result = new ArrayList<>();
        for (CanalEntry.Entry entry : entries) {
            if (entry.getEntryType() != CanalEntry.EntryType.ROWDATA) continue;
            CanalEntry.RowChange change = CanalEntry.RowChange.parseFrom(entry.getStoreValue());
            for (CanalEntry.RowData row : change.getRowDatasList()) {
                Map<String, String> columns = columns(change.getEventType() == CanalEntry.EventType.DELETE
                        ? row.getBeforeColumnsList() : row.getAfterColumnsList());
                Aggregate aggregate = aggregate(entry.getHeader().getTableName(), columns);
                if (aggregate == null) continue;
                String rawId = entry.getHeader().getLogfileName() + ":" + entry.getHeader().getLogfileOffset() + ":"
                        + entry.getHeader().getTableName() + ":" + change.getEventType().name() + ":" + aggregate.key;
                result.add(new CanalSyncEvent(sha256(rawId), entry.getHeader().getTableName(), change.getEventType().name(),
                        aggregate.type, aggregate.key, entry.getHeader().getLogfileName(), entry.getHeader().getLogfileOffset(),
                        entry.getHeader().getExecuteTime(), safeColumns(entry.getHeader().getTableName(), columns)));
            }
        }
        return result;
    }

    private Aggregate aggregate(String table, Map<String, String> columns) {
        if ("video".equalsIgnoreCase(table) || "video_stats".equalsIgnoreCase(table)) {
            return value(columns, "vid") == null ? null : new Aggregate("VIDEO", value(columns, "vid"));
        }
        if ("user".equalsIgnoreCase(table)) {
            return value(columns, "uid") == null ? null : new Aggregate("USER", value(columns, "uid"));
        }
        if ("category".equalsIgnoreCase(table)) {
            String mc = value(columns, "mc_id"), sc = value(columns, "sc_id");
            return mc == null || sc == null ? null : new Aggregate("CATEGORY", mc + "|" + sc);
        }
        return null;
    }

    private Map<String, String> columns(List<CanalEntry.Column> values) {
        Map<String, String> result = new LinkedHashMap<>();
        for (CanalEntry.Column column : values) result.put(column.getName().toLowerCase(Locale.ROOT), column.getIsNull() ? null : column.getValue());
        return result;
    }

    private Map<String, String> safeColumns(String table, Map<String, String> columns) {
        Set<String> allowed;
        if ("user".equalsIgnoreCase(table)) allowed = new HashSet<>(Arrays.asList("uid", "nickname"));
        else if ("category".equalsIgnoreCase(table)) allowed = new HashSet<>(Arrays.asList("mc_id", "sc_id", "mc_name", "sc_name"));
        else if ("video_stats".equalsIgnoreCase(table)) allowed = new HashSet<>(Arrays.asList("vid", "play", "good"));
        else allowed = new HashSet<>(Arrays.asList("vid", "uid", "title", "descr", "tags", "mc_id", "sc_id", "status", "upload_date"));
        Map<String, String> result = new LinkedHashMap<>();
        for (String key : allowed) if (columns.containsKey(key)) result.put(key, columns.get(key));
        return result;
    }

    private void saveCheckpoint(long batchId, List<CanalEntry.Entry> entries) {
        CanalEntry.Header header = entries.get(entries.size() - 1).getHeader();
        Date now = new Date();
        CanalSyncCheckpoint checkpoint = checkpointMapper.selectById(properties.getCanal().getDestination());
        if (checkpoint == null) {
            checkpoint = new CanalSyncCheckpoint();
            checkpoint.setDestination(properties.getCanal().getDestination());
            checkpoint.setBatchId(batchId); checkpoint.setBinlogFile(header.getLogfileName());
            checkpoint.setBinlogPosition(header.getLogfileOffset()); checkpoint.setUpdatedAt(now);
            checkpointMapper.insert(checkpoint);
        } else {
            checkpointMapper.update(null, new UpdateWrapper<CanalSyncCheckpoint>()
                    .eq("destination", checkpoint.getDestination()).set("batch_id", batchId)
                    .set("binlog_file", header.getLogfileName()).set("binlog_position", header.getLogfileOffset())
                    .set("updated_at", now));
        }
    }

    private String sha256(String value) throws Exception {
        byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder();
        for (byte b : bytes) result.append(String.format("%02x", b));
        return result.toString();
    }

    private String value(Map<String, String> map, String key) { return map.get(key); }
    private String empty(String value) { return value == null ? "" : value; }
    private void disconnect() { if (connector != null) try { connector.disconnect(); } catch (Exception ignored) { } }
    private void sleep(long millis) { try { Thread.sleep(millis); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }

    private static class Aggregate {
        private final String type; private final String key;
        private Aggregate(String type, String key) { this.type = type; this.key = key; }
    }
}
