package com.cixingji.backend.pojo.dto.traffic;

import lombok.Data;

@Data
public class BusinessLogEvent {
    private String eventId;
    private String traceId;
    private String action;
    private String method;
    private String path;
    private Integer responseStatus;
    private Long latencyMs;
    private String actorKey;
    private long occurredAt;
}
