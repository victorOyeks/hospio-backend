package com.hospio.platform.system;

import com.hospio.platform.system.SystemHealthDtos.Mount;
import com.hospio.platform.system.SystemHealthDtos.Report;
import com.hospio.platform.system.SystemHealthDtos.ServiceRow;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * System health in the background (docs/system-health.md): once a minute the server is looked at and the alert rules applied; every
 * 5 minutes a snapshot is stored for the trends (kept 7 days). One instance runs each (SingleInstanceScheduling). Off with
 * WORKER_ENABLED=false or SYSTEM_HEALTH_ENABLED=false.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnExpression("${hospio.worker.enabled:true} and ${hospio.system-health.enabled:true}")
public class SystemHealthJob {

    private final SystemHealthService health;
    private final SystemAlertService alerts;
    private final SnapshotStore snapshots;

    @Scheduled(fixedDelay = 60_000, initialDelay = 90_000)
    public void check() {
        try {
            alerts.evaluate(health.collect());
        } catch (RuntimeException e) {
            log.error("System health check failed: {}", e.getMessage(), e);
        }
    }

    @Scheduled(cron = "0 */5 * * * *")
    public void snapshot() {
        try {
            snapshots.save(snapshot(health.report(false)));
        } catch (RuntimeException e) {
            log.error("System health snapshot failed: {}", e.getMessage(), e);
        }
    }

    /** The main disk is the one mounted at / (or the largest). Up = running and healthy. */
    static SnapshotStore.Snapshot snapshot(Report r) {
        Mount disk = r.server() == null ? null : r.server().mounts().stream().filter(m -> "/".equals(m.path())).findFirst()
                .orElse(r.server().mounts().isEmpty() ? null : r.server().mounts().get(0));
        int up = (int) r.services().stream().filter(SystemHealthJob::up).count();
        return new SnapshotStore.Snapshot(Instant.now(), r.server() == null ? null : r.server().cpuPct(), r.server() == null ? null : r.server().load1(),
                r.server() == null ? null : SystemHealthService.pct(r.server().memUsed(), r.server().memTotal()),
                r.server() == null ? null : SystemHealthService.pct(r.server().swapUsed(), r.server().swapTotal()),
                disk == null ? null : disk.usedPct(), disk == null ? null : disk.used(), disk == null ? null : disk.used() + disk.free(),
                r.database() == null ? null : r.database().hospioBytes(), up, r.services().size());
    }

    static boolean up(ServiceRow s) {
        return ("active".equals(s.state()) || s.state() == null) && "UP".equals(s.health());
    }
}
