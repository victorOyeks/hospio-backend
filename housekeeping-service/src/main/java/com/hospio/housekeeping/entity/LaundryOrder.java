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

/** A guest's laundry: received → washing → ready → delivered; charged to the room or paid at the POS. */
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "laundry_orders")
public class LaundryOrder extends TenantEntity {

    public enum Status { RECEIVED, WASHING, READY, DELIVERED, CANCELLED }

    @Column(name = "booking_id", length = 36)
    private String bookingId;

    @Column(name = "guest_name", length = 150)
    private String guestName;

    @Column(name = "room_id", length = 36)
    private String roomId;

    @Column(name = "room_number", length = 20)
    private String roomNumber;

    @Column(nullable = false, columnDefinition = "text")
    private String items;

    @Column(nullable = false)
    private BigDecimal total;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @lombok.Builder.Default
    private Status status = Status.RECEIVED;

    @Column(name = "charged_to_room", nullable = false)
    @lombok.Builder.Default
    private boolean chargedToRoom = false;

    @Column(nullable = false)
    @lombok.Builder.Default
    private boolean paid = false;

    @Column(name = "payment_id", length = 36)
    private String paymentId;

    @Column(name = "pocket_note", length = 300)
    private String pocketNote;

    @Column(name = "received_by", length = 36)
    private String receivedBy;

    @Column(name = "delivered_at")
    private Instant deliveredAt;
}
