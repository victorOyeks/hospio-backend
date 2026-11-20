package com.hospio.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.hospio.common.delivery.DeliveryService;
import com.hospio.common.enums.AccessLevel;
import com.hospio.common.enums.Role;
import com.hospio.common.kafka.EventPublisher;
import com.hospio.common.kafka.KafkaTopics;
import com.hospio.common.kafka.events.DeliveryRequestedEvent;
import com.hospio.common.kafka.events.HospioEvent;
import com.hospio.common.kafka.events.UserCreatedEvent;
import com.hospio.common.tenancy.Tenant;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.identity.entity.RoleGrant;
import com.hospio.identity.entity.UserAccount;
import com.hospio.identity.repository.InviteRepository;
import com.hospio.identity.repository.RoleGrantRepository;
import com.hospio.identity.repository.UserAccountRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The bootstrap super admin against a real Postgres (Liquibase 013, the advisory lock, the one-row table): created once, skipped
 * when a super admin exists, never twice when instances start together, re-issued only before the first sign-in, and the
 * password never logged. Needs Docker (skipped without it).
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@TestPropertySource(properties = {
        "spring.kafka.bootstrap-servers=localhost:1",
        "spring.kafka.listener.auto-startup=false",
        "spring.kafka.admin.auto-create=false",
        "spring.kafka.admin.fail-fast=false",
        "services.internal.api-key=test-key",
        "hospio.bootstrap.admin-email=",
        "hospio.apps.admin-url=https://admin.hospio-qa.test",
        "jwt.secret=test-secret-test-secret-test-secret-test-secret-test"
})
class BootstrapAdminRaceTest {

    static final String EMAIL = "victor@oyeks-qa.test";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine").withInitScript("identity-schema.sql");

    @MockBean EventPublisher events;

