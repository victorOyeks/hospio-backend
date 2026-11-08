package com.hospio.common.integrations;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Development default: files under {@code hospio.storage.local-dir}. An S3 adapter replaces it in production. */
@Component
@ConditionalOnProperty(prefix = "hospio.storage", name = "type", havingValue = "local", matchIfMissing = true)
public class LocalObjectStorage implements ObjectStorage {

    private final Path root;

    public LocalObjectStorage(@Value("${hospio.storage.local-dir:./storage}") String dir) {
        this.root = Path.of(dir).toAbsolutePath();
    }

    @Override
    public String put(String key, byte[] content, String contentType) {
        try {
            Path target = root.resolve(key).normalize();
            Files.createDirectories(target.getParent());
            Files.write(target, content);
            return key;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public Optional<byte[]> get(String key) {
        try {
            Path target = root.resolve(key).normalize();
            return Files.exists(target) ? Optional.of(Files.readAllBytes(target)) : Optional.empty();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public String url(String key) {
        return null;
    }
}
