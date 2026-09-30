/**
 * A4.4 — client for /api/v1/admin/notification-delivery-log. Drives
 * /settings/notification-delivery-log. ADMIN-only.
 */
import { apiClient } from './apiClient'
import type { ApiResponse } from './orderService'

export type DeliveryStatus = 'SENT' | 'FAILED'

export interface NotificationDeliveryLogRow {
  id: number
  templateKey: string | null
  recipient: string
  subject: string
  body: string
  status: DeliveryStatus
  providerKind: string | null
  providerId: number | null
  errorMessage: string | null
  latencyMs: number | null
  retryOfId: number | null
  sentAt: string
}

export interface NotificationDeliveryLogPage {
  items: NotificationDeliveryLogRow[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}

export interface NotificationDeliveryLogFilter {
  templateKey?: string
  status?: DeliveryStatus | ''
  recipient?: string
  page?: number
  size?: number
}

const base = '/admin/notification-delivery-log'

export const notificationDeliveryLogService = {
  async list(filter: NotificationDeliveryLogFilter = {}): Promise<NotificationDeliveryLogPage> {
    const params: Record<string, string | number> = {}
    if (filter.templateKey) params.templateKey = filter.templateKey
    if (filter.status) params.status = filter.status
    if (filter.recipient) params.recipient = filter.recipient
    params.page = filter.page ?? 0
    params.size = filter.size ?? 50
    const { data } = await apiClient.get<ApiResponse<NotificationDeliveryLogPage>>(base, { params })
    return data.data ?? { items: [], page: 0, size: 50, totalElements: 0, totalPages: 0 }
  },

  async retry(id: number): Promise<void> {
    await apiClient.post<ApiResponse<{ retriedFrom: number }>>(`${base}/${id}/retry`)
  },
}
