package com.cixingji.backend.pojo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import lombok.Data;

import java.util.Date;

@Data
public class VideoTranscodeTask {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long assetId;
    private String status;
    private Integer progress;
    private Integer attempts;
    private String encoder;
    private String errorMessage;
    private Date nextAttemptAt;
    private Date startedAt;
    private Date finishedAt;
    private Date createdAt;
    private Date updatedAt;
}
