package com.hospio.folio.config;

import com.hospio.common.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * A save from a stale copy (the @Version backstop behind the row locks) is a 409 the user can retry, not a 500. Ordered ahead of the
 * shared handler, whose catch-all would otherwise answer it.
 */
@Slf4j
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ConcurrentChangeHandler {

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ApiResponse<Void>> concurrentChange(ObjectOptimisticLockingFailureException e, HttpServletRequest request) {
        log.warn("{} {} -> 409 concurrent change: {}", request.getMethod(), request.getRequestURI(), e.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.error("Someone else changed this just now; reload and try again"));
    }
}
