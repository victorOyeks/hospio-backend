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

/** Every change to a level: RECEIVED, ISSUED, USED, WASTE, ADJUSTED, COUNTED. */
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "stock_movements")
public class StockMovement extends TenantEntity {

    public enum Kind { RECEIVED, ISSUED, USED, WASTE, ADJUSTED, COUNTED }

    @Column(name = "item_id", nullable = false, length = 36)
    private String itemId;

    @Column(name = "store_id", nullable = false, length = 36)
    private String storeId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Kind kind;

    @Column(nullable = false)
    private BigDecimal quantity;

    @Column(length = 80)
    private String reference;

    @Column(length = 300)
    private String note;

    @Column(name = "by_user", length = 36)
    private String byUser;
}
