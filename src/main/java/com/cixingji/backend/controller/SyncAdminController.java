package com.cixingji.backend.controller;

import com.cixingji.backend.pojo.entity.CustomResponse;
import com.cixingji.backend.service.sync.SearchIndexMaintenanceService;
import com.cixingji.backend.service.sync.SearchSyncEventService;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/admin/sync")
public class SyncAdminController {
    private final SearchSyncEventService eventService;
    private final SearchIndexMaintenanceService maintenanceService;

    public SyncAdminController(SearchSyncEventService eventService, SearchIndexMaintenanceService maintenanceService) {
        this.eventService = eventService; this.maintenanceService = maintenanceService;
    }

    @GetMapping("/overview")
    public CustomResponse overview() { return new CustomResponse(200, "OK", eventService.overview()); }

    @GetMapping("/events")
    public CustomResponse events(@RequestParam(required = false) String status,
                                 @RequestParam(defaultValue = "1") int page,
                                 @RequestParam(defaultValue = "20") int size) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("list", eventService.list(status, page, size)); data.put("total", eventService.count(status));
        return new CustomResponse(200, "OK", data);
    }

    @PostMapping("/events/{id}/replay")
    public CustomResponse replay(@PathVariable Long id) { return new CustomResponse(200, "已重新入队", eventService.replay(id)); }

    @PostMapping("/events/replay-dead")
    public CustomResponse replayDead() { return new CustomResponse(200, "死信已批量重新入队", eventService.replay(null)); }

    @PostMapping("/index/rebuild")
    public CustomResponse rebuild() {
        try { return new CustomResponse(202, "全量重建已启动", maintenanceService.startRebuild()); }
        catch (IllegalStateException e) { return new CustomResponse(409, e.getMessage(), null); }
    }

    @PostMapping("/index/consistency")
    public CustomResponse consistency(@RequestParam(defaultValue = "true") boolean repair) {
        try { return new CustomResponse(202, repair ? "一致性检查与修复已启动" : "一致性检查已启动", maintenanceService.startConsistencyCheck(repair)); }
        catch (IllegalStateException e) { return new CustomResponse(409, e.getMessage(), null); }
    }

    @GetMapping("/jobs")
    public CustomResponse jobs(@RequestParam(defaultValue = "20") int limit) {
        return new CustomResponse(200, "OK", maintenanceService.jobs(limit));
    }
}
