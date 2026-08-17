import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useOutletContext } from 'react-router-dom'
import { notify } from '../utils/notify'
import { FiEdit3, FiExternalLink, FiFilter, FiGlobe, FiHome, FiLink, FiPlus, FiTrash2, FiTruck, FiUser, FiX } from 'react-icons/fi'
import type { ColumnDef } from '@tanstack/react-table'
import {
  shippingConfigService,
  type PackagePreset,
  type ShipMethodRule,
  type ShippingServiceItem,
} from '../api/shippingConfigService'
import { clientService, type Client } from '../api/clientService'
import { warehouseService, type Warehouse } from '../api/warehouseService'
import { accountRefService, type CarrierAccountRef } from '../api/accountRefService'
import { computeAllowedCarriers, platformAccounts as platformAccountsFrom } from '../utils/allowedCarriers'
import { countriesInRegion, countryName, groupByRegion, regionOf, REGIONS, type Region } from '../utils/countries'
import { formatCarrierName } from '../utils/carrierUtils'
import {
  filterPresetsForCarrierAndOrigin,
  groupKeyFor,
  groupPresets,
  groupServiceVariants,
  inferScope,
  logicalPackageCount as computeLogicalPackageCount,
  originCountriesFor,
  type PresetGroup,
  type ServiceGroup,
} from '../utils/shippingMappingFilters'
import AdvancedDataTable, { type EditCellProps, type EditNav } from './workspace/AdvancedDataTable'
import type { SettingsOutletContext } from './layout/SettingsLayout'
import PortalMenu from './workspace/PortalMenu'
import Select from './workspace/Select'
import ZoneEditorModal from './workspace/ZoneEditorModal'
import RulePackagesDrawer from './modals/RulePackagesDrawer'
import { FiPackage } from 'react-icons/fi'

/** Supported carriers — matches the ShippingServicesPage catalog. */
const CARRIERS = ['UPS', 'FEDEX', 'USPS'] as const

const filterLabelClass = 'mb-1 flex items-center gap-1 text-[10px] font-bold uppercase tracking-[0.14em] text-slate-400'

const blankRule = {
  shipviaCd: '',
  clientCode: '',
  destCodes: [] as string[],
  serviceId: '',
  /** Warehouse restriction on the draft rule — matches the ShipMethodRule
   *  transient field the backend persists into ship_method_rule_warehouse. */
  warehouseIds: [] as number[],
  /** Package variant ids picked at add-time — a group's variants all live in
   *  here when the group is picked, mirroring RulePackagesDrawer's shape. */
  presetIds: [] as number[],
}

/** Codes of a rule's destination zone (legacy single-value types included). */
const ruleCodes = (r: ShipMethodRule): string[] => {
  if (r.destType === 'COUNTRIES' && r.destValue) return r.destValue.split(/\s+/).filter(Boolean)
  if (r.destType === 'COUNTRY' && r.destValue) return [r.destValue]
  if (r.destType === 'REGION' && r.destValue) return countriesInRegion(r.destValue as never).map((c) => c.code)
  return []
}

/** Compact zone label: whole regions collapse ("Europe · all"), partial show codes. */
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
            {full
              ? `${g.region} · all`
              : g.codes.length <= 3
                ? g.codes.join(' ')
                : `${g.region} · ${g.codes.length}`}
          </span>
        )
      })}
    </span>
  )
}

/** Specificity badge parts: what narrows this rule. Used for accessible labels. */
const ruleScope = (r: ShipMethodRule) => {
  const parts: string[] = []
  if (r.clientCode) parts.push(r.clientCode)
  if (r.destType === 'COUNTRY' && r.destValue) parts.push(countryName(r.destValue))
  if (r.destType === 'REGION' && r.destValue) parts.push(r.destValue)
  return parts
}

/**
 * Shared keyboard + blur handling for inline cell editors. Enter/Tab commit
 * (with direction), Escape reverts, and clicking outside auto-saves.
 * Extracted so each editor stays terse.
 */
function useCellKeyHandlers(
  save: (dir: EditNav) => void | Promise<void>,
  cancel: () => void,
) {
  return {
    onKeyDown: (e: React.KeyboardEvent) => {
      if (e.key === 'Tab') {
        e.preventDefault()
        void save(e.shiftKey ? 'left' : 'right')
      } else if (e.key === 'Enter') {
        e.preventDefault()
        void save(e.shiftKey ? 'up' : 'down')
      } else if (e.key === 'Escape') {
        e.preventDefault()
        cancel()
      }
    },
    onBlur: () => {
      void save('none')
    },
  }
}

/** Inline editor for the Client cell — Select of client codes. */
function ClientEditor({
  row,
  finish,
  cancel,
  clients,
  onSaveField,
}: EditCellProps<ShipMethodRule> & {
  clients: Client[]
  onSaveField: (row: ShipMethodRule, patch: Partial<ShipMethodRule>) => Promise<void>
}) {
  const [value, setValue] = useState(row.clientCode || '')
  const [busy, setBusy] = useState(false)
  const save = async (dir: EditNav) => {
    if (busy) return
    if ((row.clientCode || '') === value) {
      finish(dir)
      return
    }
    setBusy(true)
    try {
      await onSaveField(row, { clientCode: value || null })
      finish(dir)
    } catch (e) {
      notify.apiError(e, 'Failed to update mapping.')
    } finally {
      setBusy(false)
    }
  }
  const handlers = useCellKeyHandlers(save, cancel)
  return (
    <Select autoFocus value={value} onChange={(e) => setValue(e.target.value)} {...handlers}>
      <option value="">Any client</option>
      {clients.map((c) => (
        <option key={c.clientCode} value={c.clientCode}>
          {c.clientCode}
        </option>
      ))}
    </Select>
  )
}

/** Inline editor for the Order Ship Via cell — free-text input, uppercased. */
function ShipViaEditor({
  row,
  finish,
  cancel,
  onSaveField,
}: EditCellProps<ShipMethodRule> & {
  onSaveField: (row: ShipMethodRule, patch: Partial<ShipMethodRule>) => Promise<void>
}) {
  const [value, setValue] = useState(row.shipviaCd)
  const [busy, setBusy] = useState(false)
  const save = async (dir: EditNav) => {
    if (busy) return
    const trimmed = value.trim()
    if (!trimmed) {
      notify.error('Order Ship Via cannot be empty.')
      return
    }
    if (row.shipviaCd === trimmed) {
      finish(dir)
      return
    }
    setBusy(true)
    try {
      await onSaveField(row, { shipviaCd: trimmed })
      finish(dir)
    } catch (e) {
      notify.apiError(e, 'Failed to update mapping.')
    } finally {
      setBusy(false)
    }
  }
  const handlers = useCellKeyHandlers(save, cancel)
  return (
    <input
      autoFocus
      value={value}
      onChange={(e) => setValue(e.target.value.toUpperCase())}
      className="w-full rounded-lg border border-slate-200 bg-white px-2 py-1 font-mono text-[12px] font-semibold text-slate-950 outline-none transition focus:border-[#412d15]"
      {...handlers}
    />
  )
}

/** Inline editor for the Carrier Ship Via cell — Select of enabled services.
 *  Services whose scope doesn't match the mapping's inferred scope are grayed
 *  out (still selectable) so the user sees the whole catalog and picks with
 *  awareness of fit. */
function ServiceEditor({
  row,
  finish,
  cancel,
  services,
  onSaveField,
  scopeMatch,
}: EditCellProps<ShipMethodRule> & {
  services: ShippingServiceItem[]
  onSaveField: (row: ShipMethodRule, patch: Partial<ShipMethodRule>) => Promise<void>
  /** Called per service to decide if it matches the row's mapping scope. */
  scopeMatch: (svc: ShippingServiceItem) => boolean
}) {
  const [value, setValue] = useState(String(row.serviceId))
  const [busy, setBusy] = useState(false)
  const save = async (dir: EditNav) => {
    if (busy) return
    const nextId = Number(value)
    if (!nextId) {
      notify.error('Pick a carrier service.')
      return
    }
    if (row.serviceId === nextId) {
      finish(dir)
      return
    }
    setBusy(true)
    try {
      await onSaveField(row, { serviceId: nextId })
      finish(dir)
    } catch (e) {
      notify.apiError(e, 'Failed to update mapping.')
    } finally {
      setBusy(false)
    }
  }
  const handlers = useCellKeyHandlers(save, cancel)
  return (
    <Select autoFocus value={value} onChange={(e) => setValue(e.target.value)} {...handlers}>
      {services.map((s) => {
        const fits = scopeMatch(s)
        const scopeTag = s.scope === 'DOMESTIC' ? ' [D]' : s.scope === 'INTERNATIONAL' ? ' [I]' : ' [D+I]'
        return (
          <option
            key={s.id}
            value={s.id}
            disabled={!s.enabled}
            style={fits ? undefined : { color: '#94a3b8' }}
          >
            {s.carrier} — {s.name}{scopeTag}{s.enabled ? '' : ' (disabled)'}{fits ? '' : ' — scope mismatch'}
          </option>
        )
      })}
    </Select>
  )
}

