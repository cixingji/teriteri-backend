package com.cixingji.backend.service.media;

import com.cixingji.backend.config.MediaProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import javax.annotation.PostConstruct;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class MediaProbeService {
    private final MediaProperties properties;
    private final ObjectMapper objectMapper;

    public MediaProbeService(MediaProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void healthCheck() {
        try {
            verifyAvailable();
            log.info("FFmpeg/FFprobe 健康检查通过");
        } catch (Exception e) {
            log.warn("FFmpeg/FFprobe 健康检查未通过，上传仍可进行，但转码任务会重试: {}", e.getMessage());
        }
    }

    public ProbeResult probe(Path source) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(Arrays.asList(
                properties.getFfprobe(), "-v", "error", "-print_format", "json",
                "-show_format", "-show_streams", source.toString()))
                .redirectErrorStream(true).start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        copy(process.getInputStream(), output);
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("FFprobe 检测超时");
        }
        if (process.exitValue() != 0) {
            throw new IOException("视频文件无法解析: " + abbreviate(output.toString(StandardCharsets.UTF_8.name())));
        }
        JsonNode json = objectMapper.readTree(output.toByteArray());
        JsonNode video = null;
        boolean audio = false;
        for (JsonNode stream : json.path("streams")) {
            if ("video".equals(stream.path("codec_type").asText()) && video == null) video = stream;
            if ("audio".equals(stream.path("codec_type").asText())) audio = true;
        }
        if (video == null) throw new IOException("文件中没有视频轨道");
        double duration = json.path("format").path("duration").asDouble(video.path("duration").asDouble(0));
        double fps = frameRate(video.path("avg_frame_rate").asText("0/1"));
        String transfer = video.path("color_transfer").asText("");
        boolean hdr = "smpte2084".equalsIgnoreCase(transfer) || "arib-std-b67".equalsIgnoreCase(transfer);
        return new ProbeResult(video.path("width").asInt(), video.path("height").asInt(), duration,
                fps, audio, hdr);
    }

    public void verifyAvailable() throws IOException, InterruptedException {
        Process process = new ProcessBuilder(properties.getFfmpeg(), "-version").redirectErrorStream(true).start();
        if (!process.waitFor(10, TimeUnit.SECONDS) || process.exitValue() != 0) {
            process.destroyForcibly();
            throw new IOException("FFmpeg 不可用，请执行 scripts/setup-ffmpeg.ps1 或配置 media.ffmpeg");
        }
        Process probe = new ProcessBuilder(properties.getFfprobe(), "-version").redirectErrorStream(true).start();
        if (!probe.waitFor(10, TimeUnit.SECONDS) || probe.exitValue() != 0) {
            probe.destroyForcibly();
            throw new IOException("FFprobe 不可用，请配置 media.ffprobe");
        }
    }

    private double frameRate(String value) {
        String[] parts = value.split("/");
        if (parts.length != 2) return 0;
        double denominator = Double.parseDouble(parts[1]);
        return denominator == 0 ? 0 : Double.parseDouble(parts[0]) / denominator;
    }

    private void copy(java.io.InputStream input, java.io.OutputStream output) throws IOException {
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) >= 0) if (read > 0) output.write(buffer, 0, read);
    }

    private String abbreviate(String value) {
        return value.length() <= 300 ? value : value.substring(value.length() - 300);
    }

    @Data
    @AllArgsConstructor
    public static class ProbeResult {
        private int width;
        private int height;
        private double duration;
        private double fps;
        private boolean audio;
        private boolean hdr;
    }
}
