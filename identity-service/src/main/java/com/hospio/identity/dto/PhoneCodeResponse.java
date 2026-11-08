package com.hospio.identity.dto;

import java.time.Instant;

/** When the code just sent stops working. */
public record PhoneCodeResponse(Instant expiresAt) {
}
