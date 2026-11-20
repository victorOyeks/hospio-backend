package com.hospio.identity.service;

import com.hospio.common.delivery.Delivery;
import com.hospio.common.delivery.DeliveryService;
import com.hospio.common.enums.AccessLevel;
import com.hospio.common.enums.Role;
import com.hospio.common.kafka.EventPublisher;
import com.hospio.common.kafka.KafkaTopics;
import com.hospio.common.kafka.events.AccessChangedEvent;
import com.hospio.common.tenancy.Tenant;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.identity.entity.UserAccount;
import com.hospio.identity.repository.RoleGrantRepository;
import com.hospio.identity.repository.UserAccountRepository;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

/**
 * The bootstrap super admin (docs/admin-portal.md, "Bootstrap super admin"). On a fresh environment, where no platform
 * SUPER_ADMIN exists, identity-service creates one for {@code hospio.bootstrap.admin-email} (env {@code BOOTSTRAP_ADMIN_EMAIL};
 * empty switches it off) with a random temporary password that is only ever held in memory and stored as the usual hash. The
 * account is made by the same path as platform staff added in the console ({@link UserService#newLogin}): must change the
 * password on first sign-in, temporary password expiry, the same TEMP_PASSWORD email (DeliveryComposer) to the admin console.
 *
 * <p>Race-safe: the whole decision runs in one platform-scope transaction under {@code pg_advisory_xact_lock}; the
 * {@code identity.bootstrap_admin} table holds one row at most (id is always 1) and email is unique, so a second instance or a
 * restart never makes a second account.
 *
 * <p>Re-issue rule: only for the bootstrap account, only while it has never signed in and still must change its password, and
 * only while no other platform SUPER_ADMIN exists. On start-up a new temporary password replaces the old one when the old one
 * has expired, or when its email was never handed to Kafka and no instance is still trying to send it. The internal endpoint
 * {@code POST /identity/internal/bootstrap-admin/reissue} re-issues under the same conditions without waiting (e.g. the email
 * got lost after Kafka). Never after the first sign-in.
 */
@Slf4j
@Service
public class BootstrapAdminService {

    /** The advisory lock every instance takes before deciding ("HospioBA"). */
    static final long LOCK_KEY = 0x486f7370696f4241L;
    static final String FULL_NAME = "Hospio360 Admin";
    static final String CREATED_NOTE = "Bootstrap super admin created";
    /** 16 characters of the temporary-password alphabet: about 92 bits. */
    static final int PASSWORD_LENGTH = 16;
    static final Duration SEND_TIMEOUT = Duration.ofSeconds(35);
    static final Duration MAX_DELAY = Duration.ofMinutes(5);

    public enum Outcome {
        /** No email configured. */
        DISABLED,
        /** Another platform SUPER_ADMIN exists. */
        SUPER_ADMIN_EXISTS,
        /** An account (not a super admin) already uses the email; it is never promoted. */
        EMAIL_TAKEN,
        CREATED,
        REISSUED,
        /** The current temporary password's email is already with Kafka and it has not expired. */
        ALREADY_SENT,
        /** Another instance (or an earlier run) is still trying to send the current temporary password. */
        SENDING_ELSEWHERE,
        /** The bootstrap account has signed in or changed its password: nothing more to do, ever. */
        DONE
    }

    /** What {@link #prepare} decided. {@code temporaryPassword} is set only for CREATED and REISSUED and is never logged. */
    public record Issue(Outcome outcome, String userId, String fullName, String email, String temporaryPassword, Instant expiresAt,
                        int generation, Instant sendingUntil) {

        static Issue of(Outcome outcome) {
            return new Issue(outcome, null, null, null, null, null, 0, null);
        }

        public boolean issued() {
            return outcome == Outcome.CREATED || outcome == Outcome.REISSUED;
        }

        @Override
        public String toString() {
            return "Issue[" + outcome + ", " + mask(email) + ", generation " + generation + "]";
        }
    }

