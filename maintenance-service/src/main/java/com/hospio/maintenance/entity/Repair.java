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

    public enum Status { OPEN, ASSIGNED, IN_PROGRESS, WAITING_PARTS, DONE, CANCELLED }

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
}
