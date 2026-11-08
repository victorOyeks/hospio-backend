package com.hospio.folio.dto;

import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;

public record OrganizationRequest(@NotBlank String name, String contactName, String contactPhone, String contactEmail, String address,
                                  BigDecimal creditLimit, BigDecimal dailyCap, String billingCycle, Integer paymentTermsDays,
                                  String agreementNotes) {
}
