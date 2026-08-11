package com.multiship.backend.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Sprint 50 Tier 0.5 PR D — admin mints an invite: recipient email,
 * pre-assigned clientCode, target role (USER or TENANT — ADMIN invites
 * are refused server-side).
 */
@Data
public class UserInviteRequest {

    @NotBlank
    @Email
    private String email;

    @NotBlank
    private String clientCode;

    /** USER or TENANT. Case-insensitive; server upper-cases. */
    @NotBlank
    private String role;
}
