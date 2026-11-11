package com.hospio.payment.entity;

import com.hospio.common.entity.TenantEntity;
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
 * One business × provider × day of approved card and transfer payments, with the provider's fee and Hospio360's
 * share taken off: what the business is owed. PENDING until it joins a payout batch, SETTLED once paid, FAILED
 * when the bank bounced it. Cash never settles. {@code hotelId} is set when the day's payments came from one hotel.
 */
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "settlements")
public class Settlement extends TenantEntity {

    public enum Status { PENDING, SETTLED, FAILED }

    @Column(name = "business_name", length = 150)
    private String businessName;

    @Column(nullable = false, length = 40)
    private String provider;

    @Column(name = "period_date", nullable = false)
    private LocalDate periodDate;

    @Column(nullable = false)
    private BigDecimal gross;

    @Column(name = "provider_fees", nullable = false)
    private BigDecimal providerFees;

    @Column(name = "hms_share", nullable = false)
    private BigDecimal hmsShare;

    @Column(nullable = false)
    private BigDecimal net;

    @Column(name = "payment_count", nullable = false)
    private int paymentCount;

    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    @Column(nullable = false, length = 20)
    @lombok.Builder.Default
    private Status status = Status.PENDING;

    @Column(name = "settled_at")
    private Instant settledAt;

    @Column(name = "payout_batch_id", length = 36)
    private String payoutBatchId;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;
}
