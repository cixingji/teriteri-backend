package com.cixingji.backend.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "traffic")
public class TrafficProperties {
    private boolean enabled = true;
    private long playDeduplicateSeconds = 1800;
    private long aggregateDelayMs = 2000;
    private int aggregateBatchSize = 5000;
    private int maxAttempts = 5;
    private final Kafka kafka = new Kafka();
    private final RateLimit rateLimit = new RateLimit();

    @Data
    public static class Kafka {
        private boolean enabled;
        private String playTopic = "teriteri.video.play.v1";
        private String businessLogTopic = "teriteri.business.log.v1";
        private int consumerConcurrency = 3;
    }

    @Data
    public static class RateLimit {
        private boolean enabled = true;
        private double playPermitsPerSecond = 1000;
        private double likePermitsPerSecond = 500;
        private double commentPermitsPerSecond = 200;
        private double actorPlayPermitsPerSecond = 5;
        private double actorLikePermitsPerSecond = 10;
        private double actorCommentPermitsPerSecond = 2;
    }
}
