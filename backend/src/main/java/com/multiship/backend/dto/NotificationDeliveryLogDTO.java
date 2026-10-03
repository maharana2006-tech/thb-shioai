package com.multiship.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** A4.4 — serialization shape for the delivery-log list + retry endpoints. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationDeliveryLogDTO {
    private Long id;
    private String templateKey;
    private String recipient;
    private String subject;
    private String body;
    private String status;
    private String providerKind;
    private Long providerId;
    private String errorMessage;
    private Integer latencyMs;
    private Long retryOfId;
    private LocalDateTime sentAt;
}
