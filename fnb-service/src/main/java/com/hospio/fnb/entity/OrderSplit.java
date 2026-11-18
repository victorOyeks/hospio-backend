package com.hospio.fnb.entity;

import com.hospio.common.entity.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/**
 * A bill divided into parts (docs/plan-venue-type.md, phase 4): equally between people, by item, or by amount. {@code amount} is
 * what was divided (what the order still owed). One ACTIVE split per order; UNDONE before any part is paid, DONE when every part is.
 */
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "order_splits")
public class OrderSplit extends TenantEntity {

    public static final String ACTIVE = "ACTIVE";
    public static final String UNDONE = "UNDONE";
    public static final String DONE = "DONE";

    @Column(name = "order_id", nullable = false, length = 36)
    private String orderId;

    @Column(name = "mode", nullable = false, length = 10)
    private String mode;

    @Column(name = "status", nullable = false, length = 10)
    @lombok.Builder.Default
    private String status = ACTIVE;

    @Column(name = "amount", nullable = false)
    private BigDecimal amount;

    @Column(name = "created_by", length = 36)
    private String createdBy;

    @Column(name = "created_by_name", length = 150)
    private String createdByName;

    @Column(name = "ended_by_name", length = 150)
    private String endedByName;

    @Column(name = "ended_at")
    private Instant endedAt;
}
