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
public class ProblemReportedEvent extends HospioEvent {
    private String reportId;
    private String roomId;
    private String roomNumber;
    private String description;
    private String repairId;

    @Override
    public String summary() {
        return "Problem in room " + roomNumber + ": " + description;
    }
}
