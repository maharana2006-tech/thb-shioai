import { apiClient } from './apiClient'
import type { ApiResponse, QueueStats } from './orderService'

/** One-round-trip dashboard aggregate — every number deep-links somewhere. */
export interface DashboardData {
  queue: QueueStats
  today: {
    labelsToday: number
    labelsYesterday: number
    pendingNow: number
    exceptionsNow: number
    intlPending: number
  }
  trend: Array<{ date: string; count: number }>
  carrierSplit: Record<string, number>
  recentLabels: Array<{
    orderNo: number
    client: string
    carrier: string
    trackingNumber: string | null
    city: string | null
    country: string | null
    generatedAt: string | null
  }>
  health: {
    unverifiedAccounts: number
    clientsWithoutDefault: number
    rulesToDisabledServices: number
    customsGapLanes: Array<{ client: string; country: string; origin: string }>
  }
}

export const dashboardService = {
  /**
   * @param signal Sprint 49 Tier 4 Fix 3/4 — pass an AbortSignal to
   *               cancel an in-flight poll (fires on unmount / when
   *               the next tick starts before the previous returned).
   */
  load: async (signal?: AbortSignal): Promise<DashboardData | null> => {
    const r = await apiClient.get<ApiResponse<DashboardData>>('/dashboard', { signal })
    return r.data ?? null
  },
}
