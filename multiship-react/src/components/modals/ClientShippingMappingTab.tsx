import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { FiCheck, FiEdit3, FiGlobe, FiHome, FiPackage, FiPlus, FiTrash2 } from 'react-icons/fi'
import { notify } from '../../utils/notify'
import {
  shippingConfigService,
  type PackagePreset,
  type ShipMethodRule,
  type ShippingServiceItem,
} from '../../api/shippingConfigService'
import {
  clientWarehouseService,
  type ClientWarehouse,
  type Warehouse,
} from '../../api/warehouseService'
import { countriesInRegion, countryName, groupByRegion, type Region } from '../../utils/countries'
import { formatCarrierName } from '../../utils/carrierUtils'
import {
  groupKeyFor,
  groupPresets,
  groupServiceVariants,
  inferScope,
  logicalPackageCount as computeLogicalPackageCount,
  originCountriesFor,
  serviceEligible,
  type PresetGroup,
  type Scope,
  type ServiceGroup,
} from '../../utils/shippingMappingFilters'
import Select from '../workspace/Select'
import PortalMenu from '../workspace/PortalMenu'
import ZoneEditorModal from '../workspace/ZoneEditorModal'
import RulePackagesDrawer from './RulePackagesDrawer'

/** Codes of a rule's destination zone. Mirrors ShippingServiceMappingPage. */
const ruleCodes = (r: ShipMethodRule): string[] => {
  if (r.destType === 'COUNTRIES' && r.destValue) return r.destValue.split(/\s+/).filter(Boolean)
  if (r.destType === 'COUNTRY' && r.destValue) return [r.destValue]
  if (r.destType === 'REGION' && r.destValue) return countriesInRegion(r.destValue as Region).map((c) => c.code)
  return []
}

/** Compact zone label — collapses whole regions, else lists a few codes. */
function ZoneChips({ codes }: { codes: string[] }) {
  if (!codes.length) return <span className="text-[11.5px] text-slate-400">Anywhere</span>
  return (
    <span className="flex flex-wrap gap-1">
      {groupByRegion(codes).map((g) => {
        const full = g.codes.length === countriesInRegion(g.region).length
        return (
          <span
            key={g.region}
            title={g.codes.map(countryName).join(', ')}
            className="inline-flex items-center gap-1 rounded-full bg-sky-50 px-2 py-0.5 text-[10.5px] font-semibold text-sky-700 ring-1 ring-sky-100"
          >
            <FiGlobe className="h-3 w-3" />
            {full ? `${g.region} · all` : g.codes.length <= 3 ? g.codes.join(' ') : `${g.region} · ${g.codes.length}`}
          </span>
        )
      })}
    </span>
  )
}

const blankDraft = {
  shipviaCd: '',
  destCodes: [] as string[],
  serviceId: '',
  warehouseIds: [] as number[],
  /** Variant preset ids — a package group's every origin variant lives here
   *  when the group is picked, mirroring RulePackagesDrawer's save shape. */
  presetIds: [] as number[],
}

/**
 * Per-client Shipping Service Mapping tab.
 *
 * Column + form order (per operator ask):
 *   Order Ship Via → Warehouse → Ship to → Carrier Ship Via → Packages
 *
 * Cascading pickers:
 *  - Carrier Ship Via candidates are filtered by (a) the origin country of the
 *    selected warehouses and (b) the inferred DOMESTIC/INTERNATIONAL scope of
 *    the destination zone.
 *  - Packages open in RulePackagesDrawer, which itself filters presets by the
 *    rule's Carrier Ship Via (same-carrier or carrier-agnostic).
 *
 * Warehouse edit uses a PortalMenu popover so opening it does not resize the
 * table row (the prior inline expansion was pushing rows onto a second line).
 */
