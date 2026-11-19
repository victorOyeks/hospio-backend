package com.hospio.platform.system;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hospio.common.delivery.Delivery;
import com.hospio.common.delivery.DeliveryService;
import com.hospio.common.kafka.EventPublisher;
import com.hospio.common.kafka.events.NoticeEvent;
import com.hospio.platform.client.IdentityClient;
import com.hospio.platform.entity.Alert;
import com.hospio.platform.repository.AlertRepository;
import com.hospio.platform.repository.PlatformSettingRepository;
import com.hospio.platform.service.PlatformSettingsService;
import com.hospio.platform.settings.SystemHealthSettings;
import com.hospio.platform.system.SystemHealthDtos.Backups;
import com.hospio.platform.system.SystemHealthDtos.Database;
import com.hospio.platform.system.SystemHealthDtos.History;
import com.hospio.platform.system.SystemHealthDtos.Kafka;
import com.hospio.platform.system.SystemHealthDtos.Mount;
import com.hospio.platform.system.SystemHealthDtos.Reason;
import com.hospio.platform.system.SystemHealthDtos.Redis;
import com.hospio.platform.system.SystemHealthDtos.Report;
import com.hospio.platform.system.SystemHealthDtos.Server;
import com.hospio.platform.system.SystemHealthDtos.ServiceRow;
import com.hospio.platform.system.SystemHealthDtos.Thresholds;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.env.MockEnvironment;

/**
 * System health rules: the levels on the page and the banner's reasons, the alert conditions with their waits, one alert per condition
 * until it clears (worse once, recovered once), the disk estimate, the fixed unit list and the limits' checks.
 */
class SystemHealthRulesTest {

    static final long GB = 1024L * 1024 * 1024;
    static final Thresholds T = SystemHealthService.thresholds(SystemHealthSettings.DEFAULTS);

    static Server server(double diskPct, double memPct, double swapPct) {
        long total = 100 * GB;
        long used = (long) (total * diskPct / 100);
        Mount root = new Mount("/", "/dev/sda1", "ext4", total, used, total - used, diskPct, SystemHealthService.diskLevel(diskPct));
        long mem = 12 * GB;
        long swap = 4 * GB;
        return new Server("vmi", "Ubuntu", "6.8", 100L, Instant.now(), "UTC", 6, 1.0, 1.0, 1.0, 10.0, mem, (long) (mem * memPct / 100), 0L, swap,
                (long) (swap * swapPct / 100), 0.0, List.of(root), List.of(), null, null, root.level(), null);
    }

    static ServiceRow svc(String key, String state, String health) {
        return new ServiceRow(key, key, "API", "hospio-" + key, 9210, state, state, health, health, SystemHealthService.level(state, health, 0),
                "abc1234", null, null, null, 0, null, null);
    }

    static Database db(boolean up, int connections) {
        return new Database(up, "16.15", up ? null : "Not reachable: SQLException", 1L, 1L, List.of(), List.of(), connections, 100, 0.1, List.of(),
                99.9, 0L, 0, new Backups(false, "No scheduled backup"), "OK");
    }

    static Report report(Server s, List<ServiceRow> services, Database d) {
        Kafka k = new Kafka(true, null, 1, 40, 0L, List.of(), "OK");
        Redis r = new Redis(true, null, "7", 1L, 1L, 0L, 1L, 1L, "OK");
        return new Report(Instant.now(), "OK", List.of(), List.of(), s, services, d, k, r, List.of(), new History(List.of(), List.of(), null, null, ""),
                T, List.of(), 0.1);
    }

    // ---- page levels and reasons ------------------------------------------------------------------------

    @Test
    void serviceLevels() {
        assertThat(SystemHealthService.level("active", "UP", 0)).isEqualTo("OK");
        assertThat(SystemHealthService.level("failed", "DOWN", 0)).isEqualTo("PROBLEM");
        assertThat(SystemHealthService.level("activating", "DOWN", 0)).isEqualTo("PROBLEM");
        assertThat(SystemHealthService.level("active", "DOWN", 0)).isEqualTo("PROBLEM");
        assertThat(SystemHealthService.level("active", "UNKNOWN", 0)).isEqualTo("WARNING");
        assertThat(SystemHealthService.level("active", "UP", 3)).isEqualTo("WARNING");
        assertThat(SystemHealthService.level(null, "UP", null)).isEqualTo("OK");
    }

