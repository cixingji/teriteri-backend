package com.cixingji.backend.pojo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

@Data
@TableName("traffic_business_log")
public class TrafficBusinessLog {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String eventId;
    private String traceId;
    private String action;
    private String method;
    private String path;
    private Integer responseStatus;
    private Long latencyMs;
    private String actorKey;
    private Date occurredAt;
    private Date createdAt;
}
