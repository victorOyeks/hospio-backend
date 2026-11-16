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
 * How a hotel's daily fee is collected: the provider it is debited from (MONIEPOINT or OPAY) and whether the debit is on. Only a
 * SUPER_ADMIN switches it on, and only when that provider's debit is configured on the server. While off the hotel is in shadow mode.
 */
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "fee_collections")
public class FeeCollection extends TenantEntity {

    @Column(length = 20)
    private String provider;

    @Column(name = "debit_enabled", nullable = false)
    @lombok.Builder.Default
    private boolean debitEnabled = false;

    @Column(name = "changed_by", length = 36)
    private String changedBy;

    @Column(name = "changed_by_name", length = 150)
    private String changedByName;

    @Column(name = "changed_at")
    private Instant changedAt;

    @Column(name = "pos_paused", nullable = false)
    @lombok.Builder.Default
    private boolean posPaused = false;
}
