package com.multiship.backend.dto;

/**
 * The logged-in user's own account, for the self-service Profile page.
 * Read-only fields (username/email/role/clientCode) plus the editable fullName.
 * Never carries the password hash or any carrier secrets.
 */
public record ProfileResponse(
        String username,
        String email,
        String fullName,
        String role,
        String clientCode,
        boolean emailVerified) {
}
