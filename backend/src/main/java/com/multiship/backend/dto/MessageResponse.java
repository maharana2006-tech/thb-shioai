package com.multiship.backend.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor // Enables seamless JSON processing across enterprise serialization engines
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MessageResponse {
    private String message;

    /** Stable machine-readable code (see {@link ErrorCode}); null on success. */
    private String errorCode;

    public MessageResponse(String message) {
        this.message = message;
    }

    public MessageResponse(String message, ErrorCode errorCode) {
        this.message = message;
        this.errorCode = errorCode != null ? errorCode.name() : null;
    }
}
