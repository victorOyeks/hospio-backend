package com.hospio.platform.entity;

import com.hospio.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/**
 * A hotel, lounge, bar or restaurant asked for a demo on the landing site ("Book a demo", docs/onboarding.md). Self sign-up is off:
 * the team calls them, meets them, agrees terms and onboards the business with the back-office wizard, then links the business
 * here (ONBOARDED). Platform-only table: no business column.
 */
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "hotel_applications")
public class HotelApplication extends BaseEntity {

    public static final String NEW = "NEW";
    public static final String CONTACTED = "CONTACTED";
    public static final String MEETING_BOOKED = "MEETING_BOOKED";
    public static final String APPROVED = "APPROVED";
    public static final String ONBOARDED = "ONBOARDED";
    public static final String DECLINED = "DECLINED";
    /** In the order an application moves through them. */
    public static final List<String> STATUSES = List.of(NEW, CONTACTED, MEETING_BOOKED, APPROVED, ONBOARDED, DECLINED);

    @Column(name = "owner_name", nullable = false, length = 150)
    private String ownerName;

    @Column(nullable = false, length = 255)
    private String email;

    /** +234XXXXXXXXXX. */
    @Column(nullable = false, length = 20)
    private String phone;

    @Column(name = "business_name", nullable = false, length = 150)
    private String businessName;

    /** HOTEL, LOUNGE, BAR, RESTAURANT or MIXED. */
    @Column(name = "business_type", nullable = false, length = 20)
    private String businessType;

    @Column(nullable = false, length = 100)
    private String city;

    @Column(nullable = false, length = 60)
    private String state;

    /** Only for a hotel or a mixed business; null when not given. */
    private Integer rooms;

    /** SEARCH, SOCIAL, FRIEND, MARKETER, EVENT or OTHER. */
    @Column(name = "heard_from", nullable = false, length = 30)
    private String heardFrom;

    /** MORNING, AFTERNOON, EVENING or ANYTIME; null when not given. */
    @Column(name = "preferred_time", length = 20)
    private String preferredTime;

    @Column(length = 1000)
    private String message;

    /** The marketer's code from the link (?ref=), as sent, upper case. */
    @Column(name = "referral_code", length = 20)
    private String referralCode;

    /** The marketer the code belonged to, when they could refer. */
    @Column(name = "marketer_profile_id", length = 36)
    private String marketerProfileId;

    @Column(name = "marketer_name", length = 150)
    private String marketerName;

    @Column(nullable = false, length = 20)
    @lombok.Builder.Default
    private String status = NEW;

    @Column(name = "status_changed_at")
    private Instant statusChangedAt;

    @Column(name = "assignee_id", length = 36)
    private String assigneeId;

    @Column(name = "assignee_name", length = 150)
    private String assigneeName;

    @Column(name = "meeting_at")
    private Instant meetingAt;

    @Column(name = "decline_reason", length = 500)
    private String declineReason;

    /** The business the team onboarded from this application. */
    @Column(name = "business_id", length = 36)
    private String businessId;

    @Column(name = "business_name_linked", length = 255)
    private String linkedBusinessName;

    @Column(name = "onboarded_at")
    private Instant onboardedAt;

    @Column(length = 64)
    private String ip;

    @Column(name = "user_agent", length = 300)
    private String userAgent;

    public boolean open() {
        return !ONBOARDED.equals(status) && !DECLINED.equals(status);
    }
}
