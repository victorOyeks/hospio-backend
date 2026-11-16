package com.hospio.common.kafka.events;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class BookingChangedEvent extends HospioEvent {
    private String bookingId;
    private String guestName;
    private String change;
    private String roomId;
    private String roomNumber;
    private java.time.LocalDate checkInDate;
    private java.time.LocalDate checkOutDate;
    /** The booking's status when it changed (CHECKED_IN: the folio follows the change). */
    private String status;
    /** The check-out date and stay total before the change, and the total after: an in-house change bills the difference. */
    private java.time.LocalDate previousCheckOutDate;
    private java.math.BigDecimal previousTotal;
    private java.math.BigDecimal newTotal;

    @Override
    public String summary() {
        return "Booking for " + guestName + " changed: " + change;
    }
}
