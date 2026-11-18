package com.hospio.tenant.dto;

import com.hospio.tenant.entity.Business;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * platform-service keeps the business's fee in step with the fee rule it just saved. {@code feeAddendumRef} is the signed addendum
 * platform-service already checked and recorded (fee lock); null at the platform default.
 */
public record FeeRequest(@NotNull Business.FeeModel feeModel, @NotNull BigDecimal feeValue,
                         @jakarta.validation.constraints.Size(max = 120) String feeAddendumRef) {
}
