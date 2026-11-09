package com.hospio.common.kafka.events;

import java.math.BigDecimal;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/** An order was paid or charged to a room. Inventory draws each line's recipe from the outlet's store. */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class OrderSettledEvent extends HospioEvent {
    private String orderId;
    private String orderNumber;
    private String outletId;
    private String storeId;
    private String settledBy;
    private List<Line> lines;

    /** {@code recipe} is the menu item's recipe at the time of sale, per portion. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Line {
        private String menuItemId;
        private String name;
        private int quantity;
        private List<Ingredient> recipe;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Ingredient {
        private String stockItemId;
        private BigDecimal quantity;
    }

    @Override
    public String summary() {
        return "Order " + orderNumber + " settled by " + settledBy;
    }
}
