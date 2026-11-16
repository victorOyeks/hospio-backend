package com.hospio.payment.pos;

import com.hospio.common.entity.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/** A hotel's POS provider and its keys (encrypted; {@code masked} is what people see). See docs/pos-providers.md. */
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "hotel_pos_configs")
public class HotelPosConfig extends TenantEntity {

    @Column(length = 20)
    private String provider;

    @Column(columnDefinition = "TEXT")
    private String secrets;

    @Column(columnDefinition = "TEXT")
    private String masked;

    @Column(name = "pos_paused", nullable = false)
    @lombok.Builder.Default
    private boolean posPaused = false;

    @Column(name = "updated_by_name", length = 150)
    private String updatedByName;
}
