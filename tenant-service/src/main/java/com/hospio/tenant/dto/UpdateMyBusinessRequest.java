package com.hospio.tenant.dto;

import jakarta.validation.constraints.NotBlank;

/** The owner's Company settings and sign-up step 1. Currency is not here: taxes live in Hotel setup. */
public record UpdateMyBusinessRequest(@NotBlank String name, String legalName, String registrationNumber, String businessType,
                                      String email, String phone, String address, String city, String logoUrl, String brandColour,
                                      String timezone) {
}
