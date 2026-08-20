import { apiClient } from './apiClient'
import type { ApiResponse, QueueStats } from './orderService'

/**
 * Fix #307 — runtime shape validation for the /dashboard response.
 *
 * <p>Prior UI trusted the raw JSON — a malformed `carrierSplit` (e.g.
 * string values instead of numbers) would render NaN silently in
 * Dashboard.tsx charts. The response is now normalised at the API
 * boundary: every numeric field defaults to 0 on missing / non-number,
 * arrays default to [], the carrierSplit map's non-number values are
 * dropped rather than passed through as NaN, and the loader throws
 * (Dashboard's existing try-catch surfaces the loadError badge) if the
 * top-level object shape is invalid.
 *
 * <p>Previously implemented with zod; replaced with a hand-rolled type
 * guard in the FE bundle-size audit — zod contributed ~27 KB gzip for
 * this one usage. See scripts/analyze-bundle.cjs.
 */

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
  /** Backend Map<String, Integer> — every value must be a number. */
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

// ─── coercion helpers ────────────────────────────────────────────────────
// Every helper is null-safe and defaults on wrong-type input. The `Rec`
// type-alias saves a few characters compared to `Record<string, unknown>`.
type Rec = Record<string, unknown>

const isRec = (v: unknown): v is Rec =>
  typeof v === 'object' && v !== null && !Array.isArray(v)

const asNum = (v: unknown, d = 0): number =>
  typeof v === 'number' && Number.isFinite(v) ? v : d

const asStr = (v: unknown, d = ''): string =>
  typeof v === 'string' ? v : d

const asNullableStr = (v: unknown): string | null =>
  typeof v === 'string' ? v : v == null ? null : null

function asArray<T>(v: unknown, mapItem: (item: unknown) => T | null): T[] {
  if (!Array.isArray(v)) return []
  const out: T[] = []
  for (const item of v) {
    const mapped = mapItem(item)
    if (mapped !== null) out.push(mapped)
  }
  return out
}

/** Numeric map — drops keys whose value isn't a finite number (matches the
 *  Fix #307 requirement: a string sneaking into `carrierSplit` used to
 *  render as NaN in the pie chart). */
function asNumberMap(v: unknown): Record<string, number> {
  if (!isRec(v)) return {}
  const out: Record<string, number> = {}
  for (const [k, val] of Object.entries(v)) {
    if (typeof val === 'number' && Number.isFinite(val)) out[k] = val
  }
  return out
}

// ─── per-object parsers ──────────────────────────────────────────────────

function parseQueue(v: unknown): QueueStats {
  const q = isRec(v) ? v : {}
  // QueueStats is a Record<string, number> from orderService; we normalise
  // the known keys explicitly and let unknown keys pass through if present
  // (previously `.passthrough()` on the zod schema).
  const known = {
    ready: asNum(q.ready),
    needsDetails: asNum(q.needsDetails),
    chooseAccount: asNum(q.chooseAccount),
    clientMissing: asNum(q.clientMissing),
    failed: asNum(q.failed),
    generated: asNum(q.generated),
  }
  const extras: Record<string, number> = {}
  for (const [k, val] of Object.entries(q)) {
    if (!(k in known) && typeof val === 'number' && Number.isFinite(val)) {
      extras[k] = val
    }
  }
  return { ...extras, ...known } as unknown as QueueStats
}

function parseToday(v: unknown): DashboardData['today'] {
  const t = isRec(v) ? v : {}
  return {
    labelsToday: asNum(t.labelsToday),
    labelsYesterday: asNum(t.labelsYesterday),
    pendingNow: asNum(t.pendingNow),
    exceptionsNow: asNum(t.exceptionsNow),
    intlPending: asNum(t.intlPending),
  }
}

function parseTrendPoint(v: unknown): { date: string; count: number } | null {
  if (!isRec(v)) return null
  const date = asStr(v.date)
  if (!date) return null
  return { date, count: asNum(v.count) }
}

function parseRecentLabel(v: unknown): DashboardData['recentLabels'][number] | null {
  if (!isRec(v)) return null
  return {
    orderNo: asNum(v.orderNo),
    client: asStr(v.client),
    carrier: asStr(v.carrier),
    trackingNumber: asNullableStr(v.trackingNumber),
    city: asNullableStr(v.city),
    country: asNullableStr(v.country),
    generatedAt: asNullableStr(v.generatedAt),
  }
}

function parseCustomsGapLane(v: unknown): { client: string; country: string; origin: string } | null {
  if (!isRec(v)) return null
  return {
    client: asStr(v.client),
    country: asStr(v.country),
    origin: asStr(v.origin),
  }
}

function parseHealth(v: unknown): DashboardData['health'] {
  const h = isRec(v) ? v : {}
  return {
    unverifiedAccounts: asNum(h.unverifiedAccounts),
    clientsWithoutDefault: asNum(h.clientsWithoutDefault),
    rulesToDisabledServices: asNum(h.rulesToDisabledServices),
    customsGapLanes: asArray(h.customsGapLanes, parseCustomsGapLane),
  }
}

/**
 * Parse the raw /dashboard response. Throws on top-level shape violations
 * so Dashboard.tsx's try-catch can surface the loadError badge; wrong
 * types on individual fields degrade to defaults per the field's parser.
 */
function parseDashboardData(raw: unknown): DashboardData {
  if (!isRec(raw)) {
    console.error('[dashboardService] response is not an object:', typeof raw)
    throw new Error('Dashboard response is malformed. Check the backend.')
  }
  return {
    queue: parseQueue(raw.queue),
    today: parseToday(raw.today),
    trend: asArray(raw.trend, parseTrendPoint),
    carrierSplit: asNumberMap(raw.carrierSplit),
    recentLabels: asArray(raw.recentLabels, parseRecentLabel),
    health: parseHealth(raw.health),
  }
}

export const dashboardService = {
  /**
   * @param signal Sprint 49 Tier 4 Fix 3/4 — pass an AbortSignal to
   *               cancel an in-flight poll (fires on unmount / when
   *               the next tick starts before the previous returned).
   */
  load: async (signal?: AbortSignal): Promise<DashboardData | null> => {
    const r = await apiClient.get<ApiResponse<unknown>>('/dashboard', { signal })
    if (r.data == null) return null
    return parseDashboardData(r.data)
  },
}
