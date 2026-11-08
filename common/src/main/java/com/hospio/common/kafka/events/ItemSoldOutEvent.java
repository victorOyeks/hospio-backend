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
public class ItemSoldOutEvent extends HospioEvent {
    private String itemId;
    private String itemName;
    private String outletName;

    @Override
    public String summary() {
        return itemName + " turned off at " + outletName;
    }
}
