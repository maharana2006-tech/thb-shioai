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
 * A revocable API credential for an EXTERNAL application to call the public
 * shipping API. The full secret is shown once at creation and never stored — we
 * keep only a bcrypt hash of the secret plus a public lookup prefix.
 *
 * Token format issued to the caller: {@code msk_<env>_<prefix>_<secret>}.
 * {@code keyPrefix} (the public lookup id) is indexed; {@code keyHash} is the
 * bcrypt hash of the secret portion.
 */
@Entity
@Table(name = "api_key",
        indexes = @Index(name = "idx_api_key_prefix", columnList = "key_prefix", unique = true))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApiKey {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Human label for the key (e.g. "Acme WMS production"). */
    @Column(nullable = false, length = 120)
    private String name;

    /** The client this key ships on behalf of; every shipment is attributed here. */
    @Column(name = "client_code", nullable = false, length = 50)
    private String clientCode;

    /** live | test — informational scope marker carried in the token. */
    @Column(nullable = false, length = 10)
    private String environment;

    /** Public lookup id (the {@code <prefix>} segment of the token). Unique. */
    @Column(name = "key_prefix", nullable = false, length = 40)
    private String keyPrefix;

    /** bcrypt hash of the secret segment — the raw secret is never persisted. */
    @Column(name = "key_hash", nullable = false, length = 100)
    private String keyHash;

    /** Space-separated permission scopes (e.g. "shipments rates tracking"). */
    @Column(length = 255)
    private String scopes;

    @Column(nullable = false)
    private boolean active;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "last_used_at")
    private LocalDateTime lastUsedAt;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    /** Username of the admin who issued the key. */
    @Column(name = "created_by", length = 100)
    private String createdBy;

    /**
     * Sprint 50 Tier 0.5 PR A — key lifecycle. NULL = never expires (platform
     * keys only per policy). Filter enforcement lands in PR C; column exists
     * now so PRs B+C can wire against a stable schema.
     */
    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    /**
     * Sprint 50 Tier 0.5 PR A — rotation trail. When a key is rotated
     * (POST /api-keys/{id}/rotate in PR C), the new key row points at the
     * previous key's id via rotated_from_id; the old key stays active for
     * a 24h grace with revoked_at set at the end of grace.
     */
    @Column(name = "rotated_from_id")
    private Long rotatedFromId;

    @Column(name = "last_rotated_at")
    private LocalDateTime lastRotatedAt;
}
