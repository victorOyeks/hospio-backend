package com.hospio.common.kafka.events;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/** A tab hit the hotel's tab limit and a drink was refused; the manager can raise it or settle the tab. */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class TabLimitReachedEvent extends HospioEvent {
    private String orderId;
    private String orderNumber;
    private String outletName;
    private String tableNo;
    private java.math.BigDecimal limit;
    private java.math.BigDecimal total;

    @Override
    public String summary() {
        return "Tab " + orderNumber + " at " + outletName + " reached the " + limit + " limit";
    }
}
