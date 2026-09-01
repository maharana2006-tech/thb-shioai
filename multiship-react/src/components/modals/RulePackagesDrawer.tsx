import { useEffect, useMemo, useRef, useState } from 'react'
import { FiAlertTriangle, FiCheck, FiPackage, FiX } from 'react-icons/fi'
import { notify } from '../../utils/notify'
import {
  shippingConfigService,
  type PackagePreset,
  type ServicePackageLink,
  type ShipMethodRule,
  type ShippingServiceItem,
} from '../../api/shippingConfigService'
import { formatCarrierName } from '../../utils/carrierUtils'
import { useModalDismiss } from '../../hooks/useModalDismiss'

/** Convert package max weight to lb for comparison against ShippingService.maxWeightLb. */
const toLb = (p: PackagePreset): number | null => {
  if (p.maxWeight == null) return null
  return p.weightUnit === 'KG' ? p.maxWeight * 2.20462 : p.maxWeight
}

/** Would this package fit under this service according to the ServicesPage
 *  compat predicate — same carrier or a custom (carrier-agnostic) box. */
const packageFits = (p: PackagePreset, carrier: string | null): boolean => {
  if (p.kind !== 'CARRIER') return true
  if (!carrier) return true
  return (p.carrier || '').toUpperCase() === carrier
}

/**
 * Drawer for editing the allowed-packages set on a single ship-method rule.
 * Filters the catalog to packages compatible with the rule's target service
 * carrier; each row shows its max weight; rows whose weight exceeds the
 * service cap are highlighted (warn, don't block).
 */
