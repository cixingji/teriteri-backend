package com.cixingji.backend.service.media;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.cixingji.backend.config.MediaProperties;
import com.cixingji.backend.mapper.MediaAssetMapper;
import com.cixingji.backend.mapper.VideoMapper;
import com.cixingji.backend.pojo.entity.MediaAsset;
import com.cixingji.backend.pojo.entity.Video;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
public class MediaLifecycleService {
    private final MediaAssetMapper assetMapper;
    private final VideoMapper videoMapper;
    private final MediaStorageService storage;
    private final MediaProperties properties;

    public MediaLifecycleService(MediaAssetMapper assetMapper, VideoMapper videoMapper,
                                 MediaStorageService storage, MediaProperties properties) {
        this.assetMapper = assetMapper; this.videoMapper = videoMapper;
        this.storage = storage; this.properties = properties;
    }

    public void release(Long assetId) {
        if (assetId == null) return;
        MediaAsset asset = assetMapper.selectById(assetId);
        if (asset == null) return;
        UpdateWrapper<MediaAsset> update = new UpdateWrapper<>();
        update.eq("id", assetId).setSql("ref_count = GREATEST(ref_count - 1, 0)").set("updated_at", new Date());
        assetMapper.update(null, update);
        MediaAsset updated = assetMapper.selectById(assetId);
        if (updated != null && updated.getRefCount() == 0) {
            assetMapper.update(null, new UpdateWrapper<MediaAsset>().eq("id", assetId).eq("ref_count", 0)
                    .set("delete_after", Date.from(Instant.now().plusSeconds(properties.getOrphanGraceHours() * 3600L)))
                    .set("updated_at", new Date()));
        }
    }

    @Scheduled(cron = "0 20 3 * * ?")
    public void cleanupOrphans() {
        List<MediaAsset> expired = assetMapper.selectList(new QueryWrapper<MediaAsset>()
                .eq("ref_count", 0).le("delete_after", new Date()));
        for (MediaAsset asset : expired) {
            try {
                if (asset.getOriginalPath() != null) Files.deleteIfExists(Path.of(asset.getOriginalPath()));
                if (asset.getHlsPath() != null) storage.deleteTree(Path.of(asset.getHlsPath()));
                assetMapper.deleteById(asset.getId());
            } catch (IOException e) {
                log.warn("清理媒体资产{}失败", asset.getId(), e);
            }
        }
        cleanupCovers();
    }

    private void cleanupCovers() {
        Set<Path> referenced = new HashSet<>();
        for (Video video : videoMapper.selectList(new QueryWrapper<Video>().select("cover_url"))) {
            if (video.getCoverUrl() != null && video.getCoverUrl().startsWith("/api/media/covers/")) {
                referenced.add(storage.coverPath(video.getCoverUrl().substring("/api/media/covers/".length())));
            }
        }
        Path covers = storage.root().resolve("covers");
        try (java.util.stream.Stream<Path> files = Files.list(covers)) {
            Instant cutoff = Instant.now().minusSeconds(properties.getOrphanGraceHours() * 3600L);
            files.filter(Files::isRegularFile).filter(path -> !referenced.contains(path))
                    .filter(path -> {
                        try { return Files.getLastModifiedTime(path).toInstant().isBefore(cutoff); }
                        catch (IOException e) { return false; }
                    }).forEach(path -> {
                        try { Files.deleteIfExists(path); } catch (IOException e) { log.warn("清理孤儿封面失败: {}", path, e); }
                    });
        } catch (IOException e) {
            log.warn("扫描孤儿封面失败", e);
        }
    }
}
