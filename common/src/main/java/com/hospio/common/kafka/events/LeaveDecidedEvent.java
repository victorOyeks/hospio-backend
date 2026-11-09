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
public class LeaveDecidedEvent extends HospioEvent {
    private String requestId;
    private String staffUserId;
    private String staffName;
    private java.time.LocalDate fromDate;
    private java.time.LocalDate toDate;
    private String decision;
    private String note;

    @Override
    public String summary() {
        return getActorName() + " " + (decision == null ? "decided" : decision.toLowerCase().replace('_', ' ')) + " leave for " + staffName + " " + fromDate + " to " + toDate;
    }
}
