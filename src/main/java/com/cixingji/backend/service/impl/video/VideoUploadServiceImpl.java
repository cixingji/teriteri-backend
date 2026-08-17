package com.cixingji.backend.service.impl.video;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.cixingji.backend.config.MediaProperties;
import com.cixingji.backend.im.IMServer;
import com.cixingji.backend.mapper.*;
import com.cixingji.backend.pojo.dto.VideoUploadInfoDTO;
import com.cixingji.backend.pojo.entity.*;
import com.cixingji.backend.service.media.MediaProbeService;
import com.cixingji.backend.service.media.MediaStorageService;
import com.cixingji.backend.service.media.VideoTranscodeService;
import com.cixingji.backend.service.utils.CurrentUser;
import com.cixingji.backend.service.video.VideoUploadService;
import com.cixingji.backend.utils.ESUtil;
import com.cixingji.backend.utils.RedisUtil;
import lombok.extern.slf4j.Slf4j;
import com.alibaba.fastjson2.JSON;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

@Slf4j
@Service
public class VideoUploadServiceImpl implements VideoUploadService {
    private static final Set<String> EXTENSIONS = new HashSet<>(Arrays.asList("mp4", "mkv"));
    private final VideoUploadSessionMapper sessionMapper;
    private final VideoUploadChunkMapper chunkMapper;
    private final MediaAssetMapper assetMapper;
    private final VideoTranscodeTaskMapper taskMapper;
    private final VideoMapper videoMapper;
    private final VideoStatsMapper statsMapper;
    private final CurrentUser currentUser;
    private final MediaProperties properties;
    private final MediaStorageService storage;
    private final MediaProbeService probeService;
    private final VideoTranscodeService transcodeService;
    private final RedisUtil redisUtil;
    private final ESUtil esUtil;
    private final Executor taskExecutor;
    private final Map<String, Object> sessionLocks = new ConcurrentHashMap<>();

    public VideoUploadServiceImpl(VideoUploadSessionMapper sessionMapper, VideoUploadChunkMapper chunkMapper,
                                  MediaAssetMapper assetMapper, VideoTranscodeTaskMapper taskMapper,
                                  VideoMapper videoMapper, VideoStatsMapper statsMapper, CurrentUser currentUser,
                                  MediaProperties properties, MediaStorageService storage,
                                  MediaProbeService probeService, VideoTranscodeService transcodeService,
                                  RedisUtil redisUtil, ESUtil esUtil,
                                  @Qualifier("taskExecutor") Executor taskExecutor) {
        this.sessionMapper = sessionMapper;
        this.chunkMapper = chunkMapper;
        this.assetMapper = assetMapper;
        this.taskMapper = taskMapper;
        this.videoMapper = videoMapper;
        this.statsMapper = statsMapper;
        this.currentUser = currentUser;
        this.properties = properties;
        this.storage = storage;
        this.probeService = probeService;
        this.transcodeService = transcodeService;
        this.redisUtil = redisUtil;
        this.esUtil = esUtil;
        this.taskExecutor = taskExecutor;
    }

