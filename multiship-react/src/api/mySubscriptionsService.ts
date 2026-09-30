/**
 * A4.5 — client for /api/v1/me/notification-subscriptions. Drives
 * /settings/notifications for the currently-signed-in user. Any role.
 * Only returns templates where opt_out_allowed is true; transactional
 * events (invite, verify, password reset) are never in the list.
 */
import { apiClient } from './apiClient'
import type { ApiResponse } from './orderService'

export interface MyNotificationSubscription {
  templateKey: string
  description: string | null
  subscribed: boolean
}

const base = '/me/notification-subscriptions'

export const mySubscriptionsService = {
  async list(): Promise<MyNotificationSubscription[]> {
    const res = await apiClient.get<ApiResponse<MyNotificationSubscription[]>>(base)
    return res.data ?? []
  },

  async set(templateKey: string, enabled: boolean): Promise<void> {
    // Backend reads `enabled` as @RequestParam, so it must go in the query
    // string. apiClient is a fetch wrapper with no axios-style `params` option.
    await apiClient.put<ApiResponse<unknown>>(
      `${base}/${encodeURIComponent(templateKey)}?enabled=${enabled}`,
      null,
    )
  },
}
