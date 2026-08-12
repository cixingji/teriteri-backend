package com.cixingji.backend.videosummary;

import com.cixingji.backend.service.impl.videosummary.TranscriptChunker;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TranscriptChunkerTest {

    @Test
    void keepsEveryCharacterWithinTheChunkLimit() {
        String transcript = repeat("[00:00:01] 第一段内容\n", 12);
        TranscriptChunker chunker = new TranscriptChunker();

        List<String> chunks = chunker.split(transcript, 100);

        assertTrue(chunks.size() > 1);
        assertTrue(chunks.stream().allMatch(chunk -> chunk.length() <= 100));
        assertEquals(transcript.replace("\n", ""), String.join("", chunks).replace("\n", ""));
    }

    private String repeat(String value, int count) {
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < count; index++) {
            result.append(value);
        }
        return result.toString();
    }
}
