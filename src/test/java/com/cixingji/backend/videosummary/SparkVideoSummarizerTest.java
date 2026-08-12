package com.cixingji.backend.videosummary;

import com.cixingji.backend.config.videosummary.VideoSummaryProperties;
import com.cixingji.backend.service.impl.videosummary.SparkVideoSummarizer;
import com.cixingji.backend.service.impl.videosummary.TranscriptChunker;
import com.cixingji.backend.service.impl.videosummary.client.TextGenerationClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SparkVideoSummarizerTest {

    @Test
    void performsMapReduceAndReturnsValidFinalJson() throws Exception {
        VideoSummaryProperties properties = new VideoSummaryProperties();
        properties.setMaxChunkChars(300);
        AtomicInteger calls = new AtomicInteger();
        TextGenerationClient client = (system, user) -> {
            calls.incrementAndGet();
            if (user.contains("最终视频总结")) {
                return "```json\n{\"title\":\"测试视频\",\"abstract\":\"摘要\","
                        + "\"keyPoints\":[\"观点\"],\"timeline\":[],\"uncertainItems\":[]}\n```";
            }
            return "{\"segmentSummary\":\"分段摘要\",\"keyPoints\":[\"观点\"],"
                    + "\"timeline\":[],\"uncertainItems\":[]}";
        };
        ObjectMapper objectMapper = new ObjectMapper();
        SparkVideoSummarizer summarizer = new SparkVideoSummarizer(
                client, new TranscriptChunker(), properties, objectMapper);

        String transcript = repeat("[00:00:01] 这是一段需要总结的视频内容。\n", 20);
        String result = summarizer.summarize(transcript);
        JsonNode json = objectMapper.readTree(result);

        assertEquals("测试视频", json.path("title").asText());
        assertTrue(calls.get() > 2, "Long transcripts should be summarized in multiple calls");
    }

    private String repeat(String value, int count) {
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < count; index++) {
            result.append(value);
        }
        return result.toString();
    }
}
