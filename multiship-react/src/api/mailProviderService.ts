/**
 * A4.1 — client for /api/v1/admin/mail-providers.
 * Drives /settings/mail. ADMIN-only on the backend.
 *
 * Secret values NEVER come back from the server; the config map returns
 * "•••" for any key the SPI marks as a secret.
 */
import { apiClient } from './apiClient'
import type { ApiResponse } from './orderService'

export interface MailKindDescriptor {
  kind: string
  requiredKeys: string[]
  secretKeys: string[]
}

export interface MailProviderSummary {
  id: number
  kind: string
  displayName: string
  active: boolean
  updatedAt: string | null
  updatedBy: string | null
  /** Config KV with secret values redacted to "•••". */
  config: Record<string, string>
}

export interface MailProviderUpsertRequest {
  /** Omit to create; set to update. */
  id?: number
  kind: string
  displayName: string
  /** Absent keys are untouched; blank strings delete the key. */
  config?: Record<string, string>
}

export interface MailTestSendRequest {
  to: string
  subject?: string
  body?: string
}

const base = '/admin/mail-providers'

export const mailProviderService = {
  async listKinds(): Promise<MailKindDescriptor[]> {
    const { data } = await apiClient.get<ApiResponse<MailKindDescriptor[]>>(`${base}/kinds`)
    return data.data ?? []
  },

  async list(): Promise<MailProviderSummary[]> {
    const { data } = await apiClient.get<ApiResponse<MailProviderSummary[]>>(base)
    return data.data ?? []
  },

  async upsert(req: MailProviderUpsertRequest): Promise<MailProviderSummary> {
    const { data } = await apiClient.post<ApiResponse<MailProviderSummary>>(base, req)
    return data.data!
  },

  async activate(id: number): Promise<MailProviderSummary> {
    const { data } = await apiClient.post<ApiResponse<MailProviderSummary>>(`${base}/${id}/activate`)
    return data.data!
  },

  async remove(id: number): Promise<void> {
    await apiClient.delete<ApiResponse<void>>(`${base}/${id}`)
  },

  /** Server returns 422 with errorCode=MAIL_SEND_FAILED on provider errors. */
  async testSend(req: MailTestSendRequest): Promise<{ delivered: boolean }> {
    const { data } = await apiClient.post<ApiResponse<{ delivered: boolean }>>(`${base}/test-send`, req)
    return data.data ?? { delivered: false }
  },
}