export default function ClientShippingMappingTab({ clientCode }: { clientCode: string }) {
  const [rules, setRules] = useState<ShipMethodRule[]>([])
  const [services, setServices] = useState<ShippingServiceItem[]>([])
  const [presets, setPresets] = useState<PackagePreset[]>([])
  const [attached, setAttached] = useState<ClientWarehouse[]>([])
  const [ruleWarehouseMap, setRuleWarehouseMap] = useState<Map<number, number[]>>(new Map())
  const [ruleIdToPresets, setRuleIdToPresets] = useState<Map<number, number[]>>(new Map())
  const [loading, setLoading] = useState(true)

  const [adding, setAdding] = useState(false)
  const [draft, setDraft] = useState({ ...blankDraft })
  const [zoneFor, setZoneFor] = useState<'new' | ShipMethodRule | null>(null)
  const [zoneCodes, setZoneCodes] = useState<string[]>([])
  /** RulePackagesDrawer target — a rule with a resolved service. */
  const [pkgFor, setPkgFor] = useState<ShipMethodRule | null>(null)
  /** Rules whose carrier was just switched — their previous package set was
   *  dropped in the process and the drawer opened with a "packages required"
   *  banner so the operator picks a replacement. Cleared when the drawer
   *  saves at least one package. */
  const [pendingPackagesForRules, setPendingPackagesForRules] = useState<Set<number>>(new Set())

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const [catalog, attachedResp, presetList] = await Promise.all([
        shippingConfigService.catalog(),
        clientWarehouseService.listForClient(clientCode),
        shippingConfigService.listPresets(),
      ])
      setServices(catalog.services)
      setPresets(presetList)
      setRules(catalog.rules.filter((r) => (r.clientCode || '') === clientCode))
      setAttached(attachedResp.data ?? [])
      const groupedWh = new Map<number, number[]>()
      for (const link of catalog.ruleWarehouses ?? []) {
        const cur = groupedWh.get(link.ruleId) ?? []
        cur.push(link.warehouseId)
        groupedWh.set(link.ruleId, cur)
      }
      setRuleWarehouseMap(groupedWh)
      const groupedPkg = new Map<number, number[]>()
      for (const link of catalog.rulePackages ?? []) {
        const cur = groupedPkg.get(link.ruleId) ?? []
        cur.push(link.presetId)
        groupedPkg.set(link.ruleId, cur)
      }
      setRuleIdToPresets(groupedPkg)
    } catch (error) {
      notify.error(error instanceof Error ? error.message : 'Failed to load mappings.')
    } finally {
      setLoading(false)
    }
  }, [clientCode])

  useEffect(() => {
    void load()
  }, [load])

  const serviceById = useMemo(() => new Map(services.map((s) => [s.id, s])), [services])
  const presetById = useMemo(() => {
    const m = new Map<number, PackagePreset>()
    for (const p of presets) if (p.id != null) m.set(p.id, p)
    return m
  }, [presets])
  const warehouseById = useMemo(() => {
    const m = new Map<number, Warehouse>()
    for (const link of attached) if (link.warehouse) m.set(link.warehouse.id, link.warehouse)
    return m
  }, [attached])

  /** Bound to the local presetById lookup; delegates to the shared helper. */
  const logicalPackageCount = useCallback(
    (presetIds: number[]) => computeLogicalPackageCount(presetIds, presetById),
    [presetById],
  )

  /**
   * The client's full origin footprint — union of ISO countries across every
   * attached warehouse. Used as the fallback origin filter for rows that have
   * no warehouse restriction ("any warehouse") so the picker still narrows to
   * origins this client actually operates from, instead of showing services
   * synced for unrelated markets. Empty set = the client has no warehouses
   * attached yet, in which case we don't filter by origin at all.
   */
  const clientOrigins = useMemo(() => {
    const s = new Set<string>()
    for (const link of attached) {
      const c = link.warehouse?.address?.country
      if (c) s.add(c.toUpperCase())
    }
    return s
  }, [attached])

  /** Effective origin filter for one rule: the rule's own warehouse origins
   *  when set, otherwise fall back to the client-wide footprint. */
  const effectiveOriginsForRule = useCallback(
    (whIds: number[]) => {
      const own = originCountriesFor(whIds, warehouseById)
      return own.size > 0 ? own : clientOrigins
    },
    [warehouseById, clientOrigins],
  )

  // Draft-time cascading: origin from picked warehouses → scope from dest codes
  // → filtered service list for the Carrier Ship Via picker.
  const draftOrigins = useMemo(
    () => {
      const own = originCountriesFor(draft.warehouseIds, warehouseById)
      return own.size > 0 ? own : clientOrigins
    },
    [draft.warehouseIds, warehouseById, clientOrigins],
  )
  const draftScope = useMemo(() => inferScope(draft.destCodes, draftOrigins), [draft.destCodes, draftOrigins])
  const draftServiceGroups = useMemo(
    () => groupServiceVariants(
      services.filter((s) => serviceEligible(s, draftOrigins, draftScope)),
      draftOrigins,
    ),
    [services, draftOrigins, draftScope],
  )
  /** All service ids that belong to one of the deduped groups above — used
   *  to validate the currently-picked draft serviceId still fits the filter. */
  const draftEligibleIds = useMemo(() => {
    const s = new Set<string>()
    for (const g of draftServiceGroups) {
      for (const v of g.variants) s.add(String(v.id))
    }
    return s
  }, [draftServiceGroups])

  // Derived selection: when the filter drops the currently picked service
  // (e.g. warehouse changed and no more services match), fall back to the
  // "unpicked" state at render time instead of mirroring it into local state
  // from an effect. That gives the Select the placeholder without cascading
  // renders — https://react.dev/learn/you-might-not-need-an-effect.
  const effectiveServiceId = useMemo(() => {
    if (!draft.serviceId) return ''
    return draftEligibleIds.has(draft.serviceId) ? draft.serviceId : ''
  }, [draft.serviceId, draftEligibleIds])

  /** Presets compatible with the draft's carrier + origins, collapsed to one
   *  entry per logical box. Empty until a Carrier Ship Via is picked because
   *  the carrier filter is what makes the list meaningful. */
  const draftPresetGroups = useMemo<PresetGroup[]>(() => {
    if (!effectiveServiceId) return []
    const svc = serviceById.get(Number(effectiveServiceId))
    if (!svc) return []
    const carrier = (svc.carrier || '').toUpperCase()
    const filtered = presets.filter((p) => {
      if (!p.enabled || p.id == null) return false
      // Carrier fit: CARRIER preset must match the picked service's carrier;
      // CUSTOM (kind !== 'CARRIER') is carrier-agnostic.
      if (p.kind === 'CARRIER' && (p.carrier || '').toUpperCase() !== carrier) return false
      // Origin fit: only CARRIER presets with an origin pinned can fail this.
      if (draftOrigins.size > 0 && p.kind === 'CARRIER' && p.originCountry) {
        if (!draftOrigins.has(p.originCountry.toUpperCase())) return false
      }
      return true
    })
    return groupPresets(filtered)
  }, [effectiveServiceId, serviceById, presets, draftOrigins])

  /** Group is "picked" iff every one of its variant ids is in draft.presetIds
   *  — matches the drawer's all-or-nothing group toggle semantics. */
  const draftSelectedGroupIds = useMemo(() => {
    const picked = new Set<number>()
    const selectedSet = new Set(draft.presetIds)
    for (const g of draftPresetGroups) {
      if (g.variantIds.every((id) => selectedSet.has(id))) picked.add(g.representative.id!)
    }
    return picked
  }, [draftPresetGroups, draft.presetIds])

  const openZone = (target: 'new' | ShipMethodRule) => {
    setZoneCodes(target === 'new' ? [...draft.destCodes] : ruleCodes(target))
    setZoneFor(target)
  }

  const closeAdd = () => {
    setAdding(false)
    setDraft({ ...blankDraft, destCodes: [], warehouseIds: [], presetIds: [] })
  }

  const saveDraft = async () => {
    if (!draft.shipviaCd.trim() || !effectiveServiceId) {
      notify.error('Enter the order ship-via code and pick a carrier service.')
      return
    }
    try {
      // Prune presetIds to only those still in the current filtered groups —
      // guards against a stale carrier / origin pick leaving invalid ids in
      // the draft between changes.
      const validPresetIds = (() => {
        const ok = new Set<number>()
        for (const g of draftPresetGroups) for (const id of g.variantIds) ok.add(id)
        return draft.presetIds.filter((id) => ok.has(id))
      })()
      await shippingConfigService.saveRule({
        shipviaCd: draft.shipviaCd.trim(),
        clientCode,
        destType: draft.destCodes.length ? 'COUNTRIES' : 'ANY',
        destValue: draft.destCodes.length ? draft.destCodes.join(' ') : null,
        serviceId: Number(effectiveServiceId),
        warehouseIds: draft.warehouseIds,
        allowedPresetIds: validPresetIds,
      })
      notify.success('Mapping added.')
      closeAdd()
      void load()
    } catch (error) {
      notify.error(error instanceof Error ? error.message : 'Failed to save the mapping.')
    }
  }

  const saveZone = async () => {
    if (zoneFor === 'new') {
      setDraft((c) => ({ ...c, destCodes: [...zoneCodes] }))
      setZoneFor(null)
      return
    }
    if (!zoneFor) return
    try {
      await shippingConfigService.saveRule({
        ...zoneFor,
        destType: zoneCodes.length ? 'COUNTRIES' : 'ANY',
        destValue: zoneCodes.length ? zoneCodes.join(' ') : null,
        // Preserve the rule's current warehouse and package sets — a zone
        // edit shouldn't silently wipe either transient list.
        warehouseIds: ruleWarehouseMap.get(zoneFor.id ?? -1) ?? [],
        allowedPresetIds: ruleIdToPresets.get(zoneFor.id ?? -1) ?? [],
      })
      notify.success('Destination zone updated.')
      setZoneFor(null)
      void load()
    } catch (error) {
      notify.error(error instanceof Error ? error.message : 'Failed to update the zone.')
    }
  }

  const saveRuleWarehouses = async (rule: ShipMethodRule, warehouseIds: number[]) => {
    try {
      await shippingConfigService.saveRule({
        ...rule,
        warehouseIds,
        allowedPresetIds: ruleIdToPresets.get(rule.id ?? -1) ?? [],
      })
      notify.success('Warehouses updated.')
      void load()
    } catch (error) {
      notify.error(error instanceof Error ? error.message : 'Failed to update warehouses.')
    }
  }

  const saveRuleService = async (rule: ShipMethodRule, serviceId: number) => {
    if (rule.serviceId === serviceId) return
    const oldSvc = serviceById.get(rule.serviceId)
    const newSvc = serviceById.get(serviceId)
    const currentPresetIds = ruleIdToPresets.get(rule.id ?? -1) ?? []
    const carrierChanged =
      (oldSvc?.carrier || '').toUpperCase() !== (newSvc?.carrier || '').toUpperCase()
    // A carrier switch with an existing package set invalidates the packages —
    // per operator ask, we ALWAYS drop the whole set on carrier change (even
    // CUSTOM boxes, which do fit but are cleared for a clean-slate re-pick),
    // then auto-open the packages drawer with a "required" gate.
    const willDropPackages = carrierChanged && currentPresetIds.length > 0

    if (willDropPackages) {
      const logicalCount = logicalPackageCount(currentPresetIds)
      const ok = await notify.confirm(
        `Switching carrier from ${formatCarrierName(oldSvc?.carrier || '—')} to ${formatCarrierName(newSvc?.carrier || '—')} will remove all ${logicalCount} linked package${logicalCount === 1 ? '' : 's'} on this rule. Continue and pick replacement package(s)?`,
        {
          title: 'Carrier change requires new packages',
          confirmLabel: 'Continue',
          danger: true,
        },
      )
      if (!ok) return // Abort switch entirely — service stays as-is, packages stay as-is.
    }

    try {
      const nextPresetIds = willDropPackages ? [] : currentPresetIds
      await shippingConfigService.saveRule({
        ...rule,
        serviceId,
        // Preserve warehouse restrictions unconditionally; packages only when
        // the carrier didn't change.
        warehouseIds: ruleWarehouseMap.get(rule.id ?? -1) ?? [],
        allowedPresetIds: nextPresetIds,
      })
      notify.success(
        willDropPackages
          ? (() => {
              const n = logicalPackageCount(currentPresetIds)
              return `Carrier service switched; ${n} package${n === 1 ? '' : 's'} cleared — pick replacement(s).`
            })()
          : 'Carrier service updated.',
      )

      if (willDropPackages && rule.id != null) {
        // Optimistically update the local map so the drawer + Packages cell
        // reflect the empty set immediately, without waiting for load().
        setRuleIdToPresets((cur) => {
          const next = new Map(cur)
          next.set(rule.id!, [])
          return next
        })
        setPendingPackagesForRules((cur) => {
          const next = new Set(cur)
          next.add(rule.id!)
          return next
        })
        // Open the drawer against a fresh copy of the rule so pkgFor reflects
        // the new serviceId immediately (load() will race in behind).
        setPkgFor({ ...rule, serviceId, allowedPresetIds: [] })
      }
      void load()
    } catch (error) {
      notify.error(error instanceof Error ? error.message : 'Failed to update the carrier service.')
    }
  }

  const removeRule = async (rule: ShipMethodRule) => {
    if (rule.id == null) return
    if (!(await notify.confirm(`Remove this ${rule.shipviaCd} mapping?`, {
      title: 'Remove mapping',
      confirmLabel: 'Remove',
      danger: true,
    }))) return
    try {
      await shippingConfigService.deleteRule(rule.id)
      void load()
    } catch (error) {
      notify.error(error instanceof Error ? error.message : 'Failed to remove the mapping.')
    }
  }

  return (
    <div
      id="client-editor-panel-mapping"
      role="tabpanel"
      aria-labelledby="mapping"
      className="px-5 py-4"
    >
      <div className="flex items-center justify-between gap-3">
        <div>
          <h4 className="text-[12.5px] font-semibold text-slate-950">Shipping service mapping</h4>
          <p className="text-[11px] leading-5 text-slate-500">
            Route this client's order ship-via codes to a carrier service, narrowed by origin
            warehouse and destination zone. Package options are filtered by the picked service.
          </p>
        </div>
        {!adding ? (
          <button
            type="button"
            onClick={() => setAdding(true)}
            className="inline-flex items-center gap-1.5 rounded-xl bg-[#1f150c] px-3 py-1.5 text-[12px] font-semibold text-white transition hover:bg-[#412d15]"
          >
            <FiPlus className="h-3.5 w-3.5" /> Add mapping
          </button>
        ) : null}
      </div>

      {/* Empty-state CTA — surfaces the "add your first mapping" action when
          the client has no rules yet. Skipped while the inline add form is
          open (the operator is already in that flow). */}
      {!loading && rules.length === 0 && !adding ? (
        <div className="mt-3 rounded-2xl border border-dashed border-slate-300 bg-slate-50/60 px-5 py-6 text-center">
          <div className="mx-auto inline-flex h-11 w-11 items-center justify-center rounded-xl bg-[#412d15]/10 text-[#412d15]">
            <FiPackage className="h-5 w-5" />
          </div>
          <p className="mt-2 text-[13.5px] font-semibold text-slate-950">No shipping-service mappings yet</p>
          <p className="mx-auto mt-1 max-w-md text-[11.5px] leading-4 text-slate-500">
            A mapping routes an order's ship-via code (e.g. <span className="font-mono">P80</span>) to a
            carrier service, optionally narrowed by warehouse origin and destination zone.
          </p>
          <button
            type="button"
            onClick={() => setAdding(true)}
            className="mt-3 inline-flex items-center gap-1.5 rounded-xl bg-[#1f150c] px-4 py-2 text-[12px] font-semibold text-white transition hover:bg-[#412d15]"
          >
            <FiPlus className="h-3.5 w-3.5" /> Add first mapping
          </button>
        </div>
      ) : null}

      {adding ? (
        <div className="mt-3 rounded-2xl border border-slate-200 bg-slate-50/60 px-3 py-2">
          {/* Compact single-row form — labels are inline (placeholder / value)
              to keep the panel one row tall. flex-wrap kicks in only under
              ~900px so the layout degrades gracefully on narrow screens. */}
          <div className="flex flex-wrap items-center gap-2">
            {/* 1) Order Ship Via */}
            <input
              value={draft.shipviaCd}
              onChange={(e) => setDraft((c) => ({ ...c, shipviaCd: e.target.value.toUpperCase() }))}
              placeholder="Ship via *"
              aria-label="Order Ship Via"
              className="w-28 rounded-xl border border-slate-200 bg-white px-2.5 py-1.5 font-mono text-[12.5px] font-semibold text-slate-950 outline-none transition focus:border-[#412d15]"
            />

            {/* 2) Warehouse — native <select multiple>. size=1 keeps it flush
                   with the other inputs; Cmd/Ctrl-click multi-selects. */}
            <select
              multiple
              size={1}
              value={draft.warehouseIds.map(String)}
              onChange={(e) => {
                const next = Array.from(e.target.selectedOptions).map((o) => Number(o.value))
                setDraft((c) => ({ ...c, warehouseIds: next }))
              }}
              aria-label="Warehouses"
              title="Ctrl/Cmd-click to pick multiple. No selection = any warehouse."
              className="h-[34px] w-40 rounded-xl border border-slate-200 bg-white px-2 text-[12.5px] font-semibold text-slate-700 outline-none transition focus:border-[#412d15]"
            >
              {attached.length === 0 ? (
                <option disabled value="">No warehouses attached</option>
              ) : (
                attached.map((link) => {
                  const wh = link.warehouse
                  if (!wh) return null
                  return (
                    <option key={wh.id} value={wh.id}>
                      {wh.code}{wh.address?.country ? ` · ${wh.address.country}` : ''}
                    </option>
                  )
                })
              )}
            </select>

            {/* 3) Ship to — compact button; count-only label to stay narrow */}
            <button
              type="button"
              onClick={() => openZone('new')}
              aria-label="Ship to destination zone"
              title={draft.destCodes.length ? draft.destCodes.join(', ') : 'Anywhere — click to narrow'}
              className="inline-flex h-[34px] w-40 items-center gap-1.5 rounded-xl border border-slate-200 bg-white px-2.5 text-left text-[12.5px] font-semibold text-slate-600 transition hover:border-[#412d15]/40"
            >
              <FiGlobe className="h-3.5 w-3.5 shrink-0 text-sky-600" />
              <span className="truncate">
                {draft.destCodes.length
                  ? `Ship to · ${draft.destCodes.length}`
                  : 'Ship to · any'}
              </span>
            </button>

            {/* 4) Carrier Ship Via — Select. Options deduped by (carrier,
                   service_code) so origin siblings collapse into one row. */}
            <div className="min-w-[220px] flex-1">
              <Select
                value={effectiveServiceId}
                onChange={(e) => {
                  const nextId = e.target.value
                  setDraft((c) => ({
                    ...c,
                    serviceId: nextId,
                    // Carrier may have flipped, invalidating current package
                    // picks — clear them here so the packages slot resets
                    // instead of showing stale ids that won't survive save.
                    presetIds: [],
                  }))
                }}
                aria-label="Carrier Ship Via"
              >
                <option value="">
                  Carrier Ship Via *{draftServiceGroups.length ? '' : ' — no match'}
                </option>
                {draftServiceGroups.map((g) => {
                  const s = g.representative
                  return (
                    <option key={g.key} value={s.id}>
                      {formatCarrierName(s.carrier)} — {s.name}
                      {s.scope && s.scope !== 'BOTH' ? ` · ${s.scope}` : ''}
                    </option>
                  )
                })}
              </Select>
            </div>

            {/* 5) Packages — inline picker for the same allowlist that the row-level
                   drawer edits after save. Options come from draftPresetGroups
                   (filtered by carrier + origin, then origin-siblings collapsed
                   to one entry). Values are group representative ids; the change
                   handler expands the picked group into all its origin variant
                   ids so the persisted set matches the drawer's semantics. */}
            <select
              multiple
              size={1}
              value={[...draftSelectedGroupIds].map(String)}
              onChange={(e) => {
                const pickedReps = new Set(Array.from(e.target.selectedOptions).map((o) => Number(o.value)))
                const nextIds: number[] = []
                for (const g of draftPresetGroups) {
                  if (pickedReps.has(g.representative.id!)) nextIds.push(...g.variantIds)
                }
                setDraft((c) => ({ ...c, presetIds: nextIds }))
              }}
              disabled={!effectiveServiceId || draftPresetGroups.length === 0}
              aria-label="Packages"
              title={
                !effectiveServiceId
                  ? 'Pick a Carrier Ship Via first — packages are filtered by carrier + origin.'
                  : draftPresetGroups.length === 0
                    ? 'No packages match this carrier + origin.'
                    : 'Ctrl/Cmd-click to pick multiple. Leave empty for unrestricted.'
              }
              className="h-[34px] w-44 rounded-xl border border-slate-200 bg-white px-2 text-[12.5px] font-semibold text-slate-700 outline-none transition focus:border-[#412d15] disabled:cursor-not-allowed disabled:bg-slate-100 disabled:text-slate-400"
            >
              {!effectiveServiceId ? (
                <option disabled value="">Packages — pick service first</option>
              ) : draftPresetGroups.length === 0 ? (
                <option disabled value="">No compatible packages</option>
              ) : (
                draftPresetGroups.map((g) => (
                  <option key={g.key} value={g.representative.id!}>
                    {g.representative.name}
                  </option>
                ))
              )}
            </select>

            <div className="ml-auto flex shrink-0 items-center gap-1.5">
              <button
                type="button"
                onClick={closeAdd}
                className="rounded-xl border border-slate-200 bg-white px-3 py-1.5 text-[12px] font-semibold text-slate-600 transition hover:bg-slate-100"
              >
                Cancel
              </button>
              <button
                type="button"
                onClick={() => void saveDraft()}
                disabled={!draft.shipviaCd.trim() || !effectiveServiceId}
                className="rounded-xl bg-[#1f150c] px-4 py-1.5 text-[12px] font-semibold text-white transition hover:bg-[#412d15] disabled:cursor-not-allowed disabled:bg-slate-300"
              >
                Save
              </button>
            </div>
          </div>

          {/* Filter context below the row — services + packages summaries */}
          <div className="mt-1.5 flex flex-wrap items-center gap-x-3 gap-y-0.5 text-[10.5px] text-slate-500">
            <span className={draftServiceGroups.length === 0 ? 'text-amber-700' : ''}>
              {draftServiceGroups.length} service option{draftServiceGroups.length === 1 ? '' : 's'}
              {draftOrigins.size ? ` from ${[...draftOrigins].join(' / ')}` : ' from any origin'}
              {draftScope !== 'ANY' ? ` · ${draftScope}` : ''}
            </span>
            <span>
              <FiPackage className="mr-1 inline h-3 w-3 -translate-y-px" />
              {!effectiveServiceId
                ? 'Packages unlock once a Carrier Ship Via is picked.'
                : draftSelectedGroupIds.size === 0
                  ? `${draftPresetGroups.length} package${draftPresetGroups.length === 1 ? '' : 's'} available · none picked = unrestricted`
                  : `${draftSelectedGroupIds.size} of ${draftPresetGroups.length} package${draftPresetGroups.length === 1 ? '' : 's'} picked`}
            </span>
          </div>
        </div>
      ) : null}

      <div className="mt-3 overflow-x-auto rounded-2xl border border-slate-200 bg-white">
        <table className="w-full text-left">
          <thead className="bg-slate-50/60">
            <tr>
              <Th>Order Ship Via</Th>
              <Th>Warehouse</Th>
              <Th>Ship to</Th>
              <Th>Carrier Ship Via</Th>
              <Th>Packages</Th>
              <Th className="text-right">Actions</Th>
            </tr>
          </thead>
          <tbody className="divide-y divide-slate-100">
            {loading ? (
              <tr>
                <td colSpan={6} className="px-3 py-6 text-center text-[12px] text-slate-500">Loading…</td>
              </tr>
            ) : rules.length === 0 ? (
              <tr>
                <td colSpan={6} className="px-3 py-6 text-center text-[12px] text-slate-500">
                  No mappings yet — click Add mapping to create one.
                </td>
              </tr>
            ) : rules.map((rule) => {
              const svc = serviceById.get(rule.serviceId)
              const whIds = ruleWarehouseMap.get(rule.id ?? -1) ?? []
              const pkgIds = ruleIdToPresets.get(rule.id ?? -1) ?? []
              return (
                <tr key={rule.id} className="align-middle">
                  <Td>
                    <span className="rounded-lg bg-[#1f150c] px-2.5 py-1 font-mono text-[11.5px] font-bold text-[#e1dcc9]">
                      {rule.shipviaCd}
                    </span>
                  </Td>
                  <Td>
                    <WarehouseCell
                      choices={attached}
                      warehouseById={warehouseById}
                      value={whIds}
                      onSave={(next) => void saveRuleWarehouses(rule, next)}
                    />
                  </Td>
                  <Td>
                    <button
                      type="button"
                      onClick={() => openZone(rule)}
                      className="inline-flex items-center gap-1 rounded-lg border border-transparent px-1 py-0.5 hover:border-slate-200 hover:bg-slate-50"
                      title="Edit destination zone"
                    >
                      <ZoneChips codes={ruleCodes(rule)} />
                    </button>
                  </Td>
                  <Td>
                    {(() => {
                      const rowOrigins = effectiveOriginsForRule(whIds)
                      return (
                        <CarrierServiceCell
                          services={services}
                          current={svc ?? null}
                          currentServiceId={rule.serviceId}
                          origins={rowOrigins}
                          scope={inferScope(ruleCodes(rule), rowOrigins)}
                          onPick={(serviceId) => void saveRuleService(rule, serviceId)}
                        />
                      )
                    })()}
                  </Td>
                  <Td>
                    {(() => {
                      const needsPackages = rule.id != null && pendingPackagesForRules.has(rule.id)
                      const logicalCount = logicalPackageCount(pkgIds)
                      return (
                        <button
                          type="button"
                          onClick={() => setPkgFor(rule)}
                          disabled={!svc || rule.id == null}
                          title={
                            needsPackages
                              ? 'Packages required — carrier was switched, previous packages were cleared. Click to pick a replacement.'
                              : logicalCount
                                ? `${logicalCount} package${logicalCount === 1 ? '' : 's'} allowed (${pkgIds.length} origin variant${pkgIds.length === 1 ? '' : 's'})`
                                : 'No package restriction — click to add'
                          }
                          className={`inline-flex items-center gap-1 rounded-lg border px-2 py-1 text-[10.5px] font-bold transition ${
                            needsPackages
                              ? 'animate-pulse border-amber-400 bg-amber-50 text-amber-800 ring-2 ring-amber-200 hover:bg-amber-100'
                              : logicalCount
                                ? 'border-[#412d15]/25 bg-[#412d15]/[0.06] text-[#412d15] hover:bg-[#412d15]/10'
                                : 'border-slate-200 bg-white text-slate-500 hover:bg-slate-50'
                          } disabled:cursor-not-allowed disabled:opacity-40`}
                        >
                          <FiPackage className="h-3 w-3" />
                          {needsPackages ? 'Required' : logicalCount || '+'}
                        </button>
                      )
                    })()}
                  </Td>
                  <Td className="text-right">
                    <button
                      type="button"
                      onClick={() => void removeRule(rule)}
                      aria-label={`Remove mapping ${rule.shipviaCd}`}
                      className="inline-flex rounded-lg border border-rose-200 bg-white p-1.5 text-rose-600 transition hover:bg-rose-50"
                    >
                      <FiTrash2 className="h-3.5 w-3.5" />
                    </button>
                  </Td>
                </tr>
              )
            })}
          </tbody>
        </table>
      </div>

      <ZoneEditorModal
        open={Boolean(zoneFor)}
        codes={zoneCodes}
        onCodesChange={setZoneCodes}
        onSave={saveZone}
        onClose={() => setZoneFor(null)}
        subject={
          zoneFor && zoneFor !== 'new' ? (
            <>
              Mapping <span className="font-mono font-bold text-slate-700">{zoneFor.shipviaCd}</span> · {clientCode}
            </>
          ) : undefined
        }
        saveLabel={zoneFor === 'new' ? 'Use this zone' : 'Save zone'}
        domesticCountry={null}
      />

      {pkgFor && pkgFor.id != null ? (() => {
        const svc = serviceById.get(pkgFor.serviceId)
        if (!svc) return null
        const whIds = ruleWarehouseMap.get(pkgFor.id!) ?? []
        const requirePick = pendingPackagesForRules.has(pkgFor.id!)
        return (
          <RulePackagesDrawer
            rule={pkgFor}
            service={svc}
            originCountries={[...effectiveOriginsForRule(whIds)]}
            currentWarehouseIds={whIds}
            initialPresetIds={ruleIdToPresets.get(pkgFor.id!) ?? []}
            requirePick={requirePick}
            requirePickReason={
              requirePick
                ? `Carrier switched to ${formatCarrierName(svc.carrier)} — the previous packages were cleared and at least one replacement is required.`
                : undefined
            }
            onClose={() => setPkgFor(null)}
            onSaved={(nextIds) => {
              setRuleIdToPresets((cur) => {
                const next = new Map(cur)
                next.set(pkgFor.id!, nextIds)
                return next
              })
              // Clear the "packages required" flag once at least one package
              // was actually saved; if the operator saves an empty set we
              // leave the flag on so the row keeps its "packages required"
              // highlight until they come back and pick one.
              if (nextIds.length > 0) {
                setPendingPackagesForRules((cur) => {
                  const next = new Set(cur)
                  next.delete(pkgFor.id!)
                  return next
                })
              }
              setPkgFor(null)
            }}
          />
        )
      })() : null}
    </div>
  )
}

