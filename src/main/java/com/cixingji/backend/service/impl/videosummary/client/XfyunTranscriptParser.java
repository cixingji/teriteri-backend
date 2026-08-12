package com.cixingji.backend.service.impl.videosummary.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Locale;

@Component
@RequiredArgsConstructor
public class XfyunTranscriptParser {

    private final ObjectMapper objectMapper;

    public String parse(String orderResult) {
        try {
            JsonNode root = objectMapper.readTree(orderResult);
            JsonNode lattice = root.path("lattice");
            if (!lattice.isArray() || lattice.size() == 0) {
                lattice = root.path("lattice2");
            }
            if (!lattice.isArray() || lattice.size() == 0) {
                throw new IllegalArgumentException("XFYun result contains no transcript lattice");
            }

            StringBuilder transcript = new StringBuilder();
            for (JsonNode item : lattice) {
                JsonNode best = item.path("json_1best");
                if (best.isTextual()) {
                    best = objectMapper.readTree(best.asText());
                }
                JsonNode sentence = best.path("st");
                long begin = parseLong(sentence.path("bg"), parseLong(item.path("begin"), 0L));
                long end = parseLong(sentence.path("ed"), parseLong(item.path("end"), begin));
                String text = readWords(sentence);
                if (!text.trim().isEmpty()) {
                    transcript.append('[')
                            .append(formatTimestamp(begin))
                            .append(" - ")
                            .append(formatTimestamp(end))
                            .append("] ")
                            .append(text.trim())
                            .append('\n');
                }
            }

            String value = transcript.toString().trim();
            if (value.isEmpty()) {
                throw new IllegalArgumentException("XFYun result contains an empty transcript");
            }
            return value;
        } catch (IOException e) {
            throw new IllegalArgumentException("Could not parse XFYun transcript", e);
        }
    }

    private String readWords(JsonNode sentence) {
        StringBuilder text = new StringBuilder();
        for (JsonNode rt : sentence.path("rt")) {
            for (JsonNode wordSegment : rt.path("ws")) {
                JsonNode candidates = wordSegment.path("cw");
                if (candidates.isArray() && candidates.size() > 0) {
                    text.append(candidates.get(0).path("w").asText(""));
                }
            }
        }
        return text.toString();
    }

    private long parseLong(JsonNode node, long fallback) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return fallback;
        }
        try {
            return Long.parseLong(node.asText());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private String formatTimestamp(long milliseconds) {
        long totalSeconds = Math.max(milliseconds, 0L) / 1000L;
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;
        return String.format(Locale.ROOT, "%02d:%02d:%02d", hours, minutes, seconds);
    }
}
