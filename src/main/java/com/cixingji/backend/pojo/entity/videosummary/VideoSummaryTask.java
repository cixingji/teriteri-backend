package com.cixingji.backend.pojo.entity.videosummary;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("video_summary_task")
public class VideoSummaryTask {

    @TableId(type = IdType.INPUT)
    private String taskId;
    private Integer vid;
    private String summaryVersion;
    private String status;
    private String asrOrderId;
    private String transcript;
    private String summaryJson;
    private String errorMessage;
    private Integer retryCount;
    private Date nextAttemptAt;
    private Date createdAt;
    private Date updatedAt;
    private Integer version;
}
