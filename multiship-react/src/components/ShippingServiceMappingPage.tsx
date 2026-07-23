import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useOutletContext } from 'react-router-dom'
import { notify } from '../utils/notify'
import { FiExternalLink, FiFilter, FiGlobe, FiLink, FiPlus, FiTrash2, FiTruck, FiUser, FiX } from 'react-icons/fi'
import type { ColumnDef } from '@tanstack/react-table'
import {
  shippingConfigService,
  type ShipMethodRule,
  type ShippingServiceItem,
} from '../api/shippingConfigService'
import { clientService, type Client } from '../api/clientService'
import { countriesInRegion, countryName, groupByRegion, regionOf, REGIONS, type Region } from '../utils/countries'
import AdvancedDataTable, { type EditCellProps, type EditNav } from './workspace/AdvancedDataTable'
import type { SettingsOutletContext } from './layout/SettingsLayout'
import Select from './workspace/Select'
import ZoneEditorModal from './workspace/ZoneEditorModal'

/** Supported carriers — matches the ShippingServicesPage catalog. */
const CARRIERS = ['UPS', 'FEDEX', 'USPS'] as const

const filterLabelClass = 'mb-1 flex items-center gap-1 text-[10px] font-bold uppercase tracking-[0.14em] text-slate-400'

const blankRule = { shipviaCd: '', clientCode: '', destCodes: [] as string[], serviceId: '' }

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
      notify.error(e instanceof Error ? e.message : 'Failed to update mapping.')
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
      notify.error(e instanceof Error ? e.message : 'Failed to update mapping.')
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
      notify.error(e instanceof Error ? e.message : 'Failed to update mapping.')
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
  const [loading, setLoading] = useState(true)
  const [newRule, setNewRule] = useState({ ...blankRule })
  /** Zone modal target: 'new' = the add-row, otherwise the rule being edited. */
  const [zoneFor, setZoneFor] = useState<'new' | ShipMethodRule | null>(null)
  const [zoneCodes, setZoneCodes] = useState<string[]>([])

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
  const closeAddForm = useCallback(() => {
    setAdding(false)
    setPopout(false)
    setNewRule({ ...blankRule, destCodes: [] })
  }, [])

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const [catalog, clientPage] = await Promise.all([
        shippingConfigService.catalog(),
        clientService.listClients({ size: 200 }),
      ])
      setServices(catalog.services)
      setRules(catalog.rules)
      setClients(clientPage.data?.content ?? [])
    } catch (e) {
      notify.error(e instanceof Error ? e.message : 'Failed to load the mapping catalog.')
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
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

  /**
   * Whether a rule (or a draft) targets domestic-only, international, or a
   * mix — inferred from the destination country codes vs the domestic country.
   * Returns 'DOMESTIC' | 'INTERNATIONAL' | 'BOTH' | 'ANY' (no destination).
   */
  const inferScope = useCallback(
    (destCodes: string[], domesticCountry: string | null): 'DOMESTIC' | 'INTERNATIONAL' | 'BOTH' | 'ANY' => {
      if (!destCodes.length) return 'ANY'
      if (!domesticCountry) return 'ANY'
      const dom = domesticCountry.toUpperCase()
      const hasDom = destCodes.some((c) => c.toUpperCase() === dom)
      const hasIntl = destCodes.some((c) => c.toUpperCase() !== dom)
      if (hasDom && hasIntl) return 'BOTH'
      return hasDom ? 'DOMESTIC' : 'INTERNATIONAL'
    },
    [],
  )

  /** A service's scope is compatible with a mapping's inferred scope. */
  const serviceMatchesScope = (
    svc: ShippingServiceItem,
    mappingScope: 'DOMESTIC' | 'INTERNATIONAL' | 'BOTH' | 'ANY',
  ): boolean => {
    if (mappingScope === 'ANY') return true
    if (svc.scope === 'BOTH') return true
    if (mappingScope === 'BOTH') return true // mixed mapping: any single-scope service is a partial fit
    return svc.scope === mappingScope
  }

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
      })
      notify.success('Mapping added.')
      setNewRule({ ...blankRule, destCodes: [] })
      closeAddForm()
      void load()
    } catch (e) {
      notify.error(e instanceof Error ? e.message : 'Failed to save the mapping.')
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
      })
      notify.success('Destination zone updated.')
      setZoneFor(null)
      void load()
    } catch (e) {
      notify.error(e instanceof Error ? e.message : 'Failed to update the zone.')
    }
  }

  /**
   * Persist a single-field edit from an inline cell editor. Throws on failure
   * so the editor can toast the error and stay in edit mode with the bad value.
   */
  const saveRuleField = useCallback(
    async (rule: ShipMethodRule, patch: Partial<ShipMethodRule>) => {
      await shippingConfigService.saveRule({ ...rule, ...patch })
      void load()
    },
    [load],
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
      notify.error(e instanceof Error ? e.message : 'Failed to remove the mapping.')
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
          editCell: (p: EditCellProps<ShipMethodRule>) => (
            <ServiceEditor
              {...p}
              services={services}
              onSaveField={saveRuleField}
              scopeMatch={(svc) => {
                const scope = inferScope(ruleCodes(p.row), domesticFor(p.row.clientCode))
                return serviceMatchesScope(svc, scope)
              }}
            />
          ),
        },
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
    [services, serviceById, clients, saveRuleField, domesticFor, inferScope],
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
                  <div className="min-w-[140px] flex-1">
                    <Select value={newRule.clientCode} onChange={(e) => setNewRule((c) => ({ ...c, clientCode: e.target.value }))}>
                      <option value="">Any client</option>
                      {clients.map((c) => (<option key={c.clientCode} value={c.clientCode}>{c.clientCode}</option>))}
                    </Select>
                  </div>
                  <input
                    value={newRule.shipviaCd}
                    onChange={(e) => setNewRule((c) => ({ ...c, shipviaCd: e.target.value.toUpperCase() }))}
                    placeholder="Ship via (e.g. P80)"
                    className="w-32 shrink-0 rounded-xl border border-slate-200 bg-white px-3 py-2 font-mono text-[12px] font-semibold text-slate-950 outline-none transition focus:border-[#412d15]"
                  />
                  <button
                    type="button"
                    onClick={() => openZone('new')}
                    className="inline-flex min-w-[160px] flex-1 items-center gap-1.5 rounded-xl border border-slate-200 bg-white px-3 py-2 text-left text-[12px] font-semibold text-slate-600 transition hover:border-[#412d15]/40"
                  >
                    <FiGlobe className="h-3.5 w-3.5 shrink-0 text-sky-600" />
                    {newRule.destCodes.length ? <ZoneChips codes={newRule.destCodes} /> : 'Ships to — anywhere'}
                  </button>
                  <div className="min-w-[200px] flex-1">
                    <Select value={newRule.serviceId} onChange={(e) => setNewRule((c) => ({ ...c, serviceId: e.target.value }))}>
                      <option value="">Carrier ship via…</option>
                      {services.filter((s) => s.enabled).map((s) => {
                        const scope = inferScope(newRule.destCodes, domesticFor(newRule.clientCode))
                        const fits = serviceMatchesScope(s, scope)
                        const tag = s.scope === 'DOMESTIC' ? ' [D]' : s.scope === 'INTERNATIONAL' ? ' [I]' : ' [D+I]'
                        return (
                          <option key={s.id} value={s.id} style={fits ? undefined : { color: '#94a3b8' }}>
                            {s.carrier} — {s.name}{tag}{fits ? '' : ' — scope mismatch'}
                          </option>
                        )
                      })}
                    </Select>
                  </div>
                  <div className="ml-auto flex shrink-0 items-center gap-1.5">
                    <button
                      type="button"
                      onClick={() => void saveNewRule()}
                      className="inline-flex items-center gap-1 rounded-xl bg-[#1f150c] px-4 py-2 text-[11.5px] font-semibold text-white transition hover:bg-[#412d15]"
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
                      onClick={closeAddForm}
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
                onClick={closeAddForm}
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
                <Select value={newRule.clientCode} onChange={(e) => setNewRule((c) => ({ ...c, clientCode: e.target.value }))}>
                  <option value="">Any client</option>
                  {clients.map((c) => (<option key={c.clientCode} value={c.clientCode}>{c.clientCode}</option>))}
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
                </span>
                <Select value={newRule.serviceId} onChange={(e) => setNewRule((c) => ({ ...c, serviceId: e.target.value }))}>
                  <option value="">Pick a carrier service…</option>
                  {services.filter((s) => s.enabled).map((s) => {
                    const scope = inferScope(newRule.destCodes, domesticFor(newRule.clientCode))
                    const fits = serviceMatchesScope(s, scope)
                    const tag = s.scope === 'DOMESTIC' ? ' [D]' : s.scope === 'INTERNATIONAL' ? ' [I]' : ' [D+I]'
                    return (
                      <option key={s.id} value={s.id} style={fits ? undefined : { color: '#94a3b8' }}>
                        {s.carrier} — {s.name}{tag}{fits ? '' : ' — scope mismatch'}
                      </option>
                    )
                  })}
                </Select>
              </label>
            </div>

            <div className="mt-5 flex items-center justify-end gap-2 border-t border-slate-100 pt-4">
              <button
                type="button"
                onClick={closeAddForm}
                className="rounded-xl border border-slate-200 bg-white px-4 py-2 text-[13px] font-semibold text-slate-600 transition hover:bg-slate-50"
              >
                Cancel
              </button>
              <button
                type="button"
                onClick={() => void saveNewRule()}
                className="rounded-xl bg-[#1f150c] px-5 py-2 text-[13px] font-semibold text-white transition hover:bg-[#412d15]"
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
