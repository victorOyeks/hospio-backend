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
public class DiscountAppliedEvent extends HospioEvent {
    private String folioId;
    private String lineId;
    private java.math.BigDecimal amount;
    private String reason;
    private String kind;

    @Override
    public String summary() {
        return kind + " of " + amount + ": " + reason;
    }
}
