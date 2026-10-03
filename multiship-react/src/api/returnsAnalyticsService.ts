/**
 * Returns F11 payoff — client for /api/v1/admin/analytics/returns.
 * Drives /settings/returns-analytics. ADMIN-only.
 */
import { apiClient } from './apiClient'
import type { ApiResponse } from './orderService'

export interface ReasonRollupRow {
  /** ISO date of the Monday that starts the week. */
  weekStart: string
  /** One of WRONG_ITEM / DEFECTIVE / NO_LONGER_NEEDED / SIZE / OTHER /
   *  UNKNOWN (legacy + nulls). */
  reason: string
  count: number
}

export interface ReasonRollupResponse {
  items: ReasonRollupRow[]
  weeks: number
  sinceDate: string
}

export const returnsAnalyticsService = {
  async reasonRollup(weeks = 12): Promise<ReasonRollupResponse> {
    const res = await apiClient.get<ApiResponse<ReasonRollupResponse>>(
      `/admin/analytics/returns/reason-rollup?weeks=${weeks}`,
    )
    return res.data ?? { items: [], weeks, sinceDate: '' }
  },
}
