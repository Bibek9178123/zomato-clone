package com.zomato.exception;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    public record ApiError(LocalDateTime timestamp, int status, String error, String message, String path) {}

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(ResourceNotFoundException ex, HttpServletRequest req) {
        log.warn("Resource not found: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiError(LocalDateTime.now(), 404, "Not Found", ex.getMessage(), req.getRequestURI()));
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiError> handleBusiness(BusinessException ex, HttpServletRequest req) {
        log.warn("Business exception: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiError(LocalDateTime.now(), 400, "Bad Request", ex.getMessage(), req.getRequestURI()));
    }

    @ExceptionHandler(PaymentException.class)
    public ResponseEntity<ApiError> handlePayment(PaymentException ex, HttpServletRequest req) {
        log.error("Payment exception: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED)
                .body(new ApiError(LocalDateTime.now(), 402, "Payment Required", ex.getMessage(), req.getRequestURI()));
    }

    @ExceptionHandler({org.springframework.security.core.AuthenticationException.class, org.springframework.security.authentication.BadCredentialsException.class, org.springframework.security.core.userdetails.UsernameNotFoundException.class})
    public ResponseEntity<ApiError> handleAuthException(Exception ex, HttpServletRequest req) {
        log.warn("Authentication failed: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new ApiError(LocalDateTime.now(), 401, "Unauthorized", "Invalid email or password", req.getRequestURI()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest req) {
        Map<String, String> fieldErrors = new HashMap<>();
        String firstMessage = "Validation failed";
        for (org.springframework.validation.ObjectError error : ex.getBindingResult().getAllErrors()) {
            if (error instanceof FieldError fe) {
                fieldErrors.put(fe.getField(), error.getDefaultMessage());
                firstMessage = error.getDefaultMessage();
            }
        }
        Map<String, Object> response = new HashMap<>();
        response.put("timestamp", LocalDateTime.now());
        response.put("status", 400);
        response.put("error", "Validation Failed");
        response.put("message", firstMessage);
        response.put("fields", fieldErrors);
        response.put("path", req.getRequestURI());
        return ResponseEntity.badRequest().body(response);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleGeneral(Exception ex, HttpServletRequest req) {
        log.error("Unexpected error: {}", ex.getMessage(), ex);
        String msg = ex.getMessage() != null && !ex.getMessage().isBlank() ? ex.getMessage() : "An unexpected error occurred";
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiError(LocalDateTime.now(), 500, "Internal Server Error", msg, req.getRequestURI()));
    }
}
