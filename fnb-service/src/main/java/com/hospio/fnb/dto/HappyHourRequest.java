package com.hospio.fnb.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalTime;

public record HappyHourRequest(@NotBlank String name, @NotNull LocalTime fromTime, @NotNull LocalTime toTime, @NotBlank String days,
                               @NotNull BigDecimal discountPct, Boolean active) {
}
