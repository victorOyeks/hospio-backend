package com.hospio.platform.system;

import com.hospio.platform.system.SystemHealthDtos.DirSize;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * The size of a few folders that grow (logs, the system journal, uploaded files, the web apps), like {@code du -s}, measured in the
 * background at most every 15 minutes so the page never waits for it. Only sizes are read; folders this service may not list are
 * left out of the total and say so. A walk stops after 400,000 entries or 20 seconds.
 */
@Slf4j
@Component
public class DirSizes {

    static final Duration EVERY = Duration.ofMinutes(15);
    private static final int MAX_ENTRIES = 400_000;
    private static final long MAX_MS = 20_000;
    private static final ExecutorService WALKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "system-health-du");
        t.setDaemon(true);
        return t;
    });

    private final Map<String, DirSize> sizes = new ConcurrentHashMap<>();
    private volatile Instant measuredAt;
    private final AtomicBoolean running = new AtomicBoolean();

    /** When the sizes shown were measured (null until the first measurement ends). */
    public Instant measuredAt() {
        return measuredAt;
    }

    /** The last sizes (label → path); starts a new measurement in the background when they are older than 15 minutes. */
    public List<DirSize> sizes(Map<String, String> dirs) {
        Instant at = measuredAt;
        if ((at == null || at.plus(EVERY).isBefore(Instant.now())) && running.compareAndSet(false, true)) {
            Map<String, String> copy = new LinkedHashMap<>(dirs);
            WALKER.execute(() -> {
                try {
                    copy.forEach((label, path) -> sizes.put(label, measure(label, path)));
                    measuredAt = Instant.now();
                } finally {
                    running.set(false);
                }
            });
        }
        List<DirSize> out = new ArrayList<>();
        dirs.forEach((label, path) -> out.add(sizes.getOrDefault(label, new DirSize(label, path, null, "Being measured"))));
        out.sort((a, b) -> Long.compare(b.bytes() == null ? -1 : b.bytes(), a.bytes() == null ? -1 : a.bytes()));
        return out;
    }

    static DirSize measure(String label, String path) {
        Path root = Path.of(path);
        if (!Files.isDirectory(root)) {
            return new DirSize(label, path, null, "Not on this server");
        }
        long[] total = {0};
        int[] entries = {0};
        int[] unreadable = {0};
        long deadline = System.currentTimeMillis() + MAX_MS;
        boolean[] cut = {false};
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    total[0] += attrs.isRegularFile() ? attrs.size() : 0;
                    if (++entries[0] > MAX_ENTRIES || System.currentTimeMillis() > deadline) {
                        cut[0] = true;
                        return FileVisitResult.TERMINATE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    unreadable[0]++;
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException | RuntimeException e) {
            log.debug("System health: {} not measured: {}", path, e.toString());
            return new DirSize(label, path, null, "Couldn't be read");
        }
        String note = cut[0] ? "At least this much (too many files to count)"
                : unreadable[0] > 0 ? "Some folders can't be read by the service, so the real size is larger" : null;
        return new DirSize(label, path, total[0], note);
    }
}
