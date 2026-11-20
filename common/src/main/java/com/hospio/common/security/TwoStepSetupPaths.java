package com.hospio.common.security;

import java.util.Set;

/**
 * What a platform staff session limited to setting up 2-step sign-in may call ("Require 2-step sign-in for every admin",
 * hospio-backend docs/sign-in.md): its own account and 2-step status, the 2-step setup itself, a first-password change (the
 * bootstrap super admin), the hand-over to the admin app and signing out. Everything else, in every service, is refused with
 * 403 {@link #MESSAGE} until 2-step is on and the session has been renewed.
 */
public final class TwoStepSetupPaths {

    public static final String MESSAGE = "Set up 2-step sign-in to continue. Hospio360 requires it for every admin";

    private static final Set<String> EXACT = Set.of(
            "/identity/users/me",
            "/identity/users/me/two-step",
            "/identity/users/me/two-step/setup",
            "/identity/users/me/two-step/enable",
            "/identity/auth/refresh",
            "/identity/auth/logout",
            "/identity/auth/logout-everywhere",
            "/identity/auth/change-password",
            "/identity/auth/handoff");

    private TwoStepSetupPaths() {
    }

    /** {@code uri}: the request URI with the service's context path (e.g. {@code /identity/users/me}). */
    public static boolean allows(String uri) {
        if (uri == null) {
            return false;
        }
        String path = uri.length() > 1 && uri.endsWith("/") ? uri.substring(0, uri.length() - 1) : uri;
        return EXACT.contains(path);
    }
}
