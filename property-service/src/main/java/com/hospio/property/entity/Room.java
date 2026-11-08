package com.hospio.property.entity;

import com.hospio.common.entity.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.LocalDate;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/** A physical room. Occupancy lives in reservation-service and cleanliness in housekeeping-service. */
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "rooms")
public class Room extends TenantEntity {

    public enum Status { IN_SERVICE, OUT_OF_ORDER }

    @Column(name = "room_type_id", nullable = false, length = 36)
    private String roomTypeId;

    @Column(name = "area_id", length = 36)
    private String areaId;

    @Column(nullable = false, length = 20)
    private String number;

    @Column(length = 20)
    private String floor;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @lombok.Builder.Default
    private Status status = Status.IN_SERVICE;

    @Column(name = "out_of_order_reason", length = 300)
    private String outOfOrderReason;

    @Column(name = "out_of_order_until")
    private LocalDate outOfOrderUntil;

    @Column(length = 500)
    private String notes;
}
