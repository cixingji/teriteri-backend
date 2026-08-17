package com.cixingji.backend.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.cixingji.backend.mapper.MediaAssetMapper;
import com.cixingji.backend.mapper.VideoMapper;
import com.cixingji.backend.mapper.VideoTranscodeTaskMapper;
import com.cixingji.backend.pojo.entity.CustomResponse;
import com.cixingji.backend.pojo.entity.MediaAsset;
import com.cixingji.backend.pojo.entity.Video;
import com.cixingji.backend.pojo.entity.VideoTranscodeTask;
import com.cixingji.backend.service.media.VideoTranscodeService;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/admin/transcode")
public class AdminTranscodeController {
    private final VideoTranscodeTaskMapper taskMapper;
    private final MediaAssetMapper assetMapper;
    private final VideoMapper videoMapper;
    private final VideoTranscodeService transcodeService;

    public AdminTranscodeController(VideoTranscodeTaskMapper taskMapper, MediaAssetMapper assetMapper,
                                    VideoMapper videoMapper, VideoTranscodeService transcodeService) {
        this.taskMapper = taskMapper; this.assetMapper = assetMapper;
        this.videoMapper = videoMapper; this.transcodeService = transcodeService;
    }

    @GetMapping("/tasks")
    public CustomResponse tasks(@RequestParam(value = "status", required = false) String status,
                                @RequestParam(value = "page", defaultValue = "1") int page,
                                @RequestParam(value = "size", defaultValue = "20") int size) {
        int safeSize = Math.max(1, Math.min(100, size));
        QueryWrapper<VideoTranscodeTask> query = new QueryWrapper<>();
        if (status != null && !status.trim().isEmpty()) query.eq("status", status.trim().toUpperCase(Locale.ROOT));
        query.orderByDesc("id").last("LIMIT " + Math.max(0, page - 1) * safeSize + "," + safeSize);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (VideoTranscodeTask task : taskMapper.selectList(query)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("task", task);
            MediaAsset asset = assetMapper.selectById(task.getAssetId());
            row.put("asset", asset);
            row.put("videos", videoMapper.selectList(new QueryWrapper<Video>().eq("asset_id", task.getAssetId())));
            Long ahead = taskMapper.selectCount(new QueryWrapper<VideoTranscodeTask>()
                    .in("status", Arrays.asList("QUEUED", "RETRY")).lt("id", task.getId()));
            row.put("queuePosition", "RUNNING".equals(task.getStatus()) ? 0 : (ahead == null ? 1 : ahead + 1));
            rows.add(row);
        }
        Map<String, Object> result = new HashMap<>();
        result.put("list", rows);
        result.put("total", taskMapper.selectCount(status == null || status.isEmpty()
                ? new QueryWrapper<>() : new QueryWrapper<VideoTranscodeTask>().eq("status", status.toUpperCase(Locale.ROOT))));
        return new CustomResponse(200, "OK", result);
    }

    @PostMapping("/tasks/{taskId}/retry")
    public CustomResponse retry(@PathVariable Long taskId) {
        return transcodeService.retry(taskId) ? new CustomResponse() : new CustomResponse(409, "任务当前不能重试", null);
    }
}
