import { apiClient } from './apiClient'
import type { ApiResponse } from './orderService'

/**
 * V132 admin surface for the DB-driven DTC background scheduler.
 * Backing controller: {@code DtcSchedulerAdminController} at
 * /api/v1/admin/dtc-scheduler. Edits apply on the engine's next minute tick.
 */

export type DayCode = 'MON' | 'TUE' | 'WED' | 'THU' | 'FRI' | 'SAT' | 'SUN'

/** Interval window: startTime/endTime/intervalMinutes. Daily window: runAt. Times are "HH:mm". */
export interface SchedulerWindow {
  id: number | null
  label: string
  days: DayCode[]
  startTime: string | null
  endTime: string | null
  intervalMinutes: number | null
  runAt: string | null
  enabled: boolean
}

export interface SchedulerJob {
  jobKey: string
  name: string
  description: string | null
  enabled: boolean
  params: Record<string, unknown>
  windows: SchedulerWindow[]
  running: boolean
  lastRunAt: string | null
  /** SUCCESS | FAILED | SKIPPED */
  lastStatus: string | null
  lastMessage: string | null
  lastDurationMs: number | null
  /** ISO offset date-times in the scheduler's timezone; empty when off. */
  nextRuns: string[]
  updatedAt: string | null
  updatedBy: string | null
}

export interface SchedulerSettings {
  enabled: boolean
  /** IANA tz; null = server time. */
  timezone: string | null
  serverTimezone: string
  updatedAt: string | null
  updatedBy: string | null
}

export interface SchedulerOverview {
  settings: SchedulerSettings
  jobs: SchedulerJob[]
}

export interface SchedulerRun {
  id: number
  jobKey: string
  /** SCHEDULE | MANUAL */
  triggerType: string
  triggeredBy: string | null
  startedAt: string
  finishedAt: string | null
  /** RUNNING | SUCCESS | FAILED | SKIPPED */
  status: string
  message: string | null
  durationMs: number | null
}

export interface JobUpdate {
  enabled?: boolean
  params?: Record<string, unknown>
  windows?: SchedulerWindow[]
}

const BASE = '/admin/dtc-scheduler'

export const dtcSchedulerService = {
  overview: () =>
    apiClient.get<ApiResponse<SchedulerOverview>>(BASE)
      .then((r) => r.data as SchedulerOverview),

  updateSettings: (req: { enabled?: boolean; timezone?: string }) =>
    apiClient.put<ApiResponse<SchedulerSettings>>(`${BASE}/settings`, req)
      .then((r) => r.data as SchedulerSettings),

  updateJob: (jobKey: string, req: JobUpdate) =>
    apiClient.put<ApiResponse<SchedulerJob>>(`${BASE}/jobs/${jobKey}`, req)
      .then((r) => r.data as SchedulerJob),

  runNow: (jobKey: string) =>
    apiClient.post<ApiResponse<void>>(`${BASE}/jobs/${jobKey}/run-now`, {}),

  runs: (jobKey: string, limit = 50) =>
    apiClient.get<ApiResponse<SchedulerRun[]>>(`${BASE}/jobs/${jobKey}/runs?limit=${limit}`)
      .then((r) => r.data ?? []),

  resetDefaults: () =>
    apiClient.post<ApiResponse<SchedulerOverview>>(`${BASE}/reset-defaults`, {})
      .then((r) => r.data as SchedulerOverview),
}
