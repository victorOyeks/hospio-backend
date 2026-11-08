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
public class BookingCancelledEvent extends HospioEvent {
    private String bookingId;
    private String guestName;
    private String reason;
    private Boolean refundable;

    @Override
    public String summary() {
        return "Booking for " + guestName + " cancelled: " + reason;
    }
}