    @Override
    @Transactional
    public CustomResponse initUpload(String hash, String fileName, Long totalSize, Integer totalChunks) {
        Integer uid = currentUser.getUserId();
        String extension = extension(fileName);
        if (!validHash(hash) || !EXTENSIONS.contains(extension)) return error(400, "只支持MP4、MKV文件");
        if (totalSize == null || totalSize <= 0 || totalSize > properties.getMaxFileSize()) return error(400, "视频不能超过2GB");
        int expectedChunks = (int) ((totalSize + properties.getChunkSize() - 1) / properties.getChunkSize());
        if (totalChunks == null || totalChunks != expectedChunks) return error(400, "分片数量与5MB分片规则不一致");

        VideoUploadSession session = sessionMapper.selectOne(new QueryWrapper<VideoUploadSession>()
                .eq("uid", uid).eq("file_hash", hash).eq("total_size", totalSize)
                .notIn("status", Arrays.asList("CANCELLED", "COMPLETED"))
                .orderByDesc("created_at").last("LIMIT 1"));
        if (session != null) return responseFor(session);
        Long active = sessionMapper.selectCount(new QueryWrapper<VideoUploadSession>().eq("uid", uid).eq("status", "UPLOADING"));
        if (active != null && active > 0) return error(409, "同一时间只能上传一个视频");

        MediaAsset asset = findAsset(hash, totalSize);
        Date now = new Date();
        session = new VideoUploadSession();
        session.setId(UUID.randomUUID().toString().replace("-", ""));
        session.setUid(uid);
        session.setFileHash(hash);
        session.setFileName(safeFileName(fileName));
        session.setExtension(extension);
        session.setTotalSize(totalSize);
        session.setTotalChunks(totalChunks);
        session.setUploadedBytes(0L);
        session.setAssetId(asset == null ? null : asset.getId());
        session.setStatus(asset == null ? "UPLOADING" : "ASSET_READY");
        session.setCreatedAt(now);
        session.setUpdatedAt(now);
        session.setExpiresAt(Date.from(Instant.now().plusSeconds(7 * 24 * 3600L)));
        sessionMapper.insert(session);
        return responseFor(session);
    }

    @Override
    public CustomResponse getUploadSession(String hash) {
        VideoUploadSession session = latestSession(currentUser.getUserId(), hash);
        return session == null ? error(404, "没有可恢复的上传任务") : responseFor(session);
    }

    @Override
    public CustomResponse askCurrentChunk(String hash) {
        VideoUploadSession session = latestSession(currentUser.getUserId(), hash);
        if (session == null) return new CustomResponse(200, "OK", 0);
        Set<Integer> indexes = new HashSet<>();
        for (VideoUploadChunk chunk : chunks(session.getId())) indexes.add(chunk.getChunkIndex());
        int next = 0;
        while (indexes.contains(next)) next++;
        return new CustomResponse(200, "OK", next);
    }

    @Override
    public CustomResponse uploadChunk(MultipartFile chunk, String sessionId, Integer index, String chunkHash) throws IOException {
        VideoUploadSession session = ownedSession(sessionId);
        if (session == null) return error(404, "上传任务不存在");
        if (!"UPLOADING".equals(session.getStatus())) return error(409, "当前任务不能继续上传分片");
        if (index == null || index < 0 || index >= session.getTotalChunks()) return error(400, "分片序号不合法");
        if (chunk.isEmpty() || chunk.getSize() > properties.getChunkSize()) return error(400, "单个分片不能超过5MB");
        if (!validHash(chunkHash)) return error(400, "分片哈希格式不正确");

        String lockKey = sessionId + ":" + index;
        Object chunkLock = sessionLocks.computeIfAbsent(lockKey, ignored -> new Object());
        try {
            synchronized (chunkLock) {
                VideoUploadChunk existing = chunkMapper.selectOne(new QueryWrapper<VideoUploadChunk>()
                        .eq("session_id", sessionId).eq("chunk_index", index).last("LIMIT 1"));
                if (existing != null) {
                    if (existing.getChunkHash().equalsIgnoreCase(chunkHash) && existing.getChunkSize().equals(chunk.getSize())) {
                        return responseFor(sessionMapper.selectById(sessionId));
                    }
                    return error(409, "该分片已存在但校验值不同");
                }
                Path path = storage.chunkPath(sessionId, index);
                storage.saveAtomically(chunk.getInputStream(), path);
                String actual = storage.md5(path);
                if (!actual.equalsIgnoreCase(chunkHash)) {
                    Files.deleteIfExists(path);
                    return error(400, "分片校验失败");
                }
                VideoUploadChunk record = new VideoUploadChunk();
                record.setSessionId(sessionId);
                record.setChunkIndex(index);
                record.setChunkHash(actual);
                record.setChunkSize(chunk.getSize());
                record.setStoragePath(path.toString());
                record.setCreatedAt(new Date());
                try {
                    chunkMapper.insert(record);
                } catch (DuplicateKeyException duplicate) {
                    return responseFor(sessionMapper.selectById(sessionId));
                }
                sessionMapper.update(null, new UpdateWrapper<VideoUploadSession>().eq("id", sessionId)
                        .setSql("uploaded_bytes = uploaded_bytes + " + chunk.getSize()).set("updated_at", new Date()));
                return responseFor(sessionMapper.selectById(sessionId));
            }
        } finally {
            sessionLocks.remove(lockKey, chunkLock);
        }
    }

