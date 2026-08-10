package com.multiship.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Sprint 50 Tier 0.5 PR D — public invite-accept payload. Username +
 * password + fullName are chosen by the invitee; email + clientCode +
 * role are inherited from the invite row (server never trusts the
 * client to name their own tenant / role).
 */
@Data
public class AcceptInviteRequest {

    @NotBlank
    private String token;

    @NotBlank
    @Size(min = 3, max = 20)
    private String username;

    @NotBlank
    @Size(min = 6)
    private String password;

    @NotBlank
    private String fullName;
}