export default function RulePackagesDrawer({
  rule,
  service,
  originCountries,
  initialPresetIds,
  currentWarehouseIds,
  serviceLinks,
  requirePick,
  requirePickReason,
  onClose,
  onSaved,
}: {
  rule: ShipMethodRule
  service: ShippingServiceItem
  /** ISO countries the rule ships from — narrows CARRIER packages to those
   *  synced for the same origin(s). Empty / omitted = no origin filter. */
  originCountries?: string[]
  initialPresetIds: number[]
  /** The rule's currently-persisted warehouse restriction set. MUST be passed
   *  in from the caller: the backend's catalog() endpoint returns raw
   *  ShipViaMapping rows without the @Transient warehouseIds field, so the
   *  rule object we receive can't be trusted for warehouseIds. Passing it
   *  through the payload here prevents the save from silently wiping every
   *  warehouse row on the rule (deleteAllByRuleId + empty insert). */
  currentWarehouseIds?: number[]
  /** Sprint 52 — service_package rows from shippingConfigService.catalog().
   *  Filters the CARRIER preset pool to what Service Catalog links to
   *  this rule's service. Omit / empty = no additional filter (backwards
   *  compat with callers that predate this prop). CUSTOM presets bypass
   *  the filter (carrier-agnostic — always visible). */
  serviceLinks?: ServicePackageLink[]
  /** When true, the operator MUST pick at least one package before Save is
   *  enabled — used by the carrier-switch flow that dropped the previous
   *  package set. */
  requirePick?: boolean
  /** Short reason surfaced as a banner when requirePick is true. */
  requirePickReason?: string
  onClose: () => void
  onSaved: (nextIds: number[]) => void
}) {
  // A11y audit — focus trap + Escape-to-close + focus restoration.
  const dialogRef = useRef<HTMLElement>(null)
  useModalDismiss(true, dialogRef, onClose)
  const [presets, setPresets] = useState<PackagePreset[]>([])
  const [selected, setSelected] = useState<Set<number>>(new Set(initialPresetIds))
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)

  useEffect(() => {
    let alive = true
    shippingConfigService.listPresets()
      .then((list) => { if (alive) setPresets(list) })
      .catch(() => { /* notify covers it */ })
      .finally(() => { if (alive) setLoading(false) })
    return () => { alive = false }
  }, [])

  const carrier = useMemo(() => (service.carrier || '').toUpperCase(), [service])
  const cap = service.maxWeightLb ?? null

  /** Normalized ISO set derived from the origin filter — cheap uppercase +
   *  dedup so downstream comparisons don't have to think about casing. */
  const originSet = useMemo(() => {
    const s = new Set<string>()
    for (const c of originCountries ?? []) if (c) s.add(c.toUpperCase())
    return s
  }, [originCountries])

  /** Sprint 52 — preset IDs linked to THIS rule's service in Service
   *  Catalog. Only used for kind=CARRIER presets; CUSTOM boxes always
   *  bypass. When serviceLinks is omitted (legacy callers) the filter
   *  step is skipped entirely, preserving pre-Sprint-52 behaviour. When
   *  serviceLinks is passed but yields zero matches (empty pool OR
   *  service marked branded_packaging_allowed=false), CARRIER presets
   *  are hidden — matching the strict rule the user picked ("empty pool
   *  → zero branded allowed"). */
  const catalogLinkedPresetIds = useMemo<Set<number> | null>(() => {
    if (serviceLinks == null) return null
    return new Set(
      serviceLinks
        .filter((l) => l.serviceId === service.id)
        .map((l) => l.presetId),
    )
  }, [serviceLinks, service.id])

  /**
   * Preset eligibility narrowed by four constraints:
   *   1) enabled + persisted (has an id),
   *   2) carrier fit for the rule's service (same carrier or carrier-agnostic
   *      CUSTOM box),
   *   3) origin fit — CARRIER presets must have originCountry inside the
   *      rule's origin footprint. CUSTOM boxes have no origin, so they pass
   *      through as long as the origin filter isn't the only intent.
   *   4) Sprint 52 — Service Catalog fit: CARRIER presets must be linked
   *      to this service in service_package (or the caller may omit
   *      serviceLinks to skip this step). CUSTOM presets always bypass
   *      — they mirror the backend PackagingCompatibilityGuard's kind=
   *      CUSTOM short-circuit. Empty pool + branded_packaging_allowed=
   *      false naturally collapse into "no CARRIER preset passes" here.
   */
  const eligible = useMemo(
    () => presets.filter((p) => {
      if (!p.enabled || p.id == null) return false
      if (!packageFits(p, carrier)) return false
      // Origin fit — only relevant when the filter is active.
      if (originSet.size > 0) {
        // Origin filter is on. CUSTOM (no origin pinned) always passes — it
        // ships from anywhere. CARRIER must match one of the picked origins.
        if (p.kind === 'CARRIER') {
          const svcOrigin = (p.originCountry || '').toUpperCase()
          // Presets without an origin column (legacy) fall through as eligible
          // so hand-added CARRIER boxes aren't hidden by a filter they predate.
          if (svcOrigin && !originSet.has(svcOrigin)) return false
        }
      }
      // Sprint 52 — Service Catalog scope. CUSTOM bypasses; CARRIER must
      // be linked. Null catalogLinkedPresetIds means the caller didn't
      // pass serviceLinks (legacy) — skip this step.
      if (catalogLinkedPresetIds != null && p.kind === 'CARRIER') {
        if (!catalogLinkedPresetIds.has(p.id)) return false
      }
      return true
    }),
    [presets, carrier, originSet, catalogLinkedPresetIds],
  )

  /**
   * Group carrier presets that share a (carrier, carrier_package_code)
   * identity. The connector sync writes one preset row per (carrier, code,
   * origin) — so a single logical box like "FedEx 10kg Box" surfaces as
   * ~15 rows here, one per origin. That looked like a duplication bug to
   * operators; we collapse them into one selectable card and toggle every
   * origin variant together so a rule stays valid regardless of the
   * shipment's origin. CUSTOM boxes (no carrier_package_code) are their
   * own group keyed by id — nothing to collapse.
   */
  type PkgGroup = {
    key: string
    representative: PackagePreset
    variantIds: number[]
    origins: string[]
  }
  const groups = useMemo<PkgGroup[]>(() => {
    const map = new Map<string, PkgGroup>()
    for (const p of eligible) {
      const isCarrier = p.kind === 'CARRIER' && !!p.carrierPackageCode
      const key = isCarrier
        ? `${(p.carrier || '').toUpperCase()}|${p.carrierPackageCode}`
        : `id:${p.id}`
      const origin = p.originCountry ? p.originCountry.toUpperCase() : ''
      const existing = map.get(key)
      if (existing) {
        existing.variantIds.push(p.id!)
        if (origin && !existing.origins.includes(origin)) existing.origins.push(origin)
      } else {
        map.set(key, {
          key,
          representative: p,
          variantIds: [p.id!],
          origins: origin ? [origin] : [],
        })
      }
    }
    for (const g of map.values()) g.origins.sort()
    return Array.from(map.values())
  }, [eligible])

  /** Selection state of a group vs. the `selected` set — 'all' means every
   *  origin variant is picked, 'partial' means only some (usually a legacy
   *  save from before the grouping change), 'none' means untouched. */
  const groupState = (g: PkgGroup): 'all' | 'partial' | 'none' => {
    const hits = g.variantIds.filter((id) => selected.has(id)).length
    if (hits === 0) return 'none'
    if (hits === g.variantIds.length) return 'all'
    return 'partial'
  }

  const toggleGroup = (g: PkgGroup) => {
    setSelected((cur) => {
      const next = new Set(cur)
      const state = groupState(g)
      if (state === 'all') {
        for (const id of g.variantIds) next.delete(id)
      } else {
        // 'none' or 'partial' → promote to fully selected
        for (const id of g.variantIds) next.add(id)
      }
      return next
    })
  }

  const overWeightCount = useMemo(() => {
    if (cap == null) return 0
    // Count unique logical groups whose representative exceeds cap — using
    // raw preset ids would multi-count each origin variant of the same box.
    let n = 0
    for (const g of groups) {
      if (groupState(g) === 'none') continue
      const lb = toLb(g.representative)
      if (lb != null && lb > cap) n++
    }
    return n
    // groupState is derived from `selected`; keeping the dep list explicit.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selected, groups, cap])

  const selectedGroupCount = useMemo(
    () => groups.filter((g) => groupState(g) !== 'none').length,
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [selected, groups],
  )

  const save = async () => {
    setSaving(true)
    try {
      const nextIds = Array.from(selected).sort((a, b) => a - b)
      const r = await shippingConfigService.saveRule({
        ...rule,
        // Preserve the rule's warehouse restrictions — the spread above only
        // carries what catalog() returned, and warehouseIds is a @Transient
        // field the catalog endpoint doesn't populate. Sending null/omitted
        // here would trigger deleteAllByRuleId on the backend and wipe every
        // warehouse row on this rule.
        warehouseIds: currentWarehouseIds ?? rule.warehouseIds ?? [],
        allowedPresetIds: nextIds,
      })
      if (overWeightCount > 0) {
        notify.info(`${overWeightCount} package${overWeightCount === 1 ? '' : 's'} exceed the service's ${cap} lb cap — saved with a warning.`)
      } else {
        notify.success(r.message || 'Rule packages saved.')
      }
      onSaved(nextIds)
    } catch (error) {
      notify.apiError(error, 'Failed to save.')
    } finally {
      setSaving(false)
    }
  }

  return (
    <>
      <div className="fixed inset-0 z-[55] bg-slate-950/50 backdrop-blur-sm" onClick={onClose} aria-hidden="true" />
      <aside
        ref={dialogRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby="rule-pkg-title"
        className="fixed inset-y-0 right-0 z-[60] flex w-full max-w-[520px] flex-col border-l border-slate-200 bg-white shadow-[-18px_0_50px_rgba(8,14,26,0.18)]"
      >
        <header className="flex items-start justify-between gap-3 border-b border-slate-100 px-5 py-4">
          <div className="min-w-0 flex-1">
            <p className="text-[10.5px] font-bold uppercase tracking-[0.16em] text-slate-400">
              Rule packages
            </p>
            <h3 id="rule-pkg-title" className="mt-1 truncate text-[15px] font-semibold text-slate-950">
              {rule.shipviaCd} → {formatCarrierName(service.carrier)} · {service.name}
            </h3>
            <p className="mt-1 text-[11.5px] leading-5 text-slate-500">
              {cap != null
                ? <>Service max: <span className="font-semibold text-slate-800">{cap} lb</span>. </>
                : <>Service has no weight cap set. </>}
              Filter: {formatCarrierName(carrier)} + carrier-agnostic customs
              {originSet.size > 0
                ? <> · origin <span className="font-mono font-semibold text-slate-700">{[...originSet].sort().join(' / ')}</span></>
                : null}.
            </p>
          </div>
          <button
            type="button"
            aria-label="Close"
            onClick={onClose}
            className="rounded-xl border border-slate-200 bg-white p-2 text-slate-500 transition hover:bg-slate-50"
          >
            <FiX className="h-4 w-4" />
          </button>
        </header>

        <div className="flex-1 overflow-y-auto px-5 py-4">
          {requirePick ? (
            <div
              role="alert"
              className="mb-3 flex items-start gap-2 rounded-xl border border-amber-300 bg-amber-50 px-3 py-2.5 text-[11.5px] text-amber-900"
            >
              <FiAlertTriangle className="mt-0.5 h-3.5 w-3.5 shrink-0 text-amber-600" />
              <div>
                <p className="font-semibold">Packages required</p>
                <p className="mt-0.5 leading-4">
                  {requirePickReason ??
                    'Pick at least one package before saving. Save is disabled until then.'}
                </p>
              </div>
            </div>
          ) : null}

          {loading ? (
            <p className="rounded-xl border border-dashed border-slate-200 bg-white px-3 py-3 text-center text-[11.5px] text-slate-500">
              Loading…
            </p>
          ) : groups.length === 0 ? (
            <p className="rounded-xl border border-dashed border-slate-200 bg-white px-3 py-3 text-center text-[11.5px] text-slate-500">
              No compatible packages for {formatCarrierName(carrier)}. Add carrier packages or custom boxes on the Packages page.
            </p>
          ) : (
            <div className="space-y-1.5">
              {groups.map((g) => {
                const p = g.representative
                const state = groupState(g)
                const on = state !== 'none'
                const lb = toLb(p)
                const overCap = cap != null && lb != null && lb > cap
                return (
                  <button
                    key={g.key}
                    type="button"
                    onClick={() => toggleGroup(g)}
                    className={`flex w-full items-center gap-2.5 rounded-xl border px-3 py-2 text-left transition ${
                      on
                        ? overCap
                          ? 'border-amber-400 bg-amber-50'
                          : 'border-[#412d15] bg-[#412d15]/[0.06]'
                        : 'border-slate-200 bg-white hover:bg-slate-50'
                    }`}
                  >
                    <FiPackage className={`h-4 w-4 shrink-0 ${overCap ? 'text-amber-600' : on ? 'text-[#412d15]' : 'text-slate-500'}`} />
                    <div className="min-w-0 flex-1">
                      <p className="truncate text-[12px] font-semibold text-slate-900">
                        {p.name}
                        <span className="ml-1 font-normal text-slate-500">
                          · {p.kind}
                          {p.carrier ? ` · ${formatCarrierName(p.carrier)}` : ''}
                        </span>
                      </p>
                      <p className="text-[10.5px] text-slate-500">
                        {p.length && p.width && p.height
                          ? `${p.length}×${p.width}×${p.height} ${(p.dimUnit || 'in').toLowerCase()}`
                          : 'no dims'}
                        {p.maxWeight != null
                          ? ` · up to ${p.maxWeight} ${(p.weightUnit || 'lb').toLowerCase()}${lb != null && p.weightUnit === 'KG' ? ` (${Math.round(lb)} lb)` : ''}`
                          : ' · no max weight'}
                      </p>
                      {g.origins.length > 0 ? (
                        <p
                          className="mt-0.5 truncate text-[10.5px] text-slate-500"
                          title={
                            g.origins.length === 1
                              ? `Origin: ${g.origins[0]}`
                              : `${g.variantIds.length} origin variants — all get linked when picked`
                          }
                        >
                          Origins:{' '}
                          <span className="font-mono text-slate-600">
                            {g.origins.length <= 6 ? g.origins.join(' · ') : `${g.origins.slice(0, 6).join(' · ')} +${g.origins.length - 6}`}
                          </span>
                        </p>
                      ) : null}
                    </div>
                    {state === 'partial' ? (
                      <span
                        title="Only some origin variants are currently linked — click to link all."
                        className="inline-flex shrink-0 items-center rounded-full bg-slate-200 px-2 py-0.5 text-[10px] font-bold uppercase tracking-wide text-slate-700"
                      >
                        Partial
                      </span>
                    ) : null}
                    {overCap ? (
                      <span
                        title={`Exceeds the service's ${cap} lb cap`}
                        className="inline-flex shrink-0 items-center gap-1 rounded-full bg-amber-100 px-2 py-0.5 text-[10px] font-bold uppercase tracking-wide text-amber-800"
                      >
                        <FiAlertTriangle className="h-3 w-3" />
                        Over cap
                      </span>
                    ) : null}
                    {on ? <FiCheck className="h-4 w-4 shrink-0 text-[#412d15]" /> : null}
                  </button>
                )
              })}
            </div>
          )}

          <p className="mt-3 text-[11px] text-slate-500">
            {selectedGroupCount} package{selectedGroupCount === 1 ? '' : 's'} selected
            {selectedGroupCount === 0 ? ' (unrestricted)' : ` · ${selected.size} origin variant${selected.size === 1 ? '' : 's'} linked`}
            {overWeightCount > 0 ? ` · ${overWeightCount} over cap` : ''}
          </p>
        </div>

        <footer className="flex items-center justify-end gap-2 border-t border-slate-100 bg-slate-50/60 px-5 py-3">
          <button
            type="button"
            onClick={onClose}
            className="rounded-xl border border-slate-200 bg-white px-4 py-2 text-[13px] font-semibold text-slate-600 transition hover:bg-slate-100"
          >
            Cancel
          </button>
          <button
            type="button"
            onClick={() => void save()}
            disabled={saving || (requirePick && selected.size === 0)}
            title={
              requirePick && selected.size === 0
                ? 'Pick at least one package to enable Save.'
                : undefined
            }
            className="rounded-xl bg-[#1f150c] px-5 py-2 text-[13px] font-semibold text-white transition hover:bg-[#412d15] disabled:cursor-not-allowed disabled:opacity-50"
          >
            {saving ? 'Saving…' : 'Save packages'}
          </button>
        </footer>
      </aside>
    </>
  )
}
