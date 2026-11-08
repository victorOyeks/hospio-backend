package com.hospio.tenant.entity;

import com.hospio.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/** The handover pack sent to the owner at the end of onboarding, and when they accepted it. */
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "handovers")
public class Handover extends BaseEntity {

    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    @Column(name = "pack_key")
    private String packKey;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "sent_by", length = 36)
    private String sentBy;

    @Column(name = "accepted_at")
    private Instant acceptedAt;
}
