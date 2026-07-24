import { apiClient } from './apiClient'
import type { ApiResponse } from './orderService'

// ===== Destination rules =====

/** Read shape of GET /clients/{code}/destinations. */
export interface ClientDestinationRules {
  clientCode: string
  /** ALLOW | DENY, or null when unrestricted. */
  mode: 'ALLOW' | 'DENY' | null
  /** ISO-3166 alpha-2, sorted A→Z. */
  countries: string[]
}

/** PUT /clients/{code}/destinations body. */
export interface ReplaceDestinationRulesPayload {
  mode: 'ALLOW' | 'DENY'
  countries: string[]
}

// ===== Shipping policy =====

export type RateStrategy = 'CHEAPEST' | 'FASTEST' | 'FIXED'

export interface ClientShippingPolicy {
  clientCode: string
  rateStrategy: RateStrategy
  /** Populated only when rateStrategy=FIXED. */
  fixedServiceId: number | null
  /** Local cutoff HH:mm:ss; null = no cutoff. */
  cutoffTime: string | null
  /** IANA zone id, e.g. America/New_York. */
  cutoffTz: string | null
  createdAt: string | null
  updatedAt: string | null
}

export interface UpdateClientPolicyPayload {
  rateStrategy: RateStrategy
  /** Required when rateStrategy=FIXED. */
  fixedServiceId?: number | null
  cutoffTime?: string | null
  cutoffTz?: string | null
}

// ===== Billing markup =====

export type MarkupKind = 'PERCENT' | 'FLAT'

export interface ClientBillingMarkup {
  clientCode: string
  kind: MarkupKind
  /** DECIMAL(12,4); non-negative. */
  value: number
  /** ISO-4217. */
  currency: string
  createdAt: string | null
  updatedAt: string | null
}

export interface UpdateClientMarkupPayload {
  kind: MarkupKind
  value: number
  currency: string
}

export const clientDestinationsService = {
  get: (clientCode: string) =>
    apiClient.get<ApiResponse<ClientDestinationRules>>(
      `/clients/${encodeURIComponent(clientCode)}/destinations`,
    ),

  replace: (clientCode: string, payload: ReplaceDestinationRulesPayload) =>
    apiClient.put<ApiResponse<ClientDestinationRules>>(
      `/clients/${encodeURIComponent(clientCode)}/destinations`,
      payload,
    ),

  clear: (clientCode: string) =>
    apiClient.delete<ApiResponse<void>>(
      `/clients/${encodeURIComponent(clientCode)}/destinations`,
    ),
}

export const clientShippingPolicyService = {
  get: (clientCode: string) =>
    apiClient.get<ApiResponse<ClientShippingPolicy>>(
      `/clients/${encodeURIComponent(clientCode)}/policy`,
    ),

  update: (clientCode: string, payload: UpdateClientPolicyPayload) =>
    apiClient.put<ApiResponse<ClientShippingPolicy>>(
      `/clients/${encodeURIComponent(clientCode)}/policy`,
      payload,
    ),
}

export const clientBillingMarkupService = {
  get: (clientCode: string) =>
    apiClient.get<ApiResponse<ClientBillingMarkup>>(
      `/clients/${encodeURIComponent(clientCode)}/markup`,
    ),

  update: (clientCode: string, payload: UpdateClientMarkupPayload) =>
    apiClient.put<ApiResponse<ClientBillingMarkup>>(
      `/clients/${encodeURIComponent(clientCode)}/markup`,
      payload,
    ),
}
