package com.hospio.identity.service;

import com.hospio.common.exception.GoneException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Whether a business may create its own account (POST /auth/sign-up). Off by default in every profile (owner decision 2026-11-21,
 * docs/onboarding.md "Applications"): businesses book a demo on the landing site and the team onboards them with the back-office
 * wizard; the owner gets the usual invite at handover. Invites (owner, staff, marketer) are not self sign-up and are never affected.
 * The sign-up code stays so it can be switched back on (SELF_SIGNUP_ENABLED=true).
 */
@Component
public class SelfSignUpSwitch {

    public static final String CLOSED_MESSAGE = "Hospio360 accounts are set up by our team — book a demo at hospio360.com/book-a-demo";

    private final boolean enabled;

    public SelfSignUpSwitch(@Value("${hospio.signup.self-serve-enabled:false}") boolean enabled) {
        this.enabled = enabled;
    }

    public boolean enabled() {
        return enabled;
    }

    /** 410 with {@link #CLOSED_MESSAGE} while self sign-up is off. */
    public void require() {
        if (!enabled) {
            throw new GoneException(CLOSED_MESSAGE);
        }
    }
}
