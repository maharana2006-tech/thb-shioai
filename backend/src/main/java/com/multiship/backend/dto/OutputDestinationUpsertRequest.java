package com.multiship.backend.dto;

import com.multiship.backend.service.output.DestinationType;
import com.multiship.backend.service.output.DocType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Sprint 52 — request payload for creating / updating a
 * {@link com.multiship.backend.model.ClientOutputDestination}.
 *
 * <p>The optional {@code sftpPasswordPlain} / {@code sftpPrivateKeyPlain}
 * fields let the UI submit the actual secret material without exposing
 * it in the config JSON — the service encrypts and writes to
 * {@link com.multiship.backend.model.SystemSetting}, then swaps in a
 * pointer id on {@code config.passwordSecretId} / {@code privateKeySecretId}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OutputDestinationUpsertRequest {

    @NotBlank
    @Size(max = 64)
    private String clientCode;

    @NotNull
    private DocType docType;

    @NotNull
    private DestinationType destinationType;

    /** Raw JSON config — shape depends on destinationType. See
     *  {@link com.multiship.backend.service.output.OutputConfig}. */
    @NotBlank
    private String config;

    private boolean active;

    @Size(max = 500)
    private String notes;

    /** Only used for SFTP + PASSWORD auth: write-only, encrypted at rest. */
    private String sftpPasswordPlain;

    /** Only used for SFTP + KEY auth: write-only PEM bytes, encrypted at rest. */
    private String sftpPrivateKeyPlain;

    /**
     * Sprint 52 output-polish (follow-up #2) — optional known_hosts file
     * body. When set, the server encrypts + stores it and rewrites the
     * config JSON with a {@code knownHostsSecretId} pointer, and the
     * driver enables strict host-key checking against it. Write-only:
     * GET responses never echo the plaintext or the pointer id.
     */
    private String sftpKnownHostsPlain;
}
