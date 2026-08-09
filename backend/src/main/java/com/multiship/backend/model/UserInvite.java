package com.multiship.backend.model;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Sprint 50 Tier 0.5 PR A — schema-only. Admin-minted invite that carries a
 * pre-assigned {@code clientCode} + {@code role} so accepting the invite
 * creates a properly-scoped user without the invitee choosing their own
 * tenant. PR D wires the mint + accept endpoints.
 *
 * <p>Lifecycle: created (consumed_at=null, expires_at=now+7d) → consumed
 * (consumed_at populated, single-use). Expired-and-unconsumed rows are
 * retained for audit; the accept endpoint rejects them with
 * {@code errorCode=INVITE_EXPIRED}.
 */
@Entity
@Table(name = "user_invites",
        indexes = {
                @Index(name = "idx_user_invites_token", columnList = "token", unique = true),
                @Index(name = "idx_user_invites_email", columnList = "email"),
        })
@Data
public class UserInvite {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Opaque high-entropy token issued to the invitee via email or admin copy. */
    @Column(nullable = false, unique = true, length = 100)
    private String token;

    @Column(nullable = false, length = 255)
    private String email;

    /** Tenant the invitee will be scoped to on accept. */
    @Column(name = "client_code", nullable = false, length = 50)
    private String clientCode;

    /** Role the invitee will be created with — USER or TENANT (not ADMIN). */
    @Column(nullable = false, length = 20)
    private String role;

    @Column(name = "invited_by", nullable = false, length = 100)
    private String invitedBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    /** Null until the invitee accepts. */
    @Column(name = "consumed_at")
    private LocalDateTime consumedAt;
}
