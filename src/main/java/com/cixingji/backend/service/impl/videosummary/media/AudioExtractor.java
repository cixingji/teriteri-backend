package com.cixingji.backend.service.impl.videosummary.media;

import java.nio.file.Path;

public interface AudioExtractor {
    AudioArtifact extract(String videoSource, Path outputPath);
}
