package com.cixingji.backend.traffic;

import com.cixingji.backend.config.TrafficProperties;
import com.cixingji.backend.mapper.TrafficPlayEventMapper;
import com.cixingji.backend.service.traffic.TrafficEventService;
import com.cixingji.backend.service.traffic.TrafficMetrics;
import com.cixingji.backend.service.video.VideoStatsService;
import com.cixingji.backend.utils.RedisUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TrafficEventServiceTest {
    @Test
    void synchronousFallbackStillDeduplicatesPlayWindow() {
        TrafficProperties properties = new TrafficProperties();
        properties.getKafka().setEnabled(false);
        RedisUtil redis = mock(RedisUtil.class);
        when(redis.setIfAbsent(anyString(), eq("1"), anyLong(), eq(TimeUnit.SECONDS))).thenReturn(true, false);
        VideoStatsService videoStats = mock(VideoStatsService.class);
        TrafficEventService service = new TrafficEventService(properties, redis, videoStats,
                mock(TrafficPlayEventMapper.class), mock(KafkaTemplate.class), new ObjectMapper(), new TrafficMetrics());

        assertTrue(service.publishPlay(7, "USER", "42"));
        assertFalse(service.publishPlay(7, "USER", "42"));
        verify(videoStats, times(1)).updateStats(7, "play", true, 1);
    }
}