    @Test
    void oneSlowAnswerAfterAHealthyOneIsNotYetAWarning() {
        SystemHealthService svc = new SystemHealthService(null, null, null, null, null, null, null, null, null, null);
        SystemHealthConfig.Target web = new SystemHealthConfig.Target("his-fe-staging", "Hotel app", "WEB", "his-fe-staging", 3001,
                "http://127.0.0.1:3001/", "/nonexistent/.sha");
        assertThat(svc.row(web, null, false, "UNKNOWN", "slow", true).level()).isEqualTo("OK");
        assertThat(svc.row(web, null, false, "UNKNOWN", "slow", false).level()).isEqualTo("WARNING");
    }

    @Test
    void diskColoursAt80And90() {
        assertThat(SystemHealthService.diskLevel(79.9)).isEqualTo("OK");
        assertThat(SystemHealthService.diskLevel(80)).isEqualTo("WARNING");
        assertThat(SystemHealthService.diskLevel(90)).isEqualTo("PROBLEM");
    }

    @Test
    void reasonsListProblemsFirstInWords() {
        Report r = report(server(85, 50, 10), List.of(svc("fnb-service", "active", "UP"), svc("maintenance-service", "failed", "DOWN")), db(true, 85));
        List<Reason> reasons = SystemHealthService.reasons(r.server(), r.services(), r.database(), r.kafka(), r.redis(), r.certs(), T);
        assertThat(reasons.get(0).level()).isEqualTo("PROBLEM");
        assertThat(reasons.get(0).text()).isEqualTo("maintenance-service is failed (stopped after an error)");
        assertThat(reasons).extracting(Reason::text).contains("Disk / is 85% full (15.0 GB free)", "Database connections 85 of 100 in use");
    }

    @Test
    void memoryNeedsBothMemoryAndSwapHigh() {
        assertThat(SystemHealthService.memoryPressure(92.0, 40.0, T)).isFalse();
        assertThat(SystemHealthService.memoryPressure(92.0, 80.0, T)).isTrue();
        assertThat(SystemHealthService.memoryPressure(92.0, null, T)).isTrue();
        assertThat(SystemHealthService.memoryPressure(80.0, 90.0, T)).isFalse();
    }

    // ---- alerts ------------------------------------------------------------------------------------------

    private AlertRepository alerts;
    private IdentityClient identity;
    private EventPublisher events;
    private DeliveryService deliveries;
    private Map<String, AlertStateStore.State> stored;
    private Map<String, Alert> saved;
    private SystemAlertService service;

    @BeforeEach
    void setUp() {
        alerts = mock(AlertRepository.class);
        identity = mock(IdentityClient.class);
        events = mock(EventPublisher.class);
        deliveries = mock(DeliveryService.class);
        stored = new LinkedHashMap<>();
        saved = new HashMap<>();
        when(alerts.save(any(Alert.class))).thenAnswer(inv -> {
            Alert a = inv.getArgument(0);
            if (a.getId() == null) {
                a.setId("alert-" + (saved.size() + 1));
            }
            saved.put(a.getId(), a);
            return a;
        });
        when(alerts.findById(any())).thenAnswer(inv -> Optional.ofNullable(saved.get((String) inv.getArgument(0))));
        when(identity.platformStaff("SUPER_ADMIN")).thenReturn(List.of(
                new IdentityClient.Contact("u1", "Amoria", "amoria@hospio360.com", null, null, "ACTIVE", List.of("SUPER_ADMIN"))));
        AlertStateStore store = new AlertStateStore(null) {
            @Override
            public Map<String, State> load() {
                return new LinkedHashMap<>(stored);
            }

            @Override
            public void insert(State s) {
                stored.putIfAbsent(s.key(), s);
            }

            @Override
            public void update(String key, String level, String title, String detail) {
                State s = stored.get(key);
                stored.put(key, new State(key, level, title, detail, s.raisedAt(), s.alertId()));
            }

            @Override
            public void delete(String key) {
                stored.remove(key);
            }
        };
        service = new SystemAlertService(store, alerts, identity, events, deliveries, "https://admin-staging.hospio360.com");
    }

