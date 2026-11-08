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
public class RepairDoneEvent extends HospioEvent {
    private String repairId;
    private String roomId;
    private String roomNumber;
    private String title;
    private String doneBy;

    @Override
    public String summary() {
        return "Repair done: " + title;
    }
}
