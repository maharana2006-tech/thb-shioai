import { apiClient } from './apiClient'
import type { ApiResponse } from './orderService'

/** One carrier service level from the catalog (UPS Ground, FedEx 2Day…). */
export interface ShippingServiceItem {
  id: number
  carrier: string
  serviceCode: string
  name: string
  scope: 'DOMESTIC' | 'INTERNATIONAL' | 'BOTH'
  enabled: boolean
  sortOrder: number
}

/**
 * A ship-method RULE: order ship-method code → carrier service, optionally
 * narrowed by client and destination (country/region). Most specific wins.
 */
export interface ShipMethodRule {
  id?: number
  shipviaCd: string
  clientCode?: string | null
  /** ANY | COUNTRIES (zone: space-separated codes in destValue). REGION/COUNTRY are legacy. */
  destType?: 'ANY' | 'COUNTRIES' | 'REGION' | 'COUNTRY' | null
  destValue?: string | null
  serviceId: number
}

/** Service ↔ package link with the negotiated discount %. */
export interface ServicePackageLink {
  id?: number
  serviceId: number
  presetId: number
  discountPct?: number | null
}

export interface PackagePreset {
  id?: number
  name: string
  kind: 'CARRIER' | 'CUSTOM'
  carrierPackageCode?: string | null
  carrier?: string | null
  length?: number | null
  width?: number | null
  height?: number | null
  dimUnit: string
  maxWeight?: number | null
  weightUnit: string
  tareWeight?: number | null
  /** Internal (usable) dimensions — packing space; external dims drive rating. */
  internalLength?: number | null
  internalWidth?: number | null
  internalHeight?: number | null
  /** Packaging material cost per box. */
  boxCost?: number | null
  /** Flat-rate carrier packaging: fixed price, DIM weight doesn't apply. */
  flatRate?: boolean | null
  /** Auto-pick tie-break: lower = preferred. */
  sortOrder?: number | null
  /** Serialized as `default` by the backend (Lombok boolean isDefault). */
  default?: boolean
  enabled: boolean
}

/** DIM weight in the preset's weight unit (139 in→lb / 5000 cm→kg, dims rounded UP). Null: no dims or flat-rate. */
export const dimWeightOf = (p: PackagePreset): number | null => {
  if (!p.length || !p.width || !p.height || p.flatRate) return null
  const cm = p.dimUnit === 'CM'
  const vol = Math.ceil(p.length) * Math.ceil(p.width) * Math.ceil(p.height)
  let dim = vol / (cm ? 5000 : 139) // in→lb | cm→kg
  const weighsKg = p.weightUnit === 'KG'
  if (cm && !weighsKg) dim *= 2.20462
  if (!cm && weighsKg) dim /= 2.20462
  return Math.round(dim * 100) / 100
}

/** Length + girth in inches (girth = 2×W + 2×H) and the carrier oversize status. */
export const oversizeOf = (p: PackagePreset): { lengthPlusGirth: number; status: 'OK' | 'SURCHARGE' | 'OVER_MAX' } | null => {
  if (!p.length || !p.width || !p.height) return null
  const toIn = p.dimUnit === 'CM' ? 0.393701 : 1
  const lpg = (p.length + 2 * (p.width + p.height)) * toIn
  const lengthIn = p.length * toIn
  const status = lpg > 165 || lengthIn > 108 ? 'OVER_MAX' : lpg > 130 ? 'SURCHARGE' : 'OK'
  return { lengthPlusGirth: Math.round(lpg * 10) / 10, status }
}

export interface ShippingCatalog {
  services: ShippingServiceItem[]
  rules: ShipMethodRule[]
  links: ServicePackageLink[]
}

export const shippingConfigService = {
  catalog: async (): Promise<ShippingCatalog> => {
    const r = await apiClient.get<ApiResponse<ShippingCatalog>>('/shipping-services')
    return {
      services: r.data?.services ?? [],
      rules: r.data?.rules ?? [],
      links: r.data?.links ?? [],
    }
  },

  setServiceEnabled: (id: number, enabled: boolean) =>
    apiClient.patch<ApiResponse<ShippingServiceItem>>(`/shipping-services/${id}`, { enabled }),

  saveRule: (rule: ShipMethodRule) => apiClient.put<ApiResponse<ShipMethodRule>>('/ship-method-rules', rule),

  deleteRule: (id: number) => apiClient.delete<ApiResponse<void>>(`/ship-method-rules/${id}`),

  setServicePackages: (serviceId: number, links: Array<{ presetId: number; discountPct?: number | null }>) =>
    apiClient.put<ApiResponse<ServicePackageLink[]>>(`/shipping-services/${serviceId}/packages`, links),

  listPresets: async (): Promise<PackagePreset[]> => {
    const r = await apiClient.get<ApiResponse<PackagePreset[]>>('/package-presets')
    return Array.isArray(r.data) ? r.data : []
  },

  savePreset: (preset: PackagePreset) =>
    preset.id
      ? apiClient.put<ApiResponse<PackagePreset>>(`/package-presets/${preset.id}`, preset)
      : apiClient.post<ApiResponse<PackagePreset>>('/package-presets', preset),

  setDefaultPreset: (id: number) =>
    apiClient.put<ApiResponse<PackagePreset>>(`/package-presets/${id}/default`, {}),

  deletePreset: (id: number) => apiClient.delete<ApiResponse<void>>(`/package-presets/${id}`),
}
