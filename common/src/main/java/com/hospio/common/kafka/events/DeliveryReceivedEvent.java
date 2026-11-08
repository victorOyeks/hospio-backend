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
public class DeliveryReceivedEvent extends HospioEvent {
    private String deliveryId;
    private String purchaseOrderId;
    private String supplierName;
    private java.math.BigDecimal total;

    @Override
    public String summary() {
        return "Delivery from " + supplierName + " received";
    }
}
