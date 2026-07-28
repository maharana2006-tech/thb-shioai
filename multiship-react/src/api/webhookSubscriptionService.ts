import { apiClient } from './apiClient'
import type { ApiResponse } from './orderService'

export type WebhookEventType =
  | 'LABEL_GENERATED'
  | 'TRACKING_UPDATED'
  | 'EXCEPTION'
  | 'RULE_BLOCKED'

/** Sprint 46 — external webhook subscription. `secret` is masked on read. */
export interface WebhookSubscription {
  id?: number | null
  apiKeyId: number
  event: WebhookEventType
  url: string
  /** Plaintext on write; "••••••" on read. */
  secret: string
  active: boolean
  createdAt?: string | null
  updatedAt?: string | null
}

export const webhookSubscriptionService = {
  list: async (apiKeyId?: number): Promise<WebhookSubscription[]> => {
    const qs = apiKeyId ? `?apiKeyId=${apiKeyId}` : ''
    const resp = await apiClient.get<ApiResponse<WebhookSubscription[]>>(
      `/webhook-subscriptions${qs}`,
    )
    return resp.data ?? []
  },

  save: (sub: WebhookSubscription) =>
    apiClient.post<ApiResponse<WebhookSubscription>>('/webhook-subscriptions', sub),

  remove: (id: number) =>
    apiClient.delete<ApiResponse<void>>(`/webhook-subscriptions/${id}`),
}
