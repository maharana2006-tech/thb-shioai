package com.multiship.backend.controller;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.ErrorCode;
import com.multiship.backend.exception.CarrierConnectionException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.LocalDateTime;

@RestControllerAdvice(assignableTypes = CarrierController.class)
public class CarrierExceptionHandler {

    @ExceptionHandler(CarrierConnectionException.class)
    public ResponseEntity<ApiResponse<Void>> handleCarrierConnectionException(CarrierConnectionException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.<Void>builder()
                        .status("error")
                        .code(HttpStatus.BAD_REQUEST.value())
                        .errorCode(ErrorCode.CARRIER_CONNECTION_FAILED.name())
                        .message(ex.getMessage())
                        .timestamp(LocalDateTime.now())
                        .build());
    }

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

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGenericException(Exception ex) throws Exception {
        // Authorization failures must reach Spring Security's translation
        // filter (-> 403), not be masked as a 500 carrier error.
        if (ex instanceof org.springframework.security.access.AccessDeniedException) {
            throw ex;
        }

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.<Void>builder()
                        .status("error")
                        .code(HttpStatus.INTERNAL_SERVER_ERROR.value())
                        .errorCode(ErrorCode.INTERNAL_ERROR.name())
                        .message("Unexpected carrier error: " + ex.getMessage())
                        .timestamp(LocalDateTime.now())
                        .build());
    }
}
