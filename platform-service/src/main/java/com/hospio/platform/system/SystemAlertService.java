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

    public synchronized Outcome evaluate(Report report) {
        return TenantContext.runWith(Tenant.system("system-health-alerts"), () -> evaluate(report, Instant.now()));
    }

    Outcome evaluate(Report report, Instant now) {
        Map<String, AlertStateStore.State> states = store.load();
        List<Condition> holding = conditions(report, report.thresholds(), states.keySet());
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
                raise(c, now, report);
                raised.add(c.key());
            } else if (CRITICAL.equals(c.level()) && WARNING.equals(s.level())) {
                escalate(s, c, now, report);
                escalated.add(c.key());
            } else if (!Objects.equals(s.detail(), c.detail()) || !Objects.equals(s.title(), c.title())) {
                refresh(s, c, now);
            }
        }
        for (AlertStateStore.State s : states.values()) {
            if (!byKey.containsKey(s.key())) {
                recover(s, now, report);
                recovered.add(s.key());
            }
        }
        if (!raised.isEmpty() || !escalated.isEmpty() || !recovered.isEmpty()) {
            log.info("System health alerts: raised {}, worse {}, recovered {}", raised, escalated, recovered);
        }
        return new Outcome(raised, escalated, recovered, waiting);
    }

    private void raise(Condition c, Instant now, Report report) {
        Alert a = alerts.save(Alert.builder().kind(KIND).businessName("Server").title(c.title()).detail(c.detail()).tone(tone(c.level()))
                .openedAt(now).build());
        store.insert(new AlertStateStore.State(c.key(), c.level(), c.title(), c.detail(), now, a.getId()));
        tell(c.level().equals(CRITICAL) ? "Problem" : "Warning", c.title(), c.detail(), c.key(), now, report);
    }

    private void escalate(AlertStateStore.State s, Condition c, Instant now, Report report) {
        if (s.alertId() != null) {
            alerts.findById(s.alertId()).filter(Alert::isOpen).ifPresent(a -> {
                a.setTitle(c.title());
                a.setDetail(c.detail());
                a.setTone(tone(c.level()));
                alerts.save(a);
            });
        }
        store.update(s.key(), c.level(), c.title(), c.detail());
        tell("Now critical", c.title(), c.detail(), c.key(), now, report);
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

    private void recover(AlertStateStore.State s, Instant now, Report report) {
        if (s.alertId() != null) {
            alerts.findById(s.alertId()).filter(Alert::isOpen).ifPresent(a -> {
                a.setDismissedAt(now);
                a.setDismissedBy("system");
                a.setDismissedByName("Cleared automatically");
                alerts.save(a);
            });
        }
        store.delete(s.key());
        long minutes = Math.max(1, Duration.between(s.raisedAt(), now).toMinutes());
        tell("Recovered", s.title(), "Back to normal after " + minutes + (minutes == 1 ? " minute" : " minutes") + " (alerted " + WHEN.format(s.raisedAt()) + ")",
                s.key(), now, report);
    }

    // ---- telling the super admins ------------------------------------------------------------------------

    /** The bell of every Super admin and, when email alerts are on, an email to each. Never fails the evaluation. */
    private void tell(String phase, String title, String detail, String key, Instant now, Report report) {
        try {
            List<IdentityClient.Contact> admins = identity.platformStaff(Role.SUPER_ADMIN.name());
            List<String> ids = admins.stream().map(IdentityClient.Contact::id).filter(Objects::nonNull).distinct().toList();
            if (ids.isEmpty()) {
                log.warn("System health alert {} ({}): no super admin to tell", key, phase);
                return;
            }
            String heading = phase + ": " + title;
            events.publish(KafkaTopics.NOTICE, NoticeEvent.builder().kind(KIND).title(heading).body(detail).link("/dashboard/system")
                    .userIds(new ArrayList<>(ids)).build());
            if (report.thresholds() == null || report.thresholds().emailAlerts()) {
                String host = report.server() == null || report.server().hostname() == null ? "the Hospio360 server" : "server " + report.server().hostname();
                String text = "Hospio360 system health\n\n" + heading + "\n" + (detail == null ? "" : detail + "\n") + "When: " + WHEN.format(now)
                        + " (Lagos time) on " + host + "\n\nOpen System health: " + adminUrl + "/dashboard/system\n\n"
                        + ("Recovered".equals(phase) ? "" : "You get one email when this starts, one if it gets worse and one when it is back to normal. ")
                        + "The limits are in Settings → System health.\n\nHospio360\n";
                List<Delivery> mails = admins.stream().filter(a -> a.email() != null && !a.email().isBlank()).map(a -> Delivery.email(a.email(),
                        "Hospio360 server " + phase.toLowerCase(Locale.ROOT) + ": " + title, text, Delivery.Kind.NOTICE,
                        "system-health:" + key + ":" + phase + ":" + now.getEpochSecond())).toList();
                deliveries.send(mails);
            }
        } catch (RuntimeException e) {
            log.warn("System health alert {} ({}): the super admins were not told: {}", key, phase, e.getMessage());
        }
    }

    private static String tone(String level) {
        return CRITICAL.equals(level) ? "danger" : "warning";
    }
}
