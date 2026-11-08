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
public class PartsRequestedEvent extends HospioEvent {
    private String repairId;
    private String partName;
    private Integer quantity;
    private java.math.BigDecimal estimatedCost;

    @Override
    public String summary() {
        return quantity + " x " + partName + " requested";
    }
}
