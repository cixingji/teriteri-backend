package com.cixingji.backend.service.impl.videosummary;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.cixingji.backend.config.videosummary.VideoSummaryProperties;
import com.cixingji.backend.mapper.VideoMapper;
import com.cixingji.backend.mapper.videosummary.VideoSummaryTaskMapper;
import com.cixingji.backend.pojo.entity.Video;
import com.cixingji.backend.pojo.entity.videosummary.VideoSummaryStatus;
import com.cixingji.backend.pojo.entity.videosummary.VideoSummaryTask;
import com.cixingji.backend.service.impl.videosummary.client.AsrClient;
import com.cixingji.backend.service.impl.videosummary.client.AsrQueryResult;
import com.cixingji.backend.service.impl.videosummary.media.AudioArtifact;
import com.cixingji.backend.service.impl.videosummary.media.AudioExtractor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Date;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

@Slf4j
@Component
public class VideoSummaryOrchestrator {

    private static final int MAX_ERROR_LENGTH = 1000;

    private final VideoSummaryTaskMapper taskMapper;
    private final VideoMapper videoMapper;
    private final AudioExtractor audioExtractor;
    private final AsrClient asrClient;
    private final VideoSummarizer videoSummarizer;
    private final VideoSummaryProperties properties;
    private final Executor mediaExecutor;
    private final Executor remoteExecutor;

    public VideoSummaryOrchestrator(VideoSummaryTaskMapper taskMapper,
                                    VideoMapper videoMapper,
                                    AudioExtractor audioExtractor,
                                    AsrClient asrClient,
                                    VideoSummarizer videoSummarizer,
                                    VideoSummaryProperties properties,
                                    @Qualifier("videoSummaryMediaExecutor") Executor mediaExecutor,
                                    @Qualifier("videoSummaryRemoteExecutor") Executor remoteExecutor) {
        this.taskMapper = taskMapper;
        this.videoMapper = videoMapper;
        this.audioExtractor = audioExtractor;
        this.asrClient = asrClient;
        this.videoSummarizer = videoSummarizer;
        this.properties = properties;
        this.mediaExecutor = mediaExecutor;
        this.remoteExecutor = remoteExecutor;
    }

    public void dispatchExtraction(String taskId) {
        try {
            mediaExecutor.execute(() -> extractAndSubmit(taskId));
        } catch (RejectedExecutionException e) {
            log.warn("Video summary media queue is full; task {} remains pending", taskId);
        }
    }

    public void dispatchPoll(String taskId) {
        try {
            remoteExecutor.execute(() -> pollTranscription(taskId));
        } catch (RejectedExecutionException e) {
            log.warn("Video summary remote queue is full; task {} will be polled later", taskId);
        }
    }

    public void dispatchSummarization(String taskId) {
        try {
            remoteExecutor.execute(() -> summarize(taskId));
        } catch (RejectedExecutionException e) {
            log.warn("Video summary remote queue is full; task {} will be summarized later", taskId);
        }
    }

    public void handleXfyunCallback(String orderId, int status) {
        VideoSummaryTask task = taskMapper.selectOne(new LambdaQueryWrapper<VideoSummaryTask>()
                .eq(VideoSummaryTask::getAsrOrderId, orderId)
                .last("LIMIT 1"));
        if (task == null || !VideoSummaryStatus.TRANSCRIBING.name().equals(task.getStatus())) {
            return;
        }
        if (status < 0) {
            markTerminalFailure(task, "XFYun reported transcription failure");
            return;
        }
        makeDueNow(task);
        dispatchPoll(task.getTaskId());
    }

    @Scheduled(fixedDelayString = "${ai.video-summary.poll-scan-delay-ms:10000}")
    public void dispatchDueTasks() {
        if (!properties.isEnabled()) {
            return;
        }
        Date now = new Date();
        dispatchDue(VideoSummaryStatus.PENDING, now);
        dispatchDue(VideoSummaryStatus.TRANSCRIBING, now);
        dispatchDue(VideoSummaryStatus.SUMMARIZING, now);
        recoverStaleExtractions(now);
    }

    private void dispatchDue(VideoSummaryStatus status, Date now) {
        List<VideoSummaryTask> tasks = taskMapper.selectList(new LambdaQueryWrapper<VideoSummaryTask>()
                .eq(VideoSummaryTask::getStatus, status.name())
                .and(wrapper -> wrapper.isNull(VideoSummaryTask::getNextAttemptAt)
                        .or().le(VideoSummaryTask::getNextAttemptAt, now))
                .orderByAsc(VideoSummaryTask::getCreatedAt)
                .last("LIMIT " + Math.max(1, properties.getPollBatchSize())));
        for (VideoSummaryTask task : tasks) {
            if (status == VideoSummaryStatus.PENDING) {
                dispatchExtraction(task.getTaskId());
            } else if (status == VideoSummaryStatus.TRANSCRIBING) {
                dispatchPoll(task.getTaskId());
            } else {
                dispatchSummarization(task.getTaskId());
            }
        }
    }

