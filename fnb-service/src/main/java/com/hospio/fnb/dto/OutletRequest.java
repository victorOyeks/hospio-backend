package com.hospio.fnb.dto;

import com.hospio.fnb.entity.Outlet;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** {@code storeId} is the inventory store the outlet draws from; {@code status} OPEN or CLOSED (open when omitted on a new outlet). */
public record OutletRequest(@NotBlank String name, @NotNull Outlet.Kind kind, String storeId, Outlet.Status status) {
}
