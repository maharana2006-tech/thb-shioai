package com.multiship.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Sprint 50 Tier 0.5 PR E — one row rendered on the /settings/users admin
 * page. Purposefully skinny — no password hash, no carrier secrets — this
 * is the projection the admin table shows.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminUserDTO {
    private Long id;
    private String username;
    private String email;
    private String fullName;
    private String role;
    private String clientCode;
    private Boolean emailVerified;
    /** Non-null when the account is deactivated (soft-deleted). */
    private LocalDateTime deactivatedAt;
    private String deactivatedBy;
    private LocalDateTime createdAt;
}
