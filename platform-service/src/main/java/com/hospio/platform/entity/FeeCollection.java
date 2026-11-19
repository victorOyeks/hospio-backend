package com.hospio.platform.entity;

import com.hospio.common.entity.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/**
 * A hotel's POS pause for unpaid Hospio360 bills: the monthly invoice's restriction ({@code posPaused}) and an older device invoice's
 * ({@code devicePosPaused}); both must clear before POS payments resume. The debit provider and switch kept here until 19 Nov 2026
 * were removed with the fee debit (platform-023).
 */
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "fee_collections")
public class FeeCollection extends TenantEntity {

    @Column(name = "changed_by", length = 36)
    private String changedBy;

    @Column(name = "changed_by_name", length = 150)
    private String changedByName;

    @Column(name = "changed_at")
    private Instant changedAt;

    @Column(name = "pos_paused", nullable = false)
    @lombok.Builder.Default
    private boolean posPaused = false;

    /** POS payments paused for a device invoice 14 days overdue (separate from the daily fee's pause; both must clear to resume). */
    @Column(name = "device_pos_paused", nullable = false)
    @lombok.Builder.Default
    private boolean devicePosPaused = false;
}
