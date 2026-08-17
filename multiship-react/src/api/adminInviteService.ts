import { apiClient } from './apiClient'
import type { ApiResponse } from './orderService'

/**
 * Sprint 55 audit #294 — admin invite endpoint bindings.
 *
 * <p>Backend {@code AdminUserInviteController} at
 * {@code POST /api/v1/admin/user-invites} mints a token pre-scoped to
 * a client + role. Response echoes the raw accept-link so an admin
 * can copy-paste when SMTP isn't configured.
 *
 * <p>ADMIN role is deliberately NOT invitable (backend enforces).
 * Password-reset is a separate concern and not wired here.
 */

export interface InviteMintRequest {
  email: string
  clientCode: string
  /** USER or TENANT. */
  role: 'USER' | 'TENANT'
}

export interface InviteMintResponse {
  id: number
  email: string
  clientCode: string
  role: string
  invitedBy: string
  token: string
  acceptLink: string
  createdAt: string
  expiresAt: string
  consumedAt: string | null
}

export const adminInviteService = {
  mint: (req: InviteMintRequest) =>
    apiClient.post<ApiResponse<InviteMintResponse>>('/admin/user-invites', req),
}
