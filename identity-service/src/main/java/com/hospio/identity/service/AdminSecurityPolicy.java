package com.hospio.identity.service;

import com.hospio.common.enums.Role;
import com.hospio.identity.client.PlatformClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The admin portal's security settings as identity enforces them on Hospio360 staff (docs/sign-in.md, "Admin security settings"):
 * "Require 2-step sign-in for every admin" and the session length. Staff are people holding a platform role (SUPER_ADMIN, SUPPORT,
 * FINANCE_ADMIN, {@link Role#isPlatform()}); marketers and hotel accounts are never affected.
 * <p>
 * Read from platform-service and kept for {@code hospio.auth.admin-security.cache-seconds} (30). Fails safe: when platform can't
 * be read, the last value read stays in force (a setting that is on is never dropped because platform is down); before anything
 * has been read the defaults apply (2-step not required, 12 hours), as on a fresh database. A failed read is retried after
 * {@code retry-seconds} (10), not on every sign-in.
 */
@Slf4j
@Component
public class AdminSecurityPolicy {

    public static final int DEFAULT_SESSION_HOURS = 12;
    static final List<Integer> SESSION_HOURS = List.of(4, 12, 24);

    /** What is in force. */
    public record Settings(boolean require2Step, int sessionHours) {
        static final Settings DEFAULTS = new Settings(false, DEFAULT_SESSION_HOURS);
    }

    private final PlatformClient platform;
    private final Duration cacheFor;
    private final Duration retryAfter;
    private Clock clock = Clock.systemUTC();

    private volatile Settings current = Settings.DEFAULTS;
    private volatile Instant nextRead = Instant.MIN;
    private volatile boolean everRead;

    @Autowired
    public AdminSecurityPolicy(PlatformClient platform,
                               @Value("${hospio.auth.admin-security.cache-seconds:30}") long cacheSeconds,
                               @Value("${hospio.auth.admin-security.retry-seconds:10}") long retrySeconds) {
        this.platform = platform;
        this.cacheFor = Duration.ofSeconds(cacheSeconds);
        this.retryAfter = Duration.ofSeconds(retrySeconds);
    }

    AdminSecurityPolicy(PlatformClient platform, long cacheSeconds, long retrySeconds, Clock clock) {
        this(platform, cacheSeconds, retrySeconds);
        this.clock = clock;
    }

    /** Whether these roles make the person Hospio360 staff (a platform role; a marketer alone is not). */
    public static boolean isPlatformStaff(Collection<Role> roles) {
        return roles.stream().anyMatch(r -> r.isPlatform() && r != Role.SYSTEM);
    }

    public Settings settings() {
        Instant now = clock.instant();
        if (now.isBefore(nextRead)) {
            return current;
        }
        synchronized (this) {
            if (clock.instant().isBefore(nextRead)) {
                return current;
            }
            var read = platform.securitySettings();
            if (read.isPresent()) {
                Settings fresh = new Settings(Boolean.TRUE.equals(read.get().require2Step()), hours(read.get().sessionHours()));
                if (!fresh.equals(current) || !everRead) {
                    log.info("Admin security settings in force: 2-step required {}, session {} h", fresh.require2Step(), fresh.sessionHours());
                }
                current = fresh;
                everRead = true;
                nextRead = clock.instant().plus(cacheFor);
            } else {
                log.warn("Admin security settings unreadable; keeping {} (2-step required {}, session {} h)",
                        everRead ? "the last value read" : "the defaults", current.require2Step(), current.sessionHours());
                nextRead = clock.instant().plus(retryAfter);
            }
            return current;
        }
    }

    /** 4, 12 or 24; anything else (a hand-edited row) is the default 12. */
    static int hours(Integer value) {
        return value != null && SESSION_HOURS.contains(value) ? value : DEFAULT_SESSION_HOURS;
    }

    /** Whether a sign-in by someone with these roles and this 2-step state may only set up 2-step. */
    public boolean twoStepSetupOnly(Collection<Role> roles, boolean twoStepEnabled) {
        return !twoStepEnabled && isPlatformStaff(roles) && settings().require2Step();
    }

    /** The session length of a staff sign-in (hours), or empty for everyone else (the usual refresh-days). */
    public java.util.OptionalInt sessionHours(Collection<Role> roles) {
        return isPlatformStaff(roles) ? java.util.OptionalInt.of(settings().sessionHours()) : java.util.OptionalInt.empty();
    }
}
