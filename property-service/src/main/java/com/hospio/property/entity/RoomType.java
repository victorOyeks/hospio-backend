package com.hospio.property.entity;

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

@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "room_types")
public class RoomType extends TenantEntity {

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 500)
    private String description;

    @Column(name = "base_price", nullable = false)
    private BigDecimal basePrice;

    @Column(name = "capacity_adults", nullable = false)
    @lombok.Builder.Default
    private int capacityAdults = 2;

    @Column(name = "capacity_children", nullable = false)
    @lombok.Builder.Default
    private int capacityChildren = 0;

    /** Comma-separated facility labels from Hotel setup. */
    @Column(columnDefinition = "text")
    private String facilities;

    @Column(nullable = false)
    @lombok.Builder.Default
    private boolean active = true;

    /** A manager's new base price waiting for the owner (Hotel setup → Approval limits), with its approval. */
    @Column(name = "requested_base_price")
    private BigDecimal requestedBasePrice;

    @Column(name = "price_approval_id", length = 36)
    private String priceApprovalId;

    @Column(name = "price_requested_by_name", length = 150)
    private String priceRequestedByName;

    @Column(name = "price_requested_at")
    private java.time.Instant priceRequestedAt;

    /** Not stored: true on the save that sent the price to the owner. */
    @jakarta.persistence.Transient
    private boolean priceSentToOwner;
}
