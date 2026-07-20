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
  load: async (): Promise<DashboardData | null> => {
    const r = await apiClient.get<ApiResponse<DashboardData>>('/dashboard')
    return r.data ?? null
  },
}
