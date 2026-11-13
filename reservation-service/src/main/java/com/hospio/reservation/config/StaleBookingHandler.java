package com.hospio.reservation.config;

import com.hospio.common.response.ApiResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** A booking saved from a stale copy (its version moved on) is a 409 with a plain sentence, not a 500. */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class StaleBookingHandler {

    public static final String MESSAGE = "Someone else changed this booking just now; reload and try again";

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ApiResponse<Void>> stale(ObjectOptimisticLockingFailureException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.error(MESSAGE));
    }
}
