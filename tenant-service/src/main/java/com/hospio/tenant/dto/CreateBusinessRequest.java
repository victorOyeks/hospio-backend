package com.hospio.tenant.dto;

import com.hospio.tenant.entity.Business;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;

/** Platform onboarding step 1-3: business & legal, owner & contacts, fee & contract. {@code source} defaults to BACK_OFFICE. */
public record CreateBusinessRequest(@NotBlank String name, String legalName, String registrationNumber, String businessType,
                                    @NotBlank String ownerName, @NotBlank @Email String ownerEmail, String ownerPhone,
                                    Business.FeeModel feeModel, BigDecimal feeValue, String currency, String country,
                                    String timezone, String marketerId, String city, Business.Source source) {
}
