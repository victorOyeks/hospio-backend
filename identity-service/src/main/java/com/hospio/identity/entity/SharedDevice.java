package com.hospio.identity.entity;

import com.hospio.common.entity.BaseEntity;
import com.hospio.common.enums.Role;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/** A tablet or phone set up at one hotel for some roles, where staff switch person with their PIN (docs/shared-devices.md). */
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "shared_devices")
public class SharedDevice extends BaseEntity {

    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    @Column(name = "hotel_id", nullable = false, length = 36)
    private String hotelId;

    @Column(name = "hotel_name", length = 150)
    private String hotelName;

    @Column(nullable = false, length = 60)
    private String name;

    /** Comma-separated role names: the desks the device is for. */
    @Column(nullable = false, length = 300)
    private String roles;

    @Column(name = "token_hash", nullable = false, unique = true, length = 128)
    private String tokenHash;

    @Column(name = "auto_lock_minutes", nullable = false)
    @lombok.Builder.Default
    private int autoLockMinutes = 5;

    @Column(name = "created_by", length = 36)
    private String createdBy;

    @Column(name = "created_by_name", length = 150)
    private String createdByName;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    @Column(name = "last_user_id", length = 36)
    private String lastUserId;

    @Column(name = "last_user_name", length = 150)
    private String lastUserName;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "revoked_by_name", length = 150)
    private String revokedByName;

    public boolean isRevoked() {
        return revokedAt != null;
    }

    public Set<Role> roleSet() {
        Set<Role> out = EnumSet.noneOf(Role.class);
        if (roles != null) {
            Arrays.stream(roles.split(",")).map(String::trim).filter(s -> !s.isEmpty()).forEach(r -> out.add(Role.valueOf(r)));
        }
        return out;
    }

    public void setRoleSet(Collection<Role> set) {
        this.roles = set.stream().sorted().map(Enum::name).collect(Collectors.joining(","));
    }
}
