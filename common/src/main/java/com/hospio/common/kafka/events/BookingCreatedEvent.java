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
public class BookingCreatedEvent extends HospioEvent {
    private String bookingId;
    private String guestId;
    private String guestName;
    private String roomId;
    private String roomNumber;
    private java.time.LocalDate checkInDate;
    private java.time.LocalDate checkOutDate;
    private java.math.BigDecimal amount;
    private String paymentPlan;
    private String priceKind;
    private String organizationId;

    @Override
    public String summary() {
        return "Booking for " + guestName + " · room " + roomNumber + " · " + checkInDate + " to " + checkOutDate;
    }
}
