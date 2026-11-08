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
public class PaymentDeclinedEvent extends HospioEvent {
    private String paymentId;
    private String folioId;
    private java.math.BigDecimal amount;
    private String method;
    private String reason;

    @Override
    public String summary() {
        return "Payment of " + amount + " by " + method + " declined: " + reason;
    }
}
