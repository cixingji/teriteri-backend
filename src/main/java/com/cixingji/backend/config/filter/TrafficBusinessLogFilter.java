package com.cixingji.backend.config.filter;

import com.cixingji.backend.pojo.dto.traffic.BusinessLogEvent;
import com.cixingji.backend.service.traffic.BusinessLogPublisher;
import com.cixingji.backend.service.traffic.TrafficIdentity;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
public class TrafficBusinessLogFilter extends OncePerRequestFilter {
    private final BusinessLogPublisher publisher;

    public TrafficBusinessLogFilter(BusinessLogPublisher publisher) { this.publisher = publisher; }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return action(request.getRequestURI()) == null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long started = System.nanoTime();
        String traceId = request.getHeader("X-Request-ID");
        if (traceId == null || traceId.trim().isEmpty()) traceId = UUID.randomUUID().toString();
        response.setHeader("X-Request-ID", traceId);
        try {
            chain.doFilter(request, response);
        } finally {
            BusinessLogEvent event = new BusinessLogEvent();
            event.setEventId(UUID.randomUUID().toString().replace("-", ""));
            event.setTraceId(traceId);
            event.setAction(action(request.getRequestURI()));
            event.setMethod(request.getMethod());
            event.setPath(request.getRequestURI());
            event.setResponseStatus(response.getStatus());
            event.setLatencyMs((System.nanoTime() - started) / 1_000_000L);
            event.setActorKey(TrafficIdentity.requestActor(request));
            event.setOccurredAt(System.currentTimeMillis());
            publisher.publish(event);
        }
    }

    private String action(String uri) {
        if (uri.startsWith("/video/play/")) return "VIDEO_PLAY";
        if ("/video/love-or-not".equals(uri)) return "VIDEO_ATTITUDE";
        if ("/comment/add".equals(uri)) return "COMMENT_ADD";
        return null;
    }
}
