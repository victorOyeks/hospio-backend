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
public class PaymentRequestedEvent extends HospioEvent {
    private String paymentId;
    private String folioId;
    private String bookingId;
    private java.math.BigDecimal amount;
    private String method;
    private String role;
    private String terminalId;

    @Override
    public String summary() {
        return role + " asked for " + amount + " by " + method;
    }
}
