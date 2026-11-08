package com.hospio.fnb.dto;

import com.hospio.fnb.entity.Outlet;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record OutletRequest(@NotBlank String name, @NotNull Outlet.Kind kind) {
}
