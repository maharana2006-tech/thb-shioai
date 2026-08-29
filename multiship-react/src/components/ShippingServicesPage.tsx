import { useCallback, useEffect, useMemo, useState } from 'react'
import { useOutletContext } from 'react-router-dom'
import { notify } from '../utils/notify'
import {
  FiBox,
  FiDownloadCloud,
  FiGlobe,
  FiHome,
  FiSend,
  FiUsers,
  FiX,
} from 'react-icons/fi'
import {
  fitAgainstService,
  limitsOf,
  shippingConfigService,
  type PackagePreset,
  type ServicePackageLink,
  type ShippingServiceItem,
} from '../api/shippingConfigService'
import { allowlistUsageService, type ClientAllowedService } from '../api/clientCatalogService'
import { countryName } from '../utils/countries'
import type { SettingsOutletContext } from './layout/SettingsLayout'
import CarrierSyncMenuModal from './modals/CarrierSyncMenuModal'

const CARRIER_BADGE: Record<string, { bg: string; mono: string }> = {
  UPS: { bg: 'bg-[#351C15]', mono: 'UPS' },
  FEDEX: { bg: 'bg-[#4D148C]', mono: 'FDX' },
  USPS: { bg: 'bg-[#1F5AA6]', mono: 'USP' },
}

/** The carriers a manifest is always shown for (so an un-synced origin can still be synced). */
const CARRIERS = ['UPS', 'FEDEX', 'USPS'] as const

/** Common ship-from origins offered in the picker (merged with whatever's already synced). */
const COMMON_ORIGINS = ['US', 'GB', 'DE', 'FR', 'NL', 'IT', 'ES', 'IN', 'CN', 'JP', 'AU', 'CA', 'MX', 'BR', 'SG']

/** Short relative time for the "synced N ago" provenance chip. */
const relTime = (iso: string): string => {
  const secs = Math.round((Date.now() - new Date(iso).getTime()) / 1000)
  if (secs < 60) return 'just now'
  const mins = Math.round(secs / 60)
  if (mins < 60) return `${mins}m ago`
  const hrs = Math.round(mins / 60)
  if (hrs < 24) return `${hrs}h ago`
  return `${Math.round(hrs / 24)}d ago`
}

/**
 * Provenance of a carrier's service group:
 *  live      — the carrier's LIVE availability API answered (CARRIER_API)
 *  built-in  — the built-in availability model was used (CARRIER_SYNC, no live creds)
 *  seeded    — legacy starter data (CARRIER_SYNC pre-dates the live path / SEEDED)
 */
const groupProvenance = (list: ShippingServiceItem[]): { kind: 'live' | 'built-in' | 'seeded'; when: string } | null => {
  if (!list.length) return null
  const dated = list.map((s) => s.syncedAt).filter(Boolean).sort()
  const when = dated.length ? relTime(dated.at(-1) as string) : ''
  if (list.some((s) => s.source === 'CARRIER_API')) return { kind: 'live', when }
  if (list.some((s) => s.source === 'CARRIER_SYNC')) return { kind: 'built-in', when }
  return { kind: 'seeded', when: '' }
}

/**
 * Whether a package can be LINKED to a service ("according to the carrier
 * service"): your custom boxes work anywhere; a carrier's own packaging only
 * links to that carrier's services, from the same origin, on a compatible
 * scope (a domestic-only flat-rate box can't go on an international service, an
 * international-only 10/25kg box can't go on a domestic one).
 */
const packageFitsService = (p: PackagePreset, s: ShippingServiceItem): boolean => {
  if (p.kind !== 'CARRIER') return true
  if ((p.carrier || '').toUpperCase() !== s.carrier.toUpperCase()) return false
  if ((p.originCountry ?? 'US').toUpperCase() !== (s.originCountry ?? 'US').toUpperCase()) return false
  const ps = p.scope || 'BOTH'
  if (ps === 'BOTH') return true
  return s.scope === 'BOTH' || s.scope === ps
}

