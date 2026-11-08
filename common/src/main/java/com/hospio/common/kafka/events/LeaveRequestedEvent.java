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
public class LeaveRequestedEvent extends HospioEvent {
    private String requestId;
    private String staffUserId;
    private String staffName;
    private java.time.LocalDate fromDate;
    private java.time.LocalDate toDate;
    private String reason;

    @Override
    public String summary() {
        return staffName + " asked for leave " + fromDate + " to " + toDate;
    }
}
