package com.multiship.backend.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApiResponse<T> {
    private String status;
    private int code;
    private String message;
    private LocalDateTime timestamp;
    private T data;
    private ErrorDetails errors;

    /**
     * Stable machine-readable code (see {@link ErrorCode}) set on every
     * failure response; null on success. Clients branch on this, not on
     * the human-readable message.
     */
    @Schema(description = "Stable machine-readable failure code (see ErrorCode enum); null on success. Branch on this, never on message text.",
            example = "NEEDS_CARRIER_DETAILS")
    private String errorCode;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ErrorDetails {
        private String field;
        private String code;
        private String message;
    }
}