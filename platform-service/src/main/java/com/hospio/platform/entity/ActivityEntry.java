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

/** One line in the activity log: who did what, where, when, and the event that proves it. */
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "activity_log")
public class ActivityEntry extends TenantEntity {

    @Column(name = "event_id", unique = true, length = 36)
    private String eventId;

    @Column(name = "event_type", nullable = false, length = 60)
    private String eventType;

    @Column(length = 80)
    private String topic;

    @Column(name = "actor_id", length = 36)
    private String actorId;

    @Column(name = "actor_name", length = 150)
    private String actorName;

    @Column(nullable = false, length = 500)
    private String summary;

    @Column(columnDefinition = "text")
    private String payload;

    @Column(name = "occurred_at", nullable = false)
    @lombok.Builder.Default
    private Instant occurredAt = Instant.now();
}
