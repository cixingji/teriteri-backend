package com.cixingji.backend.videosummary;

import com.cixingji.backend.service.impl.videosummary.client.XfyunSignature;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class XfyunSignatureTest {

    @Test
    void matchesOfficialSignatureExample() {
        String signature = XfyunSignature.create(
                "595f23df",
                "1512041814",
                "d9f4aa7ea6d94faca62cd88a28fd5234"
        );

        assertEquals("IrrzsJeOFk1NGfJHW6SkHUoN9CU=", signature);
    }
}
