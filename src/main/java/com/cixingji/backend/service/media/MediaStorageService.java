package com.cixingji.backend.service.media;

import com.cixingji.backend.config.MediaProperties;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Comparator;

@Service
public class MediaStorageService {
    private final MediaProperties properties;

    public MediaStorageService(MediaProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    public void initialize() throws IOException {
        Files.createDirectories(root());
        Files.createDirectories(root().resolve("chunks"));
        Files.createDirectories(root().resolve("originals"));
        Files.createDirectories(root().resolve("hls"));
        Files.createDirectories(root().resolve("covers"));
        Files.createDirectories(root().resolve("work"));
    }

    public Path root() {
        return properties.rootPath();
    }

    public Path sessionDirectory(String sessionId) {
        return safe(root().resolve("chunks").resolve(sessionId));
    }

    public Path chunkPath(String sessionId, int index) {
        return safe(sessionDirectory(sessionId).resolve(index + ".part"));
    }

    public Path originalPath(String hash, String extension) {
        return safe(root().resolve("originals").resolve(hash.substring(0, 2)).resolve(hash + "." + extension));
    }

    public Path hlsDirectory(String hash) {
        return safe(root().resolve("hls").resolve(hash.substring(0, 2)).resolve(hash));
    }

    public Path workDirectory(String hash) {
        return safe(root().resolve("work").resolve(hash));
    }

    public Path coverPath(String fileName) {
        return safe(root().resolve("covers").resolve(fileName));
    }

    public Path saveAtomically(InputStream input, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        Path temporary = target.resolveSibling(target.getFileName() + ".uploading");
        Files.copy(input, temporary, StandardCopyOption.REPLACE_EXISTING);
        try {
            return Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
            return Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public String md5(Path path) throws IOException {
        return digest(path, "MD5");
    }

    public String md5(InputStream input) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) digest.update(buffer, 0, read);
            }
            return hex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private String digest(Path path, String algorithm) throws IOException {
        try (InputStream input = Files.newInputStream(path)) {
            try {
                MessageDigest digest = MessageDigest.getInstance(algorithm);
                byte[] buffer = new byte[1024 * 1024];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read > 0) digest.update(buffer, 0, read);
                }
                return hex(digest.digest());
            } catch (java.security.NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    public void deleteTree(Path directory) throws IOException {
        if (!Files.exists(directory)) return;
        try (java.util.stream.Stream<Path> paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            });
        } catch (java.io.UncheckedIOException e) {
            throw e.getCause();
        }
    }

    private Path safe(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(root())) {
            throw new IllegalArgumentException("媒体路径越界");
        }
        return normalized;
    }

    private String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format("%02x", value));
        return result.toString();
    }
}
