package com.multiship.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Sprint 51 BS-M4 — POST /auth/password/reset body. Consumes the one-shot
 * token sent by /forgot. Single-use: the server deletes the token row on
 * success and bumps the owner's {@code token_version} so any outstanding
 * JWT is invalidated.
 */
@Data
public class PasswordResetRequest {

    @NotBlank(message = "token is required")
    private String token;

    @NotBlank(message = "newPassword is required")
    @Size(min = 8, message = "newPassword must be at least 8 characters")
    private String newPassword;
}
