package com.cixingji.backend.service.impl.videosummary.client;

import com.cixingji.backend.service.impl.videosummary.media.AudioArtifact;

public interface AsrClient {
    String submit(AudioArtifact audio, String callbackUrl);

    AsrQueryResult query(String orderId);
}
