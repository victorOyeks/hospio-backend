package com.hospio.common.inventory;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.util.List;

/**
 * What housekeeping (laundry) and maintenance send inventory-service's {@code POST /internal/stock/moves}: a USE, WASTE or
 * TRANSFER under a reference (idempotent), from a store kind (and to one for a transfer), with its lines. Fields left null are
 * left out.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StockMoveRequest(String reference, String kind, String fromKind, String toKind, String team, String note, String category,
                               List<Line> lines) {

    public record Line(String itemId, BigDecimal quantity) {
    }
}
