package com.hospio.fnb.entity;

import com.hospio.common.entity.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/** One approved payment taken for an order. Unique on the payment id, so a payment event delivered twice counts once. */
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "order_payments")
public class OrderPayment extends TenantEntity {

    @Column(name = "order_id", nullable = false, length = 36)
    private String orderId;

    @Column(name = "payment_id", nullable = false, unique = true, length = 36)
    private String paymentId;

    /** CASH, CARD or TRANSFER. */
    @Column(nullable = false, length = 20)
    private String method;

    @Column(nullable = false)
    private BigDecimal amount;
}
