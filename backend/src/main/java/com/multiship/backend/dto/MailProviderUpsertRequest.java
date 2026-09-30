package com.multiship.backend.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * A4.1 — create/update payload. When {@code id} is null, a new row is
 * created and the config map is upserted; when {@code id} is set, the
 * row's kind + display + config are updated. Blank string values in
 * {@code config} delete that key. Absent keys are left untouched — so a
 * secrets edit that only sets {@code password} won't clobber {@code host}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MailProviderUpsertRequest {

    private Long id;

    @NotBlank(message = "kind required")
    private String kind;

    @NotBlank(message = "displayName required")
    private String displayName;

    /** key → value (blank string = delete). Secrets are inferred from the SPI. */
    private Map<String, String> config;
}
