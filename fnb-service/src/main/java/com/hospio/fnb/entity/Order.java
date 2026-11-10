package com.hospio.fnb.entity;

import com.hospio.common.entity.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/** A kitchen order, bar sale or open tab. Charged to a room, paid at the POS, or still open. */
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "orders")
public class Order extends TenantEntity {

    public enum Kind { DINE_IN, ROOM_SERVICE, TAB, TAKEAWAY }

    public enum Status { OPEN, SENT, READY, SERVED, CLOSED, CANCELLED }

    @Column(name = "outlet_id", nullable = false, length = 36)
    private String outletId;

    @Column(nullable = false, length = 12)
    private String number;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @lombok.Builder.Default
    private Kind kind = Kind.DINE_IN;

    @Column(name = "table_id", length = 36)
    private String tableId;

    @Column(name = "table_no", length = 20)
    private String tableNo;

    @Column(name = "room_id", length = 36)
    private String roomId;

    @Column(name = "room_number", length = 20)
    private String roomNumber;

    @Column(name = "booking_id", length = 36)
    private String bookingId;

    @Column(name = "guest_name", length = 150)
    private String guestName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @lombok.Builder.Default
    private Status status = Status.OPEN;

    @Column(nullable = false)
    @lombok.Builder.Default
    private BigDecimal subtotal = BigDecimal.ZERO;

    @Column(nullable = false)
    @lombok.Builder.Default
    private BigDecimal tax = BigDecimal.ZERO;

    @Column(nullable = false)
    @lombok.Builder.Default
    private BigDecimal discount = BigDecimal.ZERO;

    @Column(nullable = false)
    @lombok.Builder.Default
    private BigDecimal total = BigDecimal.ZERO;

    @Column(name = "charged_to_room", nullable = false)
    @lombok.Builder.Default
    private boolean chargedToRoom = false;

    @Column(nullable = false)
    @lombok.Builder.Default
    private boolean paid = false;

    @Column(name = "payment_id", length = 36)
    private String paymentId;

    @Column(name = "opened_by", length = 36)
    private String openedBy;

    @Column(name = "opened_by_name", length = 150)
    private String openedByName;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(length = 500)
    private String notes;

    @Column(name = "closed_by", length = 36)
    private String closedBy;

    @Column(name = "closed_by_name", length = 150)
    private String closedByName;

    /** PAYMENT, ROOM, COMPLIMENTARY or CANCELLED: how the order ended. */
    @Column(name = "settled_by", length = 20)
    private String settledBy;

    @Column(name = "settled_at")
    private Instant settledAt;

    /** Who settled it: took the last payment, charged the room or closed it as complimentary. */
    @Column(name = "settled_by_user", length = 36)
    private String settledByUser;

    @Column(name = "settled_by_name", length = 150)
    private String settledByName;

    @Column(name = "cancel_reason", length = 300)
    private String cancelReason;

    /** Approved payments received so far; the order is paid when this reaches the total. */
    @Column(name = "paid_amount", nullable = false)
    @lombok.Builder.Default
    private BigDecimal paidAmount = BigDecimal.ZERO;

    public boolean isSettled() {
        return paid || chargedToRoom;
    }
}