    @Test
    void aServiceDownAlertsOnlyAfterTwoMinutesThenOnceThenRecovers() {
        Instant t0 = Instant.parse("2026-11-19T12:00:00Z");
        Report down = report(server(20, 50, 10), List.of(svc("maintenance-service", "inactive", "DOWN")), db(true, 10));
        Report up = report(server(20, 50, 10), List.of(svc("maintenance-service", "active", "UP")), db(true, 10));

        assertThat(service.evaluate(down, t0).waiting()).containsExactly("SERVICE:maintenance-service");
        assertThat(service.evaluate(down, t0.plusSeconds(60)).raised()).isEmpty();
        SystemAlertService.Outcome raised = service.evaluate(down, t0.plusSeconds(121));
        assertThat(raised.raised()).containsExactly("SERVICE:maintenance-service");
        assertThat(saved.get("alert-1").getTitle()).isEqualTo("maintenance-service is down");
        assertThat(saved.get("alert-1").getBusinessId()).isNull();
        assertThat(saved.get("alert-1").getKind()).isEqualTo(SystemAlertService.KIND);

        // Still down: nothing new is sent.
        SystemAlertService.Outcome again = service.evaluate(down, t0.plusSeconds(181));
        assertThat(again.raised()).isEmpty();
        verify(events, times(1)).publish(any(), any(NoticeEvent.class));

        SystemAlertService.Outcome back = service.evaluate(up, t0.plusSeconds(400));
        assertThat(back.recovered()).containsExactly("SERVICE:maintenance-service");
        assertThat(saved.get("alert-1").getDismissedAt()).isNotNull();
        assertThat(stored).isEmpty();
        ArgumentCaptor<NoticeEvent> notice = ArgumentCaptor.forClass(NoticeEvent.class);
        verify(events, times(2)).publish(any(), notice.capture());
        assertThat(notice.getAllValues().get(0).getTitle()).isEqualTo("Problem: maintenance-service is down");
        assertThat(notice.getAllValues().get(1).getTitle()).isEqualTo("Recovered: maintenance-service is down");
        assertThat(notice.getAllValues().get(1).getUserIds()).containsExactly("u1");
        assertThat(notice.getAllValues().get(1).getLink()).isEqualTo("/dashboard/system");
    }

    @Test
    void aBlipShorterThanTheWaitSendsNothing() {
        Instant t0 = Instant.parse("2026-11-19T12:00:00Z");
        service.evaluate(report(server(20, 50, 10), List.of(svc("fnb-service", "activating", "DOWN")), db(true, 10)), t0);
        SystemAlertService.Outcome o = service.evaluate(report(server(20, 50, 10), List.of(svc("fnb-service", "active", "UP")), db(true, 10)), t0.plusSeconds(60));
        assertThat(o.raised()).isEmpty();
        assertThat(o.recovered()).isEmpty();
        verify(events, never()).publish(any(), any());
        verify(deliveries, never()).send(anyList());
    }

    @Test
    void aFullerDiskAlertsAtOnceGetsWorseOnceAndClearsOnePointUnder() {
        Instant t0 = Instant.parse("2026-11-19T12:00:00Z");
        assertThat(service.evaluate(report(server(86, 50, 10), List.of(), db(true, 10)), t0).raised()).containsExactly("DISK:/");
        assertThat(stored.get("DISK:/").level()).isEqualTo("WARNING");
        assertThat(service.evaluate(report(server(96, 50, 10), List.of(), db(true, 10)), t0.plusSeconds(60)).escalated()).containsExactly("DISK:/");
        assertThat(saved.get("alert-1").getTone()).isEqualTo("danger");
        // Back under the critical level but still over the warning: no new message, still raised.
        SystemAlertService.Outcome lower = service.evaluate(report(server(88, 50, 10), List.of(), db(true, 10)), t0.plusSeconds(120));
        assertThat(lower.raised()).isEmpty();
        assertThat(lower.recovered()).isEmpty();
        // 84.5% is within a point of 85: still raised; 83% clears.
        assertThat(service.evaluate(report(server(84.5, 50, 10), List.of(), db(true, 10)), t0.plusSeconds(180)).recovered()).isEmpty();
        assertThat(service.evaluate(report(server(83, 50, 10), List.of(), db(true, 10)), t0.plusSeconds(240)).recovered()).containsExactly("DISK:/");
        ArgumentCaptor<List<Delivery>> mails = ArgumentCaptor.forClass(List.class);
        verify(deliveries, times(3)).send(mails.capture());
        assertThat(mails.getAllValues().get(0).get(0).subject()).isEqualTo("Hospio360 server warning: Disk / is 86% full");
        assertThat(mails.getAllValues().get(0).get(0).to()).isEqualTo("amoria@hospio360.com");
        assertThat(mails.getAllValues().get(0).get(0).channel()).isEqualTo(Delivery.Channel.EMAIL);
        assertThat(mails.getAllValues().get(1).get(0).subject()).startsWith("Hospio360 server now critical");
        assertThat(mails.getAllValues().get(2).get(0).subject()).startsWith("Hospio360 server recovered");
    }

