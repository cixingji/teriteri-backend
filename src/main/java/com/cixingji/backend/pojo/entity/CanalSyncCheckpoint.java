package com.cixingji.backend.pojo.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

@Data
@TableName("canal_sync_checkpoint")
public class CanalSyncCheckpoint {
    @TableId
    private String destination;
    private Long batchId;
    private String binlogFile;
    private Long binlogPosition;
    private Date updatedAt;
}
