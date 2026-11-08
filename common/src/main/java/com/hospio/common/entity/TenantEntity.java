package com.hospio.common.entity;

import com.hospio.common.tenancy.TenantContext;
import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/**
 * Every row carries business_id and hotel_id. They default from the current tenant so services never
 * pass them by hand, and the row-level policies in Postgres enforce them on every read and write.
 */
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@MappedSuperclass
public abstract class TenantEntity extends BaseEntity {

    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    @Column(name = "hotel_id", length = 36)
    private String hotelId;

    @PrePersist
    void defaultTenant() {
        TenantContext.current().ifPresent(t -> {
            if (businessId == null) {
                businessId = t.businessId();
            }
            if (hotelId == null) {
                hotelId = t.hotelId();
            }
        });
    }
}
