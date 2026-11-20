package com.hospio.identity.service;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * The fixed one-time code for dev and staging (docs/onboarding.md, "Fixed code on dev and staging"): when
 * {@code hospio.otp.fixed-code} (env {@code OTP_FIXED_CODE}) is set, every code identity makes is that code (the phone
 * code, still sent by SMS / WhatsApp so delivery stays testable) and it is accepted wherever a code is asked for (phone
 * confirmation, the 2-step sign-in step and turning 2-step on or off, in place of the authenticator app's code).
 *
 * <p>It is set only in {@code application-dev.yml} and {@code application-staging.yml} (159753) and is empty by default.
 * The guard: a fixed code with a production profile active ({@code prod}, {@code production}, {@code live}), or with none
 * of {@code dev}, {@code staging}, {@code local}, {@code test} active, stops identity-service from starting, so a copied
 * env file can never put a known code on a real server. It must be 6 digits.
 */
@Slf4j
@Component
public class FixedOtp {

    static final Set<String> ALLOWED_PROFILES = Set.of("dev", "staging", "local", "test");
    static final Set<String> PRODUCTION_PROFILES = Set.of("prod", "production", "live");

    /** No fixed code: what services use when built without Spring (unit tests). */
    public static final FixedOtp OFF = new FixedOtp((String) null);

    private final String code;

    @Autowired
    public FixedOtp(@Value("${hospio.otp.fixed-code:}") String configured, Environment environment) {
        this(check(configured, environment.getActiveProfiles()));
        if (this.code != null) {
            log.warn("Fixed one-time code is ON (hospio.otp.fixed-code, profiles {}): every code is the fixed one. Never on production",
                    Arrays.toString(environment.getActiveProfiles()));
        }
    }

    private FixedOtp(String code) {
        this.code = code;
    }

    /** The code to use, or null when off. Throws (stopping start-up) when it is set where it must not be. */
    static String check(String configured, String[] activeProfiles) {
        if (!StringUtils.hasText(configured)) {
            return null;
        }
        String code = configured.trim();
        Set<String> profiles = Arrays.stream(activeProfiles == null ? new String[0] : activeProfiles)
                .map(p -> p.trim().toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
        if (profiles.stream().anyMatch(PRODUCTION_PROFILES::contains)) {
            throw new IllegalStateException("hospio.otp.fixed-code (OTP_FIXED_CODE) is set on a production profile " + profiles
                    + ": remove it. A fixed one-time code is for dev and staging only");
        }
        if (profiles.stream().noneMatch(ALLOWED_PROFILES::contains)) {
            throw new IllegalStateException("hospio.otp.fixed-code (OTP_FIXED_CODE) is set but none of the profiles " + ALLOWED_PROFILES
                    + " is active (active: " + profiles + "): remove it, or run with the dev or staging profile");
        }
        if (!code.matches("\\d{6}")) {
            throw new IllegalStateException("hospio.otp.fixed-code (OTP_FIXED_CODE) must be 6 digits");
        }
        return code;
    }

    static FixedOtp of(String code) {
        return new FixedOtp(code);
    }

    public boolean on() {
        return code != null;
    }

    /** The fixed code when on, else null. */
    public String code() {
        return code;
    }

    /** Whether the fixed code is on and this is it (spaces ignored). */
    public boolean matches(String typed) {
        return code != null && typed != null && code.equals(typed.replaceAll("\\s", ""));
    }
}
