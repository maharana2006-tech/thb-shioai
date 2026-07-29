import { apiClient } from './apiClient'

/** Structured address returned by the AI parser (mirrors the backend ExternalAddress). */
export interface ParsedAddress {
  name?: string | null
  company?: string | null
  phone?: string | null
  email?: string | null
  addressLine1?: string | null
  addressLine2?: string | null
  city?: string | null
  state?: string | null
  postalCode?: string | null
  countryCode?: string | null
}

export interface HsSuggestion {
  hsCode?: string | null
  heading?: string | null
  confidence?: 'high' | 'medium' | 'low' | null
  rationale?: string | null
}

export interface PackagingSuggestion {
  packageCode?: string | null
  lengthIn?: number | null
  widthIn?: number | null
  heightIn?: number | null
  weightLb?: number | null
  rationale?: string | null
}

export interface ServiceSuggestion {
  serviceCode?: string | null
  serviceName?: string | null
  incoterm?: string | null
  rationale?: string | null
}

export interface ShipmentWarning {
  severity: 'high' | 'medium' | 'low'
  field?: string | null
  message: string
}

export const aiService = {
  /** Ship from / Ship to — turn a pasted blob into a structured address. */
  parseAddress: (text: string) =>
    apiClient.post<ParsedAddress>('/ai/parse-address', { text }),

  /** Items — suggest an HS tariff code for one line. */
  suggestHs: (description: string, originCountry?: string) =>
    apiClient.post<HsSuggestion>('/ai/suggest-hs', { description, originCountry }),

  /** Package & weight — recommend a package + estimate from the item list. */
  suggestPackaging: (items: { description: string; quantity?: number }[], available?: string[]) =>
    apiClient.post<PackagingSuggestion>('/ai/suggest-packaging', { items, available }),

  /** Account & service — recommend a service + incoterm for the route. */
  recommendService: (input: {
    fromCountry?: string
    toCountry?: string
    weightLb?: number
    urgency?: string
    available?: string[]
  }) => apiClient.post<ServiceSuggestion>('/ai/recommend-service', input),

  /** Shipment — pre-ship sanity review of the whole form. */
  reviewShipment: (snapshot: Record<string, unknown>) =>
    apiClient.post<{ warnings: ShipmentWarning[] }>('/ai/review-shipment', snapshot),
}
