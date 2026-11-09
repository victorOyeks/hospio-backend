package com.hospio.finance.entity;

import com.hospio.common.entity.TenantEntity;
import com.fasterxml.jackson.annotation.JsonRawValue;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/** Closing the day: the checklist, the numbers and the report. One per hotel per business date. */
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "night_audits")
public class NightAudit extends TenantEntity {

    public enum Status { OPEN, CLOSED }

    @Column(name = "business_date", nullable = false)
    private LocalDate businessDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @lombok.Builder.Default
    private Status status = Status.OPEN;

    @Column(columnDefinition = "text")
    private String checklist;

    private BigDecimal revenue;

    @Column(name = "cash_counted")
    private BigDecimal cashCounted;

    private BigDecimal variance;

    @Column(name = "rooms_occupied")
    private Integer roomsOccupied;

    @Column(name = "report_key")
    private String reportKey;

    @Column(name = "closed_by", length = 36)
    private String closedBy;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(length = 1000)
    private String note;

    /** The closing report as JSON (revenue, payments by method, expenses, cash, occupancy), served as an object. */
    @JsonRawValue
    @Column(columnDefinition = "text")
    private String report;
}
