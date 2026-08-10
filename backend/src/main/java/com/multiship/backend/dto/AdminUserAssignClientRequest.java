package com.multiship.backend.dto;

import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Sprint 50 Tier 0.5 PR E — payload for
 * {@code PATCH /api/v1/admin/users/{id}/client}. Explicit null
 * {@code clientCode} clears the assignment (turns the user back into a
 * legacy org-wide operator); a non-empty value moves them to that tenant.
 * The admin can add a free-text {@code reason} that goes into the audit
 * trail for ops post-mortem.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AdminUserAssignClientRequest {

    /** May be null / blank to clear the assignment. */
    @Size(max = 50)
    private String clientCode;

    @Size(max = 500)
    private String reason;
}
