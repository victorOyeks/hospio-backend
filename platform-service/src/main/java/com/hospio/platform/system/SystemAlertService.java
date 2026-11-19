package com.hospio.platform.system;

import com.hospio.common.delivery.Delivery;
import com.hospio.common.delivery.DeliveryService;
import com.hospio.common.kafka.EventPublisher;
import com.hospio.common.kafka.KafkaTopics;
import com.hospio.common.kafka.events.NoticeEvent;
import com.hospio.common.enums.Role;
import com.hospio.common.tenancy.Tenant;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.platform.client.IdentityClient;
import com.hospio.platform.entity.Alert;
import com.hospio.platform.repository.AlertRepository;
import com.hospio.platform.system.SystemHealthDtos.Cert;
import com.hospio.platform.system.SystemHealthDtos.ConsumerGroup;
import com.hospio.platform.system.SystemHealthDtos.Mount;
import com.hospio.platform.system.SystemHealthDtos.Report;
import com.hospio.platform.system.SystemHealthDtos.ServiceRow;
import com.hospio.platform.system.SystemHealthDtos.Thresholds;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * System health alerts (docs/system-health.md), checked once a minute against the latest report: a disk over the warning or critical
 * level, memory and swap both high for {@code memoryMinutes}, a service down for {@code serviceDownMinutes}, a certificate under
 * {@code certDays}, a Kafka consumer stuck, database connections over the limit, and Postgres, Kafka or Redis unreachable. Each
 * condition alerts once (an open alert on the dashboard, the bell and an email to every Super admin), again only if it gets worse
 * (warning → critical), and once more when it clears ("recovered"). The raised conditions are kept in platform.system_alert_states so
 * a restart doesn't alert again; how long a condition has lasted before it is raised is kept in memory (a restart starts the wait
 * again). Never SMS or WhatsApp.
 */
@Slf4j
@Service
public class SystemAlertService {