    private void recoverStaleExtractions(Date now) {
        long cutoffMillis = now.getTime() - properties.getStageTimeoutSeconds() * 1000L;
        List<VideoSummaryTask> stale = taskMapper.selectList(new LambdaQueryWrapper<VideoSummaryTask>()
                .eq(VideoSummaryTask::getStatus, VideoSummaryStatus.EXTRACTING.name())
                .lt(VideoSummaryTask::getUpdatedAt, new Date(cutoffMillis))
                .last("LIMIT " + Math.max(1, properties.getPollBatchSize())));
        for (VideoSummaryTask task : stale) {
            retryOrFail(task, VideoSummaryStatus.EXTRACTING,
                    VideoSummaryStatus.PENDING, "Recovered a stale audio extraction task");
        }
    }

    private void extractAndSubmit(String taskId) {
        VideoSummaryTask task = taskMapper.selectById(taskId);
        if (task == null || !claimTransition(task, VideoSummaryStatus.PENDING,
                VideoSummaryStatus.EXTRACTING, null)) {
            return;
        }

        Path audioPath = resolveAudioPath(taskId);
        try {
            Video video = videoMapper.selectById(task.getVid());
            if (video == null) {
                throw new IllegalArgumentException("Video does not exist: " + task.getVid());
            }
            AudioArtifact audio = audioExtractor.extract(video.getVideoUrl(), audioPath);
            String orderId = asrClient.submit(audio, buildCallbackUrl());

            VideoSummaryTask current = taskMapper.selectById(taskId);
            Date nextPoll = secondsFromNow(properties.getPollInitialDelaySeconds());
            UpdateWrapper<VideoSummaryTask> update = guardedUpdate(current, VideoSummaryStatus.EXTRACTING);
            update.set("status", VideoSummaryStatus.TRANSCRIBING.name())
                    .set("asr_order_id", orderId)
                    .set("retry_count", 0)
                    .set("error_message", null)
                    .set("next_attempt_at", nextPoll);
            taskMapper.update(null, update);
        } catch (Exception e) {
            VideoSummaryTask current = taskMapper.selectById(taskId);
            retryOrFail(current, VideoSummaryStatus.EXTRACTING, VideoSummaryStatus.PENDING,
                    messageOf(e));
        } finally {
            deleteAudioArtifact(audioPath);
        }
    }

    private void pollTranscription(String taskId) {
        VideoSummaryTask task = taskMapper.selectById(taskId);
        if (task == null || !reserveAttempt(task, VideoSummaryStatus.TRANSCRIBING)) {
            return;
        }
        try {
            AsrQueryResult result = asrClient.query(task.getAsrOrderId());
            VideoSummaryTask current = taskMapper.selectById(taskId);
            if (result.getState() == AsrQueryResult.State.PROCESSING) {
                scheduleNextAttempt(current, VideoSummaryStatus.TRANSCRIBING,
                        properties.getPollInitialDelaySeconds(), false, null);
                return;
            }
            if (result.getState() == AsrQueryResult.State.FAILED) {
                markTerminalFailure(current, result.getErrorMessage());
                return;
            }

            UpdateWrapper<VideoSummaryTask> update = guardedUpdate(current, VideoSummaryStatus.TRANSCRIBING);
            update.set("status", VideoSummaryStatus.SUMMARIZING.name())
                    .set("transcript", result.getTranscript())
                    .set("retry_count", 0)
                    .set("error_message", null)
                    .set("next_attempt_at", new Date());
            if (taskMapper.update(null, update) == 1) {
                dispatchSummarization(taskId);
            }
        } catch (Exception e) {
            VideoSummaryTask current = taskMapper.selectById(taskId);
            retryOrFail(current, VideoSummaryStatus.TRANSCRIBING,
                    VideoSummaryStatus.TRANSCRIBING, messageOf(e));
        }
    }

    private void summarize(String taskId) {
        VideoSummaryTask task = taskMapper.selectById(taskId);
        if (task == null || !reserveAttempt(task, VideoSummaryStatus.SUMMARIZING)) {
            return;
        }
        try {
            String summary = videoSummarizer.summarize(task.getTranscript());
            VideoSummaryTask current = taskMapper.selectById(taskId);
            UpdateWrapper<VideoSummaryTask> update = guardedUpdate(current, VideoSummaryStatus.SUMMARIZING);
            update.set("status", VideoSummaryStatus.SUCCESS.name())
                    .set("summary_json", summary)
                    .set("retry_count", 0)
                    .set("error_message", null)
                    .set("next_attempt_at", null);
            taskMapper.update(null, update);
        } catch (Exception e) {
            VideoSummaryTask current = taskMapper.selectById(taskId);
            retryOrFail(current, VideoSummaryStatus.SUMMARIZING,
                    VideoSummaryStatus.SUMMARIZING, messageOf(e));
        }
    }

    private boolean claimTransition(VideoSummaryTask task,
                                    VideoSummaryStatus expected,
                                    VideoSummaryStatus next,
                                    Date nextAttemptAt) {
        UpdateWrapper<VideoSummaryTask> update = guardedUpdate(task, expected);
        update.set("status", next.name())
                .set("next_attempt_at", nextAttemptAt)
                .set("error_message", null);
        return taskMapper.update(null, update) == 1;
    }

