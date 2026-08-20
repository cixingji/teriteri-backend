package com.cixingji.backend.controller;

import com.cixingji.backend.pojo.entity.CustomResponse;
import com.cixingji.backend.service.traffic.TrafficEventService;
import com.cixingji.backend.service.traffic.TrafficIdentity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.UUID;

@RestController
public class VideoStatsController {
    @Autowired
    private TrafficEventService trafficEventService;

    /**
     * 游客观看视频时提交播放事件，稳定游客身份在同一去重窗口内只计数一次。
     * @param vid 视频ID
     * @return
     */
    @PostMapping("/video/play/visitor")
    public CustomResponse newPlayWithVisitor(@RequestParam("vid") Integer vid,
                                             HttpServletRequest request,
                                             HttpServletResponse response) {
        String visitorId = request.getHeader("X-Visitor-ID");
        if (visitorId == null || !visitorId.matches("[A-Za-z0-9_-]{8,100}")) {
            visitorId = TrafficIdentity.visitorId(request);
        }
        if (visitorId == null) {
            visitorId = UUID.randomUUID().toString().replace("-", "");
            response.addHeader("Set-Cookie", "teri_visitor_id=" + visitorId
                    + "; Max-Age=31536000; Path=/; HttpOnly; SameSite=Lax");
        }
        boolean counted = trafficEventService.publishPlay(vid, "VISITOR", visitorId);
        return new CustomResponse(200, counted ? "OK" : "同一播放周期内已计数", null);
    }
}
