package com.multiship.backend.dto;

import com.multiship.backend.model.ExternalWebhookSubscription;
import com.multiship.backend.model.ExternalWebhookSubscription.EventType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExternalWebhookSubscriptionDTO {
    private Long id;
    private Long apiKeyId;
    private EventType event;
    private String url;
    /** On read: masked. On write: the plaintext HMAC secret. */
    private String secret;
    private Boolean active;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static ExternalWebhookSubscriptionDTO from(ExternalWebhookSubscription s, boolean maskSecret) {
        return ExternalWebhookSubscriptionDTO.builder()
                .id(s.getId()).apiKeyId(s.getApiKeyId()).event(s.getEvent())
                .url(s.getUrl())
                // BUG-171 — never null: the editor treats the value as a string. A
                // stored secret is masked on read; no secret reads as empty.
                // An envelope-encrypted secret counts as "set" too (BUG-171 follow-up).
                .secret(s.getSecret() == null && s.getSecretEncrypted() == null ? "" : (maskSecret ? "••••••" : (s.getSecret() == null ? "••••••" : s.getSecret())))
                .active(s.getActive())
                .createdAt(s.getCreatedAt()).updatedAt(s.getUpdatedAt())
                .build();
    }
}
