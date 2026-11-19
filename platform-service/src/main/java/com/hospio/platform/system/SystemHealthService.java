package com.hospio.platform.system;

import com.hospio.common.tenancy.Tenant;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.platform.service.PlatformSettingsService;
import com.hospio.platform.settings.SystemHealthSettings;
import com.hospio.platform.system.SystemHealthConfig.Target;
import com.hospio.platform.system.SystemHealthDtos.Backups;
import com.hospio.platform.system.SystemHealthDtos.Cert;
import com.hospio.platform.system.SystemHealthDtos.ConsumerGroup;
import com.hospio.platform.system.SystemHealthDtos.Database;
import com.hospio.platform.system.SystemHealthDtos.History;
import com.hospio.platform.system.SystemHealthDtos.Kafka;
import com.hospio.platform.system.SystemHealthDtos.Mount;
import com.hospio.platform.system.SystemHealthDtos.OtherApp;
import com.hospio.platform.system.SystemHealthDtos.Reason;
import com.hospio.platform.system.SystemHealthDtos.Redis;
import com.hospio.platform.system.SystemHealthDtos.Report;
import com.hospio.platform.system.SystemHealthDtos.Server;
import com.hospio.platform.system.SystemHealthDtos.ServiceRow;
import com.hospio.platform.system.SystemHealthDtos.Thresholds;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Platform → System health (docs/system-health.md): the server (CPU, memory, disks), every Hospio unit (systemd state, health,
 * deployed commit, uptime, restarts, memory, CPU), Postgres, Kafka, Redis and the TLS certificates, gathered side by side, each part
 * within its own time limit so one slow part never holds up the rest. The answer is kept 15 seconds (several people with the page
 * open, each refreshing every 30 s, cost one collection); "Refresh now" may ask for a new one every 5 seconds.
 */
@Slf4j
@Service
public class SystemHealthService {

    static final Duration CACHE = Duration.ofSeconds(15);
    static final Duration MIN_FRESH = Duration.ofSeconds(5);
    /** A kept look this young is answered straight away (the job gathers one every minute), never waited for. */
    static final Duration STALE_OK = Duration.ofSeconds(150);
    private final java.util.concurrent.atomic.AtomicBoolean refreshing = new java.util.concurrent.atomic.AtomicBoolean();
    /** Each unit's health at the previous look: one slow answer after a healthy one is not yet a warning. */
    private final Map<String, String> lastHealth = new java.util.concurrent.ConcurrentHashMap<>();
    /** Page colours for a disk (the alert levels are in the settings). */
    public static final int DISK_WARN_PCT = 80;
    public static final int DISK_PROBLEM_PCT = 90;
    private static final long PART_TIMEOUT_MS = 8000;
    private static final ExecutorService PARTS = Executors.newVirtualThreadPerTaskExecutor();
    private static final Pattern STATUS = Pattern.compile("\"status\"\\s*:\\s*\"([A-Z_]+)\"");

