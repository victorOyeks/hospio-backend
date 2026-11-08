package com.hospio.fnb.dto;

import com.hospio.fnb.entity.Order;
import jakarta.validation.constraints.NotBlank;
import java.util.List;

/** Start an order or sale: at a table, for a room, as an open tab or to take away, with its first lines. */
public record OrderRequest(@NotBlank String outletId, Order.Kind kind, String tableId, String roomId, String guestName, String notes,
                           List<LineRequest> lines) {
    public record LineRequest(@NotBlank String menuItemId, Integer quantity, String options, String notes) {
    }
}