    /** Waits between send attempts; replaced in tests. */
    interface Sleeper {
        void sleep(Duration d) throws InterruptedException;
    }

    private record Row(String userId, int issueCount, Instant emailQueuedAt, Instant sendingUntil) {
    }

    private final UserAccountRepository users;
    private final RoleGrantRepository grants;
    private final UserService userService;
    private final DeliveryComposer deliveries;
    private final DeliveryService deliveryService;
    private final EventPublisher events;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final String email;
    private final Duration firstDelay;
    private final int maxAttempts;
    Sleeper sleeper = d -> Thread.sleep(d.toMillis());

    public BootstrapAdminService(UserAccountRepository users, RoleGrantRepository grants, UserService userService, DeliveryComposer deliveries,
                                 DeliveryService deliveryService, EventPublisher events, JdbcTemplate jdbc, PlatformTransactionManager transactionManager,
                                 @Value("${hospio.bootstrap.admin-email:}") String email,
                                 @Value("${hospio.bootstrap.retry-delay-ms:10000}") long retryDelayMs,
                                 @Value("${hospio.bootstrap.max-attempts:8}") int maxAttempts) {
        this.users = users;
        this.grants = grants;
        this.userService = userService;
        this.deliveries = deliveries;
        this.deliveryService = deliveryService;
        this.events = events;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactionManager);
        this.email = StringUtils.hasText(email) ? email.trim().toLowerCase(Locale.ROOT) : null;
        this.firstDelay = Duration.ofMillis(Math.max(0, retryDelayMs));
        this.maxAttempts = Math.max(1, maxAttempts);
    }

    public boolean enabled() {
        return email != null;
    }

    /**
     * Start-up: decide, and when a temporary password was issued, send it with bounded retries. When another instance is still
     * sending, waits once for its lease to run out and decides again. Never throws; runs off the start-up thread.
     */
    public Outcome runOnStartup() {
        try {
            Issue issue = prepare(false);
            if (issue.outcome() == Outcome.SENDING_ELSEWHERE && issue.sendingUntil() != null) {
                Duration wait = Duration.between(Instant.now(), issue.sendingUntil()).plusSeconds(5);
                if (!wait.isNegative()) {
                    sleeper.sleep(wait);
                }
                issue = prepare(false);
            }
            report(issue);
            if (issue.issued()) {
                deliver(issue);
            }
            return issue.outcome();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Outcome.SENDING_ELSEWHERE;
        } catch (RuntimeException e) {
            log.warn("Bootstrap super admin check failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Decides under the advisory lock, in one platform-scope transaction: create, re-issue, or nothing. {@code force} (the
     * internal endpoint) re-issues an eligible account even when its email went out and its password has not expired.
     */
    public Issue prepare(boolean force) {
        if (!enabled()) {
            return Issue.of(Outcome.DISABLED);
        }
        // The tenant is bound before the transaction opens its connection (as DataSeeder): the platform scope passes the policies.
        return TenantContext.runWith(Tenant.system("bootstrap-admin"), () -> tx.execute(status -> {
            jdbc.query("SELECT pg_advisory_xact_lock(?)", rs -> null, LOCK_KEY);
            Row row = readRow();
            return row == null ? create() : reissueIfDue(row, force);
        }));
    }

    private Issue create() {
        if (grants.existsByRoleAndBusinessIdIsNull(Role.SUPER_ADMIN)) {
            return Issue.of(Outcome.SUPER_ADMIN_EXISTS);
        }
        if (users.findByEmailIgnoreCase(email).isPresent()) {
            return new Issue(Outcome.EMAIL_TAKEN, null, null, email, null, null, 0, null);
        }
        String temporary = Hashing.temporaryPassword(PASSWORD_LENGTH);
        UserAccount user = userService.newLogin(null, null, FULL_NAME, email, null, null, Role.SUPER_ADMIN, AccessLevel.FULL, "system",
                temporary, CREATED_NOTE);
        users.flush();
        Instant now = Instant.now();
        Instant lease = lease(now, firstDelay);
        jdbc.update("INSERT INTO identity.bootstrap_admin (id, user_id, email, issue_count, issued_at, sending_until, created_at) "
                + "VALUES (1, ?, ?, 1, ?, ?, ?)", user.getId(), email, ts(now), ts(lease), ts(now));
        return new Issue(Outcome.CREATED, user.getId(), user.getFullName(), user.getEmail(), temporary, user.getTemporaryPasswordExpiresAt(), 1, lease);
    }

    private Issue reissueIfDue(Row row, boolean force) {
        UserAccount user = users.findById(row.userId()).orElse(null);
        if (user == null || user.getLastSignInAt() != null || !user.isMustChangePassword()) {
            return Issue.of(Outcome.DONE);
        }
        if (grants.existsByRoleAndBusinessIdIsNullAndUserIdNot(Role.SUPER_ADMIN, user.getId())) {
            return Issue.of(Outcome.SUPER_ADMIN_EXISTS);
        }
        Instant now = Instant.now();
        boolean expired = user.getTemporaryPasswordExpiresAt() != null && !user.getTemporaryPasswordExpiresAt().isAfter(now);
        if (!force && !expired) {
            if (row.emailQueuedAt() != null) {
                return new Issue(Outcome.ALREADY_SENT, user.getId(), user.getFullName(), user.getEmail(), null, user.getTemporaryPasswordExpiresAt(),
                        row.issueCount(), null);
            }
            if (row.sendingUntil() != null && row.sendingUntil().isAfter(now)) {
                return new Issue(Outcome.SENDING_ELSEWHERE, user.getId(), user.getFullName(), user.getEmail(), null, null, row.issueCount(),
                        row.sendingUntil());
            }
        }
        String temporary = Hashing.temporaryPassword(PASSWORD_LENGTH);
        Instant expires = userService.setTemporaryPassword(user, temporary);
        users.save(user);
        int generation = row.issueCount() + 1;
        Instant lease = lease(now, firstDelay);
        jdbc.update("UPDATE identity.bootstrap_admin SET issue_count = ?, issued_at = ?, email_queued_at = NULL, sending_until = ? WHERE id = 1",
                generation, ts(now), ts(lease));
        events.publish(KafkaTopics.ACCESS_CHANGED, AccessChangedEvent.builder().userId(user.getId()).userName(user.getFullName())
                .change("TEMPORARY_PASSWORD_REISSUED").role(Role.SUPER_ADMIN.name()).detail("bootstrap super admin").build());
        return new Issue(Outcome.REISSUED, user.getId(), user.getFullName(), user.getEmail(), temporary, expires, generation, lease);
    }

    /**
     * Sends the temporary password email (the TEMP_PASSWORD delivery staff creation sends) and waits for Kafka to take it, up to
     * {@code hospio.bootstrap.max-attempts} times with a doubling wait (capped at five minutes). Stops as soon as a newer
     * temporary password supersedes this one. True when Kafka acknowledged it.
     */
    public boolean deliver(Issue issue) {
        if (issue == null || !issue.issued()) {
            return false;
        }
        UserAccount to = UserAccount.builder().fullName(issue.fullName()).email(issue.email()).build();
        to.setId(issue.userId());
        Delivery email = deliveries.forLogin(to, Set.of(Role.SUPER_ADMIN), issue.temporaryPassword(), issue.expiresAt(), null, null).get(0);
        Duration delay = firstDelay;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            if (!renewLease(issue.generation(), delay)) {
                log.info("Bootstrap super admin temporary password for {} was replaced by a newer one; not sending this one", mask(issue.email()));
                return false;
            }
            if (deliveryService.sendConfirmed(email, SEND_TIMEOUT)) {
                markQueued(issue.generation());
                log.info(issue.outcome() == Outcome.CREATED
                        ? "Bootstrap super admin created for {}, temporary password emailed"
                        : "Bootstrap super admin {}: new temporary password emailed", mask(issue.email()));
                return true;
            }
            if (attempt < maxAttempts) {
                log.warn("Bootstrap super admin email to {} not handed to Kafka (attempt {} of {}); trying again in {} s",
                        mask(issue.email()), attempt, maxAttempts, delay.toSeconds());
                try {
                    sleeper.sleep(delay);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                delay = delay.multipliedBy(2).compareTo(MAX_DELAY) > 0 ? MAX_DELAY : delay.multipliedBy(2);
            }
        }
        releaseLease(issue.generation());
        log.warn("Bootstrap super admin email to {} not sent after {} attempt(s); a new temporary password is issued on the next start-up "
                + "or by POST /identity/internal/bootstrap-admin/reissue", mask(issue.email()), maxAttempts);
        return false;
    }

    private void report(Issue issue) {
        switch (issue.outcome()) {
            case DISABLED -> log.info("Bootstrap super admin off (hospio.bootstrap.admin-email is empty)");
            case SUPER_ADMIN_EXISTS -> log.info("Bootstrap super admin not needed: a platform super admin already exists");
            case EMAIL_TAKEN -> log.warn("Bootstrap super admin not created: {} already signs in without the super admin role; it is not promoted",
                    mask(issue.email()));
            case ALREADY_SENT -> log.info("Bootstrap super admin {} has not signed in yet; temporary password already emailed", mask(issue.email()));
            case SENDING_ELSEWHERE -> log.info("Bootstrap super admin {}: another instance is still sending the temporary password", mask(issue.email()));
            case DONE -> log.debug("Bootstrap super admin already signed in; nothing to do");
            case CREATED, REISSUED -> log.debug("Bootstrap super admin {} {}; sending the temporary password", mask(issue.email()), issue.outcome());
        }
    }

    // ---- the bootstrap_admin row ----------------------------------------------------------------------------------------

    private Row readRow() {
        List<Row> rows = jdbc.query("SELECT user_id, issue_count, email_queued_at, sending_until FROM identity.bootstrap_admin WHERE id = 1",
                (rs, i) -> new Row(rs.getString(1), rs.getInt(2), instant(rs.getTimestamp(3)), instant(rs.getTimestamp(4))));
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** Extends this sender's lease; false when a newer temporary password has replaced this one. */
    private boolean renewLease(int generation, Duration nextDelay) {
        return platform(() -> jdbc.update("UPDATE identity.bootstrap_admin SET sending_until = ? WHERE id = 1 AND issue_count = ?",
                ts(lease(Instant.now(), nextDelay)), generation)) > 0;
    }

    private void markQueued(int generation) {
        platform(() -> jdbc.update("UPDATE identity.bootstrap_admin SET email_queued_at = ?, sending_until = NULL WHERE id = 1 AND issue_count = ?",
                ts(Instant.now()), generation));
    }

    private void releaseLease(int generation) {
        try {
            platform(() -> jdbc.update("UPDATE identity.bootstrap_admin SET sending_until = NULL WHERE id = 1 AND issue_count = ?", generation));
        } catch (RuntimeException e) {
            log.warn("Bootstrap super admin send lease not released: {}", e.getMessage());
        }
    }

    private <T> T platform(java.util.function.Supplier<T> work) {
        return TenantContext.runWith(Tenant.system("bootstrap-admin"), () -> tx.execute(s -> work.get()));
    }

    /** How long a sender may still be trying: the wait before its next attempt, that attempt, and a minute's margin. */
    private static Instant lease(Instant now, Duration nextDelay) {
        return now.plus(nextDelay).plus(SEND_TIMEOUT).plusSeconds(60);
    }

    private static Timestamp ts(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    /** {@code victor@oyeksglobal.com} → {@code v***@oyeksglobal.com}. */
    static String mask(String address) {
        if (address == null) {
            return null;
        }
        int at = address.indexOf('@');
        if (at <= 0) {
            return "***";
        }
        return address.charAt(0) + "***" + address.substring(at);
    }
}
