package com.hospio.platform.entity;

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

/**
 * Someone asked, on the landing site, to become a referral partner (docs/plan-marketers.md). Hospio360 reviews it in
 * Marketers · Applications: approving it invites them as a marketer (the usual invite), declining it keeps the reason.
 * Platform-only table: no business column.
 */
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "partner_applications")
public class PartnerApplication extends BaseEntity {

    public static final String PENDING = "PENDING";
    public static final String APPROVED = "APPROVED";
    public static final String DECLINED = "DECLINED";

    @Column(name = "full_name", nullable = false, length = 150)
    private String fullName;

    @Column(nullable = false, length = 255)
    private String email;

    /** +234XXXXXXXXXX. */
    @Column(nullable = false, length = 20)
    private String phone;

    @Column(nullable = false, length = 100)
    private String city;

    @Column(nullable = false, length = 60)
    private String state;

    /** HOTEL, LOUNGE, BAR, RESTAURANT: the ones ticked, comma separated in that order. */
    @Column(name = "business_kinds", nullable = false, length = 100)
    private String businessKinds;

    /** 1-2, 3-5, 6-10 or 10+. */
    @Column(name = "business_count", nullable = false, length = 10)
    private String businessCount;

    @Column(name = "network_note", length = 500)
    private String networkNote;

    @Column(name = "company_name", length = 150)
    private String companyName;

    @Column(nullable = false, length = 20)
    @lombok.Builder.Default
    private String status = PENDING;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "decided_by", length = 36)
    private String decidedBy;

    @Column(name = "decided_by_name", length = 150)
    private String decidedByName;

    @Column(name = "decline_reason", length = 500)
    private String declineReason;

    /** The admin chose to give the reason to the applicant in the email. */
    @Column(name = "decline_reason_shared", nullable = false)
    private boolean declineReasonShared;

    /** The marketer profile the approval created. */
    @Column(name = "marketer_profile_id", length = 36)
    private String marketerProfileId;

    @Column(length = 64)
    private String ip;

    @Column(name = "user_agent", length = 300)
    private String userAgent;

    /** The Referral partner terms and Privacy Policy versions the form showed when the box was ticked. */
    @Column(name = "rpa_version", length = 30)
    private String rpaVersion;

    @Column(name = "privacy_version", length = 30)
    private String privacyVersion;
}
