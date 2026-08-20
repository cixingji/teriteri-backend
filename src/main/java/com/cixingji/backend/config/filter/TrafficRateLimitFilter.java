package com.cixingji.backend.config.filter;

import com.cixingji.backend.config.TrafficProperties;
import com.cixingji.backend.pojo.entity.CustomResponse;
import com.cixingji.backend.service.traffic.TrafficIdentity;
import com.cixingji.backend.service.traffic.TrafficMetrics;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.util.concurrent.RateLimiter;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class TrafficRateLimitFilter extends OncePerRequestFilter {
    private final TrafficProperties properties;
    private final ObjectMapper objectMapper;
    private final TrafficMetrics metrics;
    private final RateLimiter playLimiter;
    private final RateLimiter likeLimiter;
    private final RateLimiter commentLimiter;
    private final Cache<String, RateLimiter> actorLimiters = CacheBuilder.newBuilder()
            .maximumSize(100_000).expireAfterAccess(10, TimeUnit.MINUTES).build();

    public TrafficRateLimitFilter(TrafficProperties properties, ObjectMapper objectMapper, TrafficMetrics metrics) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
        TrafficProperties.RateLimit limits = properties.getRateLimit();
        this.playLimiter = RateLimiter.create(positive(limits.getPlayPermitsPerSecond()));
        this.likeLimiter = RateLimiter.create(positive(limits.getLikePermitsPerSecond()));
        this.commentLimiter = RateLimiter.create(positive(limits.getCommentPermitsPerSecond()));
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !properties.getRateLimit().isEnabled() || category(request.getRequestURI()) == null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String category = category(request.getRequestURI());
        double actorRate = actorRate(category);
        String actorKey = category + ":" + TrafficIdentity.requestActor(request);
        RateLimiter actorLimiter = actorLimiters.asMap().computeIfAbsent(actorKey, key -> RateLimiter.create(positive(actorRate)));
        if (!globalLimiter(category).tryAcquire() || !actorLimiter.tryAcquire()) {
            metrics.rejected();
            response.setStatus(429);
            response.setHeader("Retry-After", "1");
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            objectMapper.writeValue(response.getWriter(), new CustomResponse(429, "请求过于频繁，请稍后重试", null));
            return;
        }
        metrics.accepted();
        chain.doFilter(request, response);
    }

    private String category(String uri) {
        if (uri.startsWith("/video/play/")) return "play";
        if ("/video/love-or-not".equals(uri)) return "like";
        if ("/comment/add".equals(uri)) return "comment";
        return null;
    }

    private RateLimiter globalLimiter(String category) {
        if ("play".equals(category)) return playLimiter;
        if ("like".equals(category)) return likeLimiter;
        return commentLimiter;
    }

    private double actorRate(String category) {
        TrafficProperties.RateLimit limits = properties.getRateLimit();
        if ("play".equals(category)) return limits.getActorPlayPermitsPerSecond();
        if ("like".equals(category)) return limits.getActorLikePermitsPerSecond();
        return limits.getActorCommentPermitsPerSecond();
    }

    private static double positive(double value) { return value > 0 ? value : 1.0; }
}
