package com.cixingji.backend.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "sync")
public class DataSyncProperties {
    private boolean enabled = false;
    private int coalesceMillis = 1000;
    private int maxAttempts = 5;
    private Canal canal = new Canal();
    private Kafka kafka = new Kafka();

    @Data
    public static class Canal {
        private boolean enabled = false;
        private String host = "127.0.0.1";
        private int port = 11111;
        private String destination = "example";
        private String username = "";
        private String password = "";
        private String subscription = ".*\\.(video|video_stats|user|category)";
        private int batchSize = 200;
        private int pollTimeoutMillis = 1000;
    }

    @Data
    public static class Kafka {
        private boolean enabled = false;
        private String topic = "video-index-sync";
        private String deadLetterTopic = "video-index-sync-dlt";
        private int sendTimeoutSeconds = 10;
    }
}
