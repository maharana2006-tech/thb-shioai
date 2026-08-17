import { apiClient } from './apiClient'

/**
 * Sprint 51 User↔Client linkage re-audit items #1 + #2 — API surface for
 * the invite-accept + email-verify SPA pages. Backend endpoints:
 *
 *   · GET  /auth/invite/{token}   — read-only preview (public)
 *   · POST /auth/accept-invite    — commit account (public)
 *   · POST /auth/verify-email     — mark email verified (public)
 *
 * All three are on the shared `/auth/**` permitAll surface + CSRF
 * ignoringRequestMatchers list, so the standard apiClient works without
 * a session cookie. The token is opaque + URL-safe (hex from the
 * backend's SecureRandom); no encoding is required.
 */

/** Response body from GET /auth/invite/{token} on a valid invite. */
export interface InvitePreview {
  email: string
  clientCode: string
  role: string
  /** ISO-8601 LocalDateTime string from the backend (no timezone suffix). */
  expiresAt: string
}

/** Payload for POST /auth/accept-invite. Backend enforces password ≥ 6. */
export interface AcceptInvitePayload {
  token: string
  username: string
  password: string
  fullName: string
}

/** Backend uses a bare MessageResponse for accept + verify success bodies. */
export interface MessageResponse {
  message: string
}

export const inviteService = {
  previewInvite: (token: string) =>
    apiClient.get<InvitePreview>(`/auth/invite/${encodeURIComponent(token)}`),

  acceptInvite: (payload: AcceptInvitePayload) =>
    apiClient.post<MessageResponse>('/auth/accept-invite', payload),

  // The backend reads the token from `?token=` (query param, not body) so
  // the raw email link `POST /auth/verify-email?token=xxx` also works —
  // the SPA passes an empty body but must still match the query-param
  // signature Spring's @RequestParam expects.
  verifyEmail: (token: string) =>
    apiClient.post<MessageResponse>(
      `/auth/verify-email?token=${encodeURIComponent(token)}`,
      {},
    ),
}
