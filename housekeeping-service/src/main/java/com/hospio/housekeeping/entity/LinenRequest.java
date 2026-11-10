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

@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "linen_requests")
public class LinenRequest extends TenantEntity {

    @Column(name = "room_id", length = 36)
    private String roomId;

    @Column(name = "room_number", length = 20)
    private String roomNumber;

    @Column(nullable = false, length = 500)
    private String items;

    @Column(name = "requested_by", length = 36)
    private String requestedBy;

    @Column(name = "requested_by_name", length = 150)
    private String requestedByName;

    /** REQUESTED until laundry marks it SENT. */
    @Column(nullable = false, length = 20)
    @lombok.Builder.Default
    private String status = "REQUESTED";

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "sent_by_name", length = 150)
    private String sentByName;
}
