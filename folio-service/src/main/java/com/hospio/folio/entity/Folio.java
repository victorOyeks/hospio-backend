package com.hospio.folio.entity;

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

/** A guest's bill for one stay. Totals are kept current on every line change; balance = charges + tax - discounts - payments. */
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "folios")
public class Folio extends TenantEntity {

    public enum Status { OPEN, CLOSED, CLOSED_WITH_BALANCE }

    @Column(name = "booking_id", length = 36)
    private String bookingId;

    @Column(name = "guest_id", length = 36)
    private String guestId;

    @Column(name = "guest_name", nullable = false, length = 150)
    private String guestName;

    @Column(name = "guest_phone", length = 20)
    private String guestPhone;

    @Column(name = "guest_email")
    private String guestEmail;

    @Column(name = "room_id", length = 36)
    private String roomId;

    @Column(name = "room_number", length = 20)
    private String roomNumber;

    @Column(name = "organization_id", length = 36)
    private String organizationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @lombok.Builder.Default
    private Status status = Status.OPEN;

    @Column(name = "total_charges", nullable = false)
    @lombok.Builder.Default
    private BigDecimal totalCharges = BigDecimal.ZERO;

    @Column(name = "total_tax", nullable = false)
    @lombok.Builder.Default
    private BigDecimal totalTax = BigDecimal.ZERO;

    @Column(name = "total_discounts", nullable = false)
    @lombok.Builder.Default
    private BigDecimal totalDiscounts = BigDecimal.ZERO;

    @Column(name = "total_payments", nullable = false)
    @lombok.Builder.Default
    private BigDecimal totalPayments = BigDecimal.ZERO;

    @Column(nullable = false)
    @lombok.Builder.Default
    private BigDecimal balance = BigDecimal.ZERO;

    @Column(name = "opened_at", nullable = false)
    @lombok.Builder.Default
    private Instant openedAt = Instant.now();

    @Column(name = "closed_at")
    private Instant closedAt;

    public boolean isOpen() {
        return status == Status.OPEN;
    }
}
