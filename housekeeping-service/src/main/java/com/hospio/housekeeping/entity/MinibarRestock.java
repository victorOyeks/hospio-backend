package com.hospio.housekeeping.entity;

import com.hospio.common.entity.TenantEntity;
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

/** What was taken from the minibar; charged to the folio as one line. */
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "minibar_restocks")
public class MinibarRestock extends TenantEntity {

    @Column(name = "room_id", nullable = false, length = 36)
    private String roomId;

    @Column(name = "room_number", length = 20)
    private String roomNumber;

    @Column(name = "booking_id", length = 36)
    private String bookingId;

    @Column(nullable = false, columnDefinition = "text")
    private String items;

    @Column(nullable = false)
    private BigDecimal total;

    @Column(nullable = false)
    @lombok.Builder.Default
    private boolean charged = false;

    @Column(name = "restocked_by", length = 36)
    private String restockedBy;
}
