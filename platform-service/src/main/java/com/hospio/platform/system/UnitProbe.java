package com.hospio.platform.system;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * What systemd knows about each unit: one {@code systemctl show <units...> -p ...} call (read-only, through {@link CommandRunner})
 * gives the state, restarts, start time, main process, memory and CPU time of every unit at once. The process' resident memory
 * comes from /proc/&lt;pid&gt;/status when it can be read (our own processes), otherwise systemd's figure for the unit.
 */
@Component
public class UnitProbe {

    /** Unit names this probe asks about: a fixed list from configuration, checked again here. */
    static final Pattern UNIT = Pattern.compile("^[a-z0-9][a-z0-9@._-]{0,80}$");
    static final String PROPS = "Id,Description,ActiveState,SubState,NRestarts,ActiveEnterTimestampMonotonic,MainPID,MemoryCurrent,CPUUsageNSec";
    private static final long TIMEOUT_MS = 4000;

    private final CommandRunner runner;
    private final Path proc;
    /** unit → {cpu ns, nanoTime} at the previous look, for CPU % since then. */
    private final Map<String, long[]> lastCpu = new ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public UnitProbe(CommandRunner runner) {
        this(runner, Path.of("/proc"));
    }

    UnitProbe(CommandRunner runner, Path proc) {
        this.runner = runner;
        this.proc = proc;
    }

    public record Unit(String id, String description, String activeState, String subState, Integer restarts, Long activeEnterMonotonicUs,
                       Long mainPid, Long memoryCurrent, Long cpuNs) {
    }

    public record UnitView(Unit unit, Instant startedAt, Long memoryBytes, Double cpuPct) {
    }

    /** True when systemctl answered (false on a machine without systemd). */
    public boolean available() {
        return runner.systemctl("show", List.of("-p", "Version"), TIMEOUT_MS).ok();
    }

    /** Every named unit, in the order given; a unit systemd doesn't know comes back with ActiveState "inactive" / LoadState not-found. */
    public Map<String, UnitView> show(List<String> units, Long bootEpochSeconds, int cores) {
        List<String> safe = units.stream().filter(u -> u != null && UNIT.matcher(u).matches()).toList();
        Map<String, UnitView> out = new LinkedHashMap<>();
        if (safe.isEmpty()) {
            return out;
        }
        List<String> args = new ArrayList<>(List.of("-p", PROPS));
        args.addAll(safe);
        CommandRunner.Result r = runner.systemctl("show", args, TIMEOUT_MS);
        if (r.exit() == -1 && r.out().isBlank()) {
            return out;
        }
        long now = System.nanoTime();
        for (Unit u : parseShow(r.out())) {
            String key = u.id() != null && u.id().endsWith(".service") ? u.id().substring(0, u.id().length() - ".service".length()) : u.id();
            Instant started = null;
            if (bootEpochSeconds != null && u.activeEnterMonotonicUs() != null && u.activeEnterMonotonicUs() > 0 && "active".equals(u.activeState())) {
                started = Instant.ofEpochSecond(bootEpochSeconds).plusNanos(u.activeEnterMonotonicUs() * 1000);
            }
            Long rss = u.mainPid() != null && u.mainPid() > 0 ? rss(u.mainPid()).orElse(u.memoryCurrent()) : u.memoryCurrent();
            Double cpu = null;
            if (u.cpuNs() != null) {
                long[] prev = lastCpu.put(key, new long[]{u.cpuNs(), now});
                if (prev != null && now > prev[1] && u.cpuNs() >= prev[0]) {
                    cpu = HostProbe.round1(Math.min(100, (u.cpuNs() - prev[0]) * 100.0 / (now - prev[1]) / Math.max(1, cores)));
                }
            }
            out.put(key, new UnitView(u, started, rss, cpu));
        }
        return out;
    }

    /** The running services that are not in {@code skip}: everything else on the server, with its memory. */
    public List<Unit> others(java.util.Set<String> skip) {
        CommandRunner.Result r = runner.systemctl("list-units", List.of("--type=service", "--state=running", "--plain", "--no-legend"), TIMEOUT_MS);
        if (!r.ok()) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        for (String line : r.out().split("\n")) {
            String name = line.trim().split("\\s+")[0];
            if (name.endsWith(".service")) {
                String key = name.substring(0, name.length() - ".service".length());
                if (!skip.contains(key) && UNIT.matcher(name).matches()) {
                    names.add(name);
                }
            }
        }
        if (names.isEmpty()) {
            return List.of();
        }
        List<String> args = new ArrayList<>(List.of("-p", "Id,Description,MemoryCurrent,ActiveState"));
        args.addAll(names.subList(0, Math.min(names.size(), 80)));
        CommandRunner.Result shown = runner.systemctl("show", args, TIMEOUT_MS);
        return shown.out().isBlank() ? List.of() : parseShow(shown.out());
    }

    private Optional<Long> rss(long pid) {
        try {
            Path f = proc.resolve(Long.toString(pid)).resolve("status");
            if (!Files.isReadable(f)) {
                return Optional.empty();
            }
            for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                if (line.startsWith("VmRSS:")) {
                    return Optional.of(Long.parseLong(line.substring(6).trim().split("\\s+")[0]) * 1024);
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // not our process, or gone
        }
        return Optional.empty();
    }

    /** systemctl show prints KEY=VALUE lines, one blank line between units. */
    static List<Unit> parseShow(String out) {
        List<Unit> units = new ArrayList<>();
        Map<String, String> cur = new HashMap<>();
        for (String line : out.split("\n")) {
            if (line.isBlank()) {
                if (!cur.isEmpty()) {
                    units.add(unit(cur));
                    cur = new HashMap<>();
                }
                continue;
            }
            int eq = line.indexOf('=');
            if (eq > 0) {
                cur.put(line.substring(0, eq), line.substring(eq + 1));
            }
        }
        if (!cur.isEmpty()) {
            units.add(unit(cur));
        }
        return units;
    }

    private static Unit unit(Map<String, String> m) {
        Long restarts = number(m.get("NRestarts"));
        return new Unit(m.get("Id"), m.get("Description"), m.get("ActiveState"), m.get("SubState"), restarts == null ? null : restarts.intValue(),
                number(m.get("ActiveEnterTimestampMonotonic")), number(m.get("MainPID")), number(m.get("MemoryCurrent")), number(m.get("CPUUsageNSec")));
    }

    /** A number, or null for systemd's "[not set]" / empty / the 2^64-1 it prints when accounting is off. */
    static Long number(String v) {
        if (v == null || v.isBlank()) {
            return null;
        }
        try {
            long n = Long.parseLong(v.trim());
            return n < 0 ? null : n;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
