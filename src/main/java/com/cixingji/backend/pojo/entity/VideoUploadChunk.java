package com.cixingji.backend.pojo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import lombok.Data;

import java.util.Date;

@Data
public class VideoUploadChunk {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String sessionId;
    private Integer chunkIndex;
    private String chunkHash;
    private Long chunkSize;
    private String storagePath;
    private Date createdAt;
}
