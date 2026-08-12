package com.cixingji.backend.videosummary;

import com.cixingji.backend.service.impl.videosummary.client.XfyunTranscriptParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class XfyunTranscriptParserTest {

    @Test
    void parsesWordsAndKeepsTimestamps() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        String best = "{\"st\":{\"bg\":\"1000\",\"ed\":\"3500\",\"rt\":[{\"ws\":["
                + "{\"cw\":[{\"w\":\"你好\"}]},{\"cw\":[{\"w\":\"，\"}]},"
                + "{\"cw\":[{\"w\":\"视频\"}]}]}]}}";
        String orderResult = objectMapper.writeValueAsString(objectMapper.createObjectNode()
                .set("lattice", objectMapper.createArrayNode().add(
                        objectMapper.createObjectNode().put("json_1best", best))));

        String transcript = new XfyunTranscriptParser(objectMapper).parse(orderResult);

        assertEquals("[00:00:01 - 00:00:03] 你好，视频", transcript);
    }
}
