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
public class GuestCheckedOutEvent extends HospioEvent {
    private String bookingId;
    private String guestId;
    private String guestName;
    private String guestPhone;
    private String guestEmail;
    private String roomId;
    private String roomNumber;
    private java.math.BigDecimal folioBalance;
    private java.util.List<String> summaryChannels;

    @Override
    public String summary() {
        return guestName + " checked out of room " + roomNumber;
    }
}
