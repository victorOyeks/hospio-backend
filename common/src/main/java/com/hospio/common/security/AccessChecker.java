package com.hospio.common.security;

import com.hospio.common.enums.AccessLevel;
import com.hospio.common.enums.Role;
import org.springframework.stereotype.Component;

/**
 * Level-aware checks for {@code @PreAuthorize}, e.g. {@code @PreAuthorize("@access.atLeast('FRONT_OFFICE','EDIT')")}.
 * Managers and owners pass every hotel-module check; platform staff pass everything.
 */
@Component("access")
public class AccessChecker {

    public boolean atLeast(String role, String level) {
        return CurrentUser.get().map(u -> u.isPlatform() || u.isManagerOrOwner()
                || u.has(Role.valueOf(role), AccessLevel.valueOf(level))).orElse(false);
    }

    public boolean has(String role) {
        return CurrentUser.get().map(u -> u.isPlatform() || u.has(Role.valueOf(role))).orElse(false);
    }

    public boolean manager() {
        return CurrentUser.get().map(u -> u.isPlatform() || u.isManagerOrOwner()).orElse(false);
    }

    public boolean owner() {
        return CurrentUser.get().map(u -> u.isPlatform() || u.has(Role.OWNER)).orElse(false);
    }

    public boolean platform() {
        return CurrentUser.get().map(HospioUserDetails::isPlatform).orElse(false);
    }
}
