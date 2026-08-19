package com.cixingji.backend.pojo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

@Data
@TableName("search_sync_event")
public class SearchSyncEvent {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String eventId;
    private String sourceTable;
    private String eventType;
    private String aggregateType;
    private String aggregateKey;
    private String binlogFile;
    private Long binlogPosition;
    private Long executeTime;
    private String payload;
    private String status;
    private Integer attempts;
    private Date nextRetryAt;
    private String errorMessage;
    private Date createdAt;
    private Date updatedAt;
    private Date processedAt;
}