function Th({ children, className = '' }: { children: React.ReactNode; className?: string }) {
  return (
    <th className={`px-3 py-2 text-[10px] font-bold uppercase tracking-[0.14em] text-slate-500 ${className}`}>
      {children}
    </th>
  )
}

function Td({ children, className = '' }: { children: React.ReactNode; className?: string }) {
  return <td className={`px-3 py-2 text-[12px] ${className}`}>{children}</td>
}

/**
 * Per-row Warehouse cell. Read state shows compact chips + a small edit
 * affordance. Editing opens a PortalMenu popover anchored to the cell so the
 * table row never resizes (the prior inline expansion pushed rows onto a
 * second line and shifted everything below).
 */
function WarehouseCell({
  choices,
  warehouseById,
  value,
  onSave,
}: {
  choices: ClientWarehouse[]
  warehouseById: Map<number, Warehouse>
  value: number[]
  onSave: (next: number[]) => void
}) {
  const [open, setOpen] = useState(false)
  const [draft, setDraft] = useState<number[]>(value)
  const anchorRef = useRef<HTMLButtonElement>(null)

  const toggle = (id: number) => {
    setDraft((cur) => (cur.includes(id) ? cur.filter((x) => x !== id) : [...cur, id]))
  }

  const openPopover = () => {
    // Snapshot the persisted value into the local draft each time the popover
    // opens — cheaper than a sync effect (no cascading render) and keeps
    // Cancel from leaking previously-cancelled edits back in.
    setDraft(value)
    setOpen(true)
  }

  return (
    <>
      <button
        ref={anchorRef}
        type="button"
        onClick={() => (open ? setOpen(false) : openPopover())}
        aria-haspopup="dialog"
        aria-expanded={open}
        title="Edit warehouses"
        className="inline-flex max-w-full flex-wrap items-center gap-1 rounded-lg border border-transparent px-1 py-0.5 text-left transition hover:border-slate-200 hover:bg-slate-50"
      >
        {value.length === 0 ? (
          <span className="text-[11.5px] text-slate-400">Any warehouse</span>
        ) : (
          value.map((id) => {
            const wh = warehouseById.get(id)
            return (
              <span
                key={id}
                className="inline-flex items-center gap-1 rounded-full bg-[#412d15]/10 px-2 py-0.5 text-[10.5px] font-semibold text-[#412d15]"
              >
                <FiHome className="h-3 w-3" />
                {wh?.code ?? `#${id}`}
              </span>
            )
          })
        )}
        <FiEdit3 className="h-3 w-3 text-slate-400" />
      </button>
      <PortalMenu open={open} anchorRef={anchorRef} onClose={() => setOpen(false)} width={280}>
        <div className="max-h-64 overflow-y-auto p-2">
          {choices.length === 0 ? (
            <p className="px-2 py-1 text-[11.5px] text-slate-500">
              No warehouses attached — add one from the Warehouses tab.
            </p>
          ) : (
            <>
              <button
                type="button"
                onClick={() => setDraft([])}
                className={`flex w-full items-center gap-2 rounded-lg px-2 py-1.5 text-[12px] font-semibold transition ${
                  draft.length === 0
                    ? 'bg-[#412d15] text-white'
                    : 'text-slate-600 hover:bg-slate-50'
                }`}
              >
                Any warehouse
              </button>
              <div className="my-1 border-t border-slate-100" />
              {choices.map((link) => {
                const wh = link.warehouse
                if (!wh) return null
                const on = draft.includes(wh.id)
                return (
                  <label
                    key={wh.id}
                    className="flex cursor-pointer items-center gap-2 rounded-lg px-2 py-1.5 text-[12px] transition hover:bg-slate-50"
                  >
                    <input
                      type="checkbox"
                      checked={on}
                      onChange={() => toggle(wh.id)}
                      className="h-3.5 w-3.5 rounded border-slate-300 text-[#412d15] focus:ring-[#412d15]"
                    />
                    <FiHome className="h-3 w-3 text-slate-500" />
                    <span className="flex-1 truncate font-semibold text-slate-800">
                      {wh.code}
                      <span className="ml-1 font-normal text-slate-500">
                        {wh.name}
                        {wh.address?.country ? ` · ${wh.address.country}` : ''}
                      </span>
                    </span>
                  </label>
                )
              })}
            </>
          )}
        </div>
        <div className="flex items-center justify-end gap-1.5 border-t border-slate-100 bg-slate-50/60 px-2 py-1.5">
          <button
            type="button"
            onClick={() => setOpen(false)}
            className="rounded-lg border border-slate-200 bg-white px-2.5 py-1 text-[11px] font-semibold text-slate-600 transition hover:bg-slate-100"
          >
            Cancel
          </button>
          <button
            type="button"
            onClick={() => {
              onSave(draft)
              setOpen(false)
            }}
            className="rounded-lg bg-[#1f150c] px-2.5 py-1 text-[11px] font-semibold text-white transition hover:bg-[#412d15]"
          >
            Save
          </button>
        </div>
      </PortalMenu>
    </>
  )
}

