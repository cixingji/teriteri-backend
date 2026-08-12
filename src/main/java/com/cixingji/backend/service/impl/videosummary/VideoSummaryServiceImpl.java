package com.cixingji.backend.service.impl.videosummary;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.cixingji.backend.config.videosummary.VideoSummaryProperties;
import com.cixingji.backend.mapper.VideoMapper;
import com.cixingji.backend.mapper.videosummary.VideoSummaryTaskMapper;
import com.cixingji.backend.pojo.dto.videosummary.VideoSummaryResponse;
import com.cixingji.backend.pojo.entity.Video;
import com.cixingji.backend.pojo.entity.videosummary.VideoSummaryStatus;
import com.cixingji.backend.pojo.entity.videosummary.VideoSummaryTask;
import com.cixingji.backend.service.videosummary.VideoSummaryService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class VideoSummaryServiceImpl implements VideoSummaryService {

    private final VideoSummaryTaskMapper taskMapper;
    private final VideoMapper videoMapper;
    private final VideoSummaryOrchestrator orchestrator;
    private final VideoSummaryProperties properties;
    private final ObjectMapper objectMapper;

    @Override
    public VideoSummaryResponse createOrGet(Integer vid) {
        requireEnabled();
        if (vid == null || vid <= 0) {
            throw new IllegalArgumentException("vid must be a positive integer");
        }
        Video video = videoMapper.selectById(vid);
        if (video == null || Integer.valueOf(3).equals(video.getStatus())) {
            throw new IllegalArgumentException("Video does not exist: " + vid);
        }

        VideoSummaryTask existing = findVersionTask(vid);
        if (existing != null) {
            if (VideoSummaryStatus.FAILED.name().equals(existing.getStatus())) {
                existing = requeue(existing);
            }
            dispatch(existing);
            return toResponse(existing);
        }

        Date now = new Date();
        VideoSummaryTask task = new VideoSummaryTask(
                UUID.randomUUID().toString(),
                vid,
                properties.getVersion(),
                VideoSummaryStatus.PENDING.name(),
                null,
                null,
                null,
                null,
                0,
                now,
                now,
                now,
                0
        );
        try {
            taskMapper.insert(task);
        } catch (DuplicateKeyException duplicate) {
            task = findVersionTask(vid);
            if (task == null) {
                throw duplicate;
            }
        }
        dispatch(task);
        return toResponse(task);
    }

    @Override
    public VideoSummaryResponse getTask(String taskId) {
        if (!properties.isEnabled()) {
            return null;
        }
        VideoSummaryTask task = taskMapper.selectById(taskId);
        return task == null ? null : toResponse(task);
    }

    @Override
    public VideoSummaryResponse getLatest(Integer vid) {
        if (!properties.isEnabled()) {
            return null;
        }
        VideoSummaryTask task = taskMapper.selectOne(new LambdaQueryWrapper<VideoSummaryTask>()
                .eq(VideoSummaryTask::getVid, vid)
                .orderByDesc(VideoSummaryTask::getCreatedAt)
                .last("LIMIT 1"));
        return task == null ? null : toResponse(task);
    }

    @Override
    public void handleXfyunCallback(String orderId, int status) {
        orchestrator.handleXfyunCallback(orderId, status);
    }

    private VideoSummaryTask findVersionTask(Integer vid) {
        return taskMapper.selectOne(new LambdaQueryWrapper<VideoSummaryTask>()
                .eq(VideoSummaryTask::getVid, vid)
                .eq(VideoSummaryTask::getSummaryVersion, properties.getVersion())
                .last("LIMIT 1"));
    }

    private VideoSummaryTask requeue(VideoSummaryTask task) {
        Date now = new Date();
        UpdateWrapper<VideoSummaryTask> update = new UpdateWrapper<>();
        update.eq("task_id", task.getTaskId())
                .eq("status", VideoSummaryStatus.FAILED.name())
                .eq("version", task.getVersion())
                .set("status", VideoSummaryStatus.PENDING.name())
                .set("asr_order_id", null)
                .set("transcript", null)
                .set("summary_json", null)
                .set("error_message", null)
                .set("retry_count", 0)
                .set("next_attempt_at", now)
                .set("updated_at", now)
                .set("version", task.getVersion() + 1);
        taskMapper.update(null, update);
        return taskMapper.selectById(task.getTaskId());
    }

    private void dispatch(VideoSummaryTask task) {
        if (task == null) {
            return;
        }
        if (VideoSummaryStatus.PENDING.name().equals(task.getStatus())) {
            orchestrator.dispatchExtraction(task.getTaskId());
        } else if (VideoSummaryStatus.TRANSCRIBING.name().equals(task.getStatus())) {
            orchestrator.dispatchPoll(task.getTaskId());
        } else if (VideoSummaryStatus.SUMMARIZING.name().equals(task.getStatus())) {
            orchestrator.dispatchSummarization(task.getTaskId());
        }
    }

    private VideoSummaryResponse toResponse(VideoSummaryTask task) {
        JsonNode summary = null;
        if (task.getSummaryJson() != null) {
            try {
                summary = objectMapper.readTree(task.getSummaryJson());
            } catch (Exception ignored) {
                summary = objectMapper.getNodeFactory().textNode(task.getSummaryJson());
            }
        }
        return VideoSummaryResponse.builder()
                .taskId(task.getTaskId())
                .vid(task.getVid())
                .status(task.getStatus())
                .transcript(VideoSummaryStatus.SUCCESS.name().equals(task.getStatus())
                        ? task.getTranscript() : null)
                .summary(summary)
                .errorMessage(task.getErrorMessage())
                .retryCount(task.getRetryCount())
                .createdAt(task.getCreatedAt())
                .updatedAt(task.getUpdatedAt())
                .build();
    }

    private void requireEnabled() {
        if (!properties.isEnabled()) {
            throw new IllegalStateException("AI video summary is disabled");
        }
    }
}
