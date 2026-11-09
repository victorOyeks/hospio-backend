package com.hospio.maintenance.entity;

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

/** A repeating job (generator service, water tank cleaning) with a next-due date. */
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "scheduled_jobs")
public class ScheduledJob extends TenantEntity {

    @Column(nullable = false, length = 150)
    private String title;

    @Column(length = 1000)
    private String description;

    @Column(length = 100)
    private String area;

    @Column(name = "every_days", nullable = false)
    private int everyDays;

    @Column(name = "next_due", nullable = false)
    private LocalDate nextDue;

    @Column(name = "last_done")
    private LocalDate lastDone;

    @Column(name = "assigned_to", length = 36)
    private String assignedTo;

    @Column(name = "assigned_to_name", length = 150)
    private String assignedToName;

    @Column(nullable = false)
    @lombok.Builder.Default
    private boolean paused = false;

    @Column(nullable = false)
    @lombok.Builder.Default
    private boolean deleted = false;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    @lombok.Builder.Default
    private Repair.Category category = Repair.Category.OTHER;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    @lombok.Builder.Default
    private Repair.Priority priority = Repair.Priority.NORMAL;

    @Column(name = "asset_id", length = 36)
    private String assetId;

    @Column(name = "room_id", length = 36)
    private String roomId;

    @Column(name = "room_number", length = 20)
    private String roomNumber;

    /** The last due date a work order was opened for (the job never opens two for the same date). */
    @Column(name = "last_opened")
    private LocalDate lastOpened;
}
