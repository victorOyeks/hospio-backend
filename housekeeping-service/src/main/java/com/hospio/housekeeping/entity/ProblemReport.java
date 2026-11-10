package com.hospio.housekeeping.entity;

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
@Table(name = "problem_reports")
public class ProblemReport extends TenantEntity {

    @Column(name = "room_id", length = 36)
    private String roomId;

    @Column(name = "room_number", length = 20)
    private String roomNumber;

    @Column(nullable = false, length = 500)
    private String description;

    @Column(name = "photo_key")
    private String photoKey;

    @Column(name = "repair_id", length = 36)
    private String repairId;

    @Column(name = "reported_by", length = 36)
    private String reportedBy;

    @Column(nullable = false, length = 20)
    @lombok.Builder.Default
    private String status = "SENT";

    /** LOW, NORMAL or URGENT, as the reporter chose ("Can wait", "Today", "Now"). */
    @Column(length = 20)
    private String priority;

    /** True when the reporter took the room out of order with the report. */
    @Column(name = "out_of_order", nullable = false)
    private boolean outOfOrder;
}
