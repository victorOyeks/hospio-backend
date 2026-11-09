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
public class ShiftRequestDecidedEvent extends HospioEvent {
    private String requestId;
    private String staffUserId;
    private String staffName;
    private String kind;
    private java.time.LocalDate shiftDate;
    private String decision;

    @Override
    public String summary() {
        return getActorName() + " " + (decision == null ? "decided" : decision.toLowerCase()) + " " + staffName + "'s shift " + (kind == null ? "request" : kind.toLowerCase()) + " on " + shiftDate;
    }
}
