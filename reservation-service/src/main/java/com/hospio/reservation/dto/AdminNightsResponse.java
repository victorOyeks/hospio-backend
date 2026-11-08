package com.hospio.reservation.dto;

import java.math.BigDecimal;

/** Fee statements: room nights sold and their value for a business over a date range (nights clipped to the range). */
public record AdminNightsResponse(long nights, BigDecimal value) {
}
