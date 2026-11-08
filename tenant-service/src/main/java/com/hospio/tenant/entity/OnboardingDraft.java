package com.hospio.tenant.entity;

import com.hospio.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/** Where a business is in the platform's 10-step onboarding wizard; {@code data} is the saved form JSON. */
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "onboarding_drafts")
public class OnboardingDraft extends BaseEntity {

    public enum Status { IN_PROGRESS, READY_FOR_HANDOVER, DONE }

    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    @Column(nullable = false)
    @lombok.Builder.Default
    private int step = 1;

    @Column(columnDefinition = "text")
    private String data;

    @Column(name = "assigned_to", length = 36)
    private String assignedTo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @lombok.Builder.Default
    private Status status = Status.IN_PROGRESS;
}
