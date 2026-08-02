import type { PackagePreset, ShippingServiceItem } from '../api/shippingConfigService'
import type { Warehouse } from '../api/warehouseService'

export type Scope = 'DOMESTIC' | 'INTERNATIONAL' | 'BOTH' | 'ANY'

/** Uppercase set of ISO origin codes for the given warehouse ids — used as the
 *  origin footprint for a rule / draft. Empty set = "any warehouse". */
export function originCountriesFor(
  warehouseIds: number[],
  warehouseById: Map<number, Warehouse>,
): Set<string> {
  const s = new Set<string>()
  for (const id of warehouseIds) {
    const c = warehouseById.get(id)?.address?.country
    if (c) s.add(c.toUpperCase())
  }
  return s
}

/** Domestic vs international scope inferred from a destination zone against
 *  the rule's origin footprint. Falls back to ANY when either side is unknown. */
export function inferScope(destCodes: string[], origins: Set<string>): Scope {
  if (!destCodes.length) return 'ANY'
  if (origins.size === 0) return 'ANY'
  const hasDom = destCodes.some((c) => origins.has(c.toUpperCase()))
  const hasIntl = destCodes.some((c) => !origins.has(c.toUpperCase()))
  if (hasDom && hasIntl) return 'BOTH'
  return hasDom ? 'DOMESTIC' : 'INTERNATIONAL'
}

/** Predicate: is this service a candidate for a rule whose origin footprint
 *  and destination scope are as given?
 *
 *  - `svc.enabled` must be true.
 *  - Services with a pinned `originCountry` must have that origin in the
 *    rule's set — unless the rule has no origin restriction at all.
 *  - Services with no origin pinned (platform-default) are always eligible.
 *  - Scope match is symmetric with BOTH acting as a wildcard on either side. */
export function serviceEligible(
  svc: ShippingServiceItem,
  origins: Set<string>,
  scope: Scope,
): boolean {
  if (!svc.enabled) return false
  const svcOrigin = (svc.originCountry || '').toUpperCase()
  if (svcOrigin && origins.size > 0 && !origins.has(svcOrigin)) return false
  if (scope === 'ANY') return true
  if (svc.scope === 'BOTH' || scope === 'BOTH') return true
  return svc.scope === scope
}

/** A logical carrier service — collapses (carrier, service_code) siblings that
 *  the connector sync emits per origin country. Operators see one row instead
 *  of one row per origin (which reads as duplicates). */
export type ServiceGroup = {
  key: string
  representative: ShippingServiceItem
  variants: ShippingServiceItem[]
  origins: string[]
}

/** Look up the group a specific ShippingService belongs to across all sibling
 *  origin variants — used to highlight the currently-picked group even when
 *  the saved variant isn't the group's chosen representative. */
export function groupKeyFor(s: ShippingServiceItem): string {
  return `${(s.carrier || '').toUpperCase()}|${s.serviceCode || `id:${s.id}`}`
}

/** Bucket services by (carrier, service_code) and pick a representative per
 *  bucket: origin-matching variant when the rule has exactly one origin, else
 *  the platform-default (no origin) if it exists, else the first variant. */
export function groupServiceVariants(
  services: ShippingServiceItem[],
  origins: Set<string>,
): ServiceGroup[] {
  const map = new Map<string, ServiceGroup>()
  for (const s of services) {
    const key = groupKeyFor(s)
    const existing = map.get(key)
    if (existing) {
      existing.variants.push(s)
    } else {
      map.set(key, { key, representative: s, variants: [s], origins: [] })
    }
  }
  const originList = [...origins]
  for (const g of map.values()) {
    g.origins = Array.from(
      new Set(
        g.variants
          .map((v) => (v.originCountry || '').toUpperCase())
          .filter(Boolean),
      ),
    ).sort()
    const originMatch =
      originList.length === 1
        ? g.variants.find((v) => (v.originCountry || '').toUpperCase() === originList[0])
        : undefined
    const platform = g.variants.find((v) => !v.originCountry)
    g.representative = originMatch ?? platform ?? g.variants[0]
  }
  return Array.from(map.values())
}

/** Package group — same shape as service groups but keyed on the preset's
 *  (carrier, carrier_package_code). CUSTOM boxes (no code) key on id. */
export type PresetGroup = {
  key: string
  representative: PackagePreset
  variantIds: number[]
}

export function presetGroupKey(p: PackagePreset): string {
  if (p.kind === 'CARRIER' && p.carrierPackageCode) {
    return `${(p.carrier || '').toUpperCase()}|${p.carrierPackageCode}`
  }
  return `id:${p.id}`
}

export function groupPresets(list: PackagePreset[]): PresetGroup[] {
  const map = new Map<string, PresetGroup>()
  for (const p of list) {
    if (p.id == null) continue
    const key = presetGroupKey(p)
    const existing = map.get(key)
    if (existing) {
      existing.variantIds.push(p.id)
    } else {
      map.set(key, { key, representative: p, variantIds: [p.id] })
    }
  }
  return Array.from(map.values())
}

/** Presets that ship on this carrier (or are carrier-agnostic CUSTOM boxes)
 *  AND fit the origin footprint. CARRIER presets with a pinned origin must
 *  match one of the rule's origins; unpinned CARRIER presets pass through so
 *  legacy hand-added rows aren't hidden by a filter they predate. */
export function filterPresetsForCarrierAndOrigin(
  presets: PackagePreset[],
  serviceCarrier: string | null,
  origins: Set<string>,
): PackagePreset[] {
  const carrier = (serviceCarrier || '').toUpperCase()
  return presets.filter((p) => {
    if (!p.enabled || p.id == null) return false
    if (p.kind === 'CARRIER') {
      if (carrier && (p.carrier || '').toUpperCase() !== carrier) return false
      if (origins.size > 0 && p.originCountry) {
        if (!origins.has(p.originCountry.toUpperCase())) return false
      }
    }
    return true
  })
}

/** Collapse a rule's stored preset ids (variant ids — one per origin) into
 *  the count of logical packages the operator sees. Requires a lookup map. */
export function logicalPackageCount(
  presetIds: number[],
  presetById: Map<number, PackagePreset>,
): number {
  if (!presetIds.length) return 0
  const groups = new Set<string>()
  for (const id of presetIds) {
    const p = presetById.get(id)
    if (!p) continue
    groups.add(presetGroupKey(p))
  }
  return groups.size
}
