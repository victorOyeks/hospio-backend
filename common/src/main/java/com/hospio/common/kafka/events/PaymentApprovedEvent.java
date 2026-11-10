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
    /**
     * For an order: the desk whose income it is (BAR or KITCHEN, from the order's outlet), whoever took the money. Income by desk
     * uses this; {@code role} stays the payer's desk, whose till cash lands in. Null for anything that isn't an order.
     */
    private String desk;

    @Override
    public String summary() {
        return "Payment of " + amount + " by " + method + " approved";
    }
}