    @Override
    public CustomResponse cancelUpload(String value) {
        VideoUploadSession session = ownedSession(value);
        if (session == null) session = latestSession(currentUser.getUserId(), value);
        if (session == null) return new CustomResponse();
        sessionMapper.update(null, new UpdateWrapper<VideoUploadSession>().eq("id", session.getId())
                .set("status", "CANCELLED").set("updated_at", new Date()));
        chunkMapper.delete(new QueryWrapper<VideoUploadChunk>().eq("session_id", session.getId()));
        try {
            storage.deleteTree(storage.sessionDirectory(session.getId()));
        } catch (IOException e) {
            log.warn("清理取消上传分片失败: {}", session.getId(), e);
        }
        sessionLocks.remove(session.getId());
        return new CustomResponse();
    }

    @Override
    @Transactional
    public CustomResponse addVideo(MultipartFile cover, VideoUploadInfoDTO info) throws IOException {
        Integer uid = currentUser.getUserId();
        VideoUploadSession session = info.getUploadId() == null ? latestSession(uid, info.getHash()) : ownedSession(info.getUploadId());
        if (session == null) return error(404, "上传任务不存在");
        if (!validMetadata(info)) return error(400, "投稿信息不完整或超出长度限制");
        if (session.getVideoId() != null) return new CustomResponse(200, "投稿已创建", taskData(session));
        Long chunkCount = chunkMapper.selectCount(new QueryWrapper<VideoUploadChunk>().eq("session_id", session.getId()));
        if (session.getAssetId() == null && (chunkCount == null || chunkCount.intValue() != session.getTotalChunks())) {
            return error(409, "视频分片尚未全部上传");
        }

        String coverName = UUID.randomUUID().toString().replace("-", "") + ".jpg";
        validateCover(cover);
        storage.saveAtomically(cover.getInputStream(), storage.coverPath(coverName));

        Video video = new Video();
        video.setUid(uid);
        video.setTitle(info.getTitle().trim());
        video.setType(info.getType());
        video.setAuth(info.getAuth());
        video.setDuration(info.getDuration());
        video.setMcId(info.getMcId());
        video.setScId(info.getScId());
        video.setTags(info.getTags());
        video.setDescr(info.getDescr());
        video.setCoverUrl("/api/media/covers/" + coverName);
        video.setVideoUrl("");
        video.setStatus(4);
        video.setUploadDate(new Date());
        video.setAssetId(session.getAssetId());
        videoMapper.insert(video);
        statsMapper.insert(new VideoStats(video.getVid(), 0, 0, 0, 0, 0, 0, 0, 0));

        session.setVideoId(video.getVid());
        session.setStatus(session.getAssetId() == null ? "ASSEMBLING" : "TRANSCODING");
        session.setUpdatedAt(new Date());
        sessionMapper.updateById(session);
        if (session.getAssetId() != null) attachExistingAsset(session, video);
        else CompletableFuture.runAsync(() -> assemble(session.getId()), taskExecutor);

        Map<String, Object> data = new HashMap<>();
        data.put("videoId", video.getVid());
        data.put("uploadId", session.getId());
        data.put("status", session.getStatus());
        return new CustomResponse(202, "投稿已接收，正在处理视频", data);
    }

    @Override
    public CustomResponse listMyTasks() {
        List<VideoUploadSession> sessions = sessionMapper.selectList(new QueryWrapper<VideoUploadSession>()
                .eq("uid", currentUser.getUserId()).orderByDesc("created_at").last("LIMIT 20"));
        List<Map<String, Object>> result = new ArrayList<>();
        for (VideoUploadSession session : sessions) result.add(taskData(session));
        return new CustomResponse(200, "OK", result);
    }

