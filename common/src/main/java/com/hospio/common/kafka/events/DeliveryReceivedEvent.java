package com.hospio.common.kafka.events;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * A delivery was received into a store (with or without a purchase order). {@code total} is the value received: the good units times
 * their unit cost; damaged units are noted, never stocked or counted. finance-service turns it into one supplier bill per delivery,
 * due {@code receivedOn} plus {@code termsDays} (docs/inventory-stores.md, Supplier bills).
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class DeliveryReceivedEvent extends HospioEvent {
    private String deliveryId;
    private String purchaseOrderId;
    /** PO-0012, when the delivery was against an order. */
    private String purchaseOrderNumber;
    private String supplierId;
    private String supplierName;
    private java.math.BigDecimal total;
    private String invoiceNumber;
    /** The Lagos date it was received, yyyy-MM-dd. */
    private String receivedOn;
    /** The supplier's payment terms as written ("14 days", "Pay on delivery"). */
    private String paymentTerms;
    /** Days to pay read from the terms; null or 0 is due on the day received. */
    private Integer termsDays;

    @Override
    public String summary() {
        return "Delivery from " + supplierName + " received";
    }
}
