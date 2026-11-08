package com.hospio.folio.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record OrgPaymentRequest(@NotNull BigDecimal amount, @NotBlank String method, String reference, String invoiceId) {
}
