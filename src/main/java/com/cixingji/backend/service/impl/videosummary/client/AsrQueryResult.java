package com.cixingji.backend.service.impl.videosummary.client;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class AsrQueryResult {

    public enum State {
        PROCESSING,
        COMPLETED,
        FAILED
    }

    private State state;
    private String transcript;
    private String errorMessage;

    public static AsrQueryResult processing() {
        return new AsrQueryResult(State.PROCESSING, null, null);
    }

    public static AsrQueryResult completed(String transcript) {
        return new AsrQueryResult(State.COMPLETED, transcript, null);
    }

    public static AsrQueryResult failed(String errorMessage) {
        return new AsrQueryResult(State.FAILED, null, errorMessage);
    }
}
