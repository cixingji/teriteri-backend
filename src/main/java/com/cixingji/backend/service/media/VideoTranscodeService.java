package com.cixingji.backend.service.media;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.cixingji.backend.config.MediaProperties;
import com.cixingji.backend.im.IMServer;
import com.cixingji.backend.mapper.MediaAssetMapper;
import com.cixingji.backend.mapper.VideoMapper;
import com.cixingji.backend.mapper.VideoTranscodeTaskMapper;
import com.cixingji.backend.mapper.VideoUploadSessionMapper;
import com.cixingji.backend.pojo.entity.MediaAsset;
import com.cixingji.backend.pojo.entity.Video;
import com.cixingji.backend.pojo.entity.VideoTranscodeTask;
import com.cixingji.backend.utils.ESUtil;
import com.cixingji.backend.utils.RedisUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
public class VideoTranscodeService {
    private static final List<Variant> VARIANTS = Arrays.asList(
            new Variant("360p", 360, 800), new Variant("480p", 480, 1400),
            new Variant("720p", 720, 2800), new Variant("1080p", 1080, 5000));

    private final VideoTranscodeTaskMapper taskMapper;
    private final MediaAssetMapper assetMapper;
    private final VideoMapper videoMapper;
    private final VideoUploadSessionMapper sessionMapper;
    private final MediaProperties properties;
    private final MediaStorageService storage;
    private final MediaProbeService probeService;
    private final RedisUtil redisUtil;
    private final ESUtil esUtil;
    private final AtomicBoolean workerBusy = new AtomicBoolean(false);
    private final ExecutorService transcodeExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "video-transcode-worker");
        thread.setDaemon(true);
        return thread;
    });

    public VideoTranscodeService(VideoTranscodeTaskMapper taskMapper, MediaAssetMapper assetMapper,
                                 VideoMapper videoMapper, VideoUploadSessionMapper sessionMapper,
                                 MediaProperties properties, MediaStorageService storage,
                                 MediaProbeService probeService, RedisUtil redisUtil, ESUtil esUtil) {
        this.taskMapper = taskMapper;
        this.assetMapper = assetMapper;
        this.videoMapper = videoMapper;
        this.sessionMapper = sessionMapper;
        this.properties = properties;
        this.storage = storage;
        this.probeService = probeService;
        this.redisUtil = redisUtil;
        this.esUtil = esUtil;
    }

    @PostConstruct
    public void recoverInterruptedTasks() {
        UpdateWrapper<VideoTranscodeTask> update = new UpdateWrapper<>();
        update.eq("status", "RUNNING").set("status", "QUEUED").set("progress", 0)
                .set("error_message", "后端重启，任务已恢复排队").set("updated_at", new Date());
        taskMapper.update(null, update);
        try {
            probeService.verifyAvailable();
            log.info("FFmpeg/FFprobe 可用，媒体根目录: {}", storage.root());
        } catch (Exception e) {
            log.warn("FFmpeg 启动检查失败，转码任务会保留在队列中: {}", e.getMessage());
        }
    }

    public VideoTranscodeTask enqueue(Long assetId) {
        QueryWrapper<VideoTranscodeTask> existingQuery = new QueryWrapper<>();
        existingQuery.eq("asset_id", assetId).last("LIMIT 1");
        VideoTranscodeTask existing = taskMapper.selectOne(existingQuery);
        if (existing != null) return existing;
        Date now = new Date();
        VideoTranscodeTask task = new VideoTranscodeTask();
        task.setAssetId(assetId);
        task.setStatus("QUEUED");
        task.setProgress(0);
        task.setAttempts(0);
        task.setEncoder(properties.getEncoder());
        task.setCreatedAt(now);
        task.setUpdatedAt(now);
        taskMapper.insert(task);
        return task;
    }

    @Scheduled(fixedDelay = 2000)
    public void dispatch() {
        if (!workerBusy.compareAndSet(false, true)) return;
        transcodeExecutor.execute(() -> {
            try {
                processNextTask();
            } finally {
                workerBusy.set(false);
            }
        });
    }

    private void processNextTask() {
        try {
            QueryWrapper<VideoTranscodeTask> query = new QueryWrapper<>();
            query.in("status", Arrays.asList("QUEUED", "RETRY"))
                    .and(wrapper -> wrapper.isNull("next_attempt_at").or().le("next_attempt_at", new Date()))
                    .orderByAsc("id").last("LIMIT 1");
            VideoTranscodeTask task = taskMapper.selectOne(query);
            if (task == null) return;
            UpdateWrapper<VideoTranscodeTask> claim = new UpdateWrapper<>();
            claim.eq("id", task.getId()).eq("status", task.getStatus())
                    .set("status", "RUNNING").set("started_at", new Date())
                    .set("updated_at", new Date()).set("error_message", null);
            if (taskMapper.update(null, claim) == 0) return;
            transcode(taskMapper.selectById(task.getId()));
        } catch (Exception e) {
            log.error("调度转码任务失败", e);
        }
    }

    @PreDestroy
    public void shutdownWorker() {
        transcodeExecutor.shutdownNow();
    }

    public boolean retry(Long taskId) {
        VideoTranscodeTask task = taskMapper.selectById(taskId);
        if (task == null || !"FAILED".equals(task.getStatus())) return false;
        UpdateWrapper<VideoTranscodeTask> update = new UpdateWrapper<>();
        update.eq("id", taskId).set("status", "QUEUED").set("attempts", 0)
                .set("progress", 0).set("next_attempt_at", null).set("error_message", null)
                .set("updated_at", new Date());
        assetMapper.update(null, new UpdateWrapper<MediaAsset>().eq("id", task.getAssetId())
                .set("status", "TRANSCODING").set("updated_at", new Date()));
        videoMapper.update(null, new UpdateWrapper<Video>().eq("asset_id", task.getAssetId()).eq("status", 5)
                .set("status", 4));
        return taskMapper.update(null, update) > 0;
    }

    private void transcode(VideoTranscodeTask task) {
        MediaAsset asset = assetMapper.selectById(task.getAssetId());
        if (asset == null || asset.getOriginalPath() == null) {
            fail(task, "原始视频文件不存在");
            return;
        }
        Path source = Path.of(asset.getOriginalPath());
        Path work = storage.workDirectory(asset.getSourceHash());
        try {
            probeService.verifyAvailable();
            storage.deleteTree(work);
            Files.createDirectories(work);
            MediaProbeService.ProbeResult probe = probeService.probe(source);
            if (probe.getDuration() <= 0 || probe.getDuration() > properties.getMaxDurationSeconds()) {
                throw new IOException("视频时长必须大于0且不能超过4小时");
            }
            updateAssetMetadata(asset, probe);
            List<Variant> variants = new ArrayList<>();
            for (Variant variant : VARIANTS) if (variant.height <= probe.getHeight()) variants.add(variant);
            if (variants.isEmpty()) variants.add(new Variant(probe.getHeight() + "p", probe.getHeight(), 600));
            List<String> candidates = extractCandidates(source, work, probe.getDuration());
            asset.setCoverCandidates(JSON.toJSONString(candidates));

            for (int i = 0; i < variants.size(); i++) {
                encodeVariant(source, work, variants.get(i), probe, i, variants.size(), task);
            }
            writeMaster(work, variants, probe.isAudio());
            Path destination = storage.hlsDirectory(asset.getSourceHash());
            storage.deleteTree(destination);
            Files.createDirectories(destination.getParent());
            Files.move(work, destination, StandardCopyOption.REPLACE_EXISTING);

            Date now = new Date();
            asset.setHlsPath(destination.toString());
            asset.setStatus("READY");
            asset.setUpdatedAt(now);
            asset.setDeleteAfter(null);
            assetMapper.updateById(asset);
            UpdateWrapper<VideoTranscodeTask> success = new UpdateWrapper<>();
            success.eq("id", task.getId()).set("status", "SUCCESS").set("progress", 100)
                    .set("finished_at", now).set("updated_at", now).set("error_message", null);
            taskMapper.update(null, success);
            publishForReview(asset, now);
        } catch (Exception e) {
            log.error("转码任务{}失败", task.getId(), e);
            try { storage.deleteTree(work); }
            catch (IOException cleanupError) { log.warn("清理失败的转码临时目录失败: {}", work, cleanupError); }
            fail(task, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    private void encodeVariant(Path source, Path work, Variant variant, MediaProbeService.ProbeResult probe,
                               int variantIndex, int totalVariants, VideoTranscodeTask task) throws IOException, InterruptedException {
        Path output = work.resolve(variant.name);
        Files.createDirectories(output);
        List<String> command = new ArrayList<>();
        command.addAll(Arrays.asList(properties.getFfmpeg(), "-y", "-i", source.toString(), "-map", "0:v:0"));
        if (probe.isAudio()) command.addAll(Arrays.asList("-map", "0:a:0?"));
        String filter = videoFilter(variant.height, probe);
        command.addAll(Arrays.asList("-vf", filter, "-c:v", videoEncoder(), "-b:v", variant.bitrate + "k",
                "-maxrate", Math.round(variant.bitrate * 1.15) + "k", "-bufsize", (variant.bitrate * 2) + "k"));
        if ("cpu".equalsIgnoreCase(properties.getEncoder())) command.addAll(Arrays.asList("-preset", "medium", "-pix_fmt", "yuv420p"));
        else command.addAll(Arrays.asList("-preset", "p4", "-pix_fmt", "yuv420p"));
        if (probe.isAudio()) command.addAll(Arrays.asList("-c:a", "aac", "-b:a", "128k", "-ac", "2"));
        else command.add("-an");
        int keyFrames = Math.max(1, properties.getSegmentSeconds() * (int) Math.min(properties.getMaxFps(), Math.max(1, probe.getFps())));
        command.addAll(Arrays.asList("-g", String.valueOf(keyFrames), "-keyint_min", String.valueOf(keyFrames),
                "-sc_threshold", "0", "-f", "hls", "-hls_time", String.valueOf(properties.getSegmentSeconds()),
                "-hls_playlist_type", "vod", "-hls_segment_filename", output.resolve("segment_%05d.ts").toString(),
                "-progress", "pipe:1", "-nostats", output.resolve("index.m3u8").toString()));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        int last = -1;
        StringBuilder tail = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (tail.length() > 3000) tail.delete(0, tail.length() - 2000);
                tail.append(line).append('\n');
                if (line.startsWith("out_time_ms=")) {
                    long micros = Long.parseLong(line.substring("out_time_ms=".length()));
                    int local = (int) Math.min(100, micros / 1_000_000d / probe.getDuration() * 100);
                    int overall = (variantIndex * 100 + local) / totalVariants;
                    if (overall >= last + 2) {
                        updateProgress(task.getId(), overall);
                        last = overall;
                    }
                }
            }
        }
        if (!process.waitFor(12, TimeUnit.HOURS) || process.exitValue() != 0) {
            process.destroyForcibly();
            throw new IOException("FFmpeg转码失败: " + abbreviate(tail.toString()));
        }
    }

    private String videoFilter(int height, MediaProbeService.ProbeResult probe) {
        List<String> filters = new ArrayList<>();
        if (probe.isHdr()) {
            filters.add("zscale=t=linear:npl=100");
            filters.add("format=gbrpf32le");
            filters.add("zscale=p=bt709");
            filters.add("tonemap=hable:desat=0");
            filters.add("zscale=t=bt709:m=bt709:r=tv");
        }
        filters.add("scale=-2:" + height);
        if (probe.getFps() > properties.getMaxFps()) filters.add("fps=" + properties.getMaxFps());
        return String.join(",", filters);
    }

    private String videoEncoder() {
        return "nvidia".equalsIgnoreCase(properties.getEncoder()) ? "h264_nvenc" : "libx264";
    }

    private List<String> extractCandidates(Path source, Path work, double duration) throws IOException, InterruptedException {
        List<String> paths = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            int percent = new int[]{10, 50, 90}[i];
            Path target = work.resolve("cover-" + percent + ".jpg");
            Process process = new ProcessBuilder(properties.getFfmpeg(), "-y", "-ss",
                    String.valueOf(duration * percent / 100d), "-i", source.toString(), "-frames:v", "1",
                    "-vf", "scale=640:-2", target.toString()).redirectErrorStream(true).start();
            drain(process);
            if (!process.waitFor(2, TimeUnit.MINUTES) || process.exitValue() != 0) {
                process.destroyForcibly();
                throw new IOException("生成候选封面失败");
            }
            paths.add(target.getFileName().toString());
        }
        return paths;
    }

    private void writeMaster(Path work, List<Variant> variants, boolean audio) throws IOException {
        StringBuilder master = new StringBuilder("#EXTM3U\n#EXT-X-VERSION:3\n");
        for (Variant variant : variants) {
            long bandwidth = (variant.bitrate + (audio ? 128 : 0)) * 1000L;
            master.append("#EXT-X-STREAM-INF:BANDWIDTH=").append(bandwidth)
                    .append('\n')
                    .append(variant.name).append("/index.m3u8\n");
        }
        Files.writeString(work.resolve("master.m3u8"), master.toString(), StandardCharsets.UTF_8);
    }

    private void updateAssetMetadata(MediaAsset asset, MediaProbeService.ProbeResult probe) {
        asset.setWidth(probe.getWidth()); asset.setHeight(probe.getHeight()); asset.setDuration(probe.getDuration());
        asset.setFps(Math.min(properties.getMaxFps(), probe.getFps())); asset.setHasAudio(probe.isAudio() ? 1 : 0);
        asset.setHdr(probe.isHdr() ? 1 : 0); asset.setUpdatedAt(new Date());
    }

    private void publishForReview(MediaAsset asset, Date now) {
        QueryWrapper<Video> query = new QueryWrapper<>();
        query.eq("asset_id", asset.getId()).eq("status", 4);
        List<Video> videos = videoMapper.selectList(query);
        for (Video video : videos) {
            video.setStatus(0);
            video.setVideoUrl("/api/media/videos/" + video.getVid() + "/master.m3u8");
            videoMapper.update(null, new UpdateWrapper<Video>().eq("vid", video.getVid()).eq("status", 4)
                    .set("status", 0).set("video_url", video.getVideoUrl()).set("duration", asset.getDuration()));
            redisUtil.addMember("video_status:0", video.getVid());
            redisUtil.delValue("video:" + video.getVid());
            esUtil.addVideo(video);
            notifyUser(video.getUid(), "转码完成", "《" + video.getTitle() + "》已进入审核队列", video.getVid(), 100);
        }
        sessionMapper.update(null, new UpdateWrapper<com.cixingji.backend.pojo.entity.VideoUploadSession>()
                .eq("asset_id", asset.getId()).set("status", "COMPLETED").set("updated_at", now));
    }

    private void fail(VideoTranscodeTask task, String message) {
        int attempts = task.getAttempts() + 1;
        Date now = new Date();
        boolean terminal = attempts >= properties.getMaxAttempts();
        UpdateWrapper<VideoTranscodeTask> update = new UpdateWrapper<>();
        update.eq("id", task.getId()).set("attempts", attempts).set("progress", 0)
                .set("status", terminal ? "FAILED" : "RETRY").set("error_message", abbreviate(message))
                .set("next_attempt_at", terminal ? null : Date.from(Instant.now().plusSeconds(30L * attempts)))
                .set("updated_at", now);
        taskMapper.update(null, update);
        if (terminal) {
            assetMapper.update(null, new UpdateWrapper<MediaAsset>().eq("id", task.getAssetId())
                    .set("status", "FAILED").set("updated_at", now));
            videoMapper.update(null, new UpdateWrapper<Video>().eq("asset_id", task.getAssetId()).eq("status", 4)
                    .set("status", 5));
            sessionMapper.update(null, new UpdateWrapper<com.cixingji.backend.pojo.entity.VideoUploadSession>()
                    .eq("asset_id", task.getAssetId()).set("status", "FAILED")
                    .set("error_message", abbreviate(message)).set("updated_at", now));
            List<Video> videos = videoMapper.selectList(new QueryWrapper<Video>().eq("asset_id", task.getAssetId()));
            for (Video video : videos) notifyUser(video.getUid(), "转码失败", "《" + video.getTitle() + "》可手动重试", video.getVid(), 0);
        }
    }

    private void updateProgress(Long taskId, int progress) {
        taskMapper.update(null, new UpdateWrapper<VideoTranscodeTask>().eq("id", taskId)
                .set("progress", Math.max(0, Math.min(99, progress))).set("updated_at", new Date()));
        VideoTranscodeTask task = taskMapper.selectById(taskId);
        if (task == null) return;
        List<Video> videos = videoMapper.selectList(new QueryWrapper<Video>().eq("asset_id", task.getAssetId()));
        for (Video video : videos) notifyUser(video.getUid(), "转码进度", video.getTitle(), video.getVid(), progress);
    }

    private void notifyUser(Integer uid, String event, String message, Integer videoId, int progress) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("type", "接收"); payload.put("event", event); payload.put("message", message);
        payload.put("videoId", videoId); payload.put("progress", progress);
        IMServer.broadcast(uid, "system", payload);
    }

    private void drain(Process process) throws IOException {
        try (java.io.InputStream input = process.getInputStream()) {
            byte[] buffer = new byte[8192];
            while (input.read(buffer) >= 0) { }
        }
    }

    private String abbreviate(String value) {
        if (value == null) return "未知错误";
        return value.length() <= 1800 ? value : value.substring(value.length() - 1800);
    }

    private static class Variant {
        private final String name; private final int height; private final int bitrate;
        private Variant(String name, int height, int bitrate) { this.name = name; this.height = height; this.bitrate = bitrate; }
    }
}
