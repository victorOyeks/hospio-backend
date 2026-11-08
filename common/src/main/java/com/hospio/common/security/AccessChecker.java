package com.hospio.common.security;

import com.hospio.common.enums.AccessLevel;
import com.hospio.common.enums.Role;
import org.springframework.stereotype.Component;

/**
 * Level-aware checks for {@code @PreAuthorize}, e.g. {@code @PreAuthorize("@access.atLeast('FRONT_OFFICE','EDIT')")}.
 * Managers and owners pass every hotel-module check; platform staff pass everything. A read-only token
 * ({@link HospioUserDetails#isReadOnly()}: every role VIEW, or a support-access token acting as the owner)
 * passes only VIEW checks and {@link #has(String)}; it never passes {@link #manager()}, {@link #owner()}
 * or an EDIT/FULL level.
 */
@Component("access")
public class AccessChecker {

    public boolean atLeast(String role, String level) {
        AccessLevel wanted = AccessLevel.valueOf(level);
        return CurrentUser.get().map(u -> {
            if (u.isReadOnly() && wanted != AccessLevel.VIEW) {
                return false;
            }
            return u.isPlatform() || u.isManagerOrOwner() || u.has(Role.valueOf(role), wanted);
        }).orElse(false);
    }

    public boolean has(String role) {
        return CurrentUser.get().map(u -> u.isPlatform() || u.has(Role.valueOf(role))).orElse(false);
    }

    public boolean manager() {
        return CurrentUser.get().map(u -> !u.isReadOnly() && (u.isPlatform() || u.isManagerOrOwner())).orElse(false);
    }

    public boolean owner() {
        return CurrentUser.get().map(u -> !u.isReadOnly() && (u.isPlatform() || u.has(Role.OWNER))).orElse(false);
    }

    /** The caller may look at owner- or manager-level screens: an owner, a manager, the platform, or a support-access token. */
    public boolean managerView() {
        return CurrentUser.get().map(u -> u.isPlatform() || u.isManagerOrOwner()).orElse(false);
    }

    public boolean platform() {
        return CurrentUser.get().map(HospioUserDetails::isPlatform).orElse(false);
    }
}
