package com.cixingji.backend.service.impl.videosummary;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Component
public class TranscriptChunker {

    public List<String> split(String transcript, int maxChars) {
        if (transcript == null || transcript.trim().isEmpty()) {
            return Collections.emptyList();
        }
        if (maxChars < 100) {
            throw new IllegalArgumentException("maxChars must be at least 100");
        }

        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        String[] lines = transcript.split("\\r?\\n");
        for (String line : lines) {
            if (line.length() > maxChars) {
                flush(current, chunks);
                splitLongLine(line, maxChars, chunks);
                continue;
            }
            int required = current.length() == 0 ? line.length() : line.length() + 1;
            if (current.length() + required > maxChars) {
                flush(current, chunks);
            }
            if (current.length() > 0) {
                current.append('\n');
            }
            current.append(line);
        }
        flush(current, chunks);
        return chunks;
    }

    private void splitLongLine(String line, int maxChars, List<String> chunks) {
        int offset = 0;
        while (offset < line.length()) {
            int end = Math.min(line.length(), offset + maxChars);
            chunks.add(line.substring(offset, end));
            offset = end;
        }
    }

    private void flush(StringBuilder current, List<String> chunks) {
        if (current.length() > 0) {
            chunks.add(current.toString());
            current.setLength(0);
        }
    }
}
