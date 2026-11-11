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
 * Something Hospio360 should look at: overdue fees, a failing POS, an urgent ticket, a stalled onboarding, a quiet
 * business. Raised by listeners and the nightly job, dismissed by a person or automatically when the cause ends.
 * The business may be null for platform-wide alerts, so this is not a TenantEntity.
 */
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "alerts")
public class Alert extends BaseEntity {

    public static final String FEES_OVERDUE = "FEES_OVERDUE";
    public static final String NIGHT_AUDIT_MISSED = "NIGHT_AUDIT_MISSED";
    public static final String PAYMENTS_FAILING = "PAYMENTS_FAILING";
    public static final String NO_ACTIVITY = "NO_ACTIVITY";
    public static final String TICKET_URGENT = "TICKET_URGENT";
    public static final String ONBOARDING_STALLED = "ONBOARDING_STALLED";

    @Column(name = "business_id", length = 36)
    private String businessId;

    @Column(name = "business_name", length = 150)
    private String businessName;

    @Column(name = "hotel_id", length = 36)
    private String hotelId;

    @Column(nullable = false, length = 40)
    private String kind;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(length = 1000)
    private String detail;

    /** danger, warning or grey. */
    @Column(nullable = false, length = 10)
    @lombok.Builder.Default
    private String tone = "warning";

    @Column(name = "opened_at", nullable = false)
    @lombok.Builder.Default
    private Instant openedAt = Instant.now();

    @Column(name = "dismissed_at")
    private Instant dismissedAt;

    @Column(name = "dismissed_by", length = 36)
    private String dismissedBy;

    @Column(name = "dismissed_by_name", length = 150)
    private String dismissedByName;

    public boolean isOpen() {
        return dismissedAt == null;
    }
}
