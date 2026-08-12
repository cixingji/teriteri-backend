package com.cixingji.backend.service.videosummary;

import com.cixingji.backend.pojo.dto.videosummary.VideoSummaryResponse;

public interface VideoSummaryService {
    VideoSummaryResponse createOrGet(Integer vid);

    VideoSummaryResponse getTask(String taskId);

    VideoSummaryResponse getLatest(Integer vid);

    void handleXfyunCallback(String orderId, int status);
}
