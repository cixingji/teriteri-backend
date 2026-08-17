package com.cixingji.backend.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.nio.file.Paths;

@Data
@Configuration
@ConfigurationProperties(prefix = "media")
public class MediaProperties {
    private String root = "data/media";
    private String ffmpeg = "ffmpeg";
    private String ffprobe = "ffprobe";
    private String encoder = "cpu";
    private long maxFileSize = 2L * 1024 * 1024 * 1024;
    private long chunkSize = 5L * 1024 * 1024;
    private int maxDurationSeconds = 4 * 60 * 60;
    private int segmentSeconds = 6;
    private int maxFps = 60;
    private int maxAttempts = 3;
    private int orphanGraceHours = 24;

    public Path rootPath() {
        return Paths.get(root).toAbsolutePath().normalize();
    }
}
