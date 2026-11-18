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

/** A tip taken with a payment for a sale: not a sale, kept against whoever served (opened the sale). One per payment. */
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "order_tips")
public class OrderTip extends TenantEntity {

    @Column(name = "order_id", nullable = false, length = 36)
    private String orderId;

    @Column(name = "outlet_id", nullable = false, length = 36)
    private String outletId;

    @Column(name = "payment_id", nullable = false, unique = true, length = 36)
    private String paymentId;

    @Column(name = "part_id", length = 36)
    private String partId;

    @Column(name = "amount", nullable = false)
    private BigDecimal amount;

    @Column(name = "method", nullable = false, length = 20)
    private String method;

    @Column(name = "staff_id", length = 36)
    private String staffId;

    @Column(name = "staff_name", length = 150)
    private String staffName;

    @Column(name = "taken_by_name", length = 150)
    private String takenByName;
}
