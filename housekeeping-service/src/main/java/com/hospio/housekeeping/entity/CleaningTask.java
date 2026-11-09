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

/** One room to clean on one day: stay-over, departure or deep clean, then inspected by a supervisor. */
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "cleaning_tasks")
public class CleaningTask extends TenantEntity {

    public enum Kind { STAYOVER, DEPARTURE, DEEP }

    public enum Status { TODO, IN_PROGRESS, DONE, INSPECTED, SENT_BACK, SKIPPED }

    public enum Priority { NORMAL, RUSH }

    @Column(name = "room_id", nullable = false, length = 36)
    private String roomId;

    @Column(name = "room_number", length = 20)
    private String roomNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Kind kind;

    @Column(name = "due_date", nullable = false)
    private LocalDate dueDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @lombok.Builder.Default
    private Status status = Status.TODO;

    @Column(name = "assigned_to", length = 36)
    private String assignedTo;

    @Column(name = "assigned_to_name", length = 150)
    private String assignedToName;

    /** JSON of checklist item → done, from Hotel setup → Housekeeping. */
    @Column(columnDefinition = "text")
    private String checklist;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "done_at")
    private Instant doneAt;

    @Column(name = "done_by", length = 36)
    private String doneBy;

    @Column(name = "inspected_by", length = 36)
    private String inspectedBy;

    @Column(name = "inspected_at")
    private Instant inspectedAt;

    @Column(name = "inspection_note", length = 500)
    private String inspectionNote;

    @Column(name = "skip_reason", length = 300)
    private String skipReason;

    /** RUSH when the front desk needs the room sooner (a guest is waiting or arriving). */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @lombok.Builder.Default
    private Priority priority = Priority.NORMAL;

    /** Not stored: true when the room has a BOOKED arrival on the task's date (reservation-service). */
    @jakarta.persistence.Transient
    private boolean guestArrives;
}
