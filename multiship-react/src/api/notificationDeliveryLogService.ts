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
    const qs = new URLSearchParams()
    if (filter.templateKey) qs.set('templateKey', filter.templateKey)
    if (filter.status) qs.set('status', filter.status)
    if (filter.recipient) qs.set('recipient', filter.recipient)
    qs.set('page', String(filter.page ?? 0))
    qs.set('size', String(filter.size ?? 50))
    const res = await apiClient.get<ApiResponse<NotificationDeliveryLogPage>>(`${base}?${qs.toString()}`)
    return res.data ?? { items: [], page: 0, size: 50, totalElements: 0, totalPages: 0 }
  },

  async retry(id: number): Promise<void> {
    await apiClient.post<ApiResponse<{ retriedFrom: number }>>(`${base}/${id}/retry`)
  },
}