/**
 * Shipping Services — the carrier service catalog (toggle what the platform
 * offers) and each service's ALLOWED PACKAGES (its carrier's own packaging plus
 * custom boxes). The order-method → service mappings live on their own page —
 * see ShippingServiceMappingPage.
 */
export default function ShippingServicesPage() {
  const [services, setServices] = useState<ShippingServiceItem[]>([])
  const [links, setLinks] = useState<ServicePackageLink[]>([])
  const [presets, setPresets] = useState<PackagePreset[]>([])
  const [assignments, setAssignments] = useState<ClientAllowedService[]>([])
  const [loading, setLoading] = useState(true)
  /** Service whose allowed-packages modal is open. */
  const [pkgService, setPkgService] = useState<ShippingServiceItem | null>(null)
  const [pkgDraft, setPkgDraft] = useState<Set<number>>(new Set())
  /** The origin country whose services are shown — carrier availability is lane-specific. */
  const [origin, setOrigin] = useState('US')
  const [originCountries, setOriginCountries] = useState<string[]>([])
  /** Carrier currently syncing (its Sync button spins). */
  const [syncing, setSyncing] = useState<string | null>(null)
  /** Carrier whose sync menu modal is open (env + account picker). */
  const [syncMenuFor, setSyncMenuFor] = useState<string | null>(null)

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const [catalog, presetList, usageResp] = await Promise.all([
        shippingConfigService.catalog(),
        shippingConfigService.listPresets(),
        allowlistUsageService.services(),
      ])
      setServices(catalog.services)
      setLinks(catalog.links)
      setOriginCountries(catalog.originCountries)
      setPresets(presetList)
      // Usage is decorative — a fetch failure shouldn't break the catalog view.
      setAssignments(usageResp.data ?? [])
    } catch (e) {
      notify.apiError(e, 'Failed to load the catalog.')
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- data fetch on mount; load() sets loading + catalog state
    void load()
  }, [load])

  const { registerRefresh } = useOutletContext<SettingsOutletContext>()
  useEffect(() => {
    registerRefresh(load)
    return () => registerRefresh(null)
  }, [registerRefresh, load])

  // Services offered FROM the selected origin (seeded rows default to US).
  const visibleServices = useMemo(
    () => services.filter((s) => (s.originCountry ?? 'US').toUpperCase() === origin.toUpperCase()),
    [services, origin],
  )
  // Always render the three carriers so an un-synced origin still shows a Sync button.
  const byCarrier = useMemo(
    () => CARRIERS.map((c) => [c, visibleServices.filter((s) => s.carrier === c)] as const),
    [visibleServices],
  )
  // Origin picker: a curated set of common origins merged with any already in the catalog.
  const originOptions = useMemo(() => {
    const merged = new Set<string>([...COMMON_ORIGINS, ...originCountries.map((c) => c.toUpperCase()), origin])
    return [...merged].sort((a, b) => countryName(a).localeCompare(countryName(b)))
  }, [originCountries, origin])

  const linksByService = useMemo(() => {
    const m = new Map<number, ServicePackageLink[]>()
    links.forEach((l) => m.set(l.serviceId, [...(m.get(l.serviceId) ?? []), l]))
    return m
  }, [links])
  /** service id -> allowlist rows (each = one client using the service). */
  const assignmentsByService = useMemo(() => {
    const m = new Map<number, ClientAllowedService[]>()
    assignments.forEach((a) => m.set(a.serviceId, [...(m.get(a.serviceId) ?? []), a]))
    return m
  }, [assignments])
  const enabledCount = visibleServices.filter((s) => s.enabled).length

  /** Perform the sync after the modal picker chooses env + account. */
  const runSync = async (carrier: string, accountId: number) => {
    setSyncing(carrier)
    try {
      const res = await shippingConfigService.syncServices(carrier, origin, accountId)
      const d = res.data
      if (d) {
        const head =
          d.total > 0
            ? `${carrier} · ${countryName(origin)}: ${d.added} new, ${d.updated} refreshed`
            : `${carrier} offers no services from ${countryName(origin)}`
        const msg = `${head} — ${d.via}.`
        // A live carrier response gets a success check; a built-in/fallback
        // result is reported neutrally so it's never mistaken for live data.
        if (d.live) notify.success(msg)
        else notify.info(msg)
      }
      await load()
    } catch (e) {
      notify.apiError(e, 'Failed to sync from the carrier.')
      throw e
    } finally {
      setSyncing(null)
    }
  }

  const toggle = async (svc: ShippingServiceItem) => {
    setServices((cur) => cur.map((s) => (s.id === svc.id ? { ...s, enabled: !s.enabled } : s)))
    try {
      await shippingConfigService.setServiceEnabled(svc.id, !svc.enabled)
    } catch (e) {
      notify.apiError(e, 'Failed to update the service.')
      void load()
    }
  }

  // ===== per-service allowed packages =====

  const openPackages = (svc: ShippingServiceItem) => {
    const current = linksByService.get(svc.id) ?? []
    setPkgDraft(new Set(current.map((l) => l.presetId)))
    setPkgService(svc)
  }

  const savePackages = async () => {
    if (!pkgService) return
    const payload = [...pkgDraft].map((presetId) => ({ presetId }))
    try {
      await shippingConfigService.setServicePackages(pkgService.id, payload)
      notify.success(`Allowed packages saved for ${pkgService.name}.`)
      setPkgService(null)
      void load()
    } catch (e) {
      notify.apiError(e, 'Failed to save packages.')
    }
  }

  return (
    <div className="space-y-4 pb-16">
      {/* Page-specific actions bar — origin picker only; Refresh is global (submenus row). */}
      <div className="flex flex-wrap items-center gap-2">
        <label
          title="Origin — the country shipments depart from. Filters which carrier services are available (it is NOT the destination)."
          className="flex items-center gap-1.5 rounded-xl border border-slate-200 bg-white pl-3 pr-1.5 py-1.5"
        >
          <FiGlobe className="h-3.5 w-3.5 shrink-0 text-slate-400" />
          <span className="text-[10px] font-bold uppercase tracking-[0.14em] text-slate-400">
            Ship from <span className="text-slate-300">· origin</span>
          </span>
          <select
            value={origin}
            onChange={(e) => setOrigin(e.target.value)}
            className="cursor-pointer bg-transparent py-1 pr-1 text-[12.5px] font-semibold text-[#1f150c] outline-none"
          >
            {originOptions.map((code) => (
              <option key={code} value={code}>
                {countryName(code)} ({code})
              </option>
            ))}
          </select>
        </label>
      </div>

      {/* health strip — tally tickets */}
      <section className="grid grid-cols-2 gap-3">
        {[
          { label: `Services from ${origin}`, value: `${enabledCount}/${visibleServices.length}`, unit: 'services', icon: FiSend, tone: 'border-[#412d15]/25 bg-[#412d15]/[0.06] text-[#412d15]' },
          { label: 'Package links', value: links.length, unit: 'links', icon: FiBox, tone: 'border-emerald-200 bg-emerald-50 text-emerald-600' },
        ].map((c, idx) => (
          <div key={c.label} className="rounded-2xl border border-slate-200 bg-white shadow-sm">
            <div className="flex items-start justify-between gap-2 px-4 pt-3.5">
              <p className="flex min-w-0 items-baseline gap-2">
                <span className="shrink-0 font-mono text-[9px] font-bold tracking-widest text-slate-300">
                  {String(idx + 1).padStart(2, '0')}
                </span>
                <span className="truncate text-[10px] font-bold uppercase tracking-[0.16em] text-slate-400">{c.label}</span>
              </p>
              <span className={`inline-flex h-8 w-8 shrink-0 items-center justify-center rounded-full border ${c.tone}`}>
                <c.icon className="h-4 w-4" />
              </span>
            </div>
            <p className="flex items-baseline gap-1.5 px-4 pb-4 pt-1">
              <span className="text-[28px] font-semibold leading-none tabular-nums tracking-tight text-slate-950">{c.value}</span>
              <span className="font-mono text-[9.5px] font-semibold uppercase tracking-[0.18em] text-slate-400">{c.unit}</span>
            </p>
          </div>
        ))}
      </section>

      {/* service catalog, grouped by carrier */}
      <section className="grid gap-4 lg:grid-cols-3">
        {loading && !services.length ? (
          <p className="col-span-3 py-10 text-center text-sm text-slate-500">Loading catalog…</p>
        ) : (
          byCarrier.map(([carrier, list]) => {
            const badge = CARRIER_BADGE[carrier] ?? { bg: 'bg-slate-700', mono: carrier.slice(0, 3) }
            const on = list.filter((s) => s.enabled).length
            // Sprint 52 PR 3 — aggregate the "enabled but zero linked
            // packages" count for the carrier header chip. Same predicate
            // as the per-row needsPackages badge; disabled services with
            // 0 links don't count (turning a service ON is when the guard
            // begins to matter).
            const needsPackagesCount = list.filter(
              (s) => s.enabled && (linksByService.get(s.id) ?? []).length === 0,
            ).length
            const prov = groupProvenance(list)
            const isSyncing = syncing === carrier
            return (
              <div key={carrier} className="overflow-hidden rounded-2xl border border-slate-200 bg-white shadow-sm">
                {/* document band: carrier plate + manifest title + origin + sync */}
                <div className="flex items-center justify-between gap-2 bg-[#1f150c] px-4 py-2.5">
                  <p className="flex min-w-0 items-center gap-2.5">
                    <span className={`flex h-6 w-9 shrink-0 items-center justify-center rounded ${badge.bg} font-mono text-[9px] font-black tracking-wider text-white`}>
                      {badge.mono}
                    </span>
                    <span className="truncate text-[10px] font-black uppercase tracking-[0.2em] text-[#e1dcc9]">
                      {carrier} · from {origin}
                    </span>
                  </p>
                  <div className="flex shrink-0 items-center gap-1.5">
                    {list.length ? (
                      <span className="rounded bg-[#e1dcc9]/15 px-2 py-0.5 font-mono text-[10px] font-bold tabular-nums text-[#e1dcc9]">
                        {on}/{list.length} ON
                      </span>
                    ) : null}
                    {needsPackagesCount > 0 ? (
                      <span
                        title={`${needsPackagesCount} enabled ${needsPackagesCount === 1 ? 'service has' : 'services have'} zero linked packages — manual-label will fail with SERVICE_HAS_NO_LINKED_PACKAGES until at least one preset is linked.`}
                        data-testid={`carrier-needs-packages-${carrier}`}
                        className="inline-flex items-center gap-1 rounded bg-amber-200/30 px-2 py-0.5 font-mono text-[10px] font-bold tabular-nums text-amber-100"
                      >
                        ⚠ {needsPackagesCount} need packages
                      </span>
                    ) : null}
                    <button
                      type="button"
                      onClick={() => setSyncMenuFor(carrier)}
                      disabled={isSyncing}
                      title={`Pull ${carrier}'s available services from ${countryName(origin)}`}
                      className="inline-flex items-center gap-1 rounded bg-[#e1dcc9]/15 px-2 py-1 text-[10px] font-bold uppercase tracking-wide text-[#e1dcc9] transition hover:bg-[#e1dcc9]/25 disabled:opacity-50"
                    >
                      <FiDownloadCloud className={`h-3 w-3 ${isSyncing ? 'animate-pulse' : ''}`} />
                      {isSyncing ? 'Syncing' : 'Sync'}
                    </button>
                  </div>
                </div>
                {prov ? (
                  <div className="flex items-center gap-1.5 border-b border-dashed border-slate-200 bg-[#faf9f7] px-4 py-1.5">
                    <span
                      className={`h-1.5 w-1.5 rounded-full ${
                        prov.kind === 'live' ? 'bg-emerald-500' : prov.kind === 'built-in' ? 'bg-amber-400' : 'bg-slate-300'
                      }`}
                    />
                    <span className="font-mono text-[9px] font-bold uppercase tracking-[0.14em] text-slate-400">
                      {prov.kind === 'live'
                        ? 'Live carrier API'
                        : prov.kind === 'built-in'
                          ? 'Built-in availability'
                          : 'Starter catalog'}
                      {prov.when ? ` · ${prov.when}` : ''}
                    </span>
                  </div>
                ) : null}
                {!list.length ? (
                  <div className="px-4 py-8 text-center">
                    <p className="text-[12.5px] font-medium text-slate-500">
                      No {carrier} services from {countryName(origin)} yet.
                    </p>
                    <button
                      type="button"
                      onClick={() => setSyncMenuFor(carrier)}
                      disabled={isSyncing}
                      className="mt-2.5 inline-flex items-center gap-1.5 rounded-xl bg-[#1f150c] px-3 py-1.5 text-[11.5px] font-semibold text-white transition hover:bg-[#412d15] disabled:opacity-50"
                    >
                      <FiDownloadCloud className="h-3.5 w-3.5" />
                      {isSyncing ? 'Syncing…' : 'Sync from carrier'}
                    </button>
                  </div>
                ) : null}
                <ul className="divide-y divide-dashed divide-slate-200">
                  {list.map((s, i) => {
                    const pkgCount = (linksByService.get(s.id) ?? []).length
                    // Sprint 52 PR 3 — enabled service with zero linked
                    // packages will hard-fail every manual-label attempt
                    // with SERVICE_HAS_NO_LINKED_PACKAGES (guard in PR 1,
                    // PR #512). Surface that as an amber warning rather
                    // than the neutral grey used for disabled/unconfigured
                    // rows so admins can see the gap at a glance.
                    const needsPackages = s.enabled && pkgCount === 0
                    const clientRows = assignmentsByService.get(s.id) ?? []
                    const clientCount = clientRows.length
                    const clientTooltip = clientRows.length
                      ? clientRows
                          .map((r) => `${r.clientCode}${r.isDefault ? ' ★' : ''}`)
                          .join(', ')
                      : 'No clients allowed yet.'
                    return (
                      <li key={s.id} className="flex items-center gap-2.5 px-4 py-2.5">
                        <span className="w-5 shrink-0 font-mono text-[9px] font-bold tracking-widest text-slate-300">
                          {String(i + 1).padStart(2, '0')}
                        </span>
                        <button
                          type="button"
                          role="switch"
                          aria-checked={s.enabled}
                          onClick={() => void toggle(s)}
                          className={`relative h-5 w-9 shrink-0 rounded-full transition ${s.enabled ? 'bg-[#1f150c]' : 'bg-slate-200'}`}
                          title={s.enabled ? 'Disable' : 'Enable'}
                        >
                          <span className={`absolute top-0.5 h-4 w-4 rounded-full bg-white shadow transition-all ${s.enabled ? 'left-[18px]' : 'left-0.5'}`} />
                        </button>
                        <div className="min-w-0 flex-1">
                          <p className={`truncate text-[12.5px] font-semibold ${s.enabled ? 'text-slate-900' : 'text-slate-400'}`}>{s.name}</p>
                          <p className="font-mono text-[10.5px] text-slate-400">{s.serviceCode}</p>
                        </div>
                        <button
                          type="button"
                          onClick={() => openPackages(s)}
                          title={
                            needsPackages
                              ? `${s.serviceCode} is enabled but has zero linked packages — manual-label will fail with SERVICE_HAS_NO_LINKED_PACKAGES. Click to link at least one preset.`
                              : pkgCount
                                ? `${pkgCount} linked package${pkgCount === 1 ? '' : 's'} — click to edit`
                                : 'No linked packages (service disabled) — click to link'
                          }
                          data-testid={`pkg-count-${s.id}`}
                          className={`inline-flex items-center gap-1 rounded-lg border px-1.5 py-1 text-[10px] font-bold transition ${
                            needsPackages
                              ? 'border-amber-300 bg-amber-50 text-amber-700 hover:bg-amber-100'
                              : pkgCount
                                ? 'border-emerald-200 bg-emerald-50 text-emerald-700 hover:bg-emerald-100'
                                : 'border-slate-200 bg-white text-slate-400 hover:bg-slate-50'
                          }`}
                        >
                          <FiBox className="h-3 w-3" /> {needsPackages ? '⚠ 0' : pkgCount || '+'}
                        </button>
                        <span
                          title={`Assigned to: ${clientTooltip}`}
                          className={`inline-flex items-center gap-1 rounded-lg border px-1.5 py-1 text-[10px] font-bold transition ${
                            clientCount
                              ? 'border-[#412d15]/20 bg-[#412d15]/[0.06] text-[#412d15]'
                              : 'border-slate-200 bg-white text-slate-400'
                          }`}
                        >
                          <FiUsers className="h-3 w-3" /> {clientCount || 0}
                        </span>
                        <span
                          className={`inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-[9.5px] font-bold uppercase tracking-wide ring-1 ${
                            s.scope === 'INTERNATIONAL' ? 'bg-sky-50 text-sky-700 ring-sky-100' : 'bg-emerald-50 text-emerald-700 ring-emerald-100'
                          }`}
                        >
                          {s.scope === 'INTERNATIONAL' ? <FiGlobe className="h-2.5 w-2.5" /> : <FiHome className="h-2.5 w-2.5" />}
                          {s.scope === 'INTERNATIONAL' ? 'Intl' : s.scope === 'BOTH' ? 'Both' : 'Dom'}
                        </span>
                      </li>
                    )
                  })}
                </ul>
              </div>
            )
          })
        )}
      </section>

      {/* allowed-packages modal */}
      {pkgService ? (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center bg-slate-950/45 p-4 backdrop-blur-sm"
          role="dialog"
          aria-modal="true"
          onClick={() => setPkgService(null)}
        >
          <div
            className="w-full max-w-md rounded-2xl border border-slate-200 bg-white p-5 shadow-[0_30px_80px_rgba(15,23,42,0.35)]"
            onClick={(e) => e.stopPropagation()}
          >
            <div className="mb-1 flex items-start justify-between">
              <h3 className="inline-flex items-center gap-2 text-[15px] font-semibold text-slate-950">
                <span className="inline-flex h-7 w-7 items-center justify-center rounded-lg bg-emerald-50 text-emerald-600"><FiBox className="h-4 w-4" /></span>
                Allowed packages
              </h3>
              <button
                type="button"
                onClick={() => setPkgService(null)}
                className="rounded-xl border border-slate-200 bg-white p-2 text-slate-500 transition hover:bg-slate-50"
                aria-label="Close"
              >
                <FiX className="h-4 w-4" />
              </button>
            </div>
            <p className="text-[12px] text-slate-500">
              <span className="font-semibold text-slate-700">{pkgService.carrier} — {pkgService.name}</span>: tick the
              packages this service may ship in. The smallest box whose max weight fits the order is auto-picked; none
              ticked = the global default package.
            </p>
            {(() => {
              const lim = limitsOf(pkgService)
              return (
                <p className="mt-1.5 flex flex-wrap items-center gap-1.5">
                  <span className="text-[9.5px] font-bold uppercase tracking-[0.14em] text-slate-400">Service limits</span>
                  <span className="rounded-full bg-slate-100 px-2 py-0.5 font-mono text-[10px] font-semibold text-slate-600">
                    max {lim.maxWeightLb ?? '—'} lb
                  </span>
                  <span className="rounded-full bg-slate-100 px-2 py-0.5 font-mono text-[10px] font-semibold text-slate-600">
                    {lim.maxLengthGirthIn ?? '—'}&quot; L+girth
                  </span>
                  {lim.maxLengthIn != null ? (
                    <span className="rounded-full bg-slate-100 px-2 py-0.5 font-mono text-[10px] font-semibold text-slate-600">
                      {lim.maxLengthIn}&quot; length
                    </span>
                  ) : null}
                </p>
              )
            })()}

            <ul className="mt-3 max-h-[300px] divide-y divide-slate-100 overflow-y-auto rounded-xl border border-slate-200">
              {presets
                .filter((p) => packageFitsService(p, pkgService))
                .map((p) => {
                  const on = pkgDraft.has(p.id!)
                  const fit = fitAgainstService(p, pkgService)
                  const fitChip =
                    fit.status === 'FITS'
                      ? null
                      : fit.status === 'SURCHARGE'
                        ? { cls: 'bg-amber-50 text-amber-700 ring-amber-200', label: '⚠ Surcharge' }
                        : fit.status === 'OVERWEIGHT'
                          ? { cls: 'bg-amber-50 text-amber-700 ring-amber-200', label: '⚠ Over weight limit' }
                          : { cls: 'bg-rose-50 text-rose-700 ring-rose-200', label: '✕ Exceeds this service' }
                  return (
                    <li key={p.id} className="flex items-center gap-2.5 px-3 py-2.5">
                      <input
                        type="checkbox"
                        checked={on}
                        onChange={(e) =>
                          setPkgDraft((cur) => {
                            const next = new Set(cur)
                            if (e.target.checked) next.add(p.id!)
                            else next.delete(p.id!)
                            return next
                          })
                        }
                        className="h-4 w-4 rounded border-slate-300 text-slate-950 focus:ring-slate-300"
                      />
                      <div className="min-w-0 flex-1">
                        <p className="flex items-center gap-1.5 text-[12.5px] font-semibold text-slate-900">
                          <span className="truncate">{p.name}</span>
                          {p.kind === 'CARRIER' ? (
                            <span className="shrink-0 rounded-full bg-slate-100 px-1.5 py-0.5 text-[9px] font-bold uppercase tracking-wide text-slate-500">
                              {p.carrier}
                            </span>
                          ) : null}
                          {fitChip ? (
                            <span
                              title={fit.reason}
                              className={`shrink-0 rounded-full px-1.5 py-0.5 text-[9px] font-bold uppercase tracking-wide ring-1 ${fitChip.cls}`}
                            >
                              {fitChip.label}
                            </span>
                          ) : null}
                        </p>
                        <p className="text-[10.5px] text-slate-400">
                          {p.length && p.width && p.height ? `${p.length}×${p.width}×${p.height} ${p.dimUnit.toLowerCase()} · ` : ''}
                          max {p.maxWeight ?? '—'} {p.weightUnit.toLowerCase()}
                          {p.kind === 'CARRIER' ? ' · carrier packaging' : ' · your box'}
                        </p>
                      </div>
                    </li>
                  )
                })}
            </ul>
            <p className="mt-1.5 text-[10.5px] text-slate-400">
              Only {pkgService.carrier}'s own packaging and your custom boxes can be linked to a {pkgService.carrier} service.
            </p>

            <div className="mt-4 flex items-center justify-end gap-2 border-t border-slate-100 pt-4">
              <button
                type="button"
                onClick={() => setPkgService(null)}
                className="rounded-xl border border-slate-200 bg-white px-4 py-2 text-[13px] font-semibold text-slate-600 transition hover:bg-slate-50"
              >
                Cancel
              </button>
              <button
                type="button"
                onClick={() => void savePackages()}
                className="rounded-xl bg-[#1f150c] px-5 py-2 text-[13px] font-semibold text-white transition hover:bg-[#412d15]"
              >
                Save packages
              </button>
            </div>
          </div>
        </div>
      ) : null}
      {syncMenuFor ? (
        <CarrierSyncMenuModal
          carrier={syncMenuFor}
          originCountry={origin}
          kind="services"
          onClose={() => setSyncMenuFor(null)}
          onConfirm={(accountId) => runSync(syncMenuFor, accountId)}
        />
      ) : null}
    </div>
  )
}
