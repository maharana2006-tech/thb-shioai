package com.multiship.backend.model;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Sprint 50 Tier 0.5 PR A — schema-only. Rate-limit window backing table for
 * the public signup endpoint. PR D uses a moving-window count over this table
 * to enforce {@code 5 attempts/email/hour + 20 attempts/IP/hour}.
 *
 * <p>Retention: rows older than 24h can be purged by a scheduled job; the
 * window queries only look back 1h so anything older is dead weight.
 */
@Entity
@Table(name = "signup_attempts",
        indexes = {
                @Index(name = "idx_signup_attempts_email_created", columnList = "email, created_at"),
                @Index(name = "idx_signup_attempts_ip_created", columnList = "ip, created_at"),
        })
@Data
public class SignupAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 255)
    private String email;

    /** Client IP as seen by the app (accounts for X-Forwarded-For if wired). */
    @Column(nullable = false, length = 45)  // 45 = INET6_ADDRSTRLEN
    private String ip;

    /** True when the attempt actually created a User row. */
    @Column(nullable = false)
    private boolean succeeded;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
