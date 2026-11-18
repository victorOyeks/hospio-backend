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
    private int months = 12;

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

    /** The Referral Partner Agreement version the marketer accepted in the portal, and who and when. */
    @Column(name = "agreement_version", length = 30)
    private String agreementVersion;

    @Column(name = "agreement_accepted_at")
    private Instant agreementAcceptedAt;

    @Column(name = "agreement_accepted_by", length = 36)
    private String agreementAcceptedBy;

    @Column(name = "agreement_accepted_name", length = 150)
    private String agreementAcceptedName;

    /** When the bank details were last saved; a change after the first needs a note and a fresh check by finance. */
    @Column(name = "bank_saved_at")
    private Instant bankSavedAt;

    @Column(name = "bank_change_note", length = 500)
    private String bankChangeNote;

    /** Finance checked the current bank details (cleared by every change). */
    @Column(name = "bank_checked_at")
    private Instant bankCheckedAt;

    @Column(name = "bank_checked_by_name", length = 150)
    private String bankCheckedByName;

    public boolean isActiveOn(LocalDate day) {
        return "ACTIVE".equals(status) && (endedAt == null || endedAt.isAfter(Instant.now()));
    }
}
