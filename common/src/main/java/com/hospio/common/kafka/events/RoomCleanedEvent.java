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
public class RoomCleanedEvent extends HospioEvent {
    private String taskId;
    private String roomId;
    private String roomNumber;
    private String cleanedBy;
    private Boolean inspected;

    @Override
    public String summary() {
        return "Room " + roomNumber + " marked clean";
    }
}
