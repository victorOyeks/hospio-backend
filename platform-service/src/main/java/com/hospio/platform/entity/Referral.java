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

/** A hotel a marketer sent to Hospio360. It becomes a business when onboarding starts and follows that business to LIVE. */
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "referrals")
public class Referral extends BaseEntity {

    @Column(name = "profile_id", nullable = false, length = 36)
    private String profileId;

    @Column(name = "hotel_name", nullable = false, length = 150)
    private String hotelName;

    @Column(name = "owner_name", length = 150)
    private String ownerName;

    @Column(length = 20)
    private String phone;

    @Column(length = 255)
    private String email;

    @Column(length = 100)
    private String city;

    /** LEAD, CONTACTED, ONBOARDING, LIVE or LOST. */
    @Column(nullable = false, length = 20)
    @lombok.Builder.Default
    private String stage = "LEAD";

    @Column(length = 1000)
    private String note;

    @Column(name = "business_id", length = 36)
    private String businessId;

    @Column(name = "sent_at", nullable = false)
    @lombok.Builder.Default
    private Instant sentAt = Instant.now();
}
