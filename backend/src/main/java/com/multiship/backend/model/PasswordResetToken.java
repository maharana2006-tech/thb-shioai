package com.multiship.backend.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Sprint 51 BS-M4 — one-shot token backing the /auth/password/forgot →
 * /auth/password/reset flow. Only the hash is stored; the plaintext token
 * lives in the email that dispatched it. The reset endpoint deletes the
 * row on success (single-use); expired rows survive until an out-of-band
 * cleanup job trims them via the {@code idx_password_reset_tokens_expires_at}
 * index.
 */
@Entity
@Table(name = "password_reset_tokens",
        indexes = {
                @Index(name = "idx_password_reset_tokens_expires_at", columnList = "expires_at"),
        })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PasswordResetToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Owner of the reset request — FK users(id), cascade-delete. */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /**
     * SHA-256 hex digest of the plaintext token. Storing the hash keeps a
     * DB reader from consuming tokens directly. The plaintext is only ever
     * emailed; when the caller POSTs the plaintext to /reset we re-hash
     * and lookup by hash.
     */
    @Column(name = "token_hash", nullable = false, unique = true, length = 255)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
