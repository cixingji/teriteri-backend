package com.cixingji.backend.service.impl.videosummary.media;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.nio.file.Path;

@Data
@AllArgsConstructor
public class AudioArtifact {
    private Path path;
    private long durationMillis;
}
