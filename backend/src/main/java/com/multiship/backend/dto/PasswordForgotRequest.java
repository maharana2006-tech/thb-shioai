package com.multiship.backend.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Sprint 51 BS-M4 — POST /auth/password/forgot body. The response is 202
 * unconditionally regardless of whether the email matches a user, so an
 * attacker can't enumerate registered emails via this endpoint.
 */
@Data
public class PasswordForgotRequest {

    @NotBlank(message = "email is required")
    @Email(message = "email must be a valid address")
    private String email;
}
