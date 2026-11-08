package com.hospio.identity.entity;

import com.hospio.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/** A platform admin viewing a business as its owner, with the reason, for a limited time. */
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "support_access_grants")
public class SupportAccessGrant extends BaseEntity {

    @Column(name = "admin_id", nullable = false, length = 36)
    private String adminId;

    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    @Column(nullable = false, length = 500)
    private String reason;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;
}
