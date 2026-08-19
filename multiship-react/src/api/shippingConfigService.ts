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
  /** ISO alpha-2 origin country this service is offered from (carrier availability is lane-specific). */
  originCountry?: string | null
  /** CARRIER_API (live carrier response) | CARRIER_SYNC (built-in availability model) | SEEDED (legacy starter). */
  source?: 'CARRIER_API' | 'CARRIER_SYNC' | 'SEEDED' | null
  /** When last refreshed from the carrier API (ISO string; null for seeded rows). */
  syncedAt?: string | null
  /** Package limits this service enforces (lb / inches); null = use the carrier default. */
  maxWeightLb?: number | null
  maxLengthIn?: number | null
  maxLengthGirthIn?: number | null
  surchargeLengthGirthIn?: number | null
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
  /** Phase 6: allowed packages on this rule. Round-trips through the entity
   *  as a transient field; persisted in ship_method_rule_package. */
  allowedPresetIds?: number[]
  /** Origin warehouses this rule applies to; empty = matches any warehouse.
   *  Round-trips as a transient field; persisted in ship_method_rule_warehouse. */
  warehouseIds?: number[]
}

/** Phase 6: flat row from `rulePackages` in the catalog. Frontend groups by
 *  rule_id to render the chip per rule. */
export interface ShipMethodRulePackageLink {
  id?: number
  ruleId: number
  presetId: number
}

/** Flat row from `ruleWarehouses` in the catalog. Frontend groups by rule_id
 *  to render the warehouse chips per rule. */
export interface ShipMethodRuleWarehouseLink {
  id?: number
  ruleId: number
  warehouseId: number
}

/** Service ↔ package link (which packages a service may ship in). */
export interface ServicePackageLink {
  id?: number
  serviceId: number
  presetId: number
}