    private boolean reserveAttempt(VideoSummaryTask task, VideoSummaryStatus status) {
        UpdateWrapper<VideoSummaryTask> update = guardedUpdate(task, status);
        update.set("next_attempt_at", secondsFromNow(properties.getStageTimeoutSeconds()));
        return taskMapper.update(null, update) == 1;
    }

    private void makeDueNow(VideoSummaryTask task) {
        UpdateWrapper<VideoSummaryTask> update = guardedUpdate(task, VideoSummaryStatus.TRANSCRIBING);
        update.set("next_attempt_at", new Date());
        taskMapper.update(null, update);
    }

    private void retryOrFail(VideoSummaryTask task,
                             VideoSummaryStatus expected,
                             VideoSummaryStatus retryStatus,
                             String errorMessage) {
        if (task == null || !expected.name().equals(task.getStatus())) {
            return;
        }
        int retryCount = task.getRetryCount() == null ? 1 : task.getRetryCount() + 1;
        if (retryCount > properties.getMaxRetries()) {
            markTerminalFailure(task, errorMessage);
            return;
        }
        int delay = backoffSeconds(retryCount);
        UpdateWrapper<VideoSummaryTask> update = guardedUpdate(task, expected);
        update.set("status", retryStatus.name())
                .set("retry_count", retryCount)
                .set("error_message", truncate(errorMessage))
                .set("next_attempt_at", secondsFromNow(delay));
        taskMapper.update(null, update);
    }

    private void scheduleNextAttempt(VideoSummaryTask task,
                                     VideoSummaryStatus status,
                                     int delaySeconds,
                                     boolean incrementRetry,
                                     String errorMessage) {
        if (task == null || !status.name().equals(task.getStatus())) {
            return;
        }
        UpdateWrapper<VideoSummaryTask> update = guardedUpdate(task, status);
        update.set("next_attempt_at", secondsFromNow(delaySeconds));
        if (incrementRetry) {
            update.set("retry_count", task.getRetryCount() + 1);
        }
        if (errorMessage != null) {
            update.set("error_message", truncate(errorMessage));
        }
        taskMapper.update(null, update);
    }

    private void markTerminalFailure(VideoSummaryTask task, String errorMessage) {
        if (task == null || VideoSummaryStatus.SUCCESS.name().equals(task.getStatus())
                || VideoSummaryStatus.FAILED.name().equals(task.getStatus())) {
            return;
        }
        VideoSummaryStatus expected = VideoSummaryStatus.valueOf(task.getStatus());
        UpdateWrapper<VideoSummaryTask> update = guardedUpdate(task, expected);
        update.set("status", VideoSummaryStatus.FAILED.name())
                .set("error_message", truncate(errorMessage))
                .set("next_attempt_at", null);
        taskMapper.update(null, update);
    }

    private UpdateWrapper<VideoSummaryTask> guardedUpdate(VideoSummaryTask task,
                                                           VideoSummaryStatus expectedStatus) {
        Date now = new Date();
        return new UpdateWrapper<VideoSummaryTask>()
                .eq("task_id", task.getTaskId())
                .eq("status", expectedStatus.name())
                .eq("version", task.getVersion())
                .set("updated_at", now)
                .set("version", task.getVersion() + 1);
    }

    private Path resolveAudioPath(String taskId) {
        Path root = Paths.get(properties.getWorkDir()).toAbsolutePath().normalize();
        Path output = root.resolve(taskId + ".mp3").normalize();
        if (!output.startsWith(root)) {
            throw new IllegalArgumentException("Invalid video summary task id");
        }
        return output;
    }

    private String buildCallbackUrl() {
        if (!StringUtils.hasText(properties.getCallbackBaseUrl())
                || !StringUtils.hasText(properties.getCallbackToken())) {
            return null;
        }
        String base = properties.getCallbackBaseUrl().replaceAll("/+$", "");
        try {
            return base + "/video/summary/callback/xfyun?token="
                    + URLEncoder.encode(properties.getCallbackToken(), StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            throw new IllegalStateException("Could not build XFYun callback URL", e);
        }
    }

    private int backoffSeconds(int retryCount) {
        long multiplier = 1L << Math.min(Math.max(retryCount - 1, 0), 20);
        long delay = properties.getPollInitialDelaySeconds() * multiplier;
        return (int) Math.min(delay, properties.getPollMaxDelaySeconds());
    }

    private Date secondsFromNow(int seconds) {
        return new Date(System.currentTimeMillis() + Math.max(seconds, 1) * 1000L);
    }

    private void deleteAudioArtifact(Path audioPath) {
        try {
            Files.deleteIfExists(audioPath);
        } catch (IOException e) {
            log.warn("Could not delete audio artifact: {}", audioPath, e);
        }
    }

    private String messageOf(Exception error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return truncate(StringUtils.hasText(message) ? message : current.getClass().getSimpleName());
    }

    private String truncate(String value) {
        String safe = value == null ? "Unknown video summary error" : value;
        return safe.length() <= MAX_ERROR_LENGTH ? safe : safe.substring(0, MAX_ERROR_LENGTH);
    }
}
