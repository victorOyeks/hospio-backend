package com.hospio.common.exception;

import com.hospio.common.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.MissingServletRequestParameterException;

/**
 * Without this every custom exception falls through to Spring Boot's default handling and surfaces
 * as an opaque 500 instead of the intended 4xx with a readable message in the standard envelope.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<ApiResponse<Void>> badRequest(BadRequestException e, HttpServletRequest request) {
        warn(e, request);
        return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> notFound(ResourceNotFoundException e, HttpServletRequest request) {
        warn(e, request);
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error(e.getMessage()));
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ApiResponse<Void>> conflict(ConflictException e, HttpServletRequest request) {
        warn(e, request);
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.error(e.getMessage()));
    }

    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<ApiResponse<Void>> unauthorized(UnauthorizedException e, HttpServletRequest request) {
        warn(e, request);
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResponse.error(e.getMessage()));
    }

    @ExceptionHandler({ForbiddenException.class, AccessDeniedException.class})
    public ResponseEntity<ApiResponse<Void>> forbidden(Exception e, HttpServletRequest request) {
        warn(e, request);
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error(e.getMessage()));
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiResponse<Void>> unauthenticated(AuthenticationException e, HttpServletRequest request) {
        warn(e, request);
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResponse.error("Sign in to continue"));
    }

    @ExceptionHandler(ExternalServiceException.class)
    public ResponseEntity<ApiResponse<Void>> external(ExternalServiceException e) {
        log.error("External service failure", e);
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(ApiResponse.error(e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Map<String, String>>> invalid(MethodArgumentNotValidException e, HttpServletRequest request) {
        Map<String, String> fields = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors().forEach(f -> fields.put(f.getField(), f.getDefaultMessage()));
        log.warn("{} {} -> 400 Validation failed: {}", request.getMethod(), request.getRequestURI(), fields);
        return ResponseEntity.badRequest().body(ApiResponse.error("Validation failed", fields));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> unreadable(HttpMessageNotReadableException e, HttpServletRequest request) {
        log.warn("{} {} -> 400 unreadable body: {}", request.getMethod(), request.getRequestURI(), e.getMostSpecificCause().getMessage());
        return ResponseEntity.badRequest().body(ApiResponse.error("Request body could not be read"));
    }

    @ExceptionHandler(HospioException.class)
    public ResponseEntity<ApiResponse<Void>> hospio(HospioException e, HttpServletRequest request) {
        warn(e, request);
        return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
    }

    /** A path that is not an endpoint is a 404, not a server fault. */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> noSuchEndpoint(NoResourceFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error("No such endpoint"));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> wrongMethod(HttpRequestMethodNotSupportedException e) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(ApiResponse.error("That method is not allowed here"));
    }

    @ExceptionHandler({MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiResponse<Void>> badParameter(Exception e, HttpServletRequest request) {
        warn(e, request);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.error("A required parameter is missing or has the wrong format"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> unexpected(Exception e, HttpServletRequest request) {
        log.error("{} {} -> 500 {}: {}", request.getMethod(), request.getRequestURI(), e.getClass().getSimpleName(), e.getMessage(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error("Something went wrong on our side"));
    }

    /** One line per handled error, so a 4xx can be traced in the log without the stack: what was asked, what was answered. */
    private static void warn(Exception e, HttpServletRequest request) {
        log.warn("{} {} -> {}: {}", request.getMethod(), request.getRequestURI(), e.getClass().getSimpleName(), e.getMessage());
    }
}
