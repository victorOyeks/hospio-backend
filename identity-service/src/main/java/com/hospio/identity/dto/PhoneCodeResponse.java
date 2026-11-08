package com.hospio.identity.dto;

import com.hospio.common.delivery.Delivery;
import java.time.Instant;
import java.util.List;

/** When the code just sent stops working, and (echo on) the SMS that would have carried it. */
public record PhoneCodeResponse(Instant expiresAt, List<Delivery> deliveries) {
}
