package com.multiship.backend.dto;

/**
 * Sprint 50 PR Q1 — SPA bootstrap payload for {@code GET /api/v1/auth/session}.
 *
 * <p>Once the frontend switches to httpOnly cookie auth, the JavaScript
 * can no longer read the JWT to extract username / role / clientCode.
 * On page refresh it calls {@code /auth/session}; the backend reads
 * the cookie in {@link com.multiship.backend.config.JwtAuthenticationFilter},
 * populates the SecurityContext, and this endpoint returns the
 * non-sensitive session facts the SPA needs for role-gated UI.
 *
 * <p>Returns 401 with {@code UNAUTHORIZED} error code if the cookie is
 * absent, expired, or invalid — SPA treats that as "not logged in"
 * and redirects to /login.
 */
public record SessionResponse(String username, String role, String clientCode) {}
