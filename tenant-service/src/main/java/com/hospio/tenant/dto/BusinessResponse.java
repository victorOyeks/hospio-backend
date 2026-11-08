package com.hospio.tenant.dto;

import com.hospio.tenant.entity.Business;
import com.hospio.tenant.entity.OnboardingDraft;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record BusinessResponse(String id, String name, String legalName, String registrationNumber, String businessType,
                               Business.Source source, String city, String address, String email, String phone, String logoUrl,
                               String brandColour, String ownerUserId, String ownerName, String ownerEmail, String ownerPhone,
                               Business.Stage stage, String accountManagerId, String marketerId, Business.FeeModel feeModel,
                               BigDecimal feeValue, String currency, String country, String timezone, LocalDate contractStartsOn,
                               String contractNotes, int hotelCount, Instant suspendedAt, String suspensionReason, Instant createdAt,
                               List<HotelResponse> hotels, Onboarding onboarding) {

    /** Where the business is in its wizard; null when it never had one. */
    public record Onboarding(OnboardingDraft.Status status, int step, List<Integer> completedSteps) {
    }
}
