package com.cixingji.backend.service.traffic;

import com.cixingji.backend.mapper.TrafficBusinessLogMapper;
import com.cixingji.backend.mapper.TrafficDeadLetterMapper;
import com.cixingji.backend.pojo.dto.traffic.BusinessLogEvent;
import com.cixingji.backend.pojo.dto.traffic.PlayEvent;
import com.cixingji.backend.pojo.entity.TrafficBusinessLog;
import com.cixingji.backend.pojo.entity.TrafficDeadLetter;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import java.util.Date;

@Slf4j
@Service
public class TrafficKafkaConsumer {
    private final ObjectMapper objectMapper;
    private final TrafficEventService eventService;
    private final TrafficBusinessLogMapper businessLogMapper;
    private final TrafficDeadLetterMapper deadLetterMapper;

    public TrafficKafkaConsumer(ObjectMapper objectMapper, TrafficEventService eventService,
                                TrafficBusinessLogMapper businessLogMapper, TrafficDeadLetterMapper deadLetterMapper) {
        this.objectMapper = objectMapper;
        this.eventService = eventService;
        this.businessLogMapper = businessLogMapper;
        this.deadLetterMapper = deadLetterMapper;
    }

    @KafkaListener(topics = "${traffic.kafka.play-topic:teriteri.video.play.v1}",
            groupId = "teriteri-play-inbox-v1", autoStartup = "${traffic.kafka.enabled:false}",
            containerFactory = "trafficKafkaListenerContainerFactory")
    public void consumePlay(String payload) throws Exception {
        eventService.storeInbox(objectMapper.readValue(payload, PlayEvent.class));
    }

    @KafkaListener(topics = "${traffic.kafka.business-log-topic:teriteri.business.log.v1}",
            groupId = "teriteri-business-log-v1", autoStartup = "${traffic.kafka.enabled:false}",
            containerFactory = "trafficKafkaListenerContainerFactory")
    public void consumeBusinessLog(String payload) throws Exception {
        BusinessLogEvent event = objectMapper.readValue(payload, BusinessLogEvent.class);
        TrafficBusinessLog entity = new TrafficBusinessLog();
        entity.setEventId(event.getEventId());
        entity.setTraceId(event.getTraceId());
        entity.setAction(event.getAction());
        entity.setMethod(event.getMethod());
        entity.setPath(event.getPath());
        entity.setResponseStatus(event.getResponseStatus());
        entity.setLatencyMs(event.getLatencyMs());
        entity.setActorKey(event.getActorKey());
        entity.setOccurredAt(new Date(event.getOccurredAt()));
        entity.setCreatedAt(new Date());
        try { businessLogMapper.insert(entity); }
        catch (DuplicateKeyException duplicate) { log.debug("Ignoring duplicate business log {}", event.getEventId()); }
    }

    @KafkaListener(topics = {
            "${traffic.kafka.play-topic:teriteri.video.play.v1}.DLT",
            "${traffic.kafka.business-log-topic:teriteri.business.log.v1}.DLT"},
            groupId = "teriteri-traffic-dlt-v1", autoStartup = "${traffic.kafka.enabled:false}",
            containerFactory = "syncKafkaListenerContainerFactory")
    public void consumeDeadLetter(ConsumerRecord<String, String> record) {
        TrafficDeadLetter entity = new TrafficDeadLetter();
        entity.setSourceTopic(record.topic().substring(0, record.topic().length() - 4));
        entity.setSourcePartition(record.partition());
        entity.setSourceOffset(record.offset());
        entity.setMessageKey(record.key());
        entity.setPayload(record.value());
        entity.setStatus("OPEN");
        entity.setCreatedAt(new Date());
        try { deadLetterMapper.insert(entity); }
        catch (DuplicateKeyException duplicate) { log.debug("Ignoring duplicate dead letter {}-{}", record.topic(), record.offset()); }
    }
}
