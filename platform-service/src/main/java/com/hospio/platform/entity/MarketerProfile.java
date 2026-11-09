package com.hospio.platform.entity;

import com.hospio.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/**
 * The marketer as a person: their identity login (role MARKETER, no business), agreement defaults, bank
 * details and referral code. One {@link Marketer} agreement is created per business they bring live.
 * Platform-only table: no business column; marketers read their own row as the system, filtered by user id.
 */
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "marketer_profiles")
public class MarketerProfile extends BaseEntity {

    @Column(name = "user_id", length = 36)
    private String userId;

    @Column(name = "full_name", nullable = false, length = 150)
    private String fullName;

    @Column(nullable = false, length = 255)
    private String email;

    @Column(length = 20)
    private String phone;

    /** PENDING until the first sign-in, then ACTIVE; ENDED when the agreement stops. */
    @Column(nullable = false, length = 20)
    @lombok.Builder.Default
    private String status = "PENDING";

    @Column(name = "share_pct", nullable = false)
    @lombok.Builder.Default
    private BigDecimal sharePct = new BigDecimal("20");

    @Column(nullable = false)
    @lombok.Builder.Default
    private int months = 24;

    @Column(name = "bank_name", length = 100)
    private String bankName;

    @Column(name = "bank_account", length = 30)
    private String bankAccount;

    @Column(name = "bank_account_name", length = 150)
    private String bankAccountName;

    @Column(name = "joined_at")
    private Instant joinedAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    @Column(name = "referral_code", length = 20, unique = true)
    private String referralCode;

    public boolean isActiveOn(LocalDate day) {
        return "ACTIVE".equals(status) && (endedAt == null || endedAt.isAfter(Instant.now()));
    }
}
