package com.hospio.common.kafka.events;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/** Every live line of an order is ready; {@code openedBy} is who opened it, the person to tell. */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class OrderReadyEvent extends HospioEvent {
    private String orderId;
    private String orderNumber;
    private String outletId;
    private String outletName;
    private String outletKind;
    private String tableNo;
    private String roomNumber;
    private String openedBy;

    @Override
    public String summary() {
        return outletName + " order " + orderNumber + " is ready";
    }
}
