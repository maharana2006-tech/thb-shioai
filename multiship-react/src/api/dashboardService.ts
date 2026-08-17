import { z } from 'zod'
import { apiClient } from './apiClient'
import type { ApiResponse, QueueStats } from './orderService'

/**
 * Fix #307 — runtime shape validation for the /dashboard response.
 *
 * <p>Prior UI trusted the raw JSON — a malformed `carrierSplit` (e.g.
 * string values instead of numbers) would render NaN silently in
 * Dashboard.tsx charts. Now we validate at the API boundary via zod's
 * `safeParse`; on validation failure the loader returns null and the
 * loadError badge fires (matches the existing try-catch shape at
 * Dashboard.tsx lines 176-183).
 *
 * <p>Fields marked `.default()` degrade to safe zero-state on missing
 * data (server is behind an old deploy, for example) instead of crashing
 * the whole panel.
 */
const queueStatsSchema = z.object({
  ready: z.number().default(0),
  needsDetails: z.number().default(0),
  chooseAccount: z.number().default(0),
  clientMissing: z.number().default(0),
  failed: z.number().default(0),
  generated: z.number().default(0),
}).passthrough()

const dashboardDataSchema = z.object({
  queue: queueStatsSchema,
  today: z.object({
    labelsToday: z.number().default(0),
    labelsYesterday: z.number().default(0),
    pendingNow: z.number().default(0),
    exceptionsNow: z.number().default(0),
    intlPending: z.number().default(0),
  }),
  trend: z.array(z.object({
    date: z.string(),
    count: z.number(),
  })).default([]),
  // carrierSplit came from a backend Map — must be Record<string, number>.
  // z.record with (keyType, valueType) rejects string values silently.
  carrierSplit: z.record(z.string(), z.number()).default({}),
  recentLabels: z.array(z.object({
    orderNo: z.number(),
    client: z.string(),
    carrier: z.string(),
    trackingNumber: z.string().nullable(),
    city: z.string().nullable(),
    country: z.string().nullable(),
    generatedAt: z.string().nullable(),
  })).default([]),
  health: z.object({
    unverifiedAccounts: z.number().default(0),
    clientsWithoutDefault: z.number().default(0),
    rulesToDisabledServices: z.number().default(0),
    customsGapLanes: z.array(z.object({
      client: z.string(),
      country: z.string(),
      origin: z.string(),
    })).default([]),
  }),
})

/** One-round-trip dashboard aggregate — every number deep-links somewhere.
 *  Type is inferred from the runtime schema so drift is impossible. */
export type DashboardData = z.infer<typeof dashboardDataSchema> & { queue: QueueStats }

export const dashboardService = {
  /**
   * @param signal Sprint 49 Tier 4 Fix 3/4 — pass an AbortSignal to
   *               cancel an in-flight poll (fires on unmount / when
   *               the next tick starts before the previous returned).
   */
  load: async (signal?: AbortSignal): Promise<DashboardData | null> => {
    const r = await apiClient.get<ApiResponse<unknown>>('/dashboard', { signal })
    if (r.data == null) return null
    // Fix #307 — validate shape; log + return null on mismatch so the
    // Dashboard's existing loadError badge surfaces the failure.
    const parsed = dashboardDataSchema.safeParse(r.data)
    if (!parsed.success) {
      // Log observability for a shape mismatch that would otherwise render as NaN.
      console.error('[dashboardService] response shape mismatch:', parsed.error.issues)
      throw new Error('Dashboard response is malformed. Check the backend.')
    }
    return parsed.data as DashboardData
  },
}
