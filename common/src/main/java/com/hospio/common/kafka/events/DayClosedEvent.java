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
public class DayClosedEvent extends HospioEvent {
    private String auditId;
    private java.time.LocalDate businessDate;
    private java.math.BigDecimal revenue;
    private java.math.BigDecimal cashCounted;
    private java.math.BigDecimal variance;
    private String closedBy;

    @Override
    public String summary() {
        return "Day " + businessDate + " closed · revenue " + revenue;
    }
}
