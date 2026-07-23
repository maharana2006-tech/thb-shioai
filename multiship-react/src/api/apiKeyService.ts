import { apiClient } from './apiClient'
import type { ApiResponse } from './orderService'

/**
 * An external API key. `token` (the full plaintext `msk_...` value) is present
 * ONLY in the issue response — everywhere else it is null and `masked` is shown.
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

  revoke: (id: number) => apiClient.delete<ApiResponse<void>>(`/api-keys/${id}`),
}
