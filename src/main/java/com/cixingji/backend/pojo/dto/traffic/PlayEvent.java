package com.cixingji.backend.pojo.dto.traffic;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PlayEvent {
    private String eventId;
    private Integer vid;
    private String actorType;
    private String actorKey;
    private long occurredAt;
}
