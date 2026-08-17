package com.cixingji.backend.pojo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import lombok.Data;

import java.util.Date;

@Data
public class VideoUploadSession {
    @TableId(type = IdType.INPUT)
    private String id;
    private Integer uid;
    private String fileHash;
    private String fileName;
    private String extension;
    private Long totalSize;
    private Integer totalChunks;
    private Long uploadedBytes;
    private String status;
    private Long assetId;
    private Integer videoId;
    private String errorMessage;
    private Date createdAt;
    private Date updatedAt;
    private Date expiresAt;
}
