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

@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "support_tickets")
public class SupportTicket extends TenantEntity {

    @Column(nullable = false, length = 200)
    private String subject;

    @Column(length = 2000)
    private String body;

    @Column(nullable = false, length = 10)
    @lombok.Builder.Default
    private String priority = "NORMAL";

    @Column(nullable = false, length = 20)
    @lombok.Builder.Default
    private String status = "OPEN";

    @Column(name = "opened_by", length = 36)
    private String openedBy;

    @Column(name = "assigned_to", length = 36)
    private String assignedTo;

    @Column(length = 2000)
    private String resolution;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "business_name", length = 150)
    private String businessName;

    @Column(name = "opened_by_name", length = 150)
    private String openedByName;

    @Column(name = "assigned_to_name", length = 150)
    private String assignedToName;

    @Column(name = "last_activity_at")
    private Instant lastActivityAt;

    public boolean isOpen() {
        return "OPEN".equals(status) || "IN_PROGRESS".equals(status);
    }

    /** What the ticket is for when the app opened it: OWNERSHIP_TRANSFER, CHANGE_UNDO; null for an ordinary ticket. */
    @Column(length = 30)
    private String category;
}
