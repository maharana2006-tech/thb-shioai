package com.multiship.backend.dto;

import com.multiship.backend.service.output.DestinationType;
import com.multiship.backend.service.output.DocType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Sprint 52 — read-side view of a {@link com.multiship.backend.model.ClientOutputDestination}
 * for the admin page. Includes {@code configSafe} — the raw config JSON
 * with any secret fields elided so the UI can show a summary without
 * ever seeing the underlying pointer values or (worst case) ciphertext.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OutputDestinationDTO {
    private Long id;
    private String clientCode;
    private DocType docType;
    private DestinationType destinationType;

    /**
     * Sanitised config JSON — same shape as stored, but with SFTP
     * password/private-key pointers replaced with "***set***" / "***unset***"
     * so the UI can render "auth: KEY (set)" without exposing internals.
     */
    private String configSafe;

    private boolean active;
    private String notes;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
