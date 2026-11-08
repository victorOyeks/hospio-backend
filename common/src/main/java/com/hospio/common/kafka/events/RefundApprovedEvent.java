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
public class RefundApprovedEvent extends HospioEvent {
    private String refundId;
    private String paymentId;
    private String folioId;
    private String bookingId;
    private java.math.BigDecimal amount;
    private String approvedBy;

    @Override
    public String summary() {
        return "Refund of " + amount + " approved";
    }
}
