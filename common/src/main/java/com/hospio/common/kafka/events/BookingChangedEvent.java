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

    @Override
    public String summary() {
        return "Booking for " + guestName + " changed: " + change;
    }
}
