package com.multiship.backend.controller;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.ErrorCode;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.LocalDateTime;

/**
 * API-wide bean-validation handling. Without this, a @Valid failure on any
 * controller not covered by a scoped advice fell through to the /error
 * dispatch, which Spring Security answered with a bogus 401 — and the
 * frontend force-logs-out on 401. Validation failures must always be an
 * honest 400 VALIDATION_ERROR. (CarrierExceptionHandler keeps its
 * carrier-scoped handlers; same response shape, so overlap is harmless.)
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidationException(MethodArgumentNotValidException ex) {
        FieldError fieldError = ex.getBindingResult().getFieldError();
        ApiResponse.ErrorDetails errorDetails = fieldError == null
                ? ApiResponse.ErrorDetails.builder()
                .field("request")
                .code("VALIDATION_ERROR")
                .message("Invalid request payload.")
                .build()
                : ApiResponse.ErrorDetails.builder()
                .field(fieldError.getField())
                .code(fieldError.getCode())
                .message(fieldError.getDefaultMessage())
                .build();

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.<Void>builder()
                        .status("error")
                        .code(HttpStatus.BAD_REQUEST.value())
                        .errorCode(ErrorCode.VALIDATION_ERROR.name())
                        .message("Validation failed.")
                        .timestamp(LocalDateTime.now())
                        .errors(errorDetails)
                        .build());
    }
}
