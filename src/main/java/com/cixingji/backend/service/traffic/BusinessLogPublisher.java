package com.cixingji.backend.service.traffic;

import com.cixingji.backend.config.TrafficProperties;
import com.cixingji.backend.pojo.dto.traffic.BusinessLogEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class BusinessLogPublisher {
    private final TrafficProperties properties;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final TrafficMetrics metrics;

    public BusinessLogPublisher(TrafficProperties properties,
                                @Qualifier("syncKafkaTemplate") KafkaTemplate<String, String> kafkaTemplate,
                                ObjectMapper objectMapper, TrafficMetrics metrics) {
        this.properties = properties;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
    }

    public void publish(BusinessLogEvent event) {
        if (!properties.isEnabled() || !properties.getKafka().isEnabled()) return;
        try {
            String payload = objectMapper.writeValueAsString(event);
            kafkaTemplate.send(properties.getKafka().getBusinessLogTopic(), event.getActorKey(), payload)
                    .addCallback(result -> metrics.published(), failure -> {
                        metrics.publishFailed();
                        log.error("Business log publish failed for trace {}", event.getTraceId(), failure);
                    });
        } catch (Exception e) {
            metrics.publishFailed();
            log.error("Could not serialize business log {}", event.getTraceId(), e);
        }
    }
}
