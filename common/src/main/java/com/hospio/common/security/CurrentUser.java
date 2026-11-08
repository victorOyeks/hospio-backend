package com.hospio.common.security;

import com.hospio.common.exception.UnauthorizedException;
import java.util.Optional;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/** Reads the caller from the security context without every service repeating the cast. */
public final class CurrentUser {

    private CurrentUser() {
    }

    public static Optional<HospioUserDetails> get() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof HospioUserDetails details) {
            return Optional.of(details);
        }
        return Optional.empty();
    }

    public static HospioUserDetails require() {
        return get().orElseThrow(() -> new UnauthorizedException("Sign in to continue"));
    }
}
