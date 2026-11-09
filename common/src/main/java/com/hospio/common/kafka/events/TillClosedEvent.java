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
public class TillClosedEvent extends HospioEvent {
    private String tillId;
    private String role;
    private java.math.BigDecimal expectedCash;
    private java.math.BigDecimal countedCash;
    private java.math.BigDecimal variance;

    @Override
    public String summary() {
        return "Till " + (role == null ? "" : role.toLowerCase().replace('_', ' ') + " ") + "closed" + (variance == null || variance.signum() == 0 ? " · balanced" : " · variance " + variance);
    }
}
