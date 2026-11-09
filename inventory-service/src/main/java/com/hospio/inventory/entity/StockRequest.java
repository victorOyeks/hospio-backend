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

/** A team asks the store. {@code lines} is JSON: [{itemId, name, quantity}]. */
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "stock_requests")
public class StockRequest extends TenantEntity {

    public enum Status { REQUESTED, ISSUED, PART_ISSUED, DECLINED, CLOSED }

    @Column(name = "from_team", nullable = false, length = 30)
    private String fromTeam;

    @Column(name = "store_id", length = 36)
    private String storeId;

    @Column(nullable = false, columnDefinition = "text")
    private String lines;

    @Column(length = 300)
    private String note;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @lombok.Builder.Default
    private Status status = Status.REQUESTED;

    @Column(name = "requested_by", length = 36)
    private String requestedBy;

    @Column(name = "issued_by", length = 36)
    private String issuedBy;

    @Column(name = "issued_at")
    private Instant issuedAt;

    @Column(name = "requested_by_name", length = 150)
    private String requestedByName;

    @Column(name = "issued_by_name", length = 150)
    private String issuedByName;

    @Column(name = "reason", length = 300)
    private String reason;
}
