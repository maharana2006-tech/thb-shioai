package com.multiship.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;

/** A4.1 — serialization shape for the /admin/mail-providers list endpoint. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MailProviderDTO {
    private Long id;
    private String kind;
    private String displayName;
    private boolean active;
    private LocalDateTime updatedAt;
    private String updatedBy;
    /** Config keys visible to the admin, secrets redacted to "•••". */
    private Map<String, String> config;
}
