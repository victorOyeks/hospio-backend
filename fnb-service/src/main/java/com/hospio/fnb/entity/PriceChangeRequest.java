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

/** A cook or bartender without Full access asked to change a price; a manager decides. */
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "price_change_requests")
public class PriceChangeRequest extends TenantEntity {

    @Column(name = "menu_item_id", nullable = false, length = 36)
    private String menuItemId;

    @Column(name = "old_price", nullable = false)
    private BigDecimal oldPrice;

    @Column(name = "new_price", nullable = false)
    private BigDecimal newPrice;

    @Column(name = "approval_id", nullable = false, unique = true, length = 36)
    private String approvalId;

    @Column(name = "requested_by", nullable = false, length = 36)
    private String requestedBy;

    @Column(nullable = false, length = 20)
    @lombok.Builder.Default
    private String status = "PENDING";
}
