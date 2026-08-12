package com.cixingji.backend.controller.videosummary;

import com.cixingji.backend.config.videosummary.VideoSummaryProperties;
import com.cixingji.backend.pojo.entity.CustomResponse;
import com.cixingji.backend.service.videosummary.VideoSummaryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class XfyunAsrCallbackController {

    private final VideoSummaryService videoSummaryService;
    private final VideoSummaryProperties properties;

    @GetMapping("/video/summary/callback/xfyun")
    public ResponseEntity<CustomResponse> callback(@RequestParam Map<String, String> parameters) {
        String providedToken = parameters.get("token");
        if (!isValidToken(providedToken)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(new CustomResponse(HttpStatus.FORBIDDEN.value(), "Invalid callback token", null));
        }

        String orderId = firstNonBlank(parameters.get("orderId"), parameters.get("OrderId"));
        String statusValue = parameters.get("status");
        if (!StringUtils.hasText(orderId) || !StringUtils.hasText(statusValue)) {
            return ResponseEntity.badRequest()
                    .body(new CustomResponse(HttpStatus.BAD_REQUEST.value(), "Missing orderId or status", null));
        }
        try {
            videoSummaryService.handleXfyunCallback(orderId, Integer.parseInt(statusValue));
            return ResponseEntity.ok(new CustomResponse());
        } catch (NumberFormatException e) {
            return ResponseEntity.badRequest()
                    .body(new CustomResponse(HttpStatus.BAD_REQUEST.value(), "Invalid callback status", null));
        }
    }

    private boolean isValidToken(String providedToken) {
        String configuredToken = properties.getCallbackToken();
        if (!StringUtils.hasText(configuredToken) || !StringUtils.hasText(providedToken)) {
            return false;
        }
        return MessageDigest.isEqual(
                configuredToken.getBytes(StandardCharsets.UTF_8),
                providedToken.getBytes(StandardCharsets.UTF_8)
        );
    }

    private String firstNonBlank(String first, String second) {
        return StringUtils.hasText(first) ? first : second;
    }
}
