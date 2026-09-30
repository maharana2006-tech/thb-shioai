/**
 * A4.2 — client for /api/v1/admin/notification-templates. Drives
 * /settings/notification-templates. ADMIN-only.
 */
import { apiClient } from './apiClient'
import type { ApiResponse } from './orderService'

export interface NotificationTemplate {
  templateKey: string
  description: string | null
  subjectTemplate: string
  bodyTemplate: string
  /** A4.5 — when true, users can silence this from /settings/notifications. */
  optOutAllowed: boolean
  updatedAt: string | null
  updatedBy: string | null
}

export interface NotificationTemplateUpsertRequest {
  description?: string
  subjectTemplate: string
  bodyTemplate: string
  optOutAllowed?: boolean
}

export interface NotificationTemplatePreviewRequest {
  /** Preview a stored template (mutually exclusive with the two raw strings). */
  templateKey?: string
  /** Preview an unsaved subject string. */
  subjectTemplate?: string
  /** Preview an unsaved body string. */
  bodyTemplate?: string
  /** Vars for the render. */
  vars?: Record<string, unknown>
}

export interface NotificationTemplatePreviewResponse {
  subject: string
  body: string
}

const base = '/admin/notification-templates'

export const notificationTemplateService = {
  async list(): Promise<NotificationTemplate[]> {
    const res = await apiClient.get<ApiResponse<NotificationTemplate[]>>(base)
    return res.data ?? []
  },

  async get(key: string): Promise<NotificationTemplate> {
    const res = await apiClient.get<ApiResponse<NotificationTemplate>>(`${base}/${encodeURIComponent(key)}`)
    return res.data!
  },

  async upsert(key: string, req: NotificationTemplateUpsertRequest): Promise<NotificationTemplate> {
    const res = await apiClient.put<ApiResponse<NotificationTemplate>>(
      `${base}/${encodeURIComponent(key)}`, req)
    return res.data!
  },

  async remove(key: string): Promise<void> {
    await apiClient.delete<ApiResponse<void>>(`${base}/${encodeURIComponent(key)}`)
  },

  async preview(req: NotificationTemplatePreviewRequest): Promise<NotificationTemplatePreviewResponse> {
    const res = await apiClient.post<ApiResponse<NotificationTemplatePreviewResponse>>(
      `${base}/preview`, req)
    return res.data!
  },
}
