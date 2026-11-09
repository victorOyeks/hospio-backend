package com.hospio.inventory.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Stock moved on behalf of another service (maintenance parts on a work order, linen to and from the laundry, condemned linen):
 * {@code POST /internal/stock/moves}. Stores are named by kind: the hotel's first active store of that kind.
 */
public final class StockMoves {

    private StockMoves() {
    }

    public enum Kind { USE, WASTE, TRANSFER }

    public record Line(String itemId, BigDecimal quantity) {
    }

    public record Request(String reference, Kind kind, String fromKind, String toKind, String team, String note, List<Line> lines,
                          String category) {
    }

    public record MovedLine(String itemId, String name, String unit, BigDecimal quantity, BigDecimal unitCost, BigDecimal value) {
    }

    public record Result(String reference, String fromStoreId, String fromStore, String toStoreId, String toStore, List<MovedLine> lines,
                         BigDecimal value, boolean repeated) {
    }
}