    @Test
    void aRaisedConditionIsNotRaisedAgainAfterARestartOrADismissal() {
        stored.put("DISK:/", new AlertStateStore.State("DISK:/", "WARNING", "Disk / is 86% full", "x", Instant.now().minus(Duration.ofHours(1)), null));
        SystemAlertService.Outcome o = service.evaluate(report(server(86, 50, 10), List.of(), db(true, 10)), Instant.now());
        assertThat(o.raised()).isEmpty();
        verify(events, never()).publish(any(), any());
    }

    @Test
    void memoryHasToStayHighForTenMinutes() {
        Instant t0 = Instant.parse("2026-11-19T12:00:00Z");
        Report high = report(server(20, 93, 80), List.of(), db(true, 10));
        assertThat(service.evaluate(high, t0).waiting()).containsExactly("MEMORY");
        assertThat(service.evaluate(high, t0.plus(Duration.ofMinutes(9))).raised()).isEmpty();
        assertThat(service.evaluate(high, t0.plus(Duration.ofMinutes(10))).raised()).containsExactly("MEMORY");
    }

    @Test
    void emailOffMeansTheBellOnly() {
        Thresholds noEmail = new Thresholds(85, 95, 90, 75, 10, 2, 14, 500, 10, 80, false);
        Report r = report(server(90, 50, 10), List.of(), db(true, 10));
        Report quiet = new Report(r.at(), r.overall(), r.reasons(), r.notes(), r.server(), r.services(), r.database(), r.kafka(), r.redis(), r.certs(),
                r.history(), noEmail, r.otherApps(), 0.1);
        service.evaluate(quiet, Instant.now());
        verify(events).publish(any(), any(NoticeEvent.class));
        verify(deliveries, never()).send(anyList());
    }

    @Test
    void conditionsForDatabaseCertsAndConnections() {
        Report r = report(server(20, 50, 10), List.of(), db(false, 0));
        assertThat(SystemAlertService.conditions(r, T, java.util.Set.of())).extracting(SystemAlertService.Condition::key).containsExactly("DATABASE");
        Report busy = report(server(20, 50, 10), List.of(), db(true, 81));
        assertThat(SystemAlertService.conditions(busy, T, java.util.Set.of())).extracting(SystemAlertService.Condition::key).containsExactly("DB_CONNECTIONS");
        Report certs = new Report(Instant.now(), "OK", List.of(), List.of(), server(20, 50, 10), List.of(), db(true, 10), busy.kafka(), busy.redis(),
                List.of(new SystemHealthDtos.Cert("web-staging.hospio360.com", 10L, Instant.now().plus(Duration.ofDays(10)), "LE", null, "WARNING"),
                        new SystemHealthDtos.Cert("hospio360.com", 60L, Instant.now().plus(Duration.ofDays(60)), "LE", null, "OK")),
                busy.history(), T, List.of(), 0.1);
        assertThat(SystemAlertService.conditions(certs, T, java.util.Set.of())).extracting(SystemAlertService.Condition::key)
                .containsExactly("CERT:web-staging.hospio360.com");
    }

    // ---- history -------------------------------------------------------------------------------------------

    static SnapshotStore.Snapshot snap(Instant at, long used) {
        return new SnapshotStore.Snapshot(at, 10.0, 1.0, 80.0, 30.0, used * 100.0 / (100 * GB), used, 100 * GB, 50L << 20, 18, 18);
    }

    @Test
    void diskFullEstimateFromSteadyGrowth() {
        Instant t0 = Instant.parse("2026-11-12T00:00:00Z");
        List<SnapshotStore.Snapshot> all = new ArrayList<>();
        for (int h = 0; h <= 48; h++) {
            all.add(snap(t0.plus(Duration.ofHours(h)), 20 * GB + h * (GB / 24)));
        }
        History hist = SnapshotStore.history(all, t0.plus(Duration.ofHours(48)));
        assertThat(hist.diskGrowthPerDay()).isCloseTo((double) GB, org.assertj.core.data.Offset.offset(1e6));
        // 22 GB used of 100 GB, 1 GB a day: about 78 days.
        assertThat(hist.diskFullInDays()).isCloseTo(78.0, org.assertj.core.data.Offset.offset(0.5));
        assertThat(hist.diskTrend()).isEqualTo("GROWING");
        assertThat(hist.day()).hasSize(25);
        assertThat(hist.week()).hasSize(49);
    }

