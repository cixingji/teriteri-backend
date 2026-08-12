package com.cixingji.backend.controller.videosummary;

import com.cixingji.backend.pojo.dto.videosummary.VideoSummaryResponse;
import com.cixingji.backend.pojo.entity.CustomResponse;
import com.cixingji.backend.service.videosummary.VideoSummaryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class VideoSummaryController {

    private final VideoSummaryService videoSummaryService;

    @PostMapping("/video/summary")
    public ResponseEntity<CustomResponse> create(@RequestParam("vid") Integer vid) {
        try {
            VideoSummaryResponse task = videoSummaryService.createOrGet(vid);
            return ResponseEntity.status(HttpStatus.ACCEPTED)
                    .body(new CustomResponse(HttpStatus.OK.value(), "Video summary task accepted", task));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(new CustomResponse(HttpStatus.BAD_REQUEST.value(), e.getMessage(), null));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(new CustomResponse(HttpStatus.SERVICE_UNAVAILABLE.value(), e.getMessage(), null));
        }
    }

    @GetMapping("/video/summary/task")
    public ResponseEntity<CustomResponse> task(@RequestParam("taskId") String taskId) {
        VideoSummaryResponse task = videoSummaryService.getTask(taskId);
        if (task == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new CustomResponse(HttpStatus.NOT_FOUND.value(), "Video summary task not found", null));
        }
        return ResponseEntity.ok(new CustomResponse(200, "OK", task));
    }

    @GetMapping("/video/summary/latest")
    public ResponseEntity<CustomResponse> latest(@RequestParam("vid") Integer vid) {
        VideoSummaryResponse task = videoSummaryService.getLatest(vid);
        if (task == null) {
            return ResponseEntity.ok(new CustomResponse(200, "Video summary not found", null));
        }
        return ResponseEntity.ok(new CustomResponse(200, "OK", task));
    }
}
