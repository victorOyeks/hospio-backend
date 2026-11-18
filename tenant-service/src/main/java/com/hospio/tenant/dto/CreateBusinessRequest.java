package com.hospio.tenant.dto;

import com.hospio.tenant.entity.Business;
import java.math.BigDecimal;

/** Platform onboarding step 1-3: business & legal, owner & contacts, fee & contract. {@code source} defaults to BACK_OFFICE.
 * {@code feeAddendumRef}: required when the fee is not the platform default (fee lock). */
public record CreateBusinessRequest(String name, String legalName, String registrationNumber, String businessType,
                                    String ownerName, String ownerEmail, String ownerPhone,
                                    Business.FeeModel feeModel, BigDecimal feeValue, String currency, String country,
                                    String timezone, String marketerId, String city, Business.Source source, String referralId,
                                    String feeAddendumRef, Business.Kind venueKind) {

    public CreateBusinessRequest(String name, String legalName, String registrationNumber, String businessType,
                                 String ownerName, String ownerEmail, String ownerPhone,
                                 Business.FeeModel feeModel, BigDecimal feeValue, String currency, String country,
                                 String timezone, String marketerId, String city, Business.Source source, String referralId,
                                 String feeAddendumRef) {
        this(name, legalName, registrationNumber, businessType, ownerName, ownerEmail, ownerPhone, feeModel, feeValue, currency, country,
                timezone, marketerId, city, source, referralId, feeAddendumRef, null);
    }
}
