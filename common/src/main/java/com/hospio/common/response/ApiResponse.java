package com.hospio.common.response;

import java.time.Instant;
import java.util.UUID;

/**
 * The envelope every endpoint returns. Field names match what hospio-frontend already parses
 * (requestTime, requestType, referenceId, status, message, data), so the web app keeps working
 * while it is redesigned.
 */
public record ApiResponse<T>(Instant requestTime, String requestType, String referenceId, boolean status,
                             String message, T data) {

    public static <T> ApiResponse<T> ok(T data) {
        return ok("OK", data);
    }

    public static <T> ApiResponse<T> ok(String message, T data) {
        return new ApiResponse<>(Instant.now(), "API", UUID.randomUUID().toString(), true, message, data);
    }

    public static <T> ApiResponse<T> error(String message) {
        return new ApiResponse<>(Instant.now(), "API", UUID.randomUUID().toString(), false, message, null);
    }

    public static <T> ApiResponse<T> error(String message, T data) {
        return new ApiResponse<>(Instant.now(), "API", UUID.randomUUID().toString(), false, message, data);
    }
}
