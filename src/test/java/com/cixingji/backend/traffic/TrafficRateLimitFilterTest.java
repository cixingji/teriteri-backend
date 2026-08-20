package com.cixingji.backend.traffic;

import com.cixingji.backend.config.TrafficProperties;
import com.cixingji.backend.config.filter.TrafficRateLimitFilter;
import com.cixingji.backend.service.traffic.TrafficMetrics;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TrafficRateLimitFilterTest {
    @Test
    void returnsRealHttp429WhenActorBucketIsExhausted() throws Exception {
        TrafficProperties properties = new TrafficProperties();
        properties.getRateLimit().setPlayPermitsPerSecond(1000);
        properties.getRateLimit().setActorPlayPermitsPerSecond(1);
        TrafficMetrics metrics = new TrafficMetrics();
        TrafficRateLimitFilter filter = new TrafficRateLimitFilter(properties, new ObjectMapper(), metrics);

        MockHttpServletRequest first = new MockHttpServletRequest("POST", "/video/play/visitor");
        first.setRemoteAddr("127.0.0.1");
        MockHttpServletResponse firstResponse = new MockHttpServletResponse();
        filter.doFilter(first, firstResponse, new MockFilterChain());

        MockHttpServletRequest second = new MockHttpServletRequest("POST", "/video/play/visitor");
        second.setRemoteAddr("127.0.0.1");
        MockHttpServletResponse secondResponse = new MockHttpServletResponse();
        filter.doFilter(second, secondResponse, new MockFilterChain());

        assertEquals(200, firstResponse.getStatus());
        assertEquals(429, secondResponse.getStatus());
        assertEquals("1", secondResponse.getHeader("Retry-After"));
        assertEquals(1L, metrics.snapshot().get("rejected"));
    }
}
