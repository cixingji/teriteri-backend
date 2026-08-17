package com.cixingji.backend.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.cixingji.backend.mapper.MediaAssetMapper;
import com.cixingji.backend.mapper.UserMapper;
import com.cixingji.backend.mapper.VideoMapper;
import com.cixingji.backend.pojo.dto.auth.AuthSession;
import com.cixingji.backend.pojo.entity.MediaAsset;
import com.cixingji.backend.pojo.entity.User;
import com.cixingji.backend.pojo.entity.Video;
import com.cixingji.backend.service.auth.AuthSessionService;
import com.cixingji.backend.service.media.MediaStorageService;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

@RestController
public class MediaController {
    private final VideoMapper videoMapper;
    private final MediaAssetMapper assetMapper;
    private final UserMapper userMapper;
    private final AuthSessionService authSessionService;
    private final MediaStorageService storage;

    public MediaController(VideoMapper videoMapper, MediaAssetMapper assetMapper, UserMapper userMapper,
                           AuthSessionService authSessionService, MediaStorageService storage) {
        this.videoMapper = videoMapper; this.assetMapper = assetMapper; this.userMapper = userMapper;
        this.authSessionService = authSessionService; this.storage = storage;
    }

    @GetMapping("/media/covers/{fileName:.+}")
    public ResponseEntity<Resource> cover(@PathVariable String fileName) throws IOException {
        if (!fileName.matches("[a-f0-9]{32}\\.jpg")) return ResponseEntity.notFound().build();
        Path path = storage.coverPath(fileName);
        if (!Files.isRegularFile(path)) return ResponseEntity.notFound().build();
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofDays(7)).cachePublic())
                .contentType(MediaType.IMAGE_JPEG).body(new FileSystemResource(path));
    }

    @GetMapping("/media/videos/{vid}/**")
    public ResponseEntity<?> video(@PathVariable Integer vid,
                                   @RequestParam(value = "access_token", required = false) String accessToken,
                                   HttpServletRequest request) throws IOException {
        Video video = videoMapper.selectById(vid);
        if (video == null || video.getAssetId() == null || video.getStatus() == 3) return ResponseEntity.notFound().build();
        if (video.getStatus() != 1 && !canPreview(video, accessToken, request.getHeader(HttpHeaders.AUTHORIZATION))) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        MediaAsset asset = assetMapper.selectById(video.getAssetId());
        if (asset == null || !"READY".equals(asset.getStatus()) || asset.getHlsPath() == null) return ResponseEntity.notFound().build();
        String prefix = "/media/videos/" + vid + "/";
        String uri = request.getRequestURI();
        int position = uri.indexOf(prefix);
        if (position < 0) return ResponseEntity.badRequest().build();
        String relative = uri.substring(position + prefix.length());
        if (!relative.matches("[A-Za-z0-9_./-]+")) return ResponseEntity.badRequest().build();
        Path root = Path.of(asset.getHlsPath()).toAbsolutePath().normalize();
        Path target = root.resolve(relative).normalize();
        if (!target.startsWith(root) || !Files.isRegularFile(target)) return ResponseEntity.notFound().build();

        if (relative.endsWith(".m3u8")) {
            String playlist = Files.readString(target, StandardCharsets.UTF_8);
            if (video.getStatus() != 1 && StringUtils.hasText(accessToken)) {
                String encoded = URLEncoder.encode(accessToken, StandardCharsets.UTF_8.name());
                StringBuilder rewritten = new StringBuilder();
                for (String line : playlist.split("\\r?\\n")) {
                    if (!line.isEmpty() && !line.startsWith("#")) line += "?access_token=" + encoded;
                    rewritten.append(line).append('\n');
                }
                playlist = rewritten.toString();
            }
            return ResponseEntity.ok().cacheControl(CacheControl.noCache())
                    .contentType(MediaType.parseMediaType("application/vnd.apple.mpegurl")).body(playlist);
        }
        MediaType type = relative.endsWith(".ts") ? MediaType.parseMediaType("video/mp2t") : MediaType.IMAGE_JPEG;
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofDays(1)).cachePublic())
                .contentType(type).body(new FileSystemResource(target));
    }

    private boolean canPreview(Video video, String queryToken, String authorization) {
        String token = queryToken;
        if (!StringUtils.hasText(token) && StringUtils.hasText(authorization) && authorization.startsWith("Bearer ")) {
            token = authorization.substring(7);
        }
        if (!StringUtils.hasText(token)) return false;
        AuthSession session = authSessionService.validateAccessToken(token);
        if (session == null) return false;
        if (video.getUid().equals(session.getUserId())) return true;
        User user = userMapper.selectById(session.getUserId());
        return user != null && (Integer.valueOf(1).equals(user.getRole()) || Integer.valueOf(2).equals(user.getRole()));
    }
}
