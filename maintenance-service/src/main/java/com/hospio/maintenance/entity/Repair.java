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

/** Something broken: reported by housekeeping, the desk or a guest; assigned, worked on, done. */
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "repairs")
public class Repair extends TenantEntity {

    public enum Priority { LOW, NORMAL, HIGH, URGENT }

    public enum Status { OPEN, ASSIGNED, IN_PROGRESS, ON_HOLD, DONE, CANCELLED }

    public enum Category { PLUMBING, ELECTRICAL, AC, CARPENTRY, APPLIANCE, PAINTING, CLEANING_EQUIPMENT, IT, OTHER }

    @Column(length = 20)
    private String number;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    @lombok.Builder.Default
    private Category category = Category.OTHER;

    @Column(name = "room_id", length = 36)
    private String roomId;

    @Column(name = "room_number", length = 20)
    private String roomNumber;

    @Column(length = 100)
    private String area;

    @Column(nullable = false, length = 150)
    private String title;

    @Column(length = 1000)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    @lombok.Builder.Default
    private Priority priority = Priority.NORMAL;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @lombok.Builder.Default
    private Status status = Status.OPEN;

    @Column(length = 30)
    private String source;

    @Column(name = "photo_key")
    private String photoKey;

    @Column(name = "reported_by", length = 36)
    private String reportedBy;

    @Column(name = "assigned_to", length = 36)
    private String assignedTo;

    @Column(name = "assigned_to_name", length = 150)
    private String assignedToName;

    @Column(name = "room_out_of_order", nullable = false)
    @lombok.Builder.Default
    private boolean roomOutOfOrder = false;

    @Column(name = "done_at")
    private Instant doneAt;

    @Column(name = "done_by", length = 36)
    private String doneBy;

    @Column(name = "reported_by_name", length = 150)
    private String reportedByName;

    @Column(name = "reported_by_role", length = 30)
    private String reportedByRole;

    @Column(name = "due_at")
    private Instant dueAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "hold_reason", length = 300)
    private String holdReason;

    @Column(name = "completion_note", length = 1000)
    private String completionNote;

    @Column(name = "labour_minutes")
    private Integer labourMinutes;

    /** JSON array of the parts drawn from the Maintenance store: [{itemId, name, unit, quantity, unitCost, value}]. */
    @Column(columnDefinition = "text")
    private String parts;

    @Column(name = "parts_cost", nullable = false)
    @lombok.Builder.Default
    private BigDecimal partsCost = BigDecimal.ZERO;

    @Column(name = "vendor_name", length = 150)
    private String vendorName;

    @Column(name = "vendor_cost", nullable = false)
    @lombok.Builder.Default
    private BigDecimal vendorCost = BigDecimal.ZERO;

    @Column(name = "reopen_count", nullable = false)
    @lombok.Builder.Default
    private int reopenCount = 0;

    @Column(name = "reopen_reason", length = 300)
    private String reopenReason;

    @Column(name = "cancel_reason", length = 300)
    private String cancelReason;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "ooo_until")
    private LocalDate oooUntil;

    @Column(name = "overdue_notified", nullable = false)
    @lombok.Builder.Default
    private boolean overdueNotified = false;

    @Column(name = "asset_id", length = 36)
    private String assetId;

    @Column(name = "schedule_id", length = 36)
    private String scheduleId;

    @Column(name = "schedule_due")
    private LocalDate scheduleDue;

    public boolean isOpen() {
        return status != Status.DONE && status != Status.CANCELLED;
    }
}
