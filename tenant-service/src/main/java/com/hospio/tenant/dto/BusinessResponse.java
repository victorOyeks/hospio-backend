package com.hospio.tenant.dto;

import com.hospio.tenant.entity.Business;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record BusinessResponse(String id, String name, String legalName, String registrationNumber, String ownerUserId,
                               String ownerName, String ownerEmail, String ownerPhone, Business.Stage stage,
                               String accountManagerId, String marketerId, Business.FeeModel feeModel, BigDecimal feeValue,
                               String currency, String timezone, Instant suspendedAt, String suspensionReason,
                               Instant createdAt, List<HotelResponse> hotels) {
}
