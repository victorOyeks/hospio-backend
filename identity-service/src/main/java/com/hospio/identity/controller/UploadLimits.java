package com.hospio.identity.controller;

import com.hospio.common.response.ApiResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/** A photo over the multipart limit is the same sentence as one over 2 MB, not a 500. */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class UploadLimits {

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> tooBig(MaxUploadSizeExceededException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.error("The photo is too big · keep it under 2 MB"));
    }
}
