package com.multiship.backend.dto;

import com.multiship.backend.model.ApiKey;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * An API key for lists/reads. {@code token} is populated ONLY in the issue
 * response (shown once); everywhere else it is null and {@code masked} is shown.
 */
@Data
@Builder
public class ApiKeyResponse {

    private Long id;
    private String name;
    private String clientCode;
    private String environment;
    private String scopes;
    private boolean active;
    private String masked;
    /** Full plaintext token — present only immediately after issue. */
    private String token;
    private LocalDateTime createdAt;
    private LocalDateTime lastUsedAt;
    private LocalDateTime revokedAt;
    /** Sprint 50 Tier 0.5 PR C — drives the UI expiry badge (green >30d, amber ≤30d, red expired). */
    private LocalDateTime expiresAt;
    /** Sprint 50 Tier 0.5 PR C — non-null when the key was rotated; grace ends 24h after this. */
    private LocalDateTime lastRotatedAt;
    /** Sprint 50 Tier 0.5 PR C — id of the pre-rotation key, when this one was minted by rotation. */
    private Long rotatedFromId;

    public static ApiKeyResponse of(ApiKey k, String masked, String token) {
        return ApiKeyResponse.builder()
                .id(k.getId())
                .name(k.getName())
                .clientCode(k.getClientCode())
                .environment(k.getEnvironment())
                .scopes(k.getScopes())
                .active(k.isActive())
                .masked(masked)
                .token(token)
                .createdAt(k.getCreatedAt())
                .lastUsedAt(k.getLastUsedAt())
                .revokedAt(k.getRevokedAt())
                .expiresAt(k.getExpiresAt())
                .lastRotatedAt(k.getLastRotatedAt())
                .rotatedFromId(k.getRotatedFromId())
                .build();
    }
}