    @Test
    void noEstimateWithoutEnoughHistoryOrGrowth() {
        Instant t0 = Instant.parse("2026-11-12T00:00:00Z");
        assertThat(SnapshotStore.history(List.of(snap(t0, 20 * GB), snap(t0.plusSeconds(300), 21 * GB), snap(t0.plusSeconds(600), 22 * GB)), t0)
                .diskFullInDays()).isNull();
        List<SnapshotStore.Snapshot> flat = new ArrayList<>();
        for (int h = 0; h <= 12; h++) {
            flat.add(snap(t0.plus(Duration.ofHours(h)), 20 * GB + h * 1024));
        }
        History hist = SnapshotStore.history(flat, t0.plus(Duration.ofHours(12)));
        assertThat(hist.diskFullInDays()).isNull();
        assertThat(hist.diskTrend()).isEqualTo("STEADY");
    }

    @Test
    void snapshotCountsServicesUpAndTheRootDisk() {
        Report r = report(server(20, 50, 10), List.of(svc("a", "active", "UP"), svc("b", "failed", "DOWN")), db(true, 10));
        SnapshotStore.Snapshot s = SystemHealthJob.snapshot(r);
        assertThat(s.servicesUp()).isEqualTo(1);
        assertThat(s.servicesTotal()).isEqualTo(2);
        assertThat(s.diskPct()).isEqualTo(20.0);
    }

    // ---- configuration and limits ---------------------------------------------------------------------------

    @Test
    void theUnitListIsFixedByConfiguration() {
        MockEnvironment env = new MockEnvironment().withProperty("services.hotelconfig.url", "http://localhost:9203/hotel-config")
                .withProperty("services.identity.url", "http://localhost:9201/identity");
        SystemHealthConfig c = new SystemHealthConfig(env, true, "hospio-", "http://localhost:9200", "/opt/hospio-staging", "/home/devoyeks/apps",
                "his-fe-staging=3001=Hotel app,bad unit;rm=3002=X,hospio-admin-staging=notaport=Admin", "hospio360.com,bad host!,api-staging.hospio360.com",
                "All logs=/var/log,relative=var", "");
        assertThat(c.targets()).hasSize(16);
        SystemHealthConfig.Target hc = c.targets().stream().filter(t -> t.key().equals("hotel-config-service")).findFirst().orElseThrow();
        assertThat(hc.unit()).isEqualTo("hospio-hotel-config-service");
        assertThat(hc.healthUrl()).isEqualTo("http://localhost:9203/hotel-config/actuator/health");
        assertThat(hc.port()).isEqualTo(9203);
        assertThat(hc.commitFile()).isEqualTo("/opt/hospio-staging/hotel-config-service/app.sha");
        SystemHealthConfig.Target web = c.targets().get(15);
        assertThat(web.unit()).isEqualTo("his-fe-staging");
        assertThat(web.kind()).isEqualTo("WEB");
        assertThat(web.healthUrl()).isEqualTo("http://127.0.0.1:3001/");
        assertThat(c.certHosts()).containsExactly("hospio360.com", "api-staging.hospio360.com");
        assertThat(c.dirs()).containsOnlyKeys("All logs");
        assertThat(c.backupNote()).isEmpty();
    }

    @Test
    void theLimitsAreChecked() {
        PlatformSettingRepository repo = mock(PlatformSettingRepository.class);
        PlatformSettingsService settings = new PlatformSettingsService(repo, new ObjectMapper());
        assertThat(settings.systemGroup()).isEqualTo(SystemHealthSettings.DEFAULTS);
        assertThatThrownBy(() -> settings.update(new SystemHealthSettings(96, null, null, null, null, null, null, null, null, null, null)))
                .hasMessageContaining("under the critical level");
        assertThatThrownBy(() -> settings.update(new SystemHealthSettings(null, null, null, null, null, 0, null, null, null, null, null)))
                .hasMessageContaining("Service down for (minutes) must be between 1 and 1440");
        assertThatThrownBy(() -> settings.update(new SystemHealthSettings(null, null, null, null, null, null, 120, null, null, null, null)))
                .hasMessageContaining("Certificate days");
    }
}
