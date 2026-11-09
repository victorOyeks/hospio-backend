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

/** A count sheet for a store; the results adjust the levels once approved. */
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "stock_counts")
public class StockCount extends TenantEntity {

    public enum Status { OPEN, COUNTED, APPROVED, CANCELLED }

    @Column(name = "store_id", nullable = false, length = 36)
    private String storeId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @lombok.Builder.Default
    private Status status = Status.OPEN;

    @Column(columnDefinition = "text")
    private String lines;

    @Column(name = "started_by", length = 36)
    private String startedBy;

    @Column(name = "counted_by", length = 36)
    private String countedBy;

    @Column(name = "approved_by", length = 36)
    private String approvedBy;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "note", length = 500)
    private String note;

    @Column(name = "started_by_name", length = 150)
    private String startedByName;

    @Column(name = "counted_by_name", length = 150)
    private String countedByName;

    @Column(name = "approved_by_name", length = 150)
    private String approvedByName;

    @Column(name = "variance_value")
    private BigDecimal varianceValue;

    @Column(name = "cancel_reason", length = 300)
    private String cancelReason;
}
