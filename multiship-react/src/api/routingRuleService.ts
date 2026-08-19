import { apiClient } from './apiClient'
import type { ApiResponse } from './orderService'

/** Sprint 44 — routing rule action type. */
export type RoutingActionType = 'REROUTE' | 'BLOCK'

export interface RoutingRule {
  id?: number | null
  clientCode: string
  name: string
  priority: number
  active: boolean

  // Conditions (any may be null / undefined)
  minWeightLb?: number | null
  maxWeightLb?: number | null
  destCountries?: string | null   // CSV of ISO-2
  destRegions?: string | null     // CSV of region names
  matchCarrier?: string | null
  matchServiceId?: number | null
  minDeclaredValue?: number | null
  maxDeclaredValue?: number | null
  matchOrderSource?: string | null
  /** G2 — match on the currently-resolved fulfilment warehouse. Null = any. */
  matchWarehouseId?: number | null

  // Action
  actionType: RoutingActionType
  targetServiceId?: number | null
  /** G2 — for REROUTE, the fulfilment warehouse to rewrite the shipment to.
   *  Null = don't change the warehouse. */
  targetWarehouseId?: number | null
  blockReason?: string | null

  createdAt?: string | null
  updatedAt?: string | null
}

export interface RoutingEvaluationRequest {
  weightLb?: number | null
  destCountry?: string | null
  destRegion?: string | null
  currentCarrier?: string | null
  currentServiceId?: number | null
  /** G2 — warehouse currently resolved for the shipment; null when ad-hoc. */
  currentWarehouseId?: number | null
  declaredValue?: number | null
  orderSource?: string | null
}

export interface RoutingTraceEntry {
  ruleId: number
  ruleName: string
  priority: number
  outcome: string
  note: string
}

export interface RoutingEvaluationResult {
  status: 'MATCH' | 'NO_MATCH'
  matchedRuleId?: number | null
  matchedRuleName?: string | null
  actionType?: RoutingActionType | null
  targetServiceId?: number | null
  targetCarrier?: string | null
  /** G2 — REROUTE target warehouse; null when the rule only rewrites service. */
  targetWarehouseId?: number | null
  blockReason?: string | null
  trace: RoutingTraceEntry[]
}

const base = (clientCode: string) =>
  `/clients/${encodeURIComponent(clientCode)}/routing-rules`

export const routingRuleService = {
  /** Audited 2026-08-19 (silent-fallback batch 5) — `?? []` normalises a
   *  200-with-null-body to empty; real HTTP failures throw from apiClient. */
  list: async (clientCode: string): Promise<RoutingRule[]> => {
    const resp = await apiClient.get<ApiResponse<RoutingRule[]>>(base(clientCode))
    return resp.data ?? []
  },

  save: (clientCode: string, rule: RoutingRule) =>
    apiClient.post<ApiResponse<RoutingRule>>(base(clientCode), rule),

  remove: (clientCode: string, ruleId: number) =>
    apiClient.delete<ApiResponse<void>>(`${base(clientCode)}/${ruleId}`),

  dryRun: async (
    clientCode: string,
    request: RoutingEvaluationRequest,
  ): Promise<RoutingEvaluationResult> => {
    const resp = await apiClient.post<ApiResponse<RoutingEvaluationResult>>(
      `${base(clientCode)}/dry-run`,
      request,
    )
    return resp.data as RoutingEvaluationResult
  },
}
