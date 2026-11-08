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
public class RoomOutOfOrderEvent extends HospioEvent {
    private String roomId;
    private String roomNumber;
    private String reason;
    private java.time.LocalDate until;

    @Override
    public String summary() {
        return "Room " + roomNumber + " out of order: " + reason;
    }
}
