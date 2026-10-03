package com.multiship.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A4.5 — one entry in the /me/notification-subscriptions list. Shows every
 * opt-out-allowed template plus whether the current user is subscribed
 * (defaults to true when no explicit row exists).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MyNotificationSubscriptionDTO {
    private String templateKey;
    private String description;
    private boolean subscribed;
}