    public static final String KIND = "SYSTEM_HEALTH";
    static final String WARNING = "WARNING";
    static final String CRITICAL = "CRITICAL";
    private static final ZoneId LAGOS = ZoneId.of("Africa/Lagos");
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.ENGLISH).withZone(LAGOS);

    /** A condition that holds now: its key, level (WARNING or CRITICAL), title and detail, and how long it must last first. */
    public record Condition(String key, String level, String title, String detail, Duration sustain) {
    }

    /** What one evaluation did, for the log and the tests. */
    public record Outcome(List<String> raised, List<String> escalated, List<String> recovered, List<String> waiting) {
    }

    private final AlertStateStore store;
    private final AlertRepository alerts;
    private final IdentityClient identity;
    private final EventPublisher events;
    private final DeliveryService deliveries;
    private final String adminUrl;
    private final Map<String, Instant> firstSeen = new HashMap<>();

    public SystemAlertService(AlertStateStore store, AlertRepository alerts, IdentityClient identity, EventPublisher events, DeliveryService deliveries,
                              @Value("${hospio.apps.admin-url:https://admin-staging.hospio360.com}") String adminUrl) {
        this.store = store;
        this.alerts = alerts;
        this.identity = identity;
        this.events = events;
        this.deliveries = deliveries;
        this.adminUrl = adminUrl;
    }

    // ---- which conditions hold -----------------------------------------------------------------------

    /** The alert conditions in the report; {@code raised} are the keys already alerted (a disk stays raised until 1 point under the level). */
    static List<Condition> conditions(Report r, Thresholds t, java.util.Set<String> raised) {
        List<Condition> out = new ArrayList<>();
        Duration now = Duration.ZERO;
        if (r.server() != null) {
            for (Mount m : r.server().mounts()) {
                String key = "DISK:" + m.path();
                double pct = m.usedPct();
                String free = SystemHealthService.bytes(m.free()) + " free";
                if (pct >= t.diskCriticalPct()) {
                    out.add(new Condition(key, CRITICAL, "Disk " + m.path() + " is " + Math.round(pct) + "% full", free + " of " + SystemHealthService.bytes(m.total()), now));
                } else if (pct >= t.diskWarnPct() || raised.contains(key) && pct >= t.diskWarnPct() - 1) {
                    out.add(new Condition(key, WARNING, "Disk " + m.path() + " is " + Math.round(pct) + "% full", free + " of " + SystemHealthService.bytes(m.total()), now));
                }
            }
            Double mem = SystemHealthService.pct(r.server().memUsed(), r.server().memTotal());
            Double swap = SystemHealthService.pct(r.server().swapUsed(), r.server().swapTotal());
            if (SystemHealthService.memoryPressure(mem, swap, t) || mem != null && mem >= 97) {
                out.add(new Condition("MEMORY", mem != null && mem >= 97 ? CRITICAL : WARNING, "Memory is running out",
                        "Memory " + Math.round(mem) + "% used" + (swap == null ? "" : ", swap " + Math.round(swap) + "% used")
                                + " for over " + t.memoryMinutes() + " minutes", Duration.ofMinutes(t.memoryMinutes())));
            }
        }
        for (ServiceRow s : r.services()) {
            boolean down = s.state() != null && !"active".equals(s.state()) || !"UP".equals(s.health());
            if (down) {
                String what = s.state() != null && !"active".equals(s.state()) ? SystemHealthService.stateWords(s.state(), s.subState())
                        : "not healthy (" + s.healthDetail() + ")";
                out.add(new Condition("SERVICE:" + s.key(), CRITICAL, s.key() + " is down", s.key() + " is " + what
                        + " for over " + t.serviceDownMinutes() + " minutes", Duration.ofMinutes(t.serviceDownMinutes())));
            }
        }
        for (Cert c : r.certs()) {
            if (c.daysLeft() != null && c.daysLeft() < t.certDays()) {
                out.add(new Condition("CERT:" + c.host(), c.daysLeft() < 7 ? CRITICAL : WARNING,
                        c.daysLeft() < 0 ? "Certificate expired: " + c.host() : "Certificate expires in " + c.daysLeft() + " days: " + c.host(),
                        "Renew it with certbot on the server (sudo certbot renew)", now));
            }
        }
        Duration services = Duration.ofMinutes(t.serviceDownMinutes());
        if (r.database() != null) {
            if (!r.database().up()) {
                out.add(new Condition("DATABASE", CRITICAL, "The database is not reachable", r.database().error(), services));
            } else {
                Double c = SystemHealthService.connectionsPct(r.database());
                if (c != null && c >= t.dbConnectionsPct()) {
                    out.add(new Condition("DB_CONNECTIONS", WARNING, "Database connections nearly used up",
                            r.database().connections() + " of " + r.database().maxConnections() + " connections in use", Duration.ofMinutes(2)));
                }
            }
        }
        if (r.kafka() != null) {
            if (!r.kafka().up()) {
                out.add(new Condition("KAFKA", CRITICAL, "Kafka is not reachable", r.kafka().error(), services));
            } else {
                for (ConsumerGroup g : r.kafka().groups()) {
                    if (SystemHealthDtos.PROBLEM.equals(g.level())) {
                        out.add(new Condition("KAFKA_LAG:" + g.group(), WARNING, g.service() + " is not reading its events",
                                g.lag() + " messages waiting with no progress for " + g.stuckMinutes() + " minutes", now));
                    }
                }
            }
        }
        if (r.redis() != null && !r.redis().up()) {
            out.add(new Condition("REDIS", CRITICAL, "Redis is not reachable", r.redis().error(), services));
        }
        return out;
    }

    // ---- raising, escalating, clearing -----------------------------------------------------------------

    /** A message waiting for the next email: phase (Problem, Warning, Now critical, Recovered), title, detail, when. */
    record Pending(String phase, String title, String detail, String key, Instant at) {
    }

    /** At most one email an hour: the first goes at once, what happens in the hour after waits and goes as one digest. */
    static final Duration EMAIL_EVERY = Duration.ofHours(1);
    /** A condition raised while alerts were off is told once it is still there this long after they were switched on. */
    static final Duration AFTER_SWITCH_ON = Duration.ofMinutes(2);

    private final List<Pending> pendingEmail = new ArrayList<>();
    private Instant lastEmailAt;
    private Boolean wasOn;
    private Instant onSince;

    public synchronized Outcome evaluate(Report report) {
        return TenantContext.runWith(Tenant.system("system-health-alerts"), () -> evaluate(report, Instant.now()));
    }

    Outcome evaluate(Report report, Instant now) {
        Thresholds t = report.thresholds();
        boolean on = t == null || t.alertsOn();
        if (on && !Boolean.TRUE.equals(wasOn)) {
            onSince = now;
        }
        if (!on) {
            pendingEmail.clear();
        }
        wasOn = on;
        Map<String, AlertStateStore.State> states = store.load();
        List<Condition> holding = conditions(report, t, states.keySet());
        Map<String, Condition> byKey = new LinkedHashMap<>();
        holding.forEach(c -> byKey.put(c.key(), c));
        List<String> raised = new ArrayList<>();
        List<String> escalated = new ArrayList<>();
        List<String> recovered = new ArrayList<>();
        List<String> waiting = new ArrayList<>();
        firstSeen.keySet().retainAll(byKey.keySet());
        for (Condition c : byKey.values()) {
            Instant since = firstSeen.computeIfAbsent(c.key(), k -> now);
            AlertStateStore.State s = states.get(c.key());
            if (s == null) {
                if (Duration.between(since, now).compareTo(c.sustain()) < 0) {
                    waiting.add(c.key());
                    continue;
                }
                raise(c, now, report, on);
                raised.add(c.key());
            } else if (CRITICAL.equals(c.level()) && WARNING.equals(s.level())) {
                escalate(s, c, now, report, on);
                escalated.add(c.key());
            } else {
                if (!Objects.equals(s.detail(), c.detail()) || !Objects.equals(s.title(), c.title())) {
                    refresh(s, c, now);
                }
                // Raised while alerts were off and still there a while after they were switched on: told now, once.
                if (on && !s.notified() && onSince != null && !now.isBefore(onSince.plus(AFTER_SWITCH_ON))) {
                    store.markNotified(s.key());
                    tell(CRITICAL.equals(c.level()) ? "Problem" : "Warning", c.title(), c.detail(), c.key(), now, report);
                    raised.add(c.key());
                }
            }
        }
        for (AlertStateStore.State s : states.values()) {
            if (!byKey.containsKey(s.key())) {
                recover(s, now, report, on);
                recovered.add(s.key());
            }
        }
        if (on) {
            flushEmail(now, report);
        }
        if (!raised.isEmpty() || !escalated.isEmpty() || !recovered.isEmpty()) {
            log.info("System health alerts{}: raised {}, worse {}, recovered {}", on ? "" : " (alerts off: nobody told)", raised, escalated, recovered);
        }
        return new Outcome(raised, escalated, recovered, waiting);
    }

    private void raise(Condition c, Instant now, Report report, boolean on) {
        Alert a = alerts.save(Alert.builder().kind(KIND).businessName("Server").title(c.title()).detail(c.detail()).tone(tone(c.level()))
                .openedAt(now).build());
        store.insert(new AlertStateStore.State(c.key(), c.level(), c.title(), c.detail(), now, a.getId(), on));
        if (on) {
            tell(c.level().equals(CRITICAL) ? "Problem" : "Warning", c.title(), c.detail(), c.key(), now, report);
        }
    }

    private void escalate(AlertStateStore.State s, Condition c, Instant now, Report report, boolean on) {
        if (s.alertId() != null) {
            alerts.findById(s.alertId()).filter(Alert::isOpen).ifPresent(a -> {
                a.setTitle(c.title());
                a.setDetail(c.detail());
                a.setTone(tone(c.level()));
                alerts.save(a);
            });
        }
        store.update(s.key(), c.level(), c.title(), c.detail());
        if (on) {
            if (!s.notified()) {
                store.markNotified(s.key());
            }
            tell(s.notified() ? "Now critical" : "Problem", c.title(), c.detail(), c.key(), now, report);
        }
    }

    private void refresh(AlertStateStore.State s, Condition c, Instant now) {
        if (s.alertId() != null) {
            alerts.findById(s.alertId()).filter(Alert::isOpen).ifPresent(a -> {
                a.setTitle(c.title());
                a.setDetail(c.detail());
                alerts.save(a);
            });
        }
        store.update(s.key(), s.level(), c.title(), c.detail());
    }

    private void recover(AlertStateStore.State s, Instant now, Report report, boolean on) {
        if (s.alertId() != null) {
            alerts.findById(s.alertId()).filter(Alert::isOpen).ifPresent(a -> {
                a.setDismissedAt(now);
                a.setDismissedBy("system");
                a.setDismissedByName("Cleared automatically");
                alerts.save(a);
            });
        }
        store.delete(s.key());
        // Only a problem someone was told about gets a "recovered" note.
        if (on && s.notified()) {
            long minutes = Math.max(1, Duration.between(s.raisedAt(), now).toMinutes());
            tell("Recovered", s.title(), "Back to normal after " + minutes + (minutes == 1 ? " minute" : " minutes") + " (alerted "
                    + WHEN.format(s.raisedAt()) + ")", s.key(), now, report);
        }
    }

    // ---- telling the super admins ------------------------------------------------------------------------

    /** The bell of every Super admin now (when the bell is on), and the email queued for {@link #flushEmail}. Never fails the evaluation. */
    private void tell(String phase, String title, String detail, String key, Instant now, Report report) {
        Thresholds t = report.thresholds();
        if (t == null || t.emailAlerts()) {
            pendingEmail.add(new Pending(phase, title, detail, key, now));
        }
        if (t != null && !t.bellAlerts()) {
            return;
        }
        try {
            List<String> ids = identity.platformStaff(Role.SUPER_ADMIN.name()).stream().map(IdentityClient.Contact::id).filter(Objects::nonNull)
                    .distinct().toList();
            if (ids.isEmpty()) {
                log.warn("System health alert {} ({}): no super admin to tell", key, phase);
                return;
            }
            events.publish(KafkaTopics.NOTICE, NoticeEvent.builder().kind(KIND).title(phase + ": " + title).body(detail).link("/dashboard/system")
                    .userIds(new ArrayList<>(ids)).build());
        } catch (RuntimeException e) {
            log.warn("System health alert {} ({}): the bell was not rung: {}", key, phase, e.getMessage());
        }
    }

    /**
     * Sends what is queued as one email to every Super admin, unless an email went in the last hour (then it waits and goes as one
     * digest when the hour is up). One message: its own subject; several: "N updates" listing each.
     */
    void flushEmail(Instant now, Report report) {
        if (pendingEmail.isEmpty() || lastEmailAt != null && now.isBefore(lastEmailAt.plus(EMAIL_EVERY))) {
            return;
        }
        List<Pending> batch = new ArrayList<>(pendingEmail);
        pendingEmail.clear();
        lastEmailAt = now;
        try {
            List<IdentityClient.Contact> admins = identity.platformStaff(Role.SUPER_ADMIN.name());
            String host = report.server() == null || report.server().hostname() == null ? "the Hospio360 server" : "server " + report.server().hostname();
            String subject = batch.size() == 1 ? "Hospio360 server " + batch.get(0).phase().toLowerCase(Locale.ROOT) + ": " + batch.get(0).title()
                    : "Hospio360 server: " + batch.size() + " updates (" + summary(batch) + ")";
            StringBuilder text = new StringBuilder("Hospio360 system health on ").append(host).append("\n\n");
            for (Pending p : batch) {
                text.append(p.phase()).append(": ").append(p.title()).append('\n');
                if (p.detail() != null) {
                    text.append("  ").append(p.detail()).append('\n');
                }
                text.append("  ").append(WHEN.format(p.at())).append(" (Lagos time)\n\n");
            }
            text.append("Open System health: ").append(adminUrl).append("/dashboard/system\n\n")
                    .append("Each problem is emailed once when it starts, once if it gets worse and once when it clears, at most one email an hour ")
                    .append("(more in that hour come together). Switch alerts off or change the limits on System health → Alerts.\n\nHospio360\n");
            String ref = "system-health:" + (batch.size() == 1 ? batch.get(0).key() + ":" + batch.get(0).phase() : "digest") + ":" + now.getEpochSecond();
            deliveries.send(admins.stream().filter(a -> a.email() != null && !a.email().isBlank())
                    .map(a -> Delivery.email(a.email(), subject, text.toString(), Delivery.Kind.NOTICE, ref)).toList());
        } catch (RuntimeException e) {
            log.warn("System health alert email not sent: {}", e.getMessage());
        }
    }

    private static String summary(List<Pending> batch) {
        long problems = batch.stream().filter(p -> !"Recovered".equals(p.phase())).count();
        long back = batch.size() - problems;
        return (problems > 0 ? problems + (problems == 1 ? " problem" : " problems") : "") + (problems > 0 && back > 0 ? ", " : "")
                + (back > 0 ? back + " recovered" : "");
    }

    private static String tone(String level) {
        return CRITICAL.equals(level) ? "danger" : "warning";
    }
}
