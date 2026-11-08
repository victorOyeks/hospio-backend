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
public class StockLowEvent extends HospioEvent {
    private String itemId;
    private String itemName;
    private String storeId;
    private String storeName;
    private java.math.BigDecimal quantity;
    private java.math.BigDecimal minLevel;
    private String unit;

    @Override
    public String summary() {
        return itemName + " is low: " + quantity + " " + unit + " left";
    }
}