    @Override
    public CustomResponse retryTranscode(Long taskId) {
        VideoTranscodeTask task = taskMapper.selectById(taskId);
        if (task == null) return error(404, "转码任务不存在");
        Video video = videoMapper.selectOne(new QueryWrapper<Video>().eq("asset_id", task.getAssetId())
                .eq("uid", currentUser.getUserId()).last("LIMIT 1"));
        if (video == null) return error(403, "无权重试该任务");
        return transcodeService.retry(taskId) ? new CustomResponse() : error(409, "任务当前不能重试");
    }

    private void assemble(String sessionId) {
        VideoUploadSession session = sessionMapper.selectById(sessionId);
        if (session == null) return;
        Path original = storage.originalPath(session.getFileHash(), session.getExtension());
        Path temporary = original.resolveSibling(session.getFileHash() + ".assembling");
        try {
            Files.createDirectories(temporary.getParent());
            try (FileChannel output = FileChannel.open(temporary, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                for (VideoUploadChunk chunk : chunks(sessionId)) {
                    try (FileChannel input = FileChannel.open(Path.of(chunk.getStoragePath()), StandardOpenOption.READ)) {
                        long position = 0;
                        while (position < input.size()) position += input.transferTo(position, input.size() - position, output);
                    }
                }
            }
            if (Files.size(temporary) != session.getTotalSize() || !storage.md5(temporary).equalsIgnoreCase(session.getFileHash())) {
                throw new IOException("合并后的文件哈希或大小不一致");
            }
            MediaProbeService.ProbeResult probe = probeService.probe(temporary);
            if (probe.getDuration() <= 0 || probe.getDuration() > properties.getMaxDurationSeconds()) {
                throw new IOException("视频时长超过4小时或无法识别");
            }
            Files.move(temporary, original, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            MediaAsset asset = createOrGetAsset(session, original, probe);
            session.setAssetId(asset.getId());
            session.setStatus("TRANSCODING");
            session.setUpdatedAt(new Date());
            sessionMapper.updateById(session);
            videoMapper.update(null, new UpdateWrapper<Video>().eq("vid", session.getVideoId())
                    .set("asset_id", asset.getId()).set("duration", probe.getDuration()));
            if ("READY".equals(asset.getStatus())) publishInstant(session, videoMapper.selectById(session.getVideoId()), asset);
            else transcodeService.enqueue(asset.getId());
            cleanupChunks(sessionId);
            notifyUser(session.getUid(), "上传完成", "视频已进入转码队列", session.getVideoId(), 0);
        } catch (Exception e) {
            log.error("合并上传{}失败", sessionId, e);
            try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
            sessionMapper.update(null, new UpdateWrapper<VideoUploadSession>().eq("id", sessionId)
                    .set("status", "FAILED").set("error_message", message(e)).set("updated_at", new Date()));
            if (session.getVideoId() != null) {
                videoMapper.update(null, new UpdateWrapper<Video>().eq("vid", session.getVideoId()).set("status", 5));
            }
            notifyUser(session.getUid(), "上传处理失败", message(e), session.getVideoId(), 0);
        }
    }

    private MediaAsset createOrGetAsset(VideoUploadSession session, Path original, MediaProbeService.ProbeResult probe) {
        MediaAsset existing = findAsset(session.getFileHash(), session.getTotalSize());
        if (existing != null) {
            incrementReference(existing.getId());
            return assetMapper.selectById(existing.getId());
        }
        Date now = new Date();
        MediaAsset asset = new MediaAsset();
        asset.setSourceHash(session.getFileHash());
        asset.setFileSize(session.getTotalSize());
        asset.setExtension(session.getExtension());
        asset.setOriginalPath(original.toString());
        asset.setStatus("TRANSCODING");
        asset.setRefCount(1);
        asset.setWidth(probe.getWidth());
        asset.setHeight(probe.getHeight());
        asset.setDuration(probe.getDuration());
        asset.setFps(Math.min(properties.getMaxFps(), probe.getFps()));
        asset.setHasAudio(probe.isAudio() ? 1 : 0);
        asset.setHdr(probe.isHdr() ? 1 : 0);
        asset.setCreatedAt(now);
        asset.setUpdatedAt(now);
        try {
            assetMapper.insert(asset);
            return asset;
        } catch (DuplicateKeyException race) {
            MediaAsset winner = findAsset(session.getFileHash(), session.getTotalSize());
            incrementReference(winner.getId());
            return assetMapper.selectById(winner.getId());
        }
    }

    private void attachExistingAsset(VideoUploadSession session, Video video) {
        MediaAsset asset = assetMapper.selectById(session.getAssetId());
        incrementReference(asset.getId());
        if ("READY".equals(asset.getStatus())) publishInstant(session, video, asset);
        else if ("FAILED".equals(asset.getStatus())) {
            VideoTranscodeTask task = taskMapper.selectOne(new QueryWrapper<VideoTranscodeTask>()
                    .eq("asset_id", asset.getId()).last("LIMIT 1"));
            if (task != null) transcodeService.retry(task.getId());
            else transcodeService.enqueue(asset.getId());
        } else {
            transcodeService.enqueue(asset.getId());
        }
    }

    private void incrementReference(Long assetId) {
        assetMapper.update(null, new UpdateWrapper<MediaAsset>().eq("id", assetId)
                .setSql("ref_count = ref_count + 1").set("delete_after", null).set("updated_at", new Date()));
    }

    private void publishInstant(VideoUploadSession session, Video video, MediaAsset asset) {
        video.setStatus(0);
        video.setDuration(asset.getDuration());
        video.setVideoUrl("/api/media/videos/" + video.getVid() + "/master.m3u8");
        videoMapper.update(null, new UpdateWrapper<Video>().eq("vid", video.getVid())
                .set("status", 0).set("duration", asset.getDuration()).set("video_url", video.getVideoUrl()));
        sessionMapper.update(null, new UpdateWrapper<VideoUploadSession>().eq("id", session.getId())
                .set("status", "COMPLETED").set("updated_at", new Date()));
        redisUtil.addMember("video_status:0", video.getVid());
        esUtil.addVideo(video);
        notifyUser(video.getUid(), "秒传完成", "视频已进入审核队列", video.getVid(), 100);
    }

    private Map<String, Object> taskData(VideoUploadSession session) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("uploadId", session.getId());
        map.put("videoId", session.getVideoId());
        map.put("fileName", session.getFileName());
        map.put("status", session.getStatus());
        map.put("uploadedBytes", session.getUploadedBytes());
        map.put("totalSize", session.getTotalSize());
        map.put("error", session.getErrorMessage());
        if (session.getAssetId() != null) {
            VideoTranscodeTask task = taskMapper.selectOne(new QueryWrapper<VideoTranscodeTask>()
                    .eq("asset_id", session.getAssetId()).last("LIMIT 1"));
            if (task != null) {
                map.put("taskId", task.getId());
                map.put("transcodeStatus", task.getStatus());
                map.put("progress", task.getProgress());
                map.put("attempts", task.getAttempts());
                map.put("encoder", task.getEncoder());
                map.put("transcodeError", task.getErrorMessage());
                Long ahead = taskMapper.selectCount(new QueryWrapper<VideoTranscodeTask>()
                        .in("status", Arrays.asList("QUEUED", "RETRY")).lt("id", task.getId()));
                map.put("queuePosition", "RUNNING".equals(task.getStatus()) ? 0 : (ahead == null ? 1 : ahead + 1));
            }
            MediaAsset asset = assetMapper.selectById(session.getAssetId());
            if (asset != null && asset.getCoverCandidates() != null && session.getVideoId() != null) {
                List<String> candidates = new ArrayList<>();
                for (String name : JSON.parseArray(asset.getCoverCandidates(), String.class)) {
                    candidates.add("/api/media/videos/" + session.getVideoId() + "/" + name);
                }
                map.put("coverCandidates", candidates);
            }
        }
        return map;
    }

    private CustomResponse responseFor(VideoUploadSession session) {
        Map<String, Object> data = taskData(session);
        List<Integer> uploaded = new ArrayList<>();
        for (VideoUploadChunk chunk : chunks(session.getId())) uploaded.add(chunk.getChunkIndex());
        data.put("uploadedChunks", uploaded);
        data.put("instantUpload", session.getAssetId() != null);
        return new CustomResponse(200, "OK", data);
    }

    private List<VideoUploadChunk> chunks(String sessionId) {
        return chunkMapper.selectList(new QueryWrapper<VideoUploadChunk>()
                .eq("session_id", sessionId).orderByAsc("chunk_index"));
    }

    private void cleanupChunks(String sessionId) {
        chunkMapper.delete(new QueryWrapper<VideoUploadChunk>().eq("session_id", sessionId));
        try { storage.deleteTree(storage.sessionDirectory(sessionId)); }
        catch (IOException e) { log.warn("分片清理失败", e); }
        sessionLocks.remove(sessionId);
    }

    private VideoUploadSession latestSession(Integer uid, String hash) {
        if (hash == null) return null;
        return sessionMapper.selectOne(new QueryWrapper<VideoUploadSession>().eq("uid", uid).eq("file_hash", hash)
                .notIn("status", Arrays.asList("CANCELLED", "COMPLETED"))
                .orderByDesc("created_at").last("LIMIT 1"));
    }

    private VideoUploadSession ownedSession(String id) {
        if (id == null) return null;
        return sessionMapper.selectOne(new QueryWrapper<VideoUploadSession>().eq("id", id)
                .eq("uid", currentUser.getUserId()).last("LIMIT 1"));
    }

    private MediaAsset findAsset(String hash, Long size) {
        return assetMapper.selectOne(new QueryWrapper<MediaAsset>().eq("source_hash", hash).eq("file_size", size)
                .in("status", Arrays.asList("READY", "TRANSCODING", "FAILED")).last("LIMIT 1"));
    }

    private void validateCover(MultipartFile cover) throws IOException {
        if (cover == null || cover.isEmpty() || cover.getSize() > 5 * 1024 * 1024) {
            throw new IOException("封面必须是5MB以内的图片");
        }
        try (InputStream input = cover.getInputStream()) {
            if (ImageIO.read(input) == null) throw new IOException("封面图片无法识别");
        }
    }

    private boolean validMetadata(VideoUploadInfoDTO info) {
        return info != null && info.getTitle() != null && !info.getTitle().trim().isEmpty()
                && info.getTitle().length() <= 80 && info.getDescr() != null && info.getDescr().length() <= 2000
                && info.getDuration() != null && info.getMcId() != null && info.getScId() != null;
    }

    private String extension(String fileName) {
        if (fileName == null || !fileName.contains(".")) return "";
        return fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
    }

    private String safeFileName(String value) {
        if (value == null) return "video";
        String normalized = value.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        String name = slash >= 0 ? normalized.substring(slash + 1) : normalized;
        name = name.replaceAll("[\\r\\n\\t]", "_");
        return name.length() > 255 ? name.substring(name.length() - 255) : name;
    }

    private boolean validHash(String value) { return value != null && value.matches("(?i)[a-f0-9]{32}"); }
    private String message(Exception e) {
        String value = e.getMessage();
        return value == null ? e.getClass().getSimpleName() : value.substring(0, Math.min(900, value.length()));
    }
    private CustomResponse error(int code, String message) { return new CustomResponse(code, message, null); }

    private void notifyUser(Integer uid, String event, String message, Integer videoId, int progress) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("type", "接收");
        payload.put("event", event);
        payload.put("message", message);
        payload.put("videoId", videoId);
        payload.put("progress", progress);
        IMServer.broadcast(uid, "system", payload);
    }
}
