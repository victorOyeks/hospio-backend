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
 * One part of a split bill: what this guest pays ({@code amount}, with its share of the tax and the service charge inside it), how
 * much of it has been taken, and for a split by item what is on it ({@code items}: JSON [{lineId, name, share}]).
 */
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "order_split_parts")
public class OrderSplitPart extends TenantEntity {

    public static final String OPEN = "OPEN";
    public static final String PAID = "PAID";

    @Column(name = "split_id", nullable = false, length = 36)
    private String splitId;

    @Column(name = "order_id", nullable = false, length = 36)
    private String orderId;

    @Column(name = "position", nullable = false)
    private int position;

    @Column(name = "label", nullable = false, length = 80)
    private String label;

    @Column(name = "amount", nullable = false)
    private BigDecimal amount;

    @Column(name = "tax", nullable = false)
    @lombok.Builder.Default
    private BigDecimal tax = BigDecimal.ZERO;

    @Column(name = "service_charge", nullable = false)
    @lombok.Builder.Default
    private BigDecimal serviceCharge = BigDecimal.ZERO;

    @Column(name = "paid_amount", nullable = false)
    @lombok.Builder.Default
    private BigDecimal paidAmount = BigDecimal.ZERO;

    @Column(name = "status", nullable = false, length = 10)
    @lombok.Builder.Default
    private String status = OPEN;

    @Column(name = "method", length = 20)
    private String method;

    @Column(name = "room_number", length = 20)
    private String roomNumber;

    @Column(name = "items", columnDefinition = "TEXT")
    private String items;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "paid_by_name", length = 150)
    private String paidByName;

    public BigDecimal due() {
        return amount.subtract(paidAmount).max(BigDecimal.ZERO);
    }
}
