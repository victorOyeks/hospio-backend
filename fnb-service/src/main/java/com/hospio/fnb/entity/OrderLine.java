package com.hospio.fnb.entity;

import com.hospio.common.entity.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "order_lines")
public class OrderLine extends TenantEntity {

    public enum Status { NEW, SENT, READY, SERVED, CANCELLED }

    @Column(name = "order_id", nullable = false, length = 36)
    private String orderId;

    @Column(name = "menu_item_id", nullable = false, length = 36)
    private String menuItemId;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "unit_price", nullable = false)
    private BigDecimal unitPrice;

    @Column(name = "tax_rate")
    private BigDecimal taxRate;

    @Column(length = 300)
    private String options;

    @Column(length = 300)
    private String notes;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @lombok.Builder.Default
    private Status status = Status.NEW;

    /**
     * Everything taken off this line (an amount): happy hour, a discount given by hand ({@code manualDiscount}) and its share of a
     * discount on the whole bill ({@code billDiscount}). Reports read this one figure.
     */
    @Column(nullable = false)
    @lombok.Builder.Default
    private BigDecimal discount = BigDecimal.ZERO;

    @Column(name = "cancel_reason", length = 300)
    private String cancelReason;

    @Column(name = "cancelled_by", length = 36)
    private String cancelledBy;

    @Column(name = "cancelled_by_name", length = 150)
    private String cancelledByName;

    @Column(name = "manual_discount", nullable = false)
    @lombok.Builder.Default
    private BigDecimal manualDiscount = BigDecimal.ZERO;

    @Column(name = "bill_discount", nullable = false)
    @lombok.Builder.Default
    private BigDecimal billDiscount = BigDecimal.ZERO;

    /** Given free: the whole line is discounted, with a reason. */
    @Column(nullable = false)
    @lombok.Builder.Default
    private boolean complimentary = false;

    @Column(name = "discount_reason", length = 300)
    private String discountReason;

    @Column(name = "discount_by", length = 36)
    private String discountBy;

    @Column(name = "discount_by_name", length = 150)
    private String discountByName;

    /** The manager who approved it, when the person who gave it had to ask. */
    @Column(name = "discount_approved_by_name", length = 150)
    private String discountApprovedByName;

    @Column(name = "discount_at")
    private java.time.Instant discountAt;

    /** What happy hour took off: the discount less what was given by hand or came from the bill's discount. */
    public BigDecimal happyHourDiscount() {
        return discount.subtract(manualDiscount).subtract(billDiscount).max(BigDecimal.ZERO);
    }

    /** Price times quantity, before any discount. */
    public BigDecimal lineTotal() {
        return unitPrice.multiply(BigDecimal.valueOf(quantity));
    }

    public boolean isLive() {
        return status != Status.CANCELLED;
    }

    /** The outlet that makes this line when it isn't the one that sold it (a kitchen ticket on a patio order); null otherwise. */
    @Column(name = "prepared_at", length = 36)
    private String preparedAt;
}