    private final SystemHealthConfig config;
    private final HostProbe host;
    private final UnitProbe units;
    private final DatabaseProbe database;
    private final KafkaProbe kafka;
    private final RedisProbe redis;
    private final CertProbe certs;
    private final DirSizes dirs;
    private final SnapshotStore snapshots;
    private final PlatformSettingsService settings;
    /** HTTP/1.1: Java's default HTTP/2 asks a plain-http server to upgrade (h2c), which the Next.js web apps answer by dropping the connection. */
    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(2))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    private volatile Report cached;

    public SystemHealthService(SystemHealthConfig config, HostProbe host, UnitProbe units, DatabaseProbe database, KafkaProbe kafka,
                               RedisProbe redis, CertProbe certs, DirSizes dirs, SnapshotStore snapshots, PlatformSettingsService settings) {
        this.config = config;
        this.host = host;
        this.units = units;
        this.database = database;
        this.kafka = kafka;
        this.redis = redis;
        this.certs = certs;
        this.dirs = dirs;
        this.snapshots = snapshots;
        this.settings = settings;
    }

    /** The page's answer: the kept one when under 15 s old (5 s when {@code fresh}), otherwise a new collection. */
    public Report report(boolean fresh) {
        return withCurrentLimits(kept(fresh));
    }

    /** The limits and switches as saved now: a look gathered before a save must not show the old switch (one small settings read). */
    private Report withCurrentLimits(Report r) {
        try {
            Thresholds t = thresholds(settings.systemHealth(), config.alertsDefault(), config.environment());
            return t.equals(r.thresholds()) ? r : new Report(r.at(), r.overall(), r.reasons(), r.notes(), r.server(), r.services(), r.database(),
                    r.kafka(), r.redis(), r.certs(), r.history(), t, r.otherApps(), r.collectSeconds());
        } catch (RuntimeException e) {
            return r;
        }
    }

    private Report kept(boolean fresh) {
        Report r = cached;
        Instant now = Instant.now();
        if (fresh) {
            return r != null && r.at().plus(MIN_FRESH).isAfter(now) ? r : collect();
        }
        if (r != null && r.at().plus(STALE_OK).isAfter(now)) {
            // Answer at once with the kept look; when it is over 15 s old, a new one is gathered in the background for the next call.
            if (!r.at().plus(CACHE).isAfter(now) && refreshing.compareAndSet(false, true)) {
                PARTS.execute(() -> {
                    try {
                        collect();
                    } catch (RuntimeException e) {
                        log.warn("System health: background collection failed: {}", e.toString());
                    } finally {
                        refreshing.set(false);
                    }
                });
            }
            return r;
        }
        return collect();
    }

    /** The newest report, collected now (the alert job, once a minute). */
    public synchronized Report collect() {
        Report r = cached;
        if (r != null && r.at().plus(MIN_FRESH).isAfter(Instant.now())) {
            return r;
        }
        long started = System.nanoTime();
        SystemHealthSettings s = settings.systemHealth();
        Thresholds t = thresholds(s, config.alertsDefault(), config.environment());

        CompletableFuture<Server> server = part(this::server, () -> null);
        CompletableFuture<List<ServiceRow>> services = part(() -> services(host.bootEpochSeconds().orElse(null)), List::of);
        Backups backups = backups();
        CompletableFuture<Database> db = part(() -> database.probe(backups), () -> new Database(false, null, "No answer within 8 s", null, null,
                List.of(), List.of(), null, null, null, List.of(), null, null, null, backups, SystemHealthDtos.PROBLEM));
        CompletableFuture<Kafka> kf = part(() -> kafka.probe(t.kafkaLag(), t.kafkaStuckMinutes()),
                () -> new Kafka(false, "No answer within 8 s", null, null, null, List.of(), SystemHealthDtos.PROBLEM));
        CompletableFuture<Redis> rd = part(redis::probe,
                () -> new Redis(false, "No answer within 8 s", null, null, null, null, null, null, SystemHealthDtos.PROBLEM));
        CompletableFuture<List<Cert>> cs = part(() -> certs.probe(config.certHosts(), Math.max(t.certDays(), 21)), List::of);
        CompletableFuture<List<OtherApp>> others = part(this::otherApps, List::of);
        CompletableFuture<History> history = part(snapshots::history, () -> new History(List.of(), List.of(), null, null, "Not enough history yet"));

        Server srv = server.join();
        List<ServiceRow> rows = services.join();
        if (srv != null) {
            long hospio = rows.stream().filter(x -> x.memoryBytes() != null).mapToLong(ServiceRow::memoryBytes).sum();
            srv = withHospioMemory(srv, hospio == 0 ? null : hospio);
        }
        Database d = withDbLevel(db.join(), t);
        Kafka k = kf.join();
        Redis rr = rd.join();
        List<Cert> c = cs.join();
        List<Reason> reasons = reasons(srv, rows, d, k, rr, c, t);
        String overall = reasons.stream().map(Reason::level).reduce(SystemHealthDtos.OK, SystemHealthDtos::worst);
        List<String> notes = new ArrayList<>();
        if (!backups.scheduled()) {
            notes.add("No scheduled backup: the database is not being copied anywhere. If this server's disk fails, the data is lost.");
        }
        Report report = new Report(Instant.now(), overall, reasons, notes, srv, rows, d, k, rr, c, history.join(), t, others.join(),
                Math.round((System.nanoTime() - started) / 1e7) / 100.0);
        cached = report;
        return report;
    }

    private static <T> CompletableFuture<T> part(Supplier<T> work, Supplier<T> late) {
        CompletableFuture<T> f = CompletableFuture.supplyAsync(() -> TenantContext.runWith(Tenant.system("system-health"), work), PARTS)
                .exceptionally(e -> {
                    log.warn("System health: a part failed: {}", e.toString());
                    return late.get();
                });
        return f.completeOnTimeout(null, PART_TIMEOUT_MS, TimeUnit.MILLISECONDS).thenApply(v -> v == null ? late.get() : v);
    }

    // ---- the server --------------------------------------------------------------------------------

    Server server() {
        double[] load = host.load().orElse(null);
        HostProbe.Memory mem = host.memory().orElse(null);
        List<Mount> mounts = host.disks().stream().map(dk -> new Mount(dk.mount().path(), dk.mount().device(), dk.mount().type(), dk.total(),
                dk.used(), dk.free(), dk.usedPct(), diskLevel(dk.usedPct()))).toList();
        String level = mounts.stream().map(Mount::level).reduce(SystemHealthDtos.OK, SystemHealthDtos::worst);
        return new Server(host.hostname(), host.os(), host.kernel(), host.uptimeSeconds().orElse(null), Instant.now(), ZoneId.systemDefault().getId(),
                host.cores(), load == null ? null : load[0], load == null ? null : load[1], load == null ? null : load[2], host.cpuPct().orElse(null),
                mem == null ? null : mem.total(), mem == null ? null : mem.used(), mem == null ? null : mem.available(),
                mem == null ? null : mem.swapTotal(), mem == null ? null : mem.swapUsed(), host.memoryStallPct().orElse(null), mounts,
                dirs.sizes(config.dirs()), dirs.measuredAt(), null, level, host.hasProc() ? null : "This machine has no /proc: only what Java can see is shown");
    }

    private static Server withHospioMemory(Server s, Long hospio) {
        return new Server(s.hostname(), s.os(), s.kernel(), s.uptimeSeconds(), s.serverTime(), s.timezone(), s.cores(), s.load1(), s.load5(),
                s.load15(), s.cpuPct(), s.memTotal(), s.memUsed(), s.memAvailable(), s.swapTotal(), s.swapUsed(), s.memoryStallPct(), s.mounts(),
                s.largest(), s.largestMeasuredAt(), hospio, s.level(), s.error());
    }

    static String diskLevel(double usedPct) {
        return usedPct >= DISK_PROBLEM_PCT ? SystemHealthDtos.PROBLEM : usedPct >= DISK_WARN_PCT ? SystemHealthDtos.WARNING : SystemHealthDtos.OK;
    }

    // ---- the services ------------------------------------------------------------------------------

    List<ServiceRow> services(Long boot) {
        List<Target> targets = config.targets();
        Map<String, UnitProbe.UnitView> seen = units.show(targets.stream().map(Target::unit).toList(), boot, host.cores());
        boolean systemd = !seen.isEmpty();
        List<CompletableFuture<ServiceRow>> rows = new ArrayList<>();
        for (Target tg : targets) {
            UnitProbe.UnitView v = seen.get(tg.unit());
            rows.add(CompletableFuture.supplyAsync(() -> row(tg, v, systemd), PARTS)
                    .completeOnTimeout(row(tg, v, systemd, "UNKNOWN", "No answer within 7 s"), 7000, TimeUnit.MILLISECONDS));
        }
        List<ServiceRow> out = new ArrayList<>(rows.stream().map(CompletableFuture::join).toList());
        List<String> order = targets.stream().map(Target::key).toList();
        out.sort(Comparator.comparingInt((ServiceRow r) -> -SystemHealthDtos.rank(r.level())).thenComparingInt(r -> order.indexOf(r.key())));
        return out;
    }

    private ServiceRow row(Target tg, UnitProbe.UnitView v, boolean systemd) {
        String[] health = health(tg);
        String previous = lastHealth.put(tg.key(), health[0]);
        return row(tg, v, systemd, health[0], health[1], "UNKNOWN".equals(health[0]) && "UP".equals(previous));
    }

    ServiceRow row(Target tg, UnitProbe.UnitView v, boolean systemd, String health, String detail) {
        return row(tg, v, systemd, health, detail, false);
    }

    /** {@code once}: the first slow answer after a healthy one, shown but not yet counted as a warning. */
    ServiceRow row(Target tg, UnitProbe.UnitView v, boolean systemd, String health, String detail, boolean once) {
        String state = v == null ? (systemd ? "not-found" : null) : v.unit().activeState();
        String sub = v == null ? null : v.unit().subState();
        Integer restarts = v == null ? null : v.unit().restarts();
        String level = once && (state == null || "active".equals(state)) ? SystemHealthDtos.OK : level(state, health, restarts);
        Instant started = v == null ? null : v.startedAt();
        Long uptime = started == null ? null : Math.max(0, Duration.between(started, Instant.now()).getSeconds());
        String[] commit = commit(tg.commitFile());
        return new ServiceRow(tg.key(), tg.name(), tg.kind(), tg.unit(), tg.port(), state, sub, health, detail, level, commit[0],
                commit[1] == null ? null : Instant.parse(commit[1]), started, uptime, restarts, v == null ? null : v.memoryBytes(),
                v == null ? null : v.cpuPct());
    }

    /** PROBLEM: not running (failed, stopped, restarting) or its health says DOWN; WARNING: health unknown or 3+ restarts. */
    static String level(String state, String health, Integer restarts) {
        if (state != null && !"active".equals(state)) {
            return SystemHealthDtos.PROBLEM;
        }
        if ("DOWN".equals(health)) {
            return SystemHealthDtos.PROBLEM;
        }
        if (!"UP".equals(health) || restarts != null && restarts >= 3) {
            return SystemHealthDtos.WARNING;
        }
        return SystemHealthDtos.OK;
    }

    /** {UP|DOWN|UNKNOWN, detail}: a service's /actuator/health status, or for a web app whether its page answers. */
    String[] health(Target tg) {
        if (tg.healthUrl() == null) {
            return new String[]{"UNKNOWN", "No address configured"};
        }
        try {
            // A web app renders a page (slower under load than a service's health check): 6 s; a service: 4 s.
            HttpRequest req = HttpRequest.newBuilder(URI.create(tg.healthUrl())).timeout(Duration.ofSeconds("WEB".equals(tg.kind()) ? 6 : 4)).GET().build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            int code = res.statusCode();
            if ("WEB".equals(tg.kind())) {
                return code < 400 ? new String[]{"UP", "Page answers (HTTP " + code + ")"} : new String[]{"DOWN", "Page answers HTTP " + code};
            }
            Matcher m = STATUS.matcher(res.body() == null ? "" : res.body());
            String status = m.find() ? m.group(1) : null;
            if ("UP".equals(status)) {
                return new String[]{"UP", "Health check UP"};
            }
            return new String[]{"DOWN", "Health check " + (status == null ? "HTTP " + code : status)};
        } catch (java.net.ConnectException e) {
            return new String[]{"DOWN", "Not answering on port " + tg.port()};
        } catch (java.net.http.HttpTimeoutException e) {
            return new String[]{"UNKNOWN", "Health check didn't answer in " + ("WEB".equals(tg.kind()) ? 6 : 4) + " s"};
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new String[]{"UNKNOWN", "Interrupted"};
        } catch (Exception e) {
            return new String[]{"DOWN", "Health check failed: " + e.getClass().getSimpleName()};
        }
    }

    /** {short commit, file time} from the deploy marker; nulls when there is none. */
    static String[] commit(String file) {
        try {
            Path p = Path.of(file);
            if (!Files.isReadable(p)) {
                return new String[]{null, null};
            }
            String sha = Files.readString(p, StandardCharsets.UTF_8).trim();
            String at = Files.getLastModifiedTime(p).toInstant().toString();
            return new String[]{sha.matches("^[0-9a-f]{7,40}$") ? sha.substring(0, Math.min(7, sha.length())) : null, at};
        } catch (Exception e) {
            return new String[]{null, null};
        }
    }

    List<OtherApp> otherApps() {
        Set<String> skip = new HashSet<>();
        config.targets().forEach(t -> skip.add(t.unit()));
        return units.others(skip).stream().filter(u -> u.memoryCurrent() != null && u.memoryCurrent() > 20L * 1024 * 1024)
                .sorted((a, b) -> Long.compare(b.memoryCurrent(), a.memoryCurrent())).limit(12)
                .map(u -> new OtherApp(u.id() == null ? null : u.id().replace(".service", ""), u.description(), u.memoryCurrent())).toList();
    }

    // ---- levels and reasons ------------------------------------------------------------------------

    private Backups backups() {
        String note = config.backupNote();
        return note.isEmpty() ? new Backups(false, "No scheduled backup") : new Backups(true, note);
    }

    static Database withDbLevel(Database d, Thresholds t) {
        if (!d.up()) {
            return d;
        }
        String level = SystemHealthDtos.OK;
        if (connectionsPct(d) != null && connectionsPct(d) >= t.dbConnectionsPct()) {
            level = SystemHealthDtos.WARNING;
        }
        if (d.longestQuerySeconds() != null && d.longestQuerySeconds() >= 300) {
            level = SystemHealthDtos.WARNING;
        }
        return new Database(d.up(), d.version(), d.error(), d.totalBytes(), d.hospioBytes(), d.databases(), d.schemas(), d.connections(),
                d.maxConnections(), d.longestQuerySeconds(), d.largestTables(), d.cacheHitPct(), d.deadRows(), d.tablesNeedingVacuum(), d.backups(), level);
    }

    static Double connectionsPct(Database d) {
        return d.connections() == null || d.maxConnections() == null || d.maxConnections() == 0 ? null
                : d.connections() * 100.0 / d.maxConnections();
    }

    static Double pct(Long part, Long total) {
        return part == null || total == null || total == 0 ? null : HostProbe.round1(part * 100.0 / total);
    }

    static List<Reason> reasons(Server s, List<ServiceRow> rows, Database d, Kafka k, Redis r, List<Cert> certs, Thresholds t) {
        List<Reason> out = new ArrayList<>();
        if (s == null) {
            out.add(new Reason(SystemHealthDtos.WARNING, "Server", "The server's figures couldn't be read"));
        } else {
            for (Mount m : s.mounts()) {
                if (!SystemHealthDtos.OK.equals(m.level())) {
                    out.add(new Reason(m.level(), "Disk", "Disk " + m.path() + " is " + Math.round(m.usedPct()) + "% full (" + bytes(m.free()) + " free)"));
                }
            }
            Double memPct = pct(s.memUsed(), s.memTotal());
            Double swapPct = pct(s.swapUsed(), s.swapTotal());
            if (memPct != null && memPct >= 97) {
                out.add(new Reason(SystemHealthDtos.PROBLEM, "Memory", "Memory is " + Math.round(memPct) + "% used"));
            } else if (memoryPressure(memPct, swapPct, t)) {
                out.add(new Reason(SystemHealthDtos.WARNING, "Memory", "Memory " + Math.round(memPct) + "% used and swap " + Math.round(swapPct) + "% used"));
            }
            // Load counts threads waiting as well as running: with ~35 JVMs a load of 1-1.5x the cores is normal here while the CPU is
            // mostly idle, so only a 15-minute load over twice the cores (or the CPU over 90% busy) is called busy.
            if (s.load15() != null && s.cores() > 0 && s.load15() > 2.0 * s.cores()) {
                out.add(new Reason(s.load15() > 3.0 * s.cores() ? SystemHealthDtos.PROBLEM : SystemHealthDtos.WARNING, "CPU",
                        "CPU busy: 15-minute load " + s.load15() + " on " + s.cores() + " cores"));
            } else if (s.cpuPct() != null && s.cpuPct() >= 90) {
                out.add(new Reason(SystemHealthDtos.WARNING, "CPU", "CPU " + Math.round(s.cpuPct()) + "% busy"));
            }
        }
        for (ServiceRow row : rows) {
            if (SystemHealthDtos.OK.equals(row.level())) {
                continue;
            }
            String what = row.state() != null && !"active".equals(row.state()) ? "is " + stateWords(row.state(), row.subState())
                    : "DOWN".equals(row.health()) ? "is running but unhealthy (" + row.healthDetail() + ")"
                    : !"UP".equals(row.health()) ? "health unknown (" + row.healthDetail() + ")"
                    : "restarted " + row.restarts() + " times since it was started";
            out.add(new Reason(row.level(), "Services", row.key() + " " + what));
        }
        if (!d.up()) {
            out.add(new Reason(SystemHealthDtos.PROBLEM, "Database", "PostgreSQL: " + d.error()));
        } else {
            Double c = connectionsPct(d);
            if (c != null && c >= t.dbConnectionsPct()) {
                out.add(new Reason(SystemHealthDtos.WARNING, "Database", "Database connections " + d.connections() + " of " + d.maxConnections() + " in use"));
            }
            if (d.longestQuerySeconds() != null && d.longestQuerySeconds() >= 300) {
                out.add(new Reason(SystemHealthDtos.WARNING, "Database", "A query has been running for " + Math.round(d.longestQuerySeconds() / 60) + " minutes"));
            }
        }
        if (!k.up()) {
            out.add(new Reason(SystemHealthDtos.PROBLEM, "Kafka", "Kafka: " + k.error()));
        } else {
            for (ConsumerGroup g : k.groups()) {
                if (!SystemHealthDtos.OK.equals(g.level())) {
                    out.add(new Reason(g.level(), "Kafka", g.service() + " has " + g.lag() + " messages waiting"
                            + (g.stuckMinutes() != null ? " with no progress for " + g.stuckMinutes() + " min" : "")));
                }
            }
        }
        if (!r.up()) {
            out.add(new Reason(SystemHealthDtos.PROBLEM, "Redis", "Redis: " + r.error()));
        }
        for (Cert c : certs) {
            if (c.error() != null) {
                out.add(new Reason(SystemHealthDtos.WARNING, "Certificates", c.host() + ": " + c.error()));
            } else if (!SystemHealthDtos.OK.equals(c.level())) {
                out.add(new Reason(c.level(), "Certificates", c.daysLeft() < 0 ? "Certificate for " + c.host() + " has expired"
                        : "Certificate for " + c.host() + " expires in " + c.daysLeft() + " days"));
            }
        }
        out.sort(Comparator.comparingInt((Reason x) -> -SystemHealthDtos.rank(x.level())));
        return out;
    }

    static boolean memoryPressure(Double memPct, Double swapPct, Thresholds t) {
        return memPct != null && memPct >= t.memoryPct() && (swapPct == null || swapPct >= t.swapPct());
    }

    static String stateWords(String state, String sub) {
        return switch (state) {
            case "failed" -> "failed (stopped after an error)";
            case "inactive" -> "stopped";
            case "activating" -> "starting or restarting" + (sub != null ? " (" + sub + ")" : "");
            case "deactivating" -> "stopping";
            case "not-found" -> "not installed on this server";
            default -> state;
        };
    }

    static String bytes(long b) {
        double gb = b / 1024.0 / 1024 / 1024;
        return gb >= 1 ? String.format(Locale.ROOT, "%.1f GB", gb) : String.format(Locale.ROOT, "%d MB", b / 1024 / 1024);
    }

    static Thresholds thresholds(SystemHealthSettings s) {
        return thresholds(s, true, "test");
    }

    static Thresholds thresholds(SystemHealthSettings s, boolean alertsDefault, String environment) {
        SystemHealthSettings d = SystemHealthSettings.DEFAULTS;
        return new Thresholds(or(s.diskWarnPct(), d.diskWarnPct()), or(s.diskCriticalPct(), d.diskCriticalPct()), or(s.memoryPct(), d.memoryPct()),
                or(s.swapPct(), d.swapPct()), or(s.memoryMinutes(), d.memoryMinutes()), or(s.serviceDownMinutes(), d.serviceDownMinutes()),
                or(s.certDays(), d.certDays()), or(s.kafkaLag(), d.kafkaLag()), or(s.kafkaStuckMinutes(), d.kafkaStuckMinutes()),
                or(s.dbConnectionsPct(), d.dbConnectionsPct()), s.emailAlerts() == null ? Boolean.TRUE.equals(d.emailAlerts()) : s.emailAlerts(),
                s.alertsOn() == null ? alertsDefault : s.alertsOn(), s.bellAlerts() == null || s.bellAlerts(), s.alertsOn() != null, alertsDefault,
                environment);
    }

    private static int or(Integer v, Integer d) {
        return v != null ? v : d;
    }
}
