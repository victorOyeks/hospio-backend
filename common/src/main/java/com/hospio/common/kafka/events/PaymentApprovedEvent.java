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
public class PaymentApprovedEvent extends HospioEvent {
    private String paymentId;
    private String folioId;
    private String bookingId;
    private String guestName;
    private String guestPhone;
    private String guestEmail;
    private java.math.BigDecimal amount;
    private String method;
    private String role;
    private String providerRef;
    private String receiptPreference;
    /** The kitchen or bar order this payment settles, when it is for one. */
    private String orderId;

    @Override
    public String summary() {
        return "Payment of " + amount + " by " + method + " approved";
    }
}