export interface PackagePreset {
  id?: number
  name: string
  kind: 'CARRIER' | 'CUSTOM'
  carrierPackageCode?: string | null
  carrier?: string | null
  /** PLATFORM (attachable to any client via allowlist) or CLIENT (private
   *  to its owner, auto-allowed). Added in Phase 5a. */
  ownerType?: 'PLATFORM' | 'CLIENT' | null
  /** Owning client — populated only when ownerType=CLIENT. */
  ownerClientCode?: string | null
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
  /** CARRIER packaging: origin country it's offered from (null for custom boxes). */
  originCountry?: string | null
  /** SEEDED | CARRIER_SYNC (carrier catalogue) | CARRIER_API. */
  source?: 'SEEDED' | 'CARRIER_SYNC' | 'CARRIER_API' | null
  /** DOMESTIC | INTERNATIONAL | BOTH — lanes this packaging is valid on. */
  scope?: 'DOMESTIC' | 'INTERNATIONAL' | 'BOTH' | null
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

/** The package limits a service enforces (its own values, or the carrier default). Mirror of PackageMath. */
export interface ResolvedLimits {
  maxWeightLb: number | null
  maxLengthIn: number | null
  maxLengthGirthIn: number | null
  surchargeLengthGirthIn: number | null
}

const carrierDefaultLimits = (carrier: string, code: string): ResolvedLimits => {
  const c = (carrier || '').toUpperCase()
  const s = (code || '').toUpperCase()
  if (c === 'USPS') {
    return s.includes('PRIORITY') && !s.includes('GROUND')
      ? { maxWeightLb: 70, maxLengthIn: null, maxLengthGirthIn: 108, surchargeLengthGirthIn: null }
      : { maxWeightLb: 70, maxLengthIn: null, maxLengthGirthIn: 130, surchargeLengthGirthIn: null }
  }
  if (c === 'FEDEX') {
    return { maxWeightLb: 150, maxLengthIn: s.includes('GROUND') ? 108 : 119, maxLengthGirthIn: 165, surchargeLengthGirthIn: 130 }
  }
  return { maxWeightLb: 150, maxLengthIn: 108, maxLengthGirthIn: 165, surchargeLengthGirthIn: 130 }
}

/** A service's effective limits: its stored values, falling back to the carrier default per field. */
export const limitsOf = (s: ShippingServiceItem): ResolvedLimits => {
  const d = carrierDefaultLimits(s.carrier, s.serviceCode)
  return {
    maxWeightLb: s.maxWeightLb ?? d.maxWeightLb,
    maxLengthIn: s.maxLengthIn ?? d.maxLengthIn,
    maxLengthGirthIn: s.maxLengthGirthIn ?? d.maxLengthGirthIn,
    surchargeLengthGirthIn: s.surchargeLengthGirthIn ?? d.surchargeLengthGirthIn,
  }
}

export type ServiceFit = { status: 'FITS' | 'SURCHARGE' | 'OVER_MAX' | 'OVERWEIGHT'; reason: string }

/** How a package measures up against a specific service's limits (for the allowed-packages modal). */
export const fitAgainstService = (p: PackagePreset, s: ShippingServiceItem): ServiceFit => {
  const lim = limitsOf(s)
  const ov = oversizeOf(p)
  // 1) dimensional over-max — cannot ship on this service at all
  if (ov) {
    const lenIn = (p.length ?? 0) * (p.dimUnit === 'CM' ? 0.393701 : 1)
    if (lim.maxLengthGirthIn != null && ov.lengthPlusGirth > lim.maxLengthGirthIn) {
      return { status: 'OVER_MAX', reason: `${ov.lengthPlusGirth}" length+girth exceeds the ${lim.maxLengthGirthIn}" limit` }
    }
    if (lim.maxLengthIn != null && lenIn > lim.maxLengthIn) {
      return { status: 'OVER_MAX', reason: `${Math.round(lenIn)}" length exceeds the ${lim.maxLengthIn}" limit` }
    }
  }
  // 2) box weight capacity beyond the service's weight cap
  if (p.maxWeight != null && lim.maxWeightLb != null) {
    const boxLb = p.weightUnit === 'KG' ? p.maxWeight * 2.20462 : p.maxWeight
    if (boxLb > lim.maxWeightLb) {
      return { status: 'OVERWEIGHT', reason: `holds up to ${Math.round(boxLb)} lb, over the ${lim.maxWeightLb} lb limit` }
    }
  }
  // 3) oversize surcharge tier
  if (ov && lim.surchargeLengthGirthIn != null && ov.lengthPlusGirth > lim.surchargeLengthGirthIn) {
    return { status: 'SURCHARGE', reason: `${ov.lengthPlusGirth}" length+girth — large-package surcharge` }
  }
  return { status: 'FITS', reason: '' }
}

export interface ShippingCatalog {
  services: ShippingServiceItem[]
  rules: ShipMethodRule[]
  links: ServicePackageLink[]
  /** Phase 6: allowed-package links per rule. */
  rulePackages: ShipMethodRulePackageLink[]
  /** Origin-warehouse links per rule. */
  ruleWarehouses: ShipMethodRuleWarehouseLink[]
  /** Distinct origin countries present in the catalog. */
  originCountries: string[]
}

/** Result of a carrier availability sync. */
/** Sprint 55 audit #297 — cascade preview shape for the rule-delete flow. */
export interface RuleCascadePreview {
  ruleId: number
  shipviaCd: string
  allowedPackageCount: number
  allowedWarehouseCount: number
}

export interface SyncResult {
  carrier: string
  originCountry: string
  added: number
  updated: number
  total: number
  /** True only when the LIVE carrier API answered (not the built-in availability model). */
  live: boolean
  /** Human-readable source, e.g. "UPS Rating API" or "built-in availability — no live UPS credentials". */
  via: string
}

export const shippingConfigService = {
  /** Audited 2026-08-19 (silent-fallback batch 5) — the 6 `?? []` defaults
   *  normalise a partially-populated 200 response to empty arrays per field
   *  (BE returns a nullable-fielded aggregate DTO). Real HTTP failures throw
   *  from apiClient. */
  catalog: async (): Promise<ShippingCatalog> => {
    const r = await apiClient.get<ApiResponse<ShippingCatalog>>('/shipping-services')
    return {
      services: r.data?.services ?? [],
      rules: r.data?.rules ?? [],
      links: r.data?.links ?? [],
      rulePackages: r.data?.rulePackages ?? [],
      ruleWarehouses: r.data?.ruleWarehouses ?? [],
      originCountries: r.data?.originCountries ?? [],
    }
  },

  /** Pull a carrier's available services for an origin country from its availability API. */
  syncServices: (carrier: string, originCountry: string) =>
    apiClient.post<ApiResponse<SyncResult>>(
      `/shipping-services/sync?carrier=${encodeURIComponent(carrier)}&originCountry=${encodeURIComponent(originCountry)}`,
      {},
    ),

  setServiceEnabled: (id: number, enabled: boolean) =>
    apiClient.patch<ApiResponse<ShippingServiceItem>>(`/shipping-services/${id}`, { enabled }),

  saveRule: (rule: ShipMethodRule) => apiClient.put<ApiResponse<ShipMethodRule>>('/ship-method-rules', rule),

  deleteRule: (id: number) => apiClient.delete<ApiResponse<void>>(`/ship-method-rules/${id}`),

  /** Sprint 55 audit #297 — cascade preview counts (packages + warehouses)
   *  before the operator confirms a rule delete. */
  previewRuleDelete: (id: number) =>
    apiClient.get<ApiResponse<RuleCascadePreview>>(`/ship-method-rules/${id}/cascade-preview`),

  setServicePackages: (serviceId: number, links: Array<{ presetId: number }>) =>
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

  /** Pull a carrier's predefined packaging (fixed dims/weights/flat-rate) for an origin country. */
  syncPackages: (carrier: string, originCountry: string) =>
    apiClient.post<ApiResponse<SyncResult>>(
      `/package-presets/sync?carrier=${encodeURIComponent(carrier)}&originCountry=${encodeURIComponent(originCountry)}`,
      {},
    ),
}
