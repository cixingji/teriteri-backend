package com.cixingji.backend.service.media;

import com.cixingji.backend.config.MediaProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class MediaStorageServiceTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void createsHashPartitionedPathsInsideConfiguredRoot() throws Exception {
        MediaProperties properties = new MediaProperties();
        properties.setRoot(temporaryDirectory.resolve("media").toString());
        MediaStorageService storage = new MediaStorageService(properties);
        storage.initialize();

        Path original = storage.originalPath("0123456789abcdef0123456789abcdef", "mp4");
        assertTrue(original.startsWith(storage.root()));
        assertTrue(original.toString().contains("originals"));
        assertTrue(original.toString().contains("01"));
    }

    @Test
    void savesAtomicallyAndCalculatesContentMd5() throws Exception {
        MediaProperties properties = new MediaProperties();
        properties.setRoot(temporaryDirectory.resolve("media").toString());
        MediaStorageService storage = new MediaStorageService(properties);
        storage.initialize();
        Path target = storage.chunkPath("session-one", 0);

        storage.saveAtomically(new ByteArrayInputStream("teriteri".getBytes(StandardCharsets.UTF_8)), target);

        assertEquals("dd1901792963ab54bd89a32f389781b6", storage.md5(target));
    }

    @Test
    void rejectsCoverPathTraversal() throws Exception {
        MediaProperties properties = new MediaProperties();
        properties.setRoot(temporaryDirectory.resolve("media").toString());
        MediaStorageService storage = new MediaStorageService(properties);
        storage.initialize();

        assertThrows(IllegalArgumentException.class, () -> storage.coverPath("../../outside.jpg"));
    }
}
