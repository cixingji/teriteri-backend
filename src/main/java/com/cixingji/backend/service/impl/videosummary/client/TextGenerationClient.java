package com.cixingji.backend.service.impl.videosummary.client;

public interface TextGenerationClient {
    String complete(String systemPrompt, String userPrompt);
}
