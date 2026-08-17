import { apiClient } from './apiClient'
import type { ApiResponse } from './orderService'

/**
 * An external API key. `token` (the full plaintext `msk_...` value) is present
 * ONLY in the issue/rotate response — everywhere else it is null and `masked` is shown.
 */
export interface ApiKey {
  id: number
  name: string
  clientCode: string
  /** live | test */
  environment: string
  /** Space-separated scopes (e.g. "shipments rates tracking void addresses"). */
  scopes: string
  active: boolean
  masked: string
  token: string | null
  createdAt: string | null
  lastUsedAt: string | null
  revokedAt: string | null
  /** Audit A3 — 90-day default lifecycle (backend enforces at authenticate).
   *  Drives the list's expiry badge (green >30d, amber ≤30d, red expired). */
  expiresAt?: string | null
  /** Audit A3 — non-null while inside the 24h rotation grace window. */
  lastRotatedAt?: string | null
  /** Audit A3 — id of the pre-rotation key when this one was minted by rotation. */
  rotatedFromId?: number | null
}

export interface ApiKeyIssuePayload {
  name: string
  clientCode: string
  /** live | test — backend defaults to live when omitted. */
  environment?: string
  /** Space-separated scopes — backend defaults to the full external set. */
  scopes?: string
}

export const apiKeyService = {
  list: () => apiClient.get<ApiResponse<ApiKey[]>>('/api-keys'),

  issue: (payload: ApiKeyIssuePayload) =>
    apiClient.post<ApiResponse<ApiKey>>('/api-keys', payload),

  /**
   * Audit A1 — mints a fresh key inheriting the old key's client, env,
   * and scopes. The old key stays valid for 24h (backend adds RFC 8594
   * Deprecation/Sunset headers during the grace window). The returned
   * `token` is the new plaintext, shown once in the reveal modal.
   */
  rotate: (id: number) =>
    apiClient.post<ApiResponse<ApiKey>>(`/api-keys/${id}/rotate`, {}),

  revoke: (id: number) => apiClient.delete<ApiResponse<void>>(`/api-keys/${id}`),
}
