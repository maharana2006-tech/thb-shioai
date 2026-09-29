import { apiClient } from './apiClient'
import type { ApiResponse } from './orderService'

/**
 * G7 admin surface for the (source × carrier × warehouse) cutoff-shift
 * matrix + global holiday list. Backing controller:
 * {@code CutoffAdminController} at /api/v1/admin/cutoffs.
 */
export interface CutoffRule {
  id: number
  /** MANUAL | BULK | API | WMS | DTC | null (any). */
  source: string | null
  /** FEDEX | UPS | USPS | DHL | STAMPS_COM | USPS_DIRECT | null (any). */
  carrierCode: string | null
  warehouseId: number | null
  /** Local time on the rule's timezone. HH:mm:ss. */
  cutoffTime: string
  /** IANA tz (e.g. "America/New_York"). Null = client's tz. */
  timezone: string | null
  active: boolean
  updatedAt: string | null
  updatedBy: string | null
}

export interface Holiday {
  id: number
  /** yyyy-MM-dd */
  holidayDate: string
  name: string
  active: boolean
  updatedAt: string | null
  updatedBy: string | null
}

export interface CutoffRuleUpsert {
  source?: string | null
  carrierCode?: string | null
  warehouseId?: number | null
  cutoffTime?: string
  timezone?: string | null
  active?: boolean
}

export interface HolidayUpsert {
  holidayDate?: string
  name?: string
  active?: boolean
}

export interface SeedResult {
  created: number
  skipped: number
  warehouses: number
  carriers: number
  sources: string[]
}

const BASE = '/admin/cutoffs'

export const cutoffService = {
  listRules: () =>
    apiClient.get<ApiResponse<CutoffRule[]>>(`${BASE}/rules`)
      .then((r) => r.data ?? []),

  createRule: (req: CutoffRuleUpsert) =>
    apiClient.post<ApiResponse<CutoffRule>>(`${BASE}/rules`, req)
      .then((r) => r.data as CutoffRule),

  updateRule: (id: number, req: CutoffRuleUpsert) =>
    apiClient.put<ApiResponse<CutoffRule>>(`${BASE}/rules/${id}`, req)
      .then((r) => r.data as CutoffRule),

  deleteRule: (id: number) =>
    apiClient.delete<ApiResponse<void>>(`${BASE}/rules/${id}`),

  seedRules: () =>
    apiClient.post<ApiResponse<SeedResult>>(`${BASE}/rules/seed`, {})
      .then((r) => r.data as SeedResult),

  listHolidays: () =>
    apiClient.get<ApiResponse<Holiday[]>>(`${BASE}/holidays`)
      .then((r) => r.data ?? []),

  createHoliday: (req: HolidayUpsert) =>
    apiClient.post<ApiResponse<Holiday>>(`${BASE}/holidays`, req)
      .then((r) => r.data as Holiday),

  updateHoliday: (id: number, req: HolidayUpsert) =>
    apiClient.put<ApiResponse<Holiday>>(`${BASE}/holidays/${id}`, req)
      .then((r) => r.data as Holiday),

  deleteHoliday: (id: number) =>
    apiClient.delete<ApiResponse<void>>(`${BASE}/holidays/${id}`),
}
