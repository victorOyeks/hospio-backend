package com.hospio.inventory.entity;

import com.hospio.common.entity.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
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
@Table(name = "deliveries")
public class Delivery extends TenantEntity {

    @Column(name = "purchase_order_id", length = 36)
    private String purchaseOrderId;

    @Column(name = "supplier_name", length = 150)
    private String supplierName;

    @Column(name = "store_id", nullable = false, length = 36)
    private String storeId;

    @Column(nullable = false, columnDefinition = "text")
    private String lines;

    @Column(nullable = false)
    @lombok.Builder.Default
    private BigDecimal total = BigDecimal.ZERO;

    @Column(length = 300)
    private String note;

    @Column(name = "received_by", length = 36)
    private String receivedBy;
}
