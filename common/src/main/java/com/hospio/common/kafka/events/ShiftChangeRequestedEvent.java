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
public class ShiftChangeRequestedEvent extends HospioEvent {
    private String requestId;
    private String staffUserId;
    private String staffName;
    private String kind;
    private java.time.LocalDate shiftDate;

    @Override
    public String summary() {
        return staffName + " asked to " + kind + " a shift on " + shiftDate;
    }
}
