package com.multiship.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Sprint 51 BS-M4 — POST /auth/password/change body. Both fields are
 * required; the server verifies {@code oldPassword} via bcrypt before
 * accepting the change and bumps the caller's {@code token_version}
 * on success so every outstanding JWT for that user is invalidated.
 */
@Data
public class PasswordChangeRequest {

    @NotBlank(message = "oldPassword is required")
    private String oldPassword;

    @NotBlank(message = "newPassword is required")
    @Size(min = 8, message = "newPassword must be at least 8 characters")
    private String newPassword;
}
