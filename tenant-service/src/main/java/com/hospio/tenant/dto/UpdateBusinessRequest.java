package com.hospio.tenant.dto;

import com.hospio.tenant.entity.Business;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Platform edit of a business: profile, owner contacts, fee model and value, contract. A field left
 * null is left as it is, so the wizard can save one step at a time.
 */
public record UpdateBusinessRequest(@NotBlank String name, String legalName, String registrationNumber, String businessType,
                                    String email, String phone, String address, String city, String logoUrl, String brandColour,
                                    String ownerName, @Email String ownerEmail, String ownerPhone,
                                    Business.FeeModel feeModel, BigDecimal feeValue, String currency, String country, String timezone,
                                    LocalDate contractStartsOn, String contractNotes, String marketerId, String accountManagerId,
                                    Business.Source source) {
}
