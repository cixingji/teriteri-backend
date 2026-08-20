package com.cixingji.backend.controller;

import com.cixingji.backend.pojo.entity.CustomResponse;
import com.cixingji.backend.service.traffic.TrafficAdminService;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/admin/traffic")
public class TrafficAdminController {
    private final TrafficAdminService service;

    public TrafficAdminController(TrafficAdminService service) { this.service = service; }

    @GetMapping("/overview")
    public CustomResponse overview() { return new CustomResponse(200, "OK", service.overview()); }

    @GetMapping("/logs")
    public CustomResponse logs(@RequestParam(required = false) String action,
                               @RequestParam(defaultValue = "1") int page,
                               @RequestParam(defaultValue = "20") int size) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("list", service.logs(action, page, size));
        data.put("total", service.logCount(action));
        return new CustomResponse(200, "OK", data);
    }

    @GetMapping("/play-events")
    public CustomResponse playEvents(@RequestParam(required = false) String status,
                                     @RequestParam(defaultValue = "1") int page,
                                     @RequestParam(defaultValue = "20") int size) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("list", service.playEvents(status, page, size));
        data.put("total", service.playCount(status));
        return new CustomResponse(200, "OK", data);
    }

    @PostMapping("/play-events/replay-dead")
    public CustomResponse replayDeadPlayEvents(@RequestParam(required = false) Long id) {
        return new CustomResponse(200, "播放事件已重新入队", service.replayDeadPlayEvents(id));
    }

    @GetMapping("/dead-letters")
    public CustomResponse deadLetters(@RequestParam(defaultValue = "1") int page,
                                      @RequestParam(defaultValue = "20") int size) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("list", service.deadLetters(page, size));
        data.put("total", service.deadLetterCount());
        return new CustomResponse(200, "OK", data);
    }

    @PostMapping("/dead-letters/{id}/replay")
    public CustomResponse replayDeadLetter(@PathVariable Long id) {
        boolean replayed = service.replayDeadLetter(id);
        return new CustomResponse(replayed ? 200 : 409, replayed ? "死信已重放" : "死信不存在或已处理", replayed);
    }
}
