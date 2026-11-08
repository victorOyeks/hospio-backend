package com.hospio.identity.entity;

import com.hospio.common.entity.BaseEntity;
import com.hospio.common.enums.AccessLevel;
import com.hospio.common.enums.Role;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/** One role at one hotel (business-wide when hotel is null, as for the owner) at one level. */
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "role_grants")
public class RoleGrant extends BaseEntity {

    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;

    @Column(name = "business_id", length = 36)
    private String businessId;

    @Column(name = "hotel_id", length = 36)
    private String hotelId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @lombok.Builder.Default
    private AccessLevel level = AccessLevel.FULL;

    @Column(nullable = false)
    @lombok.Builder.Default
    private boolean paused = false;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "granted_by", length = 36)
    private String grantedBy;

    public boolean isActive() {
        return !paused && (expiresAt == null || expiresAt.isAfter(Instant.now()));
    }
}
