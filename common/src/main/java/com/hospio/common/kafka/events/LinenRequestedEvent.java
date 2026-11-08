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
public class LinenRequestedEvent extends HospioEvent {
    private String requestId;
    private String roomId;
    private String roomNumber;
    private String items;

    @Override
    public String summary() {
        return "Linen requested for room " + roomNumber + ": " + items;
    }
}