/**
 * Per-row Carrier Ship Via cell — click to open a filtered service picker
 * anchored via PortalMenu (same "escape the table row" trick as WarehouseCell,
 * so the row height stays fixed).
 *
 * The candidate list applies the same cascading filter used by the add form
 * (warehouse origin + destination scope). The currently persisted service is
 * always shown at the top even when it no longer matches the filter — an
 * out-of-scope save from earlier stays visible/editable rather than vanishing
 * silently. Single-select: clicking a row commits + closes.
 */
function CarrierServiceCell({
  services,
  current,
  currentServiceId,
  origins,
  scope,
  onPick,
}: {
  services: ShippingServiceItem[]
  current: ShippingServiceItem | null
  currentServiceId: number
  origins: Set<string>
  scope: Scope
  onPick: (serviceId: number) => void
}) {
  const [open, setOpen] = useState(false)
  const anchorRef = useRef<HTMLButtonElement>(null)

  // Bucket variants by (carrier, service_code) so the picker shows one row
  // per logical service instead of one row per origin variant that the
  // connector sync split into separate shipping_service rows.
  const eligibleGroups = useMemo(
    () => groupServiceVariants(
      services.filter((s) => serviceEligible(s, origins, scope)),
      origins,
    ),
    [services, origins, scope],
  )

  const currentKey = current ? groupKeyFor(current) : null

  // If the persisted service is no longer eligible under the current filter,
  // still surface its group at the top so the operator can see + change
  // what's saved. Build a synthetic group from every sibling variant that
  // shares the current row's key, using the same picker logic.
  const listed = useMemo<ServiceGroup[]>(() => {
    if (!current || !currentKey) return eligibleGroups
    if (eligibleGroups.some((g) => g.key === currentKey)) return eligibleGroups
    const siblingVariants = services.filter((s) => groupKeyFor(s) === currentKey)
    const [carry] = groupServiceVariants(siblingVariants, origins)
    return carry ? [carry, ...eligibleGroups] : eligibleGroups
  }, [eligibleGroups, current, currentKey, services, origins])

  return (
    <>
      <button
        ref={anchorRef}
        type="button"
        onClick={() => setOpen((c) => !c)}
        aria-haspopup="dialog"
        aria-expanded={open}
        title="Edit carrier service"
        className="inline-flex items-center gap-1.5 rounded-lg border border-slate-200 bg-white px-2 py-1 text-[11.5px] font-semibold text-slate-700 transition hover:bg-slate-50"
      >
        {current ? (
          <>
            <span
              className={`inline-block h-1.5 w-1.5 rounded-full ${current.enabled ? 'bg-emerald-500' : 'bg-amber-500'}`}
              aria-hidden
            />
            {formatCarrierName(current.carrier)} — {current.name}
          </>
        ) : (
          <span className="text-slate-400">Pick service</span>
        )}
        <FiEdit3 className="h-3 w-3 text-slate-400" />
      </button>
      <PortalMenu open={open} anchorRef={anchorRef} onClose={() => setOpen(false)} width={360}>
        <div className="max-h-80 overflow-y-auto p-2">
          <p className="mb-1 px-2 text-[10px] font-bold uppercase tracking-[0.14em] text-slate-400">
            {listed.length} option{listed.length === 1 ? '' : 's'}
            {origins.size ? ` · from ${[...origins].join('/')}` : ' · any origin'}
            {scope !== 'ANY' ? ` · ${scope}` : ''}
          </p>
          {listed.length === 0 ? (
            <p className="px-2 py-2 text-[11.5px] text-slate-500">
              No enabled services match this row's warehouse / destination.
            </p>
          ) : listed.map((g) => {
            const s = g.representative
            const picked = g.variants.some((v) => v.id === currentServiceId)
            const outOfFilter =
              current && g.key === currentKey && !eligibleGroups.some((e) => e.key === currentKey)
            return (
              <button
                key={g.key}
                type="button"
                onClick={() => {
                  onPick(s.id)
                  setOpen(false)
                }}
                className={`flex w-full items-center gap-2 rounded-lg px-2 py-1.5 text-left text-[12px] transition ${
                  picked ? 'bg-[#412d15]/[0.08] text-[#412d15]' : 'text-slate-700 hover:bg-slate-50'
                }`}
              >
                <span
                  className={`inline-block h-1.5 w-1.5 shrink-0 rounded-full ${s.enabled ? 'bg-emerald-500' : 'bg-amber-500'}`}
                  aria-hidden
                />
                <span className="flex-1 truncate font-semibold">
                  {formatCarrierName(s.carrier)} — {s.name}
                  {s.scope && s.scope !== 'BOTH' ? (
                    <span className="ml-1 font-normal text-slate-400">· {s.scope}</span>
                  ) : null}
                  {g.origins.length > 0 ? (
                    <span
                      className="ml-1 font-normal text-slate-500"
                      title={
                        g.variants.length === 1
                          ? `Origin: ${g.origins[0] || 'any'}`
                          : `${g.variants.length} origin variants — the best-fit one is saved`
                      }
                    >
                      ({g.origins.length <= 3 ? g.origins.join('·') : `${g.origins.slice(0, 3).join('·')}+${g.origins.length - 3}`})
                    </span>
                  ) : null}
                  {outOfFilter ? (
                    <span
                      title="Saved earlier under a different warehouse/destination"
                      className="ml-1 rounded-full bg-amber-100 px-1.5 py-0.5 text-[9px] font-bold uppercase tracking-wide text-amber-800"
                    >
                      out of scope
                    </span>
                  ) : null}
                </span>
                {picked ? <FiCheck className="h-3 w-3 shrink-0" /> : null}
              </button>
            )
          })}
        </div>
      </PortalMenu>
    </>
  )
}
