package com.multiship.backend.dto;

import com.multiship.backend.model.UserInvite;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Sprint 51 User↔Client linkage re-audit item #1 — public, read-only view
 * of an invite for the SPA's AcceptInvitePage.
 *
 * <p>Returned by GET {@code /api/v1/auth/invite/{token}} so the SPA can
 * render "You've been invited to {clientCode} as {role}" BEFORE the
 * invitee commits a username + password. The token itself is deliberately
 * omitted from the response body — the caller already has it in the URL,
 * and echoing it back adds no value while widening the surface for a
 * proxy log leak. The invitee's email is included so the accept form can
 * pre-fill / display it (the invite pins the email server-side; the
 * invitee cannot change it).
 */
@Data
@Builder
public class InvitePreviewResponse {

    private String email;
    private String clientCode;
    private String role;
    private LocalDateTime expiresAt;

    public static InvitePreviewResponse of(UserInvite i) {
        return InvitePreviewResponse.builder()
                .email(i.getEmail())
                .clientCode(i.getClientCode())
                .role(i.getRole())
                .expiresAt(i.getExpiresAt())
                .build();
    }
}
