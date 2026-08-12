package com.cixingji.backend.pojo.dto.videosummary;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VideoSummaryResponse {
    private String taskId;
    private Integer vid;
    private String status;
    private String transcript;
    private JsonNode summary;
    private String errorMessage;
    private Integer retryCount;
    private Date createdAt;
    private Date updatedAt;
}
