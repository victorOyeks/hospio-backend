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
public class OrderCreatedEvent extends HospioEvent {
    private String orderId;
    private String outletId;
    private String outletName;
    private String outletKind;
    private String tableNo;
    private String roomId;
    private java.math.BigDecimal total;
    private Integer itemCount;

    @Override
    public String summary() {
        return "New " + outletKind + " order of " + total + (roomId == null ? "" : " for a room");
    }
}
