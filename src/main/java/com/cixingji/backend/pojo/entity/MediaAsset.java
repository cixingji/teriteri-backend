package com.cixingji.backend.pojo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import lombok.Data;

import java.util.Date;

@Data
public class MediaAsset {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String sourceHash;
    private Long fileSize;
    private String extension;
    private String originalPath;
    private String hlsPath;
    private String status;
    private Integer refCount;
    private Integer width;
    private Integer height;
    private Double duration;
    private Double fps;
    private Integer hasAudio;
    private Integer hdr;
    private String coverCandidates;
    private Date createdAt;
    private Date updatedAt;
    private Date deleteAfter;
}
