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
public class GuestCheckedInEvent extends HospioEvent {
    private String bookingId;
    private String guestId;
    private String guestName;
    private String guestPhone;
    private String guestEmail;
    private String roomId;
    private String roomNumber;
    private java.time.LocalDate checkOutDate;
    private java.math.BigDecimal nightlyRate;
    private Integer nights;
    private String paymentPlan;
    private String organizationId;

    @Override
    public String summary() {
        return guestName + " checked in to room " + roomNumber;
    }
}
