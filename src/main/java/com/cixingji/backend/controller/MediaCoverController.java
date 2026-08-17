package com.cixingji.backend.controller;

import com.alibaba.fastjson2.JSON;
import com.cixingji.backend.mapper.MediaAssetMapper;
import com.cixingji.backend.mapper.VideoMapper;
import com.cixingji.backend.pojo.entity.CustomResponse;
import com.cixingji.backend.pojo.entity.MediaAsset;
import com.cixingji.backend.pojo.entity.Video;
import com.cixingji.backend.service.media.MediaStorageService;
import com.cixingji.backend.service.utils.CurrentUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;

@RestController
public class MediaCoverController {
    private final VideoMapper videoMapper;
    private final MediaAssetMapper assetMapper;
    private final MediaStorageService storage;
    private final CurrentUser currentUser;

    public MediaCoverController(VideoMapper videoMapper, MediaAssetMapper assetMapper,
                                MediaStorageService storage, CurrentUser currentUser) {
        this.videoMapper = videoMapper; this.assetMapper = assetMapper;
        this.storage = storage; this.currentUser = currentUser;
    }

    @GetMapping("/video/cover/candidates")
    public CustomResponse candidates(@RequestParam Integer vid) {
        Video video = ownedVideo(vid);
        if (video == null) return new CustomResponse(403, "无权查看候选封面", null);
        MediaAsset asset = assetMapper.selectById(video.getAssetId());
        if (asset == null || asset.getCoverCandidates() == null) return new CustomResponse(200, "OK", Collections.emptyList());
        return new CustomResponse(200, "OK", candidateUrls(vid, asset.getCoverCandidates()));
    }

    @PostMapping("/video/cover/select")
    public CustomResponse select(@RequestParam Integer vid, @RequestParam String fileName) throws Exception {
        Video video = ownedVideo(vid);
        if (video == null) return new CustomResponse(403, "无权修改候选封面", null);
        if (!fileName.matches("cover-(10|50|90)\\.jpg")) return new CustomResponse(400, "候选封面名称不合法", null);
        MediaAsset asset = assetMapper.selectById(video.getAssetId());
        if (asset == null || asset.getHlsPath() == null) return new CustomResponse(409, "候选封面尚未生成", null);
        Path source = Path.of(asset.getHlsPath()).resolve(fileName).normalize();
        if (!source.startsWith(Path.of(asset.getHlsPath()).normalize()) || !Files.isRegularFile(source)) {
            return new CustomResponse(404, "候选封面不存在", null);
        }
        String targetName = UUID.randomUUID().toString().replace("-", "") + ".jpg";
        Files.copy(source, storage.coverPath(targetName), StandardCopyOption.REPLACE_EXISTING);
        video.setCoverUrl("/api/media/covers/" + targetName);
        videoMapper.updateById(video);
        return new CustomResponse(200, "封面已更新", video.getCoverUrl());
    }

    private Video ownedVideo(Integer vid) {
        Video video = videoMapper.selectById(vid);
        if (video == null || video.getAssetId() == null) return null;
        return Objects.equals(video.getUid(), currentUser.getUserId()) || currentUser.isAdmin() ? video : null;
    }

    private List<String> candidateUrls(Integer vid, String json) {
        List<String> result = new ArrayList<>();
        for (String name : JSON.parseArray(json, String.class)) result.add("/api/media/videos/" + vid + "/" + name);
        return result;
    }
}
