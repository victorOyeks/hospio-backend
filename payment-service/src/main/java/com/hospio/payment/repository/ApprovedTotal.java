package com.hospio.payment.repository;

import com.hospio.common.enums.PaymentMethod;
import java.math.BigDecimal;

/** Approved payments summed in the database: one row per business, hotel, method and provider (as stored), with their count. */
public record ApprovedTotal(String businessId, String hotelId, PaymentMethod method, String provider, BigDecimal amount, Long count) {
}
