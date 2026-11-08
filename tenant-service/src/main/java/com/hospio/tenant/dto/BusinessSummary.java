package com.hospio.tenant.dto;

import com.hospio.tenant.entity.Business;
import java.math.BigDecimal;
import java.time.Instant;

/** One row of the platform's Businesses list. */
public record BusinessSummary(String id, String name, Business.Stage stage, Business.Source source, String city, int hotels, int rooms,
                              String ownerName, String ownerEmail, String accountManagerId, String accountManagerName, String marketerId,
                              Business.FeeModel feeModel, BigDecimal feeValue, Instant createdAt, Instant lastActiveAt) {
}
