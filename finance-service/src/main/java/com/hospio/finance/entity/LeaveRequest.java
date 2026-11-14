package com.hospio.finance.entity;

import com.hospio.common.entity.TenantEntity;
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

@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "leave_requests")
public class LeaveRequest extends TenantEntity {

    public enum Status { PENDING, APPROVED, DECLINED, CHANGE_REQUESTED, WITHDRAWN }

    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;

    @Column(name = "user_name", length = 150)
    private String userName;

    @Column(name = "from_date", nullable = false)
    private LocalDate fromDate;

    @Column(name = "to_date", nullable = false)
    private LocalDate toDate;

    @Column(length = 300)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @lombok.Builder.Default
    private Status status = Status.PENDING;

    @Column(name = "decided_by", length = 36)
    private String decidedBy;

    @Column(name = "decision_note", length = 300)
    private String decisionNote;

    /** Calendar days the leave covers, both ends included. */
    public long getDays() {
        return fromDate == null || toDate == null ? 0 : java.time.temporal.ChronoUnit.DAYS.between(fromDate, toDate) + 1;
    }

    /** Not stored: what went to the staff member on this decision (returned when deliveries echo is on). */
    @jakarta.persistence.Transient
    private java.util.List<com.hospio.common.delivery.Delivery> deliveries;
}
