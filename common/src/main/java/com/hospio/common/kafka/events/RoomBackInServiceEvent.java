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
public class RoomBackInServiceEvent extends HospioEvent {
    private String roomId;
    private String roomNumber;

    @Override
    public String summary() {
        return "Room " + roomNumber + " back in service";
    }
}
