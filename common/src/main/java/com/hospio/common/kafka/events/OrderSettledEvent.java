package com.hospio.common.kafka.events;

import java.math.BigDecimal;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/** An order was paid or charged to a room. Inventory draws each line's recipe from the line's store (else the outlet's). */
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

    /**
     * {@code recipe} is what one portion draws at the time of sale. {@code storeId} is the store it draws from: the preparing
     * outlet's for a dish made at another outlet; null means the order's {@code storeId} (events from before it existed).
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Line {
        private String menuItemId;
        private String name;
        private int quantity;
        private List<Ingredient> recipe;
        private String storeId;

        public Line(String menuItemId, String name, int quantity, List<Ingredient> recipe) {
            this(menuItemId, name, quantity, recipe, null);
        }
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