    @Autowired UserAccountRepository users;
    @Autowired RoleGrantRepository grants;
    @Autowired InviteRepository invites;
    @Autowired UserService userService;
    @Autowired DeliveryComposer composer;
    @Autowired DeliveryService deliveryService;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired PasswordEncoder passwordEncoder;

    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);

    @BeforeEach
    void clean() {
        TenantContext.set(Tenant.system("test"));
        jdbc.update("DELETE FROM identity.bootstrap_admin");
        invites.deleteAll();
        grants.deleteAll();
        users.deleteAll();
        TenantContext.clear();
        reset(events);
        logs.list.clear();
        logs.start();
        root.addAppender(logs);
    }

    @AfterEach
    void stop() {
        root.detachAppender(logs);
        TenantContext.clear();
    }

    private BootstrapAdminService service() {
        BootstrapAdminService s = new BootstrapAdminService(users, grants, userService, composer, deliveryService, events, jdbc,
                transactionManager, EMAIL, 0, 3);
        s.sleeper = d -> { };
        return s;
    }

    private <T> T asPlatform(Callable<T> work) {
        TenantContext.set(Tenant.system("test"));
        try {
            return work.call();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        } finally {
            TenantContext.clear();
        }
    }

    private UserAccount account() {
        return asPlatform(() -> users.findByEmailIgnoreCase(EMAIL).orElseThrow());
    }

    private Integer rows() {
        return asPlatform(() -> jdbc.queryForObject("SELECT count(*) FROM identity.bootstrap_admin", Integer.class));
    }

    private List<DeliveryRequestedEvent> emails() {
        ArgumentCaptor<HospioEvent> sent = ArgumentCaptor.forClass(HospioEvent.class);
        verify(events, org.mockito.Mockito.atLeast(0)).publishAndWait(eq(KafkaTopics.DELIVERY_REQUESTED), sent.capture(), any());
        return sent.getAllValues().stream().map(DeliveryRequestedEvent.class::cast).toList();
    }

    @Test
    void createsTheSuperAdminWithMustChangePasswordAndEmailsTheTemporaryPassword() {
        BootstrapAdminService service = service();

        assertThat(service.runOnStartup()).isEqualTo(BootstrapAdminService.Outcome.CREATED);

        UserAccount admin = account();
        assertThat(admin.getBusinessId()).isNull();
        assertThat(admin.isMustChangePassword()).isTrue();
        assertThat(admin.getTemporaryPasswordExpiresAt()).isAfter(Instant.now().plus(167, ChronoUnit.HOURS));
        assertThat(admin.getLastSignInAt()).isNull();
        List<RoleGrant> gs = asPlatform(() -> grants.findByUserId(admin.getId()));
        assertThat(gs).singleElement().satisfies(g -> {
            assertThat(g.getRole()).isEqualTo(Role.SUPER_ADMIN);
            assertThat(g.getBusinessId()).isNull();
            assertThat(g.getLevel()).isEqualTo(AccessLevel.FULL);
        });

        List<DeliveryRequestedEvent> mails = emails();
        assertThat(mails).singleElement().satisfies(m -> {
            assertThat(m.getKind()).isEqualTo("TEMP_PASSWORD");
            assertThat(m.getChannel()).isEqualTo("EMAIL");
            assertThat(m.getTo()).isEqualTo(EMAIL);
            assertThat(m.getText()).contains("Sign in at https://admin.hospio-qa.test").contains("must be changed on first sign-in");
        });
        String password = mails.get(0).getText().replaceAll("(?s).*Temporary password: (\\S+)\n.*", "$1");
        assertThat(password).hasSize(BootstrapAdminService.PASSWORD_LENGTH);
        assertThat(passwordEncoder.matches(password, admin.getPasswordHash())).isTrue();

        // The activity log's entry, and the email marked as handed over.
        ArgumentCaptor<HospioEvent> created = ArgumentCaptor.forClass(HospioEvent.class);
        verify(events).publish(eq(KafkaTopics.USER_CREATED), created.capture());
        assertThat(created.getValue().summary()).startsWith("Bootstrap super admin created");
        assertThat(asPlatform(() -> jdbc.queryForObject("SELECT email_queued_at IS NOT NULL FROM identity.bootstrap_admin", Boolean.class))).isTrue();

        assertThat(logs.list).anySatisfy(e -> assertThat(e.getFormattedMessage())
                .isEqualTo("Bootstrap super admin created for v***@oyeks-qa.test, temporary password emailed"));
        assertThat(logs.list).noneSatisfy(e -> assertThat(e.getFormattedMessage()).contains(password));

        // A restart: nothing new.
        assertThat(service.runOnStartup()).isEqualTo(BootstrapAdminService.Outcome.ALREADY_SENT);
        assertThat(rows()).isEqualTo(1);
        assertThat(emails()).hasSize(1);
    }

    @Test
    void skipsWhenAPlatformSuperAdminExistsEvenAPausedOne() {
        asPlatform(() -> {
            UserAccount other = users.save(UserAccount.builder().fullName("Ada Admin").email("ada@hospio-qa.test").passwordHash("x").build());
            return grants.save(RoleGrant.builder().userId(other.getId()).role(Role.SUPER_ADMIN).level(AccessLevel.FULL).paused(true).build());
        });

        assertThat(service().runOnStartup()).isEqualTo(BootstrapAdminService.Outcome.SUPER_ADMIN_EXISTS);

        assertThat(asPlatform(() -> users.findByEmailIgnoreCase(EMAIL))).isEmpty();
        assertThat(rows()).isZero();
        verify(events, never()).publishAndWait(any(), any(), any());
    }

    @Test
    void twoInstancesStartingAtOnceCreateOneAccount() throws Exception {
        int n = 4;
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        List<Future<BootstrapAdminService.Issue>> done = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            BootstrapAdminService instance = service();
            done.add(pool.submit(() -> {
                go.await();
                return instance.prepare(false);
            }));
        }
        go.countDown();
        List<BootstrapAdminService.Outcome> outcomes = new ArrayList<>();
        for (Future<BootstrapAdminService.Issue> f : done) {
            outcomes.add(f.get().outcome());
        }
        pool.shutdown();

        assertThat(outcomes).containsOnlyOnce(BootstrapAdminService.Outcome.CREATED);
        assertThat(outcomes).filteredOn(o -> o != BootstrapAdminService.Outcome.CREATED)
                .containsOnly(BootstrapAdminService.Outcome.SENDING_ELSEWHERE);
        assertThat(asPlatform(() -> users.findAll())).hasSize(1);
        assertThat(asPlatform(() -> grants.findAll())).hasSize(1);
        assertThat(rows()).isEqualTo(1);
    }

    @Test
    void anEmailThatNeverReachedKafkaIsReissuedOnTheNextStartAndTheOldPasswordStopsWorking() {
        doThrow(new IllegalStateException("DeliveryRequested not published: TimeoutException"))
                .when(events).publishAndWait(eq(KafkaTopics.DELIVERY_REQUESTED), any(), any());
        BootstrapAdminService service = service();

        assertThat(service.runOnStartup()).isEqualTo(BootstrapAdminService.Outcome.CREATED);
        verify(events, times(3)).publishAndWait(eq(KafkaTopics.DELIVERY_REQUESTED), any(), any());
        String firstHash = account().getPasswordHash();
        String firstPassword = emails().get(0).getText().replaceAll("(?s).*Temporary password: (\\S+)\n.*", "$1");
        assertThat(logs.list).anySatisfy(e -> assertThat(e.getFormattedMessage()).startsWith("Bootstrap super admin email to v***@oyeks-qa.test not sent after 3"));

        reset(events);
        assertThat(service.runOnStartup()).isEqualTo(BootstrapAdminService.Outcome.REISSUED);
        UserAccount admin = account();
        assertThat(admin.getPasswordHash()).isNotEqualTo(firstHash);
        assertThat(passwordEncoder.matches(firstPassword, admin.getPasswordHash())).isFalse();
        String second = emails().get(0).getText().replaceAll("(?s).*Temporary password: (\\S+)\n.*", "$1");
        assertThat(passwordEncoder.matches(second, admin.getPasswordHash())).isTrue();
        assertThat(admin.isMustChangePassword()).isTrue();
        assertThat(asPlatform(() -> users.findAll())).hasSize(1);
        assertThat(logs.list).noneSatisfy(e -> assertThat(e.getFormattedMessage()).contains(firstPassword));
        assertThat(logs.list).noneSatisfy(e -> assertThat(e.getFormattedMessage()).contains(second));
    }

    @Test
    void anExpiredTemporaryPasswordIsReissuedOnStartUp() {
        BootstrapAdminService service = service();
        service.runOnStartup();
        asPlatform(() -> {
            UserAccount a = users.findByEmailIgnoreCase(EMAIL).orElseThrow();
            a.setTemporaryPasswordExpiresAt(Instant.now().minus(1, ChronoUnit.HOURS));
            return users.save(a);
        });

        assertThat(service.runOnStartup()).isEqualTo(BootstrapAdminService.Outcome.REISSUED);
        assertThat(account().getTemporaryPasswordExpiresAt()).isAfter(Instant.now());
    }

    @Test
    void theManualReissueWorksBeforeTheFirstSignInAndNeverAfter() {
        BootstrapAdminService service = service();
        service.runOnStartup();

        BootstrapAdminService.Issue before = service.prepare(true);
        assertThat(before.outcome()).isEqualTo(BootstrapAdminService.Outcome.REISSUED);
        assertThat(before.generation()).isEqualTo(2);

        asPlatform(() -> {
            UserAccount a = users.findByEmailIgnoreCase(EMAIL).orElseThrow();
            a.setLastSignInAt(Instant.now());
            return users.save(a);
        });
        String hash = account().getPasswordHash();

        assertThat(service.prepare(true).outcome()).isEqualTo(BootstrapAdminService.Outcome.DONE);
        assertThat(service.runOnStartup()).isEqualTo(BootstrapAdminService.Outcome.DONE);
        assertThat(account().getPasswordHash()).isEqualTo(hash);
    }

    @Test
    void anOlderTemporaryPasswordIsNotSentOnceAnotherReplacedIt() {
        BootstrapAdminService service = service();
        BootstrapAdminService.Issue first = service.prepare(false);
        BootstrapAdminService.Issue second = service.prepare(true);
        assertThat(second.outcome()).isEqualTo(BootstrapAdminService.Outcome.REISSUED);

        assertThat(service.deliver(first)).isFalse();
        verify(events, never()).publishAndWait(any(), any(), any());
        assertThat(service.deliver(second)).isTrue();
        assertThat(emails()).hasSize(1);
    }

    @Test
    void theCreatedEventSaysBootstrap() {
        service().prepare(false);
        ArgumentCaptor<HospioEvent> created = ArgumentCaptor.forClass(HospioEvent.class);
        verify(events).publish(eq(KafkaTopics.USER_CREATED), created.capture());
        assertThat(((UserCreatedEvent) created.getValue()).getNote()).isEqualTo("Bootstrap super admin created");
    }
}
