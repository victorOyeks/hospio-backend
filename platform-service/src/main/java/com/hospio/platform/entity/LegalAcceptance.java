package com.hospio.platform.entity;

import com.hospio.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.LocalDate;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.Immutable;

/**
 * An append-only record (a database trigger refuses UPDATE and DELETE): someone accepted a version of a legal document
 * ({@code event} ACCEPTED), or the back office noted how the links were shared with an owner it onboarded (LINKS_SHARED, not binding).
 */
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Immutable
@Table(name = "legal_acceptances")
public class LegalAcceptance extends BaseEntity {

    public static final String ACCEPTED = "ACCEPTED";
    public static final String LINKS_SHARED = "LINKS_SHARED";

    @Column(nullable = false, length = 20)
    private String event;

    /** SELF_SIGN_UP, FIRST_SIGN_IN, HANDOVER, RE_ACCEPTANCE, PORTAL, DEVICE_PACKAGE, BACK_OFFICE. */
    @Column(nullable = false, length = 30)
    private String method;

    @Column(name = "doc_key", nullable = false, length = 20)
    private String docKey;

    @Column(nullable = false, length = 40)
    private String version;

    @Column(name = "user_id", length = 36)
    private String userId;

    @Column(name = "user_name", length = 150)
    private String userName;

    @Column(name = "user_email", length = 200)
    private String userEmail;

    @Column(length = 30)
    private String role;

    @Column(name = "business_id", length = 36)
    private String businessId;

    @Column(name = "business_name", length = 150)
    private String businessName;

    @Column(name = "hotel_id", length = 36)
    private String hotelId;

    @Column(name = "hotel_name", length = 150)
    private String hotelName;

    @Column(length = 120)
    private String reference;

    @Column(length = 40)
    private String channel;

    @Column(name = "shared_on")
    private LocalDate sharedOn;

    @Column(length = 300)
    private String note;

    @Column(length = 64)
    private String ip;

    @Column(name = "user_agent", length = 400)
    private String userAgent;

    @Column(name = "recorded_by", length = 36)
    private String recordedBy;

    @Column(name = "recorded_by_name", length = 150)
    private String recordedByName;
}
