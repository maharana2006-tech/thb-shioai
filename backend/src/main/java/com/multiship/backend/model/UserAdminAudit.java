package com.multiship.backend.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Sprint 50 Tier 0.5 PR E — one row per admin action on the users table
 * (assign clientCode, deactivate, reactivate, resend-invite). Keeps the
 * "who moved this user where, and when" story auditable without needing
 * to trawl app logs.
 */
@Entity
@Table(name = "user_admin_audit")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserAdminAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "subject_user_id", nullable = false)
    private Long subjectUserId;

    @Column(name = "subject_username", nullable = false, length = 100)
    private String subjectUsername;

    /** ASSIGN_CLIENT | DEACTIVATE | REACTIVATE | RESEND_INVITE */
    @Column(name = "action", nullable = false, length = 32)
    private String action;

    @Column(name = "old_client_code", length = 50)
    private String oldClientCode;

    @Column(name = "new_client_code", length = 50)
    private String newClientCode;

    @Column(name = "actor_username", nullable = false, length = 100)
    private String actorUsername;

    /** Free-text ops note; nullable. */
    @Column(name = "reason", length = 500)
    private String reason;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
