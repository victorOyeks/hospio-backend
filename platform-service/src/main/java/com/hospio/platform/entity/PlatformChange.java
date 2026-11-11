package com.hospio.platform.entity;

import com.hospio.common.entity.TenantEntity;
import com.hospio.common.enums.ApprovalStatus;
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

/** A change Hospio360 made inside a business (L1-L3); the owner can undo it for 7 days (section 16). */
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "platform_changes")
public class PlatformChange extends TenantEntity {

    @Column(nullable = false, length = 60)
    private String area;

    @Column(nullable = false, length = 1000)
    private String description;

    @Column(nullable = false, length = 500)
    private String reason;

    @Column(name = "before_json", columnDefinition = "text")
    private String beforeJson;

    @Column(name = "after_json", columnDefinition = "text")
    private String afterJson;

    @Column(name = "made_by", length = 36)
    private String madeBy;

    @Column(name = "made_by_name", length = 150)
    private String madeByName;

    @Column(name = "undo_until", nullable = false)
    private Instant undoUntil;

    @Column(name = "undone_at")
    private Instant undoneAt;

    @Column(name = "undone_by", length = 36)
    private String undoneBy;

    @Column(name = "business_name", length = 150)
    private String businessName;

    public boolean isUndone() {
        return undoneAt != null;
    }

    /** The owner asked Hospio360 to undo it (a support ticket, undo_ticket_id); support puts it back and marks it undone. */
    @Column(name = "undo_requested_at")
    private Instant undoRequestedAt;

    @Column(name = "undo_requested_by_name", length = 150)
    private String undoRequestedByName;

    @Column(name = "undo_ticket_id", length = 36)
    private String undoTicketId;
}
