package com.multiship.backend.dto;

import com.multiship.backend.model.UserInvite;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Sprint 50 Tier 0.5 PR D — admin-facing view of an invite. Includes
 * the raw token so the admin UI can offer a copy-fallback when the
 * mailer is log-only (dev / no-SMTP).
 */
@Data
@Builder
public class UserInviteResponse {

    private Long id;
    private String email;
    private String clientCode;
    private String role;
    private String invitedBy;
    private String token;
    private String acceptLink;
    private LocalDateTime createdAt;
    private LocalDateTime expiresAt;
    private LocalDateTime consumedAt;

    public static UserInviteResponse of(UserInvite i, String acceptLink) {
        return UserInviteResponse.builder()
                .id(i.getId())
                .email(i.getEmail())
                .clientCode(i.getClientCode())
                .role(i.getRole())
                .invitedBy(i.getInvitedBy())
                .token(i.getToken())
                .acceptLink(acceptLink)
                .createdAt(i.getCreatedAt())
                .expiresAt(i.getExpiresAt())
                .consumedAt(i.getConsumedAt())
                .build();
    }
}
