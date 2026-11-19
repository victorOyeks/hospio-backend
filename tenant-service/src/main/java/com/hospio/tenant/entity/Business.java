package com.hospio.tenant.entity;

import com.hospio.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/** A hotel business: one owner, many hotels. Stage drives the platform admin pipeline. */
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "businesses")
public class Business extends BaseEntity {

    public enum Stage { LEAD, ONBOARDING, HANDED_OVER, LIVE, AT_RISK, SUSPENDED, CHURNED }

    /** How Hospio360 earns from this business (card 8): % of guest payments, per room-night, or % of bookings. */
    public enum FeeModel { PERCENT_OF_SALES, PERCENT_OF_PAYMENTS, PER_ROOM_NIGHT, PERCENT_OF_BOOKINGS }

    /** How the business came in: the owner signed up on the hotel app, or Hospio360 onboarded it. */
    public enum Source { SELF_SERVE, BACK_OFFICE }

    /**
     * What kind of business it is, asked in both wizards (docs/plan-venue-type.md). It only sets the venue type a new location
     * starts with; MIXED (a hotel and a lounge, say) starts each one as a hotel and the type is picked per location.
     */
    public enum Kind {
        HOTEL, LOUNGE, BAR, RESTAURANT, CLUB, MIXED;

        public com.hospio.common.venue.VenueType defaultVenue() {
            return this == MIXED ? com.hospio.common.venue.VenueType.HOTEL : com.hospio.common.venue.VenueType.valueOf(name());
        }
    }

    @Column(nullable = false)
    private String name;

    @Column(name = "legal_name")
    private String legalName;

    @Column(name = "registration_number")
    private String registrationNumber;

    @Column(name = "business_type")
    private String businessType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @lombok.Builder.Default
    private Source source = Source.BACK_OFFICE;

    private String city;
    private String address;
    private String email;
    private String phone;

    @Column(name = "logo_url", length = 500)
    private String logoUrl;

    @Column(name = "brand_colour", length = 20)
    private String brandColour;

    @Column(name = "owner_user_id", length = 36)
    private String ownerUserId;

    @Column(name = "owner_name")
    private String ownerName;

    @Column(name = "owner_email")
    private String ownerEmail;

    @Column(name = "owner_phone")
    private String ownerPhone;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @lombok.Builder.Default
    private Stage stage = Stage.LEAD;

    @Column(name = "account_manager_id", length = 36)
    private String accountManagerId;

    @Column(name = "marketer_id", length = 36)
    private String marketerId;

    /** The marketer's referral (platform-service) this business came from, when any. */
    @Column(name = "referral_id", length = 36)
    private String referralId;

    @Enumerated(EnumType.STRING)
    @Column(name = "fee_model")
    private FeeModel feeModel;

    @Column(name = "fee_value")
    private BigDecimal feeValue;

    /** Fee lock: the signed addendum that allows a rate other than the platform default; null at the default. */
    @Column(name = "fee_addendum_ref", length = 120)
    private String feeAddendumRef;

    @Column(nullable = false, length = 3)
    @lombok.Builder.Default
    private String currency = "NGN";

    private String country;

    @Column(nullable = false)
    @lombok.Builder.Default
    private String timezone = "Africa/Lagos";

    @Column(name = "contract_starts_on")
    private LocalDate contractStartsOn;

    @Column(name = "contract_notes", length = 2000)
    private String contractNotes;

    @Column(name = "hotel_count", nullable = false)
    @lombok.Builder.Default
    private int hotelCount = 0;

    @Column(name = "suspended_at")
    private Instant suspendedAt;

    @Column(name = "suspension_reason", length = 500)
    private String suspensionReason;

    /** Why it is suspended (docs/suspension.md): only UNPAID_INVOICE lifts by itself when every overdue invoice is paid. */
    public enum SuspensionType { UNPAID_INVOICE, OTHER }

    @Enumerated(EnumType.STRING)
    @Column(name = "suspension_type", length = 20)
    private SuspensionType suspensionType;

    /** Whether the people at the business see {@code suspensionReason} on the suspended screen. */
    @Column(name = "suspension_show_reason", nullable = false)
    @lombok.Builder.Default
    private boolean suspensionShowReason = false;

    /** The day the 7 days' notice went to the owner (Terms 7.3), when one was sent. */
    @Column(name = "suspension_notice_sent_on")
    private LocalDate suspensionNoticeSentOn;

    /** The stage to put back on reactivation (LIVE or AT_RISK). */
    @Enumerated(EnumType.STRING)
    @Column(name = "stage_before_suspension", length = 20)
    private Stage stageBeforeSuspension;

    @Column(name = "suspended_by", length = 36)
    private String suspendedBy;

    @Column(name = "suspended_by_name", length = 150)
    private String suspendedByName;

    @Enumerated(EnumType.STRING)
    @Column(name = "venue_kind", nullable = false, length = 20)
    @lombok.Builder.Default
    private Kind venueKind = Kind.HOTEL;
}
