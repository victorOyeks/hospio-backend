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
public class CashBankedEvent extends HospioEvent {
    private java.math.BigDecimal amount;
    private String bankAccount;
    private String bankedBy;

    @Override
    public String summary() {
        return amount + " banked";
    }
}
