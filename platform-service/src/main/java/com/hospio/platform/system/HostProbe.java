package com.hospio.platform.system;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * The server itself, read the way {@code uptime}, {@code free} and {@code df} read it: /proc/loadavg, /proc/stat (CPU busy since the
 * last look), /proc/meminfo, /proc/pressure/memory (time spent waiting for memory), /proc/uptime, /proc/mounts with each disk's
 * {@link FileStore}, /etc/os-release. Everything is readable by any user; nothing is changed. On a machine without /proc (a
 * developer's Mac) the numbers the JVM knows are used and the rest is left empty.
 */
@Component
public class HostProbe {

    /** Filesystems that are disks (not memory, snaps or containers' layers). */
    static final Set<String> DISK_TYPES = Set.of("ext2", "ext3", "ext4", "xfs", "btrfs", "zfs", "vfat", "f2fs", "jfs", "reiserfs");

    private final Path proc;
    private final Path etc;
    private long[] lastCpu;

    public HostProbe() {
        this(Path.of("/proc"), Path.of("/etc"));
    }

    HostProbe(Path proc, Path etc) {
        this.proc = proc;
        this.etc = etc;
    }

    public record Memory(long total, long available, long swapTotal, long swapFree) {
        public long used() {
            return Math.max(0, total - available);
        }

        public long swapUsed() {
            return Math.max(0, swapTotal - swapFree);
        }
    }

    public record MountPoint(String device, String path, String type) {
    }

    public record Disk(MountPoint mount, long total, long used, long free) {
        /** As df counts it: used / (used + free for ordinary programs), rounded up to one decimal. */
        public double usedPct() {
            long base = used + free;
            return base <= 0 ? 0 : Math.ceil(used * 1000.0 / base) / 10.0;
        }
    }

    public boolean hasProc() {
        return Files.isReadable(proc.resolve("meminfo"));
    }

    public int cores() {
        return Runtime.getRuntime().availableProcessors();
    }

    public Optional<double[]> load() {
        return read("loadavg").map(HostProbe::parseLoadavg).or(() -> {
            double l = ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage();
            return l < 0 ? Optional.empty() : Optional.of(new double[]{l, l, l});
        });
    }

    /** Whole-server CPU busy % since the previous call (the first call: since boot). */
    public synchronized Optional<Double> cpuPct() {
        Optional<long[]> now = read("stat").map(HostProbe::parseCpu);
        if (now.isEmpty()) {
            if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os) {
                double v = os.getCpuLoad();
                return v < 0 ? Optional.empty() : Optional.of(round1(v * 100));
            }
            return Optional.empty();
        }
        long[] cur = now.get();
        long[] prev = lastCpu;
        lastCpu = cur;
        long idle = prev == null ? cur[0] : cur[0] - prev[0];
        long total = prev == null ? cur[1] : cur[1] - prev[1];
        if (total <= 0) {
            return Optional.empty();
        }
        return Optional.of(round1(Math.max(0, Math.min(100, (total - idle) * 100.0 / total))));
    }

    public Optional<Memory> memory() {
        return read("meminfo").map(HostProbe::parseMeminfo).filter(m -> m.total() > 0).or(() -> {
            if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os) {
                return Optional.of(new Memory(os.getTotalMemorySize(), os.getFreeMemorySize(), os.getTotalSwapSpaceSize(), os.getFreeSwapSpaceSize()));
            }
            return Optional.empty();
        });
    }

    /** "some avg60" of /proc/pressure/memory: the share of the last minute some program waited for memory. */
    public Optional<Double> memoryStallPct() {
        return read("pressure/memory").flatMap(HostProbe::parsePressure);
    }

    public Optional<Long> uptimeSeconds() {
        return read("uptime").map(s -> (long) Double.parseDouble(s.trim().split("\\s+")[0])).or(() ->
                Optional.empty());
    }

    /** The boot time (epoch seconds), to turn systemd's monotonic timestamps into dates. */
    public Optional<Long> bootEpochSeconds() {
        return read("stat").flatMap(HostProbe::parseBtime);
    }

    public String os() {
        try {
            Path f = etc.resolve("os-release");
            if (Files.isReadable(f)) {
                for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                    if (line.startsWith("PRETTY_NAME=")) {
                        return line.substring("PRETTY_NAME=".length()).replace("\"", "").trim();
                    }
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // fall through to the JVM's name
        }
        return System.getProperty("os.name");
    }

    public String kernel() {
        return System.getProperty("os.version");
    }

    public String hostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (IOException e) {
            return null;
        }
    }

    /** Every disk mounted on the server (one row per device), largest first. */
    public List<Disk> disks() {
        List<MountPoint> mounts = read("mounts").map(HostProbe::parseMounts).orElseGet(() -> List.of(new MountPoint("", "/", "")));
        List<Disk> out = new ArrayList<>();
        for (MountPoint m : mounts) {
            try {
                FileStore fs = Files.getFileStore(Path.of(m.path()));
                long total = fs.getTotalSpace();
                if (total <= 0) {
                    continue;
                }
                long used = Math.max(0, total - fs.getUnallocatedSpace());
                out.add(new Disk(m, total, used, fs.getUsableSpace()));
            } catch (IOException | RuntimeException ignored) {
                // a mount we can't read is left out
            }
        }
        out.sort((a, b) -> Long.compare(b.total(), a.total()));
        return out;
    }

    private Optional<String> read(String name) {
        Path f = proc.resolve(name);
        try {
            return Files.isReadable(f) ? Optional.of(Files.readString(f, StandardCharsets.UTF_8)) : Optional.empty();
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    // ---- parsing (package-visible for tests) -----------------------------------------------------------

    static double[] parseLoadavg(String s) {
        String[] p = s.trim().split("\\s+");
        return new double[]{Double.parseDouble(p[0]), Double.parseDouble(p[1]), Double.parseDouble(p[2])};
    }

    /** The "cpu" line of /proc/stat as {idle + iowait, total}. */
    static long[] parseCpu(String stat) {
        for (String line : stat.split("\n")) {
            if (line.startsWith("cpu ")) {
                String[] p = line.trim().split("\\s+");
                long total = 0;
                // user nice system idle iowait irq softirq steal (guest time is already in user)
                for (int i = 1; i < Math.min(p.length, 9); i++) {
                    total += Long.parseLong(p[i]);
                }
                long idle = Long.parseLong(p[4]) + (p.length > 5 ? Long.parseLong(p[5]) : 0);
                return new long[]{idle, total};
            }
        }
        throw new IllegalArgumentException("No cpu line");
    }

    static Optional<Long> parseBtime(String stat) {
        for (String line : stat.split("\n")) {
            if (line.startsWith("btime ")) {
                return Optional.of(Long.parseLong(line.substring(6).trim()));
            }
        }
        return Optional.empty();
    }

    static Memory parseMeminfo(String s) {
        Map<String, Long> kb = new HashMap<>();
        for (String line : s.split("\n")) {
            int colon = line.indexOf(':');
            if (colon < 0) {
                continue;
            }
            String[] v = line.substring(colon + 1).trim().split("\\s+");
            try {
                kb.put(line.substring(0, colon).trim(), Long.parseLong(v[0]));
            } catch (NumberFormatException ignored) {
                // not a number line
            }
        }
        long total = kb.getOrDefault("MemTotal", 0L);
        long available = kb.containsKey("MemAvailable") ? kb.get("MemAvailable")
                : kb.getOrDefault("MemFree", 0L) + kb.getOrDefault("Buffers", 0L) + kb.getOrDefault("Cached", 0L);
        return new Memory(total * 1024, available * 1024, kb.getOrDefault("SwapTotal", 0L) * 1024, kb.getOrDefault("SwapFree", 0L) * 1024);
    }

    static Optional<Double> parsePressure(String s) {
        for (String line : s.split("\n")) {
            if (line.startsWith("some ")) {
                for (String part : line.split("\\s+")) {
                    if (part.startsWith("avg60=")) {
                        return Optional.of(Double.parseDouble(part.substring(6)));
                    }
                }
            }
        }
        return Optional.empty();
    }

    /** Real disks from /proc/mounts: a /dev/ device on a disk filesystem, each device once (bind mounts and snaps left out). */
    static List<MountPoint> parseMounts(String s) {
        List<MountPoint> out = new ArrayList<>();
        Set<String> devices = new LinkedHashSet<>();
        for (String line : s.split("\n")) {
            String[] p = line.trim().split("\\s+");
            if (p.length < 3 || !p[0].startsWith("/dev/") || p[0].startsWith("/dev/loop") || !DISK_TYPES.contains(p[2])) {
                continue;
            }
            String path = p[1].replace("\\040", " ");
            if (devices.add(p[0])) {
                out.add(new MountPoint(p[0], path, p[2]));
            }
        }
        return out;
    }

    static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
