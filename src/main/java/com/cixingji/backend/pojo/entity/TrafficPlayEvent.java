package com.cixingji.backend.pojo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

@Data
@TableName("traffic_play_event")
public class TrafficPlayEvent {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String eventId;
    private Integer vid;
    private String actorType;
    private String actorKey;
    private String status;
    private Integer attempts;
    private Date nextRetryAt;
    private String errorMessage;
    private Date occurredAt;
    private Date createdAt;
    private Date processedAt;
}
