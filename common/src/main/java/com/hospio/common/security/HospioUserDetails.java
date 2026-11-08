package com.hospio.common.security;

import com.hospio.common.enums.AccessLevel;
import com.hospio.common.enums.Role;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * What a verified JWT says about the caller: who they are, which business they belong to, which
 * hotels the token grants, every role they hold with its level, and the scope ({@code hotel},
 * {@code business} for owners looking across hotels, {@code platform} for H.M.S staff).
 */
@Getter
public class HospioUserDetails implements UserDetails {

    private final String userId;
    private final String email;
    private final String fullName;
    private final String businessId;
    private final List<String> hotelIds;
    private final Map<Role, AccessLevel> roles;
    private final String scope;

    public HospioUserDetails(String userId, String email, String fullName, String businessId, List<String> hotelIds,
                             Map<Role, AccessLevel> roles, String scope) {
        this.userId = userId;
        this.email = email;
        this.fullName = fullName;
        this.businessId = businessId;
        this.hotelIds = hotelIds == null ? List.of() : hotelIds;
        this.roles = roles == null ? Map.of() : roles;
        this.scope = scope;
    }

    public Set<Role> roleSet() {
        return roles.keySet();
    }

    public boolean has(Role role) {
        return roles.containsKey(role);
    }

    public boolean has(Role role, AccessLevel atLeast) {
        AccessLevel level = roles.get(role);
        return level != null && level.atLeast(atLeast);
    }

    public boolean isManagerOrOwner() {
        return has(Role.MANAGER) || has(Role.OWNER);
    }

    public boolean isPlatform() {
        return JwtUtil.SCOPE_PLATFORM.equals(scope);
    }

    public boolean isMarketer() {
        return JwtUtil.SCOPE_MARKETER.equals(scope);
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return roles.keySet().stream().map(r -> new SimpleGrantedAuthority("ROLE_" + r.name())).toList();
    }

    @Override
    public String getPassword() {
        return null;
    }

    @Override
    public String getUsername() {
        return email;
    }
}
