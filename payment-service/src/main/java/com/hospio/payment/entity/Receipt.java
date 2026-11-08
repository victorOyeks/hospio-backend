package com.hospio.payment.entity;

import com.hospio.common.entity.TenantEntity;
import com.hospio.common.enums.PaymentMethod;
import com.hospio.common.enums.PaymentStatus;
import com.hospio.common.enums.Role;
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

@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "receipts")
public class Receipt extends TenantEntity {

    @Column(name = "payment_id", nullable = false, length = 36)
    private String paymentId;

    @Column(nullable = false, unique = true, length = 20)
    private String number;

    @Column(name = "storage_key")
    private String storageKey;

    @Column(name = "sent_to")
    private String sentTo;

    @Column(name = "sent_via", length = 20)
    private String sentVia;
}
