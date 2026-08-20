package com.cixingji.backend.pojo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

@Data
@TableName("traffic_dead_letter")
public class TrafficDeadLetter {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String sourceTopic;
    private Integer sourcePartition;
    private Long sourceOffset;
    private String messageKey;
    private String payload;
    private String status;
    private String errorMessage;
    private Date createdAt;
    private Date replayedAt;
}