/**
 * Shipping Service Mapping — split out from the Shipping Services page so it
 * has room to grow as more carriers/services are added. The page owns its
 * own catalog fetch (services list is needed to render the "Resolves to"
 * dropdown).
 */
export default function ShippingServiceMappingPage() {
  const [rules, setRules] = useState<ShipMethodRule[]>([])
  const [services, setServices] = useState<ShippingServiceItem[]>([])
  const [clients, setClients] = useState<Client[]>([])
  const [warehouses, setWarehouses] = useState<Warehouse[]>([])
  const [presets, setPresets] = useState<PackagePreset[]>([])
  /** Carrier accounts (client + platform) — narrows Ship Via to services
   *  the mapping can actually ship on. */
  const [accounts, setAccounts] = useState<CarrierAccountRef[]>([])
  const [loading, setLoading] = useState(true)
  const [newRule, setNewRule] = useState({ ...blankRule })
  /** Platform account the operator picked to seed / extend the new-rule
   *  Ship Via carrier filter. Reset whenever the client changes. */
  const [newRulePlatformAccountId, setNewRulePlatformAccountId] = useState<number | null>(null)
  /** Zone modal target: 'new' = the add-row, otherwise the rule being edited. */
  const [zoneFor, setZoneFor] = useState<'new' | ShipMethodRule | null>(null)
  const [zoneCodes, setZoneCodes] = useState<string[]>([])
  /** Phase 6: allowed presets per rule id — read from catalog.rulePackages. */
  const [ruleIdToPresets, setRuleIdToPresets] = useState<Map<number, number[]>>(new Map())
  /** Warehouse restrictions per rule id — read from catalog.ruleWarehouses. */
  const [ruleWarehouseMap, setRuleWarehouseMap] = useState<Map<number, number[]>>(new Map())
  /** Packages drawer target — the rule whose allowed set is being edited. */
  const [pkgFor, setPkgFor] = useState<ShipMethodRule | null>(null)
  /** Rules that just had a carrier switch which dropped their previous package
   *  set — the drawer opens with a "packages required" gate for these. */
  const [pendingPackagesForRules, setPendingPackagesForRules] = useState<Set<number>>(new Set())

  // Advanced toolbar state (search + collapsible filter panel).
  const [ruleQuery, setRuleQuery] = useState('')
  const [ruleMethodFilter, setRuleMethodFilter] = useState('')
  const [ruleClientFilter, setRuleClientFilter] = useState('')
  const [ruleRegionFilter, setRuleRegionFilter] = useState('')
  const [ruleCarrierFilter, setRuleCarrierFilter] = useState('')
  const [showRuleFilters, setShowRuleFilters] = useState(false)
  const filtersRef = useRef<HTMLDivElement>(null)
  // Add-form visibility. `adding` toggles the inline strip inside the table.
  // `popout` promotes it into a full modal dialog.
  const [adding, setAdding] = useState(false)
  const [popout, setPopout] = useState(false)

  // Close the Filters popover on outside click / Escape.
  useEffect(() => {
    if (!showRuleFilters) return
    const onDocClick = (e: MouseEvent) => {
      if (!filtersRef.current?.contains(e.target as Node)) setShowRuleFilters(false)
    }
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setShowRuleFilters(false)
    }
    document.addEventListener('mousedown', onDocClick)
    document.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('mousedown', onDocClick)
      document.removeEventListener('keydown', onKey)
    }
  }, [showRuleFilters])

  /**
   * Cancel/close the add-form. Only fires from an explicit user action
   * (the X icon, popout Cancel button, or a successful save) — never from
   * click-outside or Escape, so opening the Ships-to zone modal doesn't
   * eat the add-row. Also clears the draft so reopening starts blank.
   */
  /**
   * Fix F15 — snapshot the blank-rule state so we can detect dirty
   * edits to the newRule form and confirm before discarding. Prior
   * behavior silently discarded a 20-field entry on X / Cancel / popout-close.
   * useMemo empty deps: blankRule is fixed for the page's lifetime.
   */
  const blankRuleSnapshot = useMemo(
    () => JSON.stringify({ ...blankRule, destCodes: [] }),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [],
  )
  const closeAddForm = useCallback((opts?: { skipGuard?: boolean }) => {
    if (!opts?.skipGuard) {
      const currentSnapshot = JSON.stringify(newRule)
      if (currentSnapshot !== blankRuleSnapshot
          && !window.confirm('Discard the in-progress new mapping?')) {
        return
      }
    }
    setAdding(false)
    setPopout(false)
    setNewRule({ ...blankRule, destCodes: [] })
  }, [newRule, blankRuleSnapshot])

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const [catalog, clientPage, whPage, presetList, accountList] = await Promise.all([
        shippingConfigService.catalog(),
        clientService.listClients({ size: 200 }),
        warehouseService.listWarehouses({ size: 500 }),
        shippingConfigService.listPresets(),
        accountRefService.listAccounts(),
      ])
      setServices(catalog.services)
      setRules(catalog.rules)
      setClients(clientPage.data?.content ?? [])
      setWarehouses(whPage.data?.content ?? [])
      setPresets(presetList)
      setAccounts(accountList)
      // Phase 6 — group flat rulePackages by ruleId for chip rendering.
      const grouped = new Map<number, number[]>()
      for (const l of catalog.rulePackages ?? []) {
        const cur = grouped.get(l.ruleId) ?? []
        cur.push(l.presetId)
        grouped.set(l.ruleId, cur)
      }
      setRuleIdToPresets(grouped)
      const groupedWh = new Map<number, number[]>()
      for (const l of catalog.ruleWarehouses ?? []) {
        const cur = groupedWh.get(l.ruleId) ?? []
        cur.push(l.warehouseId)
        groupedWh.set(l.ruleId, cur)
      }
      setRuleWarehouseMap(groupedWh)
    } catch (e) {
      notify.apiError(e, 'Failed to load the mapping catalog.')
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- data fetch on mount; load() sets loading + mapping-catalog state
    void load()
  }, [load])

  // Register load() with SettingsLayout so its Refresh icon in the submenus
  // row refreshes THIS page's data. Unregister on unmount.
  const { registerRefresh } = useOutletContext<SettingsOutletContext>()
  useEffect(() => {
    registerRefresh(load)
    return () => registerRefresh(null)
  }, [registerRefresh, load])

  const serviceById = useMemo(() => new Map(services.map((s) => [s.id, s])), [services])
  const clientByCode = useMemo(
    () => new Map(clients.map((c) => [c.clientCode, c] as const)),
    [clients],
  )
  const warehouseById = useMemo(
    () => new Map(warehouses.map((w) => [w.id, w] as const)),
    [warehouses],
  )
  const presetById = useMemo(() => {
    const m = new Map<number, PackagePreset>()
    for (const p of presets) if (p.id != null) m.set(p.id, p)
    return m
  }, [presets])

  /** Warehouses pickable for a rule targeting `clientCode`:
   *   - Any-client (null / empty) → PLATFORM warehouses only. CLIENT-owned
   *     warehouses can never apply globally.
   *   - Specific client → PLATFORM + that client's own CLIENT-owned warehouses.
   *  Inactive warehouses are always hidden. */
  const warehouseChoicesForClient = useCallback(
    (clientCode: string | null | undefined): Warehouse[] => {
      const code = (clientCode || '').toUpperCase()
      return warehouses.filter((w) => {
        if (!w.active) return false
        const owner = (w.ownerType || '').toUpperCase()
        if (owner === 'PLATFORM') return true
        if (owner === 'CLIENT') {
          return !!code && (w.ownerClientCode || '').toUpperCase() === code
        }
        return false
      })
    },
    [warehouses],
  )

  /** Bound to the local presetById lookup — collapses variant ids stored on a
   *  rule into distinct logical-package count. */
  const logicalPackageCount = useCallback(
    (presetIds: number[]) => computeLogicalPackageCount(presetIds, presetById),
    [presetById],
  )

  /**
   * Domestic country for a mapping = the client's ship-from country when a
   * specific client is set. When the mapping targets "Any client", we don't
   * know what "domestic" means from the page; the zone modal offers its own
   * inline Ship-from picker so the user can pick a fallback for that session.
   * Service-scope filtering also falls back to "ANY" when this is null.
   */
  const domesticFor = useCallback(
    (clientCode?: string | null): string | null => {
      const c = clientCode ? clientByCode.get(clientCode) : null
      return c?.shipFrom?.country ? c.shipFrom.country.toUpperCase() : null
    },
    [clientByCode],
  )

  // ===== Carrier account driven allowed-carriers filter =====
  // Union of client's own active + complete accounts + (optional) the platform
  // account the operator picked to seed or extend the filter. Empty means the
  // Ship Via list should render blank + a hint to pick a platform account.
  const platformAccountList = useMemo(() => platformAccountsFrom(accounts), [accounts])
  const draftAllowed = useMemo(
    () => computeAllowedCarriers({
      clientCode: newRule.clientCode,
      accounts,
      platformAccountId: newRulePlatformAccountId,
    }),
    [accounts, newRule.clientCode, newRulePlatformAccountId],
  )

  // ===== Draft-side cascading filters (add-strip + popout modal share these) =====
  // Declared AFTER domesticFor so the TDZ isn't tripped — draftOrigins reads
  // domesticFor when the rule targets a client but has no warehouse pinned.
  const draftOrigins = useMemo(() => {
    const own = originCountriesFor(newRule.warehouseIds, warehouseById)
    if (own.size > 0) return own
    const dom = domesticFor(newRule.clientCode)
    if (dom) return new Set([dom])
    return new Set<string>()
  }, [newRule.warehouseIds, newRule.clientCode, warehouseById, domesticFor])

  const draftScope = useMemo(
    () => inferScope(newRule.destCodes, draftOrigins),
    [newRule.destCodes, draftOrigins],
  )

  const draftServiceGroups = useMemo<ServiceGroup[]>(
    () => groupServiceVariants(
      services.filter((s) => {
        if (!s.enabled) return false
        const svcOrigin = (s.originCountry || '').toUpperCase()
        if (svcOrigin && draftOrigins.size > 0 && !draftOrigins.has(svcOrigin)) return false
        // Carrier filter: services outside the allowed set are hidden. Empty
        // set = operator hasn't picked a platform account for an Any-client /
        // no-account client yet; render blank so they're prompted to pick one.
        if (draftAllowed.carriers.size > 0
            && !draftAllowed.carriers.has((s.carrier || '').toUpperCase())) {
          return false
        }
        if (draftScope === 'ANY') return true
        if (s.scope === 'BOTH' || draftScope === 'BOTH') return true
        return s.scope === draftScope
      }),
      draftOrigins,
    ),
    [services, draftOrigins, draftScope, draftAllowed.carriers],
  )

  const draftEligibleServiceIds = useMemo(() => {
    const s = new Set<string>()
    for (const g of draftServiceGroups) for (const v of g.variants) s.add(String(v.id))
    return s
  }, [draftServiceGroups])

  /** Reset the draft's serviceId at render time when the current filter no
   *  longer includes it — instead of mirroring into state from an effect. */
  const effectiveNewServiceId = useMemo(() => {
    if (!newRule.serviceId) return ''
    return draftEligibleServiceIds.has(newRule.serviceId) ? newRule.serviceId : ''
  }, [newRule.serviceId, draftEligibleServiceIds])

  /** Preset groups for the draft — filtered by picked service's carrier +
   *  origins, then origin-siblings collapsed. Empty until a service is picked. */
  const draftPresetGroups = useMemo<PresetGroup[]>(() => {
    if (!effectiveNewServiceId) return []
    const svc = serviceById.get(Number(effectiveNewServiceId))
    if (!svc) return []
    return groupPresets(
      filterPresetsForCarrierAndOrigin(presets, svc.carrier, draftOrigins),
    )
  }, [effectiveNewServiceId, serviceById, presets, draftOrigins])

  const draftSelectedPresetGroupIds = useMemo(() => {
    const picked = new Set<number>()
    const selectedSet = new Set(newRule.presetIds)
    for (const g of draftPresetGroups) {
      if (g.variantIds.every((id) => selectedSet.has(id))) picked.add(g.representative.id!)
    }
    return picked
  }, [draftPresetGroups, newRule.presetIds])

  const draftWarehouseChoices = useMemo(
    () => warehouseChoicesForClient(newRule.clientCode || null),
    [warehouseChoicesForClient, newRule.clientCode],
  )

  // Legacy `inferScope` / `serviceMatchesScope` local helpers were replaced by
  // the shared shippingMappingFilters implementations that take an origin
  // Set<string> instead of a single domestic country — they now support
  // warehouse-multi-origin rules.

  // Distinct order-method codes that actually appear — feeds the Method filter.
  const methodCodes = useMemo(
    () => [...new Set(rules.map((r) => r.shipviaCd).filter(Boolean))].sort(),
    [rules],
  )

  const filteredRules = useMemo(() => {
    const q = ruleQuery.trim().toLowerCase()
    return rules.filter((r) => {
      if (ruleMethodFilter && r.shipviaCd !== ruleMethodFilter) return false
      if (ruleClientFilter && (r.clientCode || '') !== ruleClientFilter) return false
      if (ruleCarrierFilter) {
        const svc = serviceById.get(r.serviceId)
        if (!svc || (svc.carrier || '').toUpperCase() !== ruleCarrierFilter) return false
      }
      if (ruleRegionFilter) {
        // Region filter drills into rules that *specifically* target that region.
        // Global (ANY) rules stay hidden — they surface when the filter is cleared.
        const codes = ruleCodes(r)
        if (!codes.length) return false
        const regions = new Set(codes.map((c) => regionOf(c)))
        if (!regions.has(ruleRegionFilter as Region)) return false
      }
      if (!q) return true
      const svc = serviceById.get(r.serviceId)
      const bag = [
        r.shipviaCd,
        r.clientCode || '',
        svc?.carrier || '',
        svc?.name || '',
        ...ruleCodes(r),
      ]
      return bag.join(' ').toLowerCase().includes(q)
    })
  }, [rules, ruleQuery, ruleMethodFilter, ruleClientFilter, ruleRegionFilter, ruleCarrierFilter, serviceById])

  const ruleFilterCount =
    (ruleMethodFilter ? 1 : 0) +
    (ruleClientFilter ? 1 : 0) +
    (ruleRegionFilter ? 1 : 0) +
    (ruleCarrierFilter ? 1 : 0)

  const clearRuleFilters = () => {
    setRuleMethodFilter('')
    setRuleClientFilter('')
    setRuleRegionFilter('')
    setRuleCarrierFilter('')
  }

  const saveNewRule = async () => {
    if (!newRule.shipviaCd.trim() || !newRule.serviceId) {
      notify.error('Enter the order ship-method code and pick a carrier service.')
      return
    }
    try {
      await shippingConfigService.saveRule({
        shipviaCd: newRule.shipviaCd.trim(),
        clientCode: newRule.clientCode || null,
        destType: newRule.destCodes.length ? 'COUNTRIES' : 'ANY',
        destValue: newRule.destCodes.length ? newRule.destCodes.join(' ') : null,
        serviceId: Number(newRule.serviceId),
        warehouseIds: newRule.warehouseIds,
        allowedPresetIds: newRule.presetIds,
      })
      notify.success('Mapping added.')
      setNewRule({ ...blankRule, destCodes: [], warehouseIds: [], presetIds: [] })
      // skipGuard: post-save clean-up, no need to prompt for discard.
      closeAddForm({ skipGuard: true })
      void load()
    } catch (e) {
      notify.apiError(e, 'Failed to save the mapping.')
    }
  }

  const openZone = (target: 'new' | ShipMethodRule) => {
    setZoneCodes(target === 'new' ? [...newRule.destCodes] : ruleCodes(target))
    setZoneFor(target)
  }

  const saveZone = async () => {
    if (zoneFor === 'new') {
      setNewRule((c) => ({ ...c, destCodes: [...zoneCodes] }))
      setZoneFor(null)
      return
    }
    if (!zoneFor) return
    try {
      await shippingConfigService.saveRule({
        ...zoneFor,
        destType: zoneCodes.length ? 'COUNTRIES' : 'ANY',
        destValue: zoneCodes.length ? zoneCodes.join(' ') : null,
        // Preserve both transient sets — the catalog endpoint doesn't populate
        // them on the rule object, so a save without these here would wipe the
        // rule's warehouses and packages.
        warehouseIds: ruleWarehouseMap.get(zoneFor.id ?? -1) ?? [],
        allowedPresetIds: ruleIdToPresets.get(zoneFor.id ?? -1) ?? [],
      })
      notify.success('Destination zone updated.')
      setZoneFor(null)
      void load()
    } catch (e) {
      notify.apiError(e, 'Failed to update the zone.')
    }
  }

  /**
   * Persist a single-field edit from an inline cell editor. Throws on failure
   * so the editor can toast the error and stay in edit mode with the bad value.
   *
   * The transient warehouseIds / allowedPresetIds are re-hydrated from the
   * per-rule maps because catalog() doesn't populate them on the raw
   * ShipViaMapping — omitting them here would silently wipe the rule's
   * warehouse and package restrictions on every inline field edit.
   */
  const saveRuleField = useCallback(
    async (rule: ShipMethodRule, patch: Partial<ShipMethodRule>) => {
      await shippingConfigService.saveRule({
        ...rule,
        warehouseIds: ruleWarehouseMap.get(rule.id ?? -1) ?? [],
        allowedPresetIds: ruleIdToPresets.get(rule.id ?? -1) ?? [],
        ...patch,
      })
      void load()
    },
    [load, ruleWarehouseMap, ruleIdToPresets],
  )

  /** Carrier-switch aware service saver — mirrors the client-tab flow:
   *  1) if the switch changes the canonical carrier and the rule has linked
   *     packages, confirm + drop them all + auto-open the drawer;
   *  2) otherwise a straight save. */
  const saveRuleService = useCallback(
    async (rule: ShipMethodRule, serviceId: number) => {
      if (rule.serviceId === serviceId) return
      const oldSvc = serviceById.get(rule.serviceId)
      const newSvc = serviceById.get(serviceId)
      const currentPresetIds = ruleIdToPresets.get(rule.id ?? -1) ?? []
      const carrierChanged =
        (oldSvc?.carrier || '').toUpperCase() !== (newSvc?.carrier || '').toUpperCase()
      const willDropPackages = carrierChanged && currentPresetIds.length > 0

      if (willDropPackages) {
        const n = logicalPackageCount(currentPresetIds)
        const ok = await notify.confirm(
          `Switching carrier from ${formatCarrierName(oldSvc?.carrier || '—')} to ${formatCarrierName(newSvc?.carrier || '—')} will remove all ${n} linked package${n === 1 ? '' : 's'} on this rule. Continue and pick replacement package(s)?`,
          {
            title: 'Carrier change requires new packages',
            confirmLabel: 'Continue',
            danger: true,
          },
        )
        if (!ok) return
      }

      try {
        await shippingConfigService.saveRule({
          ...rule,
          serviceId,
          warehouseIds: ruleWarehouseMap.get(rule.id ?? -1) ?? [],
          allowedPresetIds: willDropPackages ? [] : currentPresetIds,
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
          setPkgFor({ ...rule, serviceId, allowedPresetIds: [] })
        }
        void load()
      } catch (error) {
        notify.apiError(error, 'Failed to update the carrier service.')
      }
    },
    [serviceById, ruleIdToPresets, ruleWarehouseMap, logicalPackageCount, load],
  )

  /** Save a rule's warehouseIds without touching anything else — used by the
   *  Warehouse column's inline popover. */
  const saveRuleWarehouses = useCallback(
    async (rule: ShipMethodRule, warehouseIds: number[]) => {
      try {
        await shippingConfigService.saveRule({
          ...rule,
          warehouseIds,
          allowedPresetIds: ruleIdToPresets.get(rule.id ?? -1) ?? [],
        })
        notify.success('Warehouses updated.')
        void load()
      } catch (error) {
        notify.apiError(error, 'Failed to update warehouses.')
      }
    },
    [ruleIdToPresets, load],
  )

  const removeRule = async (rule: ShipMethodRule) => {
    if (!rule.id) return
    if (!(await notify.confirm(`Remove this ${rule.shipviaCd} mapping?`, {
      title: 'Remove mapping',
      confirmLabel: 'Remove',
      danger: true,
    }))) return
    try {
      await shippingConfigService.deleteRule(rule.id)
      void load()
    } catch (e) {
      notify.apiError(e, 'Failed to remove the mapping.')
    }
  }

  const mappingColumns = useMemo<ColumnDef<ShipMethodRule, unknown>[]>(
    // Column IDs (shipviaCd/clientCode/service/…) intentionally kept from the
    // original layout — Phase 3 persistence will key off these IDs, so renaming
    // them later would invalidate saved layouts. Only display headers change.
    () => [
      {
        id: 'clientCode',
        accessorFn: (r) => r.clientCode || '',
        header: 'Client',
        cell: ({ row }) =>
          row.original.clientCode ? (
            <span className="inline-flex items-center gap-1 rounded-full bg-[#412d15]/10 px-2 py-0.5 text-[11px] font-semibold text-[#412d15]">
              <FiUser className="h-3 w-3" /> {row.original.clientCode}
            </span>
          ) : (
            <span className="text-[11.5px] text-slate-400">Any client</span>
          ),
        meta: {
          headerLabel: 'Client',
          exportValue: (r: ShipMethodRule) => r.clientCode || 'Any',
          editCell: (p: EditCellProps<ShipMethodRule>) => (
            <ClientEditor {...p} clients={clients} onSaveField={saveRuleField} />
          ),
        },
      },
      {
        id: 'shipviaCd',
        accessorKey: 'shipviaCd',
        header: 'Order Ship Via',
        cell: ({ row }) => (
          <span className="rounded-lg bg-[#1f150c] px-2.5 py-1 font-mono text-[12px] font-bold text-[#e1dcc9]">
            {row.original.shipviaCd}
          </span>
        ),
        meta: {
          headerLabel: 'Order Ship Via',
          editCell: (p: EditCellProps<ShipMethodRule>) => (
            <ShipViaEditor {...p} onSaveField={saveRuleField} />
          ),
        },
      },
      {
        id: 'warehouses',
        header: 'Warehouse',
        enableSorting: false,
        cell: ({ row }) => {
          const whIds = ruleWarehouseMap.get(row.original.id ?? -1) ?? []
          const choices = warehouseChoicesForClient(row.original.clientCode)
          return (
            <WarehouseCell
              choices={choices}
              warehouseById={warehouseById}
              value={whIds}
              onSave={(next) => void saveRuleWarehouses(row.original, next)}
            />
          )
        },
        meta: { headerLabel: 'Warehouse', hideable: true, exportable: false },
      },
      {
        id: 'destination',
        header: 'Ships to',
        enableSorting: false,
        cell: ({ row }) => <ZoneChips codes={ruleCodes(row.original)} />,
        meta: {
          headerLabel: 'Ships to',
          exportValue: (r: ShipMethodRule) => {
            const codes = ruleCodes(r)
            return codes.length ? codes.join(' ') : 'Anywhere'
          },
          // Editing this cell means opening the zone-picker modal — no inline editor.
          editByPicker: (r: ShipMethodRule) => openZone(r),
        },
      },
      {
        id: 'service',
        accessorFn: (r) => serviceById.get(r.serviceId)?.name || '',
        header: 'Carrier Ship Via',
        cell: ({ row }) => {
          const svc = serviceById.get(row.original.serviceId)
          if (!svc) return <span className="text-[11.5px] text-slate-400">—</span>
          return (
            <div>
              <span className="inline-flex items-center gap-1.5 rounded-lg border border-slate-200 bg-white px-2 py-1 text-[12px] font-semibold text-slate-700">
                <span
                  className={`inline-block h-1.5 w-1.5 rounded-full ${svc.enabled ? 'bg-emerald-500' : 'bg-amber-500'}`}
                  aria-hidden
                />
                {svc.carrier} — {svc.name}
              </span>
              {!svc.enabled ? (
                <p className="mt-1 text-[10.5px] text-amber-700">Service disabled — orders fall back to the carrier default.</p>
              ) : null}
            </div>
          )
        },
        meta: {
          headerLabel: 'Carrier Ship Via',
          exportValue: (r: ShipMethodRule) => {
            const svc = serviceById.get(r.serviceId)
            return svc ? `${svc.carrier} — ${svc.name}` : ''
          },
          editCell: (p: EditCellProps<ShipMethodRule>) => {
            // Row's effective origin: warehouse restrictions take precedence
            // over the client's ship-from country. Combined with the row's
            // destination scope, they drive the picker's eligibility filter.
            const whIds = ruleWarehouseMap.get(p.row.id ?? -1) ?? []
            const origins = whIds.length
              ? originCountriesFor(whIds, warehouseById)
              : (() => {
                  const s = new Set<string>()
                  const dom = domesticFor(p.row.clientCode)
                  if (dom) s.add(dom)
                  return s
                })()
            const scope = inferScope(ruleCodes(p.row), origins)
            // Row-level carrier scope — client's own active accounts. Row
            // editor has no inline platform-account picker (too little space);
            // any-client / no-account rows keep the current service visible
            // via the currentKey fallback below so operators can still edit.
            const allowed = computeAllowedCarriers({
              clientCode: p.row.clientCode,
              accounts,
            }).carriers
            // Filter + dedupe: one option per (carrier, service_code) so origin
            // siblings collapse; carrier-switch confirmation kicks in inside
            // saveRuleService when the carrier changes.
            const eligibleGroups = groupServiceVariants(
              services.filter((s) => s.enabled).filter((s) => {
                const svcOrigin = (s.originCountry || '').toUpperCase()
                if (svcOrigin && origins.size > 0 && !origins.has(svcOrigin)) return false
                if (allowed.size > 0 && !allowed.has((s.carrier || '').toUpperCase())) return false
                if (scope === 'ANY') return true
                if (s.scope === 'BOTH' || scope === 'BOTH') return true
                return s.scope === scope
              }),
              origins,
            )
            // Always surface the currently-persisted service, even if the
            // filter dropped it — so the operator can see what's saved.
            const current = serviceById.get(p.row.serviceId)
            const currentKey = current ? groupKeyFor(current) : null
            const listedGroups = current && currentKey && !eligibleGroups.some((g) => g.key === currentKey)
              ? [
                  {
                    key: currentKey,
                    representative: current,
                    variants: [current],
                    origins: current.originCountry ? [current.originCountry.toUpperCase()] : [],
                  } as ServiceGroup,
                  ...eligibleGroups,
                ]
              : eligibleGroups
            return (
              <ServiceEditor
                {...p}
                services={listedGroups.map((g) => g.representative)}
                onSaveField={async (row, patch) => {
                  // Route service changes through saveRuleService so the
                  // carrier-switch confirmation flow (drop packages, open
                  // drawer) fires; everything else goes through the field
                  // saver unchanged.
                  if (patch.serviceId != null && patch.serviceId !== row.serviceId) {
                    await saveRuleService(row, patch.serviceId)
                  } else {
                    await saveRuleField(row, patch)
                  }
                }}
                scopeMatch={() => true}
              />
            )
          },
        },
      },
      {
        id: 'packages',
        header: 'Packages',
        enableSorting: false,
        cell: ({ row }) => {
          const pkgIds = ruleIdToPresets.get(row.original.id ?? -1) ?? []
          const svc = serviceById.get(row.original.serviceId)
          const logicalCount = logicalPackageCount(pkgIds)
          const needsPackages = row.original.id != null && pendingPackagesForRules.has(row.original.id)
          return (
            <button
              type="button"
              onClick={() => setPkgFor(row.original)}
              disabled={!svc || row.original.id == null}
              title={
                needsPackages
                  ? 'Packages required — carrier was switched, previous packages were cleared. Click to pick a replacement.'
                  : logicalCount
                    ? `${logicalCount} package${logicalCount === 1 ? '' : 's'} allowed (${pkgIds.length} origin variant${pkgIds.length === 1 ? '' : 's'})`
                    : 'No package restriction'
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
        },
        meta: { headerLabel: 'Packages', hideable: true, exportable: false },
      },
      {
        id: 'actions',
        header: '',
        enableSorting: false,
        cell: ({ row }) => (
          <div className="text-right">
            <button
              type="button"
              onClick={() => void removeRule(row.original)}
              aria-label={`Remove mapping ${row.original.shipviaCd}${ruleScope(row.original).length ? ' ' + ruleScope(row.original).join(' ') : ''}`}
              className="rounded-lg border border-rose-200 bg-white p-1.5 text-rose-600 transition hover:bg-rose-50"
            >
              <FiTrash2 className="h-3.5 w-3.5" />
            </button>
          </div>
        ),
        meta: { headerLabel: 'Actions', hideable: false, exportable: false },
      },
    ],
    // Editors close over services / clients / saveRuleField / domesticFor.
    // openZone / removeRule are stable enough that we skip them; adding them
    // would rebuild every render.
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [
      services, serviceById, clients, saveRuleField, saveRuleService,
      saveRuleWarehouses, domesticFor, ruleIdToPresets,
      ruleWarehouseMap, warehouseById, warehouseChoicesForClient,
      logicalPackageCount, pendingPackagesForRules,
    ],
  )

  return (
    <div className="space-y-4 pb-16">
      <section className="rounded-2xl border border-slate-200 bg-white p-5 shadow-sm">
        {loading && !rules.length ? (
          <p className="py-10 text-center text-sm text-slate-500">Loading mappings…</p>
        ) : (
          <AdvancedDataTable<ShipMethodRule>
            tableKey="shipping-service-mapping"
            columns={mappingColumns}
            data={filteredRules}
            search={{
              value: ruleQuery,
              onChange: setRuleQuery,
              placeholder: 'Search ship via, client, carrier, country…',
            }}
            filterToggle={
              <div ref={filtersRef} className="relative">
                <button
                  type="button"
                  onClick={() => setShowRuleFilters((cur) => !cur)}
                  aria-pressed={showRuleFilters}
                  aria-expanded={showRuleFilters}
                  aria-label="Filters"
                  title="Filters"
                  className={`inline-flex items-center gap-1.5 rounded-xl px-2.5 py-1.5 text-[12px] font-semibold transition md:px-3 ${
                    showRuleFilters || ruleFilterCount
                      ? 'bg-[#1f150c] text-white'
                      : 'border border-slate-200 bg-white text-slate-600 hover:bg-slate-50'
                  }`}
                >
                  <FiFilter className="h-3.5 w-3.5" />
                  <span className="hidden md:inline">Filters</span>
                  {ruleFilterCount ? (
                    <span className="rounded-full bg-white/25 px-1.5 py-0.5 text-[10px] tabular-nums">{ruleFilterCount}</span>
                  ) : null}
                </button>
                {showRuleFilters ? (
                  <div
                    role="dialog"
                    aria-label="Filter mappings"
                    className="absolute left-0 top-full z-30 mt-1.5 w-72 rounded-2xl border border-slate-200 bg-white p-3 shadow-[0_20px_60px_rgba(15,23,42,0.15)]"
                  >
                    <div className="space-y-2.5">
                      <div>
                        <span className={filterLabelClass}><FiLink className="h-3 w-3 text-sky-600" />Order Ship Via</span>
                        <Select
                          value={ruleMethodFilter}
                          onChange={(e) => setRuleMethodFilter(e.target.value)}
                          aria-label="Filter by order ship via"
                        >
                          <option value="">All ship vias</option>
                          {methodCodes.map((code) => (
                            <option key={code} value={code}>
                              {code}
                            </option>
                          ))}
                        </Select>
                      </div>
                      <div>
                        <span className={filterLabelClass}><FiUser className="h-3 w-3 text-[#412d15]" />Client</span>
                        <Select
                          value={ruleClientFilter}
                          onChange={(e) => setRuleClientFilter(e.target.value)}
                          aria-label="Filter by client"
                        >
                          <option value="">All clients</option>
                          {clients.map((c) => (
                            <option key={c.clientCode} value={c.clientCode}>
                              {c.clientCode}
                            </option>
                          ))}
                        </Select>
                      </div>
                      <div>
                        <span className={filterLabelClass}><FiGlobe className="h-3 w-3 text-emerald-600" />Destination region</span>
                        <Select
                          value={ruleRegionFilter}
                          onChange={(e) => setRuleRegionFilter(e.target.value)}
                          aria-label="Filter by destination region"
                        >
                          <option value="">All regions</option>
                          {REGIONS.map((region) => (
                            <option key={region} value={region}>
                              {region}
                            </option>
                          ))}
                        </Select>
                      </div>
                      <div>
                        <span className={filterLabelClass}><FiTruck className="h-3 w-3 text-violet-600" />Carrier Ship Via</span>
                        <Select
                          value={ruleCarrierFilter}
                          onChange={(e) => setRuleCarrierFilter(e.target.value)}
                          aria-label="Filter by carrier ship via"
                        >
                          <option value="">All carriers</option>
                          {CARRIERS.map((carrier) => (
                            <option key={carrier} value={carrier}>
                              {carrier}
                            </option>
                          ))}
                        </Select>
                      </div>
                    </div>

                    <div className="mt-3 flex items-center justify-end gap-2 border-t border-slate-100 pt-2.5">
                      {ruleFilterCount ? (
                        <button
                          type="button"
                          onClick={clearRuleFilters}
                          className="inline-flex items-center gap-1 rounded-xl border border-slate-200 bg-white px-2.5 py-1.5 text-[11.5px] font-semibold text-slate-600 transition hover:bg-slate-50"
                        >
                          <FiX className="h-3.5 w-3.5" />
                          Clear
                        </button>
                      ) : null}
                      <button
                        type="button"
                        onClick={() => setShowRuleFilters(false)}
                        className="inline-flex items-center gap-1 rounded-xl bg-[#1f150c] px-3 py-1.5 text-[11.5px] font-semibold text-white transition hover:bg-[#412d15]"
                      >
                        Done
                      </button>
                    </div>
                  </div>
                ) : null}
              </div>
            }
            caption={
              <p className="text-[11.5px] text-slate-400">
                Showing {filteredRules.length} of {rules.length} mapping{rules.length === 1 ? '' : 's'}
              </p>
            }
            emptyState={ruleFilterCount || ruleQuery ? 'No mappings match your filters.' : 'No mappings yet — click Add to create one.'}
            csvFilename="shipping-service-mapping"
            toolbarActions={
              <button
                type="button"
                onClick={() => setAdding(true)}
                className="inline-flex items-center gap-1.5 rounded-xl bg-[#1f150c] px-3 py-1.5 text-[12px] font-semibold text-white transition hover:bg-[#412d15]"
              >
                <FiPlus className="h-3.5 w-3.5" /> Add
              </button>
            }
            inlineFormRow={
              adding && !popout ? (
                <div className="flex flex-wrap items-center gap-2">
                  {/* Prominent "New" badge so the row reads as an add-strip, not a data row. */}
                  <span className="inline-flex shrink-0 items-center gap-1 rounded-full bg-sky-600 px-2.5 py-1 text-[10px] font-bold uppercase tracking-[0.1em] text-white shadow-sm">
                    <FiPlus className="h-3 w-3" /> New mapping
                  </span>
                  <div className="min-w-[130px] flex-1">
                    <Select
                      value={newRule.clientCode}
                      onChange={(e) => {
                        setNewRulePlatformAccountId(null)
                        setNewRule((c) => ({
                          ...c,
                          clientCode: e.target.value,
                          // Client change may invalidate previously picked warehouses
                          // (CLIENT-owned ones); safest is to reset the picker.
                          warehouseIds: [],
                          presetIds: [],
                          // Carrier context changes with the client too — drop
                          // any stale service id.
                          serviceId: '',
                        }))
                      }}
                    >
                      <option value="">Any client</option>
                      {clients.map((c) => (<option key={c.clientCode} value={c.clientCode}>{c.clientCode}</option>))}
                    </Select>
                  </div>
                  {/* Platform-account picker — always visible.
                       * When client has no carriers, filter is EMPTY without a
                         pick, so the operator must pick one to see options.
                       * When client HAS carriers, picking a platform account
                         EXTENDS the allowed set (adds that carrier). */}
                  <div className="min-w-[170px] shrink-0">
                    <Select
                      value={newRulePlatformAccountId == null ? '' : String(newRulePlatformAccountId)}
                      onChange={(e) => {
                        const raw = e.target.value
                        setNewRulePlatformAccountId(raw ? Number(raw) : null)
                        setNewRule((c) => ({ ...c, serviceId: '' }))
                      }}
                      aria-label="Platform carrier account"
                      title={
                        draftAllowed.hasClientCarriers
                          ? "Optional — pick to also allow this platform account's carrier in the Ship Via list."
                          : 'Pick a platform account to seed the Ship Via carrier filter.'
                      }
                    >
                      <option value="">
                        {platformAccountList.length === 0
                          ? 'No platform accounts'
                          : draftAllowed.hasClientCarriers
                            ? '+ platform account (optional)'
                            : 'Platform account — pick to filter'}
                      </option>
                      {platformAccountList.map((a) => (
                        <option key={a.id} value={a.id}>
                          {formatCarrierName(a.carrierCode)} · {a.accountNumber}
                        </option>
                      ))}
                    </Select>
                  </div>
                  <input
                    value={newRule.shipviaCd}
                    onChange={(e) => setNewRule((c) => ({ ...c, shipviaCd: e.target.value.toUpperCase() }))}
                    placeholder="Ship via *"
                    aria-label="Order Ship Via"
                    className="w-28 shrink-0 rounded-xl border border-slate-200 bg-white px-2.5 py-1.5 font-mono text-[12px] font-semibold text-slate-950 outline-none transition focus:border-[#412d15]"
                  />
                  {/* Warehouse — filtered per client (PLATFORM + own for a specific
                       client; PLATFORM only for Any-client). Ctrl/Cmd-click multi. */}
                  <select
                    multiple
                    size={1}
                    value={newRule.warehouseIds.map(String)}
                    onChange={(e) => {
                      const next = Array.from(e.target.selectedOptions).map((o) => Number(o.value))
                      setNewRule((c) => ({ ...c, warehouseIds: next, presetIds: [] }))
                    }}
                    aria-label="Warehouses"
                    title={
                      draftWarehouseChoices.length === 0
                        ? newRule.clientCode
                          ? `No PLATFORM or ${newRule.clientCode}-owned warehouses available.`
                          : 'No PLATFORM warehouses available.'
                        : 'Ctrl/Cmd-click to pick multiple. Empty = any warehouse.'
                    }
                    className="h-[34px] w-40 rounded-xl border border-slate-200 bg-white px-2 text-[12px] font-semibold text-slate-700 outline-none transition focus:border-[#412d15] disabled:cursor-not-allowed disabled:bg-slate-100"
                    disabled={draftWarehouseChoices.length === 0}
                  >
                    {draftWarehouseChoices.length === 0 ? (
                      <option disabled value="">Warehouse — none avail.</option>
                    ) : (
                      draftWarehouseChoices.map((w) => (
                        <option key={w.id} value={w.id}>
                          {w.code}{w.address?.country ? ` · ${w.address.country}` : ''}
                        </option>
                      ))
                    )}
                  </select>
                  <button
                    type="button"
                    onClick={() => openZone('new')}
                    className="inline-flex min-w-[160px] flex-1 items-center gap-1.5 rounded-xl border border-slate-200 bg-white px-3 py-1.5 text-left text-[12px] font-semibold text-slate-600 transition hover:border-[#412d15]/40"
                  >
                    <FiGlobe className="h-3.5 w-3.5 shrink-0 text-sky-600" />
                    {newRule.destCodes.length ? <ZoneChips codes={newRule.destCodes} /> : 'Ship to · any'}
                  </button>
                  <div className="min-w-[200px] flex-1">
                    <Select
                      value={effectiveNewServiceId}
                      onChange={(e) => setNewRule((c) => ({
                        ...c,
                        serviceId: e.target.value,
                        // Carrier may have changed; clear stale package picks.
                        presetIds: [],
                      }))}
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
                  {/* Packages — locked until service picked; groups by (carrier,
                       package_code) so origin siblings collapse. Ctrl/Cmd-click. */}
                  <select
                    multiple
                    size={1}
                    value={[...draftSelectedPresetGroupIds].map(String)}
                    onChange={(e) => {
                      const pickedReps = new Set(Array.from(e.target.selectedOptions).map((o) => Number(o.value)))
                      const nextIds: number[] = []
                      for (const g of draftPresetGroups) {
                        if (pickedReps.has(g.representative.id!)) nextIds.push(...g.variantIds)
                      }
                      setNewRule((c) => ({ ...c, presetIds: nextIds }))
                    }}
                    aria-label="Packages"
                    title={
                      !effectiveNewServiceId
                        ? 'Pick a Carrier Ship Via first.'
                        : draftPresetGroups.length === 0
                          ? 'No packages match this carrier + origin.'
                          : 'Ctrl/Cmd-click to pick multiple. Empty = unrestricted.'
                    }
                    disabled={!effectiveNewServiceId || draftPresetGroups.length === 0}
                    className="h-[34px] w-44 rounded-xl border border-slate-200 bg-white px-2 text-[12px] font-semibold text-slate-700 outline-none transition focus:border-[#412d15] disabled:cursor-not-allowed disabled:bg-slate-100 disabled:text-slate-400"
                  >
                    {!effectiveNewServiceId ? (
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
                      onClick={() => void saveNewRule()}
                      disabled={!newRule.shipviaCd.trim() || !effectiveNewServiceId}
                      className="inline-flex items-center gap-1 rounded-xl bg-[#1f150c] px-4 py-2 text-[11.5px] font-semibold text-white transition hover:bg-[#412d15] disabled:cursor-not-allowed disabled:bg-slate-300"
                    >
                      Save
                    </button>
                    <button
                      type="button"
                      onClick={() => setPopout(true)}
                      aria-label="Open in dialog"
                      title="Open in a larger form"
                      className="inline-flex h-8 w-8 items-center justify-center rounded-lg border border-slate-200 bg-white text-slate-500 transition hover:bg-slate-50"
                    >
                      <FiExternalLink className="h-3.5 w-3.5" />
                    </button>
                    <button
                      type="button"
                      onClick={() => closeAddForm()}
                      aria-label="Close"
                      className="inline-flex h-8 w-8 items-center justify-center rounded-lg border border-slate-200 bg-white text-slate-500 transition hover:bg-slate-50"
                    >
                      <FiX className="h-3.5 w-3.5" />
                    </button>
                  </div>
                </div>
              ) : null
            }
          />
        )}
      </section>

      {pkgFor && pkgFor.id != null ? (() => {
        const svc = serviceById.get(pkgFor.serviceId)
        if (!svc) return null
        const whIds = ruleWarehouseMap.get(pkgFor.id) ?? []
        // Origin filter: warehouse restrictions win; otherwise fall back to the
        // client's ship-from country (matches this page's legacy behavior).
        const origins = whIds.length
          ? originCountriesFor(whIds, warehouseById)
          : (() => {
              const s = new Set<string>()
              const dom = domesticFor(pkgFor.clientCode)
              if (dom) s.add(dom)
              return s
            })()
        const requirePick = pendingPackagesForRules.has(pkgFor.id)
        return (
          <RulePackagesDrawer
            rule={pkgFor}
            service={svc}
            originCountries={[...origins]}
            currentWarehouseIds={whIds}
            initialPresetIds={ruleIdToPresets.get(pkgFor.id) ?? []}
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

      <ZoneEditorModal
        open={Boolean(zoneFor)}
        codes={zoneCodes}
        onCodesChange={setZoneCodes}
        onSave={saveZone}
        onClose={() => setZoneFor(null)}
        subject={
          zoneFor && zoneFor !== 'new' ? (
            <>
              Mapping <span className="font-mono font-bold text-slate-700">{zoneFor.shipviaCd}</span>
              {zoneFor.clientCode ? <> · {zoneFor.clientCode}</> : null}
            </>
          ) : undefined
        }
        saveLabel={zoneFor === 'new' ? 'Use this zone' : 'Save zone'}
        domesticCountry={
          zoneFor === 'new'
            ? domesticFor(newRule.clientCode)
            : zoneFor
              ? domesticFor(zoneFor.clientCode)
              : null
        }
      />

      {/* Popout — the same add-form promoted to a 2-column modal for extra room.
          Backdrop click intentionally does NOT dismiss — only Cancel / X / Save
          close the flow, so the user can't accidentally lose their draft. */}
      {adding && popout ? (
        <div
          className="fixed inset-0 z-40 flex items-center justify-center bg-slate-950/45 p-4 backdrop-blur-sm"
          role="dialog"
          aria-modal="true"
        >
          <div className="w-full max-w-2xl rounded-2xl border border-slate-200 bg-white p-5 shadow-[0_30px_80px_rgba(15,23,42,0.35)]">
            <div className="mb-3 flex items-start justify-between">
              <div>
                <h3 className="inline-flex items-center gap-2 text-[15px] font-semibold text-slate-950">
                  <span className="inline-flex h-7 w-7 items-center justify-center rounded-lg bg-sky-50 text-sky-700">
                    <FiPlus className="h-4 w-4" />
                  </span>
                  Add mapping
                </h3>
                <p className="mt-1 text-[12px] text-slate-500">
                  Route an order ship-method to a carrier service, optionally narrowed by client and destination.
                </p>
              </div>
              <button
                type="button"
                onClick={() => closeAddForm()}
                className="rounded-xl border border-slate-200 bg-white p-2 text-slate-500 transition hover:bg-slate-50"
                aria-label="Close"
              >
                <FiX className="h-4 w-4" />
              </button>
            </div>

            <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
              <label className="block">
                <span className="mb-1 block text-[10.5px] font-semibold uppercase tracking-[0.1em] text-slate-400">
                  Client
                </span>
                <Select
                  value={newRule.clientCode}
                  onChange={(e) => {
                    setNewRulePlatformAccountId(null)
                    setNewRule((c) => ({
                      ...c,
                      clientCode: e.target.value,
                      warehouseIds: [],
                      presetIds: [],
                      serviceId: '',
                    }))
                  }}
                >
                  <option value="">Any client</option>
                  {clients.map((c) => (<option key={c.clientCode} value={c.clientCode}>{c.clientCode}</option>))}
                </Select>
              </label>
              <label className="block">
                <span className="mb-1 block text-[10.5px] font-semibold uppercase tracking-[0.1em] text-slate-400">
                  Platform carrier account
                  <span className="ml-1 normal-case font-normal tracking-normal text-slate-400">
                    · {draftAllowed.hasClientCarriers
                      ? 'optional — extends allowed carriers'
                      : 'required — seeds the Ship Via filter'}
                  </span>
                </span>
                <Select
                  value={newRulePlatformAccountId == null ? '' : String(newRulePlatformAccountId)}
                  onChange={(e) => {
                    const raw = e.target.value
                    setNewRulePlatformAccountId(raw ? Number(raw) : null)
                    setNewRule((c) => ({ ...c, serviceId: '' }))
                  }}
                >
                  <option value="">
                    {platformAccountList.length === 0
                      ? 'No platform accounts'
                      : draftAllowed.hasClientCarriers
                        ? '— none —'
                        : 'Pick to filter Ship Via —'}
                  </option>
                  {platformAccountList.map((a) => (
                    <option key={a.id} value={a.id}>
                      {formatCarrierName(a.carrierCode)} · {a.accountNumber}
                    </option>
                  ))}
                </Select>
              </label>
              <label className="block">
                <span className="mb-1 block text-[10.5px] font-semibold uppercase tracking-[0.1em] text-slate-400">
                  Order Ship Via <span className="text-rose-500">*</span>
                </span>
                <input
                  value={newRule.shipviaCd}
                  onChange={(e) => setNewRule((c) => ({ ...c, shipviaCd: e.target.value.toUpperCase() }))}
                  placeholder="e.g. P80"
                  className="w-full rounded-xl border border-slate-200 bg-white px-3 py-2 font-mono text-[13px] font-semibold text-slate-950 outline-none transition focus:border-[#412d15] focus:ring-4 focus:ring-[#412d15]/10"
                />
              </label>
              <label className="block sm:col-span-2">
                <span className="mb-1 block text-[10.5px] font-semibold uppercase tracking-[0.1em] text-slate-400">
                  Warehouse
                  <span className="ml-1 normal-case font-normal tracking-normal text-slate-400">
                    · {newRule.clientCode
                      ? `PLATFORM + ${newRule.clientCode}-owned`
                      : 'PLATFORM only (any-client rule)'}
                  </span>
                </span>
                {draftWarehouseChoices.length === 0 ? (
                  <p className="rounded-xl border border-dashed border-slate-200 bg-white px-3 py-2 text-[11.5px] text-slate-500">
                    No warehouses match this client — attach one first, then re-open this dialog.
                  </p>
                ) : (
                  <div className="flex flex-wrap gap-1.5">
                    <button
                      type="button"
                      onClick={() => setNewRule((c) => ({ ...c, warehouseIds: [], presetIds: [] }))}
                      className={`inline-flex items-center gap-1 rounded-full px-2.5 py-1 text-[11px] font-semibold transition ${
                        newRule.warehouseIds.length === 0
                          ? 'bg-[#412d15] text-white'
                          : 'border border-slate-200 bg-white text-slate-600 hover:bg-slate-50'
                      }`}
                    >
                      Any
                    </button>
                    {draftWarehouseChoices.map((w) => {
                      const on = newRule.warehouseIds.includes(w.id)
                      return (
                        <button
                          key={w.id}
                          type="button"
                          onClick={() => setNewRule((c) => ({
                            ...c,
                            warehouseIds: on
                              ? c.warehouseIds.filter((id) => id !== w.id)
                              : [...c.warehouseIds, w.id],
                            presetIds: [], // origin change → clear packages
                          }))}
                          className={`inline-flex items-center gap-1 rounded-full px-2.5 py-1 text-[11px] font-semibold transition ${
                            on
                              ? 'bg-[#412d15] text-white'
                              : 'border border-slate-200 bg-white text-slate-600 hover:bg-slate-50'
                          }`}
                          title={`${w.name}${w.address?.country ? ` (${w.address.country})` : ''}`}
                        >
                          <FiHome className="h-3 w-3" />
                          {w.code}
                        </button>
                      )
                    })}
                  </div>
                )}
              </label>
              <label className="block sm:col-span-2">
                <span className="mb-1 block text-[10.5px] font-semibold uppercase tracking-[0.1em] text-slate-400">
                  Ships to
                </span>
                <button
                  type="button"
                  onClick={() => openZone('new')}
                  className="inline-flex w-full items-center gap-1.5 rounded-xl border border-slate-200 bg-white px-3 py-2 text-left text-[13px] font-semibold text-slate-600 transition hover:border-[#412d15]/40"
                >
                  <FiGlobe className="h-3.5 w-3.5 shrink-0 text-sky-600" />
                  {newRule.destCodes.length ? <ZoneChips codes={newRule.destCodes} /> : 'Anywhere — click to narrow'}
                </button>
              </label>
              <label className="block sm:col-span-2">
                <span className="mb-1 block text-[10.5px] font-semibold uppercase tracking-[0.1em] text-slate-400">
                  Carrier Ship Via <span className="text-rose-500">*</span>
                  <span className="ml-1 normal-case font-normal tracking-normal text-slate-400">
                    · {draftServiceGroups.length} option{draftServiceGroups.length === 1 ? '' : 's'}
                    {draftOrigins.size ? ` from ${[...draftOrigins].join(' / ')}` : ' from any origin'}
                    {draftScope !== 'ANY' ? ` · ${draftScope}` : ''}
                  </span>
                </span>
                <Select
                  value={effectiveNewServiceId}
                  onChange={(e) => setNewRule((c) => ({
                    ...c,
                    serviceId: e.target.value,
                    presetIds: [],
                  }))}
                >
                  <option value="">Pick a carrier service…</option>
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
              </label>
              <label className="block sm:col-span-2">
                <span className="mb-1 block text-[10.5px] font-semibold uppercase tracking-[0.1em] text-slate-400">
                  Packages
                  <span className="ml-1 normal-case font-normal tracking-normal text-slate-400">
                    {!effectiveNewServiceId
                      ? '· pick a Carrier Ship Via first'
                      : draftPresetGroups.length === 0
                        ? '· no compatible packages for this carrier + origin'
                        : `· ${draftPresetGroups.length} available · ${draftSelectedPresetGroupIds.size} picked · empty = unrestricted`}
                  </span>
                </span>
                {draftPresetGroups.length > 0 ? (
                  <div className="flex flex-wrap gap-1.5">
                    {draftPresetGroups.map((g) => {
                      const on = draftSelectedPresetGroupIds.has(g.representative.id!)
                      return (
                        <button
                          key={g.key}
                          type="button"
                          onClick={() => {
                            setNewRule((c) => {
                              const cur = new Set(c.presetIds)
                              if (on) {
                                for (const id of g.variantIds) cur.delete(id)
                              } else {
                                for (const id of g.variantIds) cur.add(id)
                              }
                              return { ...c, presetIds: [...cur] }
                            })
                          }}
                          className={`inline-flex items-center gap-1 rounded-full px-2.5 py-1 text-[11px] font-semibold transition ${
                            on
                              ? 'bg-[#412d15] text-white'
                              : 'border border-slate-200 bg-white text-slate-600 hover:bg-slate-50'
                          }`}
                        >
                          <FiPackage className="h-3 w-3" />
                          {g.representative.name}
                        </button>
                      )
                    })}
                  </div>
                ) : (
                  <p className="rounded-xl border border-dashed border-slate-200 bg-white px-3 py-2 text-[11.5px] text-slate-500">
                    {!effectiveNewServiceId
                      ? 'Package options unlock once a Carrier Ship Via is picked.'
                      : 'No packages match this carrier + origin. Add carrier packages or custom boxes on the Packages page.'}
                  </p>
                )}
              </label>
            </div>

            <div className="mt-5 flex items-center justify-end gap-2 border-t border-slate-100 pt-4">
              <button
                type="button"
                onClick={() => closeAddForm()}
                className="rounded-xl border border-slate-200 bg-white px-4 py-2 text-[13px] font-semibold text-slate-600 transition hover:bg-slate-50"
              >
                Cancel
              </button>
              <button
                type="button"
                onClick={() => void saveNewRule()}
                disabled={!newRule.shipviaCd.trim() || !effectiveNewServiceId}
                className="rounded-xl bg-[#1f150c] px-5 py-2 text-[13px] font-semibold text-white transition hover:bg-[#412d15] disabled:cursor-not-allowed disabled:bg-slate-300"
              >
                Save mapping
              </button>
            </div>
          </div>
        </div>
      ) : null}
    </div>
  )
}

/**
 * Per-row Warehouse cell — compact chips + a PortalMenu-anchored checklist
 * for editing. Portalling the popover keeps the row height stable (the
 * AdvancedDataTable's overflow container would otherwise clip an inline
 * expansion, or the row itself would grow to fit the checklist).
 *
 * `choices` is already scoped by client via warehouseChoicesForClient — the
 * cell only offers PLATFORM warehouses for Any-client rules and PLATFORM +
 * client-owned for client-scoped rules.
 */
function WarehouseCell({
  choices,
  warehouseById,
  value,
  onSave,
}: {
  choices: Warehouse[]
  warehouseById: Map<number, Warehouse>
  value: number[]
  onSave: (next: number[]) => void
}) {
  const [open, setOpen] = useState(false)
  const [draft, setDraft] = useState<number[]>(value)
  const anchorRef = useRef<HTMLButtonElement>(null)

  const openPopover = () => {
    setDraft(value)
    setOpen(true)
  }

  const toggle = (id: number) => {
    setDraft((cur) => (cur.includes(id) ? cur.filter((x) => x !== id) : [...cur, id]))
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
              No warehouses available for this rule's client scope.
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
              {choices.map((w) => {
                const on = draft.includes(w.id)
                return (
                  <label
                    key={w.id}
                    className="flex cursor-pointer items-center gap-2 rounded-lg px-2 py-1.5 text-[12px] transition hover:bg-slate-50"
                  >
                    <input
                      type="checkbox"
                      checked={on}
                      onChange={() => toggle(w.id)}
                      className="h-3.5 w-3.5 rounded border-slate-300 text-[#412d15] focus:ring-[#412d15]"
                    />
                    <FiHome className="h-3 w-3 text-slate-500" />
                    <span className="flex-1 truncate font-semibold text-slate-800">
                      {w.code}
                      <span className="ml-1 font-normal text-slate-500">
                        {w.name}
                        {w.address?.country ? ` · ${w.address.country}` : ''}
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
