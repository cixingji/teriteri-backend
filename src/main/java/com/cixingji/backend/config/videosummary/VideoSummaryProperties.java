package com.cixingji.backend.config.videosummary;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "ai.video-summary")
public class VideoSummaryProperties {

    private boolean enabled = false;
    private String version = "v1";
    private String workDir = "./public/video-summary";
    private String ffmpegPath = "";
    private int extractTimeoutSeconds = 900;
    private int stageTimeoutSeconds = 1800;
    private int maxRetries = 3;
    private int pollInitialDelaySeconds = 20;
    private int pollMaxDelaySeconds = 120;
    private long pollScanDelayMs = 10000L;
    private int pollBatchSize = 10;
    private String callbackBaseUrl = "";
    private String callbackToken = "";
    private int maxChunkChars = 6000;

    private int mediaCorePoolSize = 2;
    private int mediaMaxPoolSize = 2;
    private int mediaQueueCapacity = 20;
    private int remoteCorePoolSize = 4;
    private int remoteMaxPoolSize = 8;
    private int remoteQueueCapacity = 100;

    private final Xfyun xfyun = new Xfyun();
    private final Spark spark = new Spark();

    @Data
    public static class Xfyun {
        private String appId = "";
        private String secretKey = "";
        private String uploadUrl = "https://raasr.xfyun.cn/v2/api/upload";
        private String resultUrl = "https://raasr.xfyun.cn/v2/api/getResult";
        private String language = "cn";
        private int connectTimeoutMs = 10000;
        private int readTimeoutMs = 120000;
    }

    @Data
    public static class Spark {
        private String apiPassword = "";
        private String endpoint = "https://spark-api-open.xf-yun.com/v1/chat/completions";
        private String model = "lite";
        private int connectTimeoutMs = 10000;
        private int readTimeoutMs = 120000;
        private int maxTokens = 2048;
        private double temperature = 0.2D;
    }
}
