package com.hospio.housekeeping.entity;

import com.hospio.common.entity.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/** Soiled hotel linen sent by housekeeping to laundry, received, returned clean (or condemned), or closed short. */
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "linen_batches")
public class LinenBatch extends TenantEntity {

    public enum Status { SENT, RECEIVED, PART_RETURNED, RETURNED, CLOSED }

    @Column(nullable = false, length = 20)
    private String number;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @lombok.Builder.Default
    private Status status = Status.SENT;

    /** JSON: [{itemId, name, unit, sent, received, returned, condemned, lost, unitCost}]. */
    @Column(nullable = false, columnDefinition = "text")
    private String lines;

    @Column(length = 300)
    private String note;

    @Column(name = "sent_by", length = 36)
    private String sentBy;

    @Column(name = "sent_by_name", length = 150)
    private String sentByName;

    @Column(name = "received_at")
    private Instant receivedAt;

    @Column(name = "received_by_name", length = 150)
    private String receivedByName;

    @Column(name = "returned_at")
    private Instant returnedAt;

    @Column(nullable = false)
    @lombok.Builder.Default
    private int returns = 0;

    @Column(name = "closed_reason", length = 300)
    private String closedReason;

    @Column(name = "condemned_value", nullable = false)
    @lombok.Builder.Default
    private BigDecimal condemnedValue = BigDecimal.ZERO;

    @Column(name = "lost_value", nullable = false)
    @lombok.Builder.Default
    private BigDecimal lostValue = BigDecimal.ZERO;
}
