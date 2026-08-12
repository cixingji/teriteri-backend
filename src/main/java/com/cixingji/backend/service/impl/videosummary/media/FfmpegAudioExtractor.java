package com.cixingji.backend.service.impl.videosummary.media;

import com.cixingji.backend.config.videosummary.VideoSummaryProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import ws.schild.jave.EncoderException;
import ws.schild.jave.MultimediaObject;
import ws.schild.jave.process.ffmpeg.DefaultFFMPEGLocator;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class FfmpegAudioExtractor implements AudioExtractor {

    private static final int MAX_LOG_CHARS = 20000;

    private final VideoSummaryProperties properties;

    @Override
    public AudioArtifact extract(String videoSource, Path outputPath) {
        if (videoSource == null || videoSource.trim().isEmpty()) {
            throw new IllegalArgumentException("Video source is empty");
        }

        try {
            Path parent = outputPath.toAbsolutePath().normalize().getParent();
            if (parent == null) {
                throw new IllegalArgumentException("Audio output path has no parent directory");
            }
            Files.createDirectories(parent);

            ProcessBuilder processBuilder = new ProcessBuilder(buildCommand(videoSource, outputPath));
            processBuilder.redirectErrorStream(true);
            Process process = processBuilder.start();
            StringBuilder processOutput = new StringBuilder();
            Thread outputReader = startOutputReader(process.getInputStream(), processOutput);

            boolean completed = process.waitFor(properties.getExtractTimeoutSeconds(), TimeUnit.SECONDS);
            if (!completed) {
                process.destroy();
                if (!process.waitFor(3, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
                throw new IllegalStateException("FFmpeg audio extraction timed out");
            }
            outputReader.join(1000L);

            if (process.exitValue() != 0) {
                throw new IllegalStateException("FFmpeg audio extraction failed: "
                        + sanitize(tail(processOutput)));
            }
            if (!Files.isRegularFile(outputPath) || Files.size(outputPath) == 0L) {
                throw new IllegalStateException("FFmpeg produced an empty audio file");
            }

            return new AudioArtifact(outputPath, readDurationMillis(outputPath));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            deleteFailedOutput(outputPath);
            throw new IllegalStateException("FFmpeg audio extraction was interrupted", e);
        } catch (IOException | RuntimeException e) {
            deleteFailedOutput(outputPath);
            throw new IllegalStateException("Could not extract video audio", e);
        }
    }

    List<String> buildCommand(String videoSource, Path outputPath) {
        List<String> command = new ArrayList<>();
        Collections.addAll(command,
                resolveFfmpegPath(),
                "-nostdin",
                "-y",
                "-i", videoSource,
                "-map", "0:a:0",
                "-vn",
                "-ac", "1",
                "-ar", "16000",
                "-codec:a", "libmp3lame",
                "-b:a", "64k",
                outputPath.toAbsolutePath().normalize().toString()
        );
        return command;
    }

    private String resolveFfmpegPath() {
        if (StringUtils.hasText(properties.getFfmpegPath())) {
            return properties.getFfmpegPath();
        }
        return new DefaultFFMPEGLocator().getExecutablePath();
    }

    private Thread startOutputReader(InputStream inputStream, StringBuilder output) {
        Thread reader = new Thread(() -> {
            try (BufferedReader bufferedReader = new BufferedReader(
                    new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = bufferedReader.readLine()) != null) {
                    synchronized (output) {
                        output.append(line).append('\n');
                        if (output.length() > MAX_LOG_CHARS * 2) {
                            output.delete(0, output.length() - MAX_LOG_CHARS);
                        }
                    }
                }
            } catch (IOException e) {
                log.debug("Stopped reading FFmpeg output", e);
            }
        }, "video-summary-ffmpeg-output");
        reader.setDaemon(true);
        reader.start();
        return reader;
    }

    private long readDurationMillis(Path audioPath) {
        try {
            long duration = new MultimediaObject(audioPath.toFile()).getInfo().getDuration();
            return Math.max(duration, 1L);
        } catch (EncoderException e) {
            log.warn("Could not read extracted audio duration; using a safe fallback", e);
            return 1L;
        }
    }

    private String tail(StringBuilder output) {
        synchronized (output) {
            int start = Math.max(0, output.length() - MAX_LOG_CHARS);
            return output.substring(start).trim();
        }
    }

    private String sanitize(String processOutput) {
        return processOutput.replaceAll("(?i)https?://\\S+", "<redacted-video-url>");
    }

    private void deleteFailedOutput(Path outputPath) {
        try {
            Files.deleteIfExists(outputPath);
        } catch (IOException cleanupError) {
            log.warn("Could not delete failed audio artifact: {}", outputPath, cleanupError);
        }
    }
}
