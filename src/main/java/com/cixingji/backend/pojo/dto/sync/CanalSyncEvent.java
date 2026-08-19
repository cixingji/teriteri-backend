package com.cixingji.backend.pojo.dto.sync;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CanalSyncEvent {
    private String eventId;
    private String sourceTable;
    private String eventType;
    private String aggregateType;
    private String aggregateKey;
    private String binlogFile;
    private Long binlogPosition;
    private Long executeTime;
    private Map<String, String> columns;
}
