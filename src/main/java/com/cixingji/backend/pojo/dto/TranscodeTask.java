package com.cixingji.backend.pojo.dto;


import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class TranscodeTask {
    private String videoHash; // 用于查询视频ID
    private String originalOssPath; // 原始视频在 OSS 的路径

}