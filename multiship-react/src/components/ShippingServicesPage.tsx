import { useEffect, useMemo, useState } from 'react'
import toast from 'react-hot-toast'
import {
  FiBox,
  FiDownloadCloud,
  FiGlobe,
  FiHome,
  FiLink,
  FiPlus,
  FiRefreshCw,
  FiSend,
  FiTrash2,
  FiTruck,
  FiUser,
  FiX,
} from 'react-icons/fi'
import {
  fitAgainstService,
  limitsOf,
  shippingConfigService,
  type PackagePreset,
  type ServicePackageLink,
  type ShipMethodRule,
  type ShippingServiceItem,
} from '../api/shippingConfigService'
import { clientService, type Client } from '../api/clientService'
import { countriesInRegion, countryName, groupByRegion } from '../utils/countries'
import PageSectionHeader from './workspace/PageSectionHeader'
import RegionCountryPicker from './workspace/RegionCountryPicker'
import Select from './workspace/Select'

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

/**
 * Shipping Services — the carrier service catalog (toggle what the platform
 * offers), each service's ALLOWED PACKAGES (its carrier's own packaging + custom boxes), and
 * the SHIP-METHOD RULES that resolve an order's ship method to a carrier
 * service by client + destination (most specific wins).
 */
export default function ShippingServicesPage() {
  const [services, setServices] = useState<ShippingServiceItem[]>([])
  const [rules, setRules] = useState<ShipMethodRule[]>([])
  const [links, setLinks] = useState<ServicePackageLink[]>([])
  const [presets, setPresets] = useState<PackagePreset[]>([])
  const [clients, setClients] = useState<Client[]>([])
  const [loading, setLoading] = useState(true)
  const [newRule, setNewRule] = useState({ ...blankRule })
  /** Zone modal target: 'new' = the add-row, otherwise the rule being edited. */
  const [zoneFor, setZoneFor] = useState<'new' | ShipMethodRule | null>(null)
  const [zoneCodes, setZoneCodes] = useState<string[]>([])
  /** Service whose allowed-packages modal is open. */
  const [pkgService, setPkgService] = useState<ShippingServiceItem | null>(null)
  const [pkgDraft, setPkgDraft] = useState<Set<number>>(new Set())
  /** The origin country whose services are shown — carrier availability is lane-specific. */
  const [origin, setOrigin] = useState('US')
  const [originCountries, setOriginCountries] = useState<string[]>([])
  /** Carrier currently syncing (its Sync button spins). */
  const [syncing, setSyncing] = useState<string | null>(null)

  const load = async () => {
    setLoading(true)
    try {
      const [catalog, presetList, clientPage] = await Promise.all([
        shippingConfigService.catalog(),
        shippingConfigService.listPresets(),
        clientService.listClients({ size: 200 }),
      ])
      setServices(catalog.services)
      setRules(catalog.rules)
      setLinks(catalog.links)
      setOriginCountries(catalog.originCountries)
      setPresets(presetList)
      setClients(clientPage.data?.content ?? [])
    } catch (e) {
      toast.error(e instanceof Error ? e.message : 'Failed to load the catalog.')
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    void load()
  }, [])

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

  const serviceById = useMemo(() => new Map(services.map((s) => [s.id, s])), [services])
  const linksByService = useMemo(() => {
    const m = new Map<number, ServicePackageLink[]>()
    links.forEach((l) => m.set(l.serviceId, [...(m.get(l.serviceId) ?? []), l]))
    return m
  }, [links])
  const enabledCount = visibleServices.filter((s) => s.enabled).length

  const syncCarrier = async (carrier: string) => {
    setSyncing(carrier)
    try {
      const res = await shippingConfigService.syncServices(carrier, origin)
      const d = res.data
      if (d) {
        const head =
          d.total > 0
            ? `${carrier} · ${countryName(origin)}: ${d.added} new, ${d.updated} refreshed`
            : `${carrier} offers no services from ${countryName(origin)}`
        const msg = `${head} — ${d.via}.`
        // A live carrier response gets a success check; a built-in/fallback
        // result is reported neutrally so it's never mistaken for live data.
        if (d.live) toast.success(msg)
        else toast(msg, { icon: 'ℹ️' })
      }
      await load()
    } catch (e) {
      toast.error(e instanceof Error ? e.message : 'Failed to sync from the carrier.')
    } finally {
      setSyncing(null)
    }
  }

  const toggle = async (svc: ShippingServiceItem) => {
    setServices((cur) => cur.map((s) => (s.id === svc.id ? { ...s, enabled: !s.enabled } : s)))
    try {
      await shippingConfigService.setServiceEnabled(svc.id, !svc.enabled)
    } catch (e) {
      toast.error(e instanceof Error ? e.message : 'Failed to update the service.')
      void load()
    }
  }

  // ===== rules =====

  const saveNewRule = async () => {
    if (!newRule.shipviaCd.trim() || !newRule.serviceId) {
      toast.error('Enter the order ship-method code and pick a carrier service.')
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
      toast.success('Rule added.')
      setNewRule({ ...blankRule, destCodes: [] })
      void load()
    } catch (e) {
      toast.error(e instanceof Error ? e.message : 'Failed to save the rule.')
    }
  }

  // ===== destination-zone modal =====

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
      toast.success('Destination zone updated.')
      setZoneFor(null)
      void load()
    } catch (e) {
      toast.error(e instanceof Error ? e.message : 'Failed to update the zone.')
    }
  }

  const changeRuleService = async (rule: ShipMethodRule, serviceId: number) => {
    try {
      await shippingConfigService.saveRule({ ...rule, serviceId })
      void load()
    } catch (e) {
      toast.error(e instanceof Error ? e.message : 'Failed to update the rule.')
    }
  }

  const removeRule = async (rule: ShipMethodRule) => {
    if (!rule.id) return
    if (!window.confirm(`Remove this ${rule.shipviaCd} rule?`)) return
    try {
      await shippingConfigService.deleteRule(rule.id)
      void load()
    } catch (e) {
      toast.error(e instanceof Error ? e.message : 'Failed to remove the rule.')
    }
  }

  /** Specificity badge: what narrows this rule. */
  const ruleScope = (r: ShipMethodRule) => {
    const parts: string[] = []
    if (r.clientCode) parts.push(r.clientCode)
    if (r.destType === 'COUNTRY' && r.destValue) parts.push(countryName(r.destValue))
    if (r.destType === 'REGION' && r.destValue) parts.push(r.destValue)
    return parts
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
      toast.success(`Allowed packages saved for ${pkgService.name}.`)
      setPkgService(null)
      void load()
    } catch (e) {
      toast.error(e instanceof Error ? e.message : 'Failed to save packages.')
    }
  }

  return (
    <div className="space-y-4 pb-16">
      <PageSectionHeader
        title="Shipping Services"
        description="Carrier service levels pulled from each carrier's availability API per ship-from (origin) country, their allowed packages, and the ship-method rules that pick the service by client + where the order ships to (destination)."
        actions={
          <div className="flex items-center gap-2">
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
            <button
              type="button"
              onClick={() => void load()}
              className="inline-flex items-center gap-1.5 rounded-xl border border-slate-200 bg-white px-3 py-2 text-[12.5px] font-semibold text-slate-600 transition hover:bg-slate-50"
            >
              <FiRefreshCw className="h-3.5 w-3.5" /> Refresh
            </button>
          </div>
        }
      />

      {/* health strip — tally tickets */}
      <section className="grid grid-cols-3 gap-3">
        {[
          { label: `Services from ${origin}`, value: `${enabledCount}/${visibleServices.length}`, unit: 'services', icon: FiSend, tone: 'border-[#412d15]/25 bg-[#412d15]/[0.06] text-[#412d15]' },
          { label: 'Ship-method rules', value: rules.length, unit: 'rules', icon: FiLink, tone: 'border-sky-200 bg-sky-50 text-sky-700' },
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
                    <button
                      type="button"
                      onClick={() => void syncCarrier(carrier)}
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
                      onClick={() => void syncCarrier(carrier)}
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
                          title="Allowed packages"
                          className={`inline-flex items-center gap-1 rounded-lg border px-1.5 py-1 text-[10px] font-bold transition ${
                            pkgCount ? 'border-emerald-200 bg-emerald-50 text-emerald-700 hover:bg-emerald-100' : 'border-slate-200 bg-white text-slate-400 hover:bg-slate-50'
                          }`}
                        >
                          <FiBox className="h-3 w-3" /> {pkgCount || '+'}
                        </button>
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

      {/* ship-method rules */}
      <section className="rounded-2xl border border-slate-200 bg-white p-5 shadow-sm">
        <h3 className="inline-flex items-center gap-2 text-[13.5px] font-semibold text-slate-950">
          <span className="inline-flex h-6 w-6 items-center justify-center rounded-lg bg-sky-50 text-sky-700"><FiLink className="h-3.5 w-3.5" /></span>
          Ship-method rules
        </h3>
        <p className="mt-1 text-[12px] text-slate-500">
          An order's ship method (P80, F77…) resolves to a carrier service. Narrow a rule by client and where the order{' '}
          <span className="font-semibold text-slate-600">ships to</span> (a region or set of destination countries) — the
          most specific rule wins (client + country → client + region → client → global).
        </p>

        <div className="mt-3 overflow-x-auto">
          <table className="w-full min-w-[760px] text-[13px] text-slate-700">
            <thead className="border-b border-dashed border-slate-300 text-left font-mono text-[9px] font-bold uppercase tracking-[0.18em] text-slate-400">
              <tr>
                <th className="px-2.5 py-2.5">Order method</th>
                <th className="px-2.5 py-2.5">Client</th>
                <th className="px-2.5 py-2.5" title="Where the parcel is going — a region or a set of destination countries">
                  Ships to
                </th>
                <th className="px-2.5 py-2.5">Resolves to</th>
                <th className="px-2.5 py-2.5 text-right">Actions</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-dashed divide-slate-200">
              {rules.map((r) => (
                <tr key={r.id}>
                  <td className="px-2.5 py-2.5">
                    <span className="rounded-lg bg-[#1f150c] px-2.5 py-1 font-mono text-[12px] font-bold text-[#e1dcc9]">{r.shipviaCd}</span>
                  </td>
                  <td className="px-2.5 py-2.5">
                    {r.clientCode ? (
                      <span className="inline-flex items-center gap-1 rounded-full bg-[#412d15]/10 px-2 py-0.5 text-[11px] font-semibold text-[#412d15]">
                        <FiUser className="h-3 w-3" /> {r.clientCode}
                      </span>
                    ) : (
                      <span className="text-[11.5px] text-slate-400">Any client</span>
                    )}
                  </td>
                  <td className="px-2.5 py-2.5">
                    <button
                      type="button"
                      onClick={() => openZone(r)}
                      title="Edit destination zone"
                      className="rounded-lg px-1 py-0.5 text-left transition hover:bg-slate-50"
                    >
                      <ZoneChips codes={ruleCodes(r)} />
                    </button>
                  </td>
                  <td className="px-2.5 py-2.5">
                    <div className="max-w-[300px]">
                      <Select value={String(r.serviceId)} onChange={(e) => void changeRuleService(r, Number(e.target.value))}>
                        {services.map((s) => (
                          <option key={s.id} value={s.id} disabled={!s.enabled}>
                            {s.carrier} — {s.name}{s.enabled ? '' : ' (disabled)'}
                          </option>
                        ))}
                      </Select>
                    </div>
                    {serviceById.get(r.serviceId) && !serviceById.get(r.serviceId)!.enabled ? (
                      <p className="mt-1 text-[10.5px] text-amber-700">Service disabled — orders fall back to the carrier default.</p>
                    ) : null}
                  </td>
                  <td className="px-2.5 py-2.5 text-right">
                    <button
                      type="button"
                      onClick={() => void removeRule(r)}
                      aria-label={`Remove rule ${r.shipviaCd}${ruleScope(r).length ? ' ' + ruleScope(r).join(' ') : ''}`}
                      className="rounded-lg border border-rose-200 bg-white p-1.5 text-rose-600 transition hover:bg-rose-50"
                    >
                      <FiTrash2 className="h-3.5 w-3.5" />
                    </button>
                  </td>
                </tr>
              ))}
              {/* add row */}
              <tr className="bg-slate-50/60 align-top">
                <td className="px-2.5 py-2.5">
                  <input
                    value={newRule.shipviaCd}
                    onChange={(e) => setNewRule((c) => ({ ...c, shipviaCd: e.target.value.toUpperCase() }))}
                    placeholder="e.g. P80"
                    className="w-24 rounded-xl border border-slate-200 bg-white px-3 py-2 font-mono text-[12px] font-semibold text-slate-950 outline-none transition focus:border-[#412d15]"
                  />
                </td>
                <td className="px-2.5 py-2.5">
                  <div className="min-w-[150px]">
                    <Select value={newRule.clientCode} onChange={(e) => setNewRule((c) => ({ ...c, clientCode: e.target.value }))}>
                      <option value="">Any client</option>
                      {clients.map((c) => (<option key={c.clientCode} value={c.clientCode}>{c.clientCode}</option>))}
                    </Select>
                  </div>
                </td>
                <td className="px-2.5 py-2.5">
                  <button
                    type="button"
                    onClick={() => openZone('new')}
                    className="inline-flex min-w-[160px] items-center gap-1.5 rounded-xl border border-slate-200 bg-white px-3 py-2 text-left text-[12px] font-semibold text-slate-600 transition hover:border-[#412d15]/40"
                  >
                    <FiGlobe className="h-3.5 w-3.5 shrink-0 text-sky-600" />
                    {newRule.destCodes.length ? <ZoneChips codes={newRule.destCodes} /> : 'Anywhere — click to narrow'}
                  </button>
                </td>
                <td className="px-2.5 py-2.5">
                  <div className="max-w-[300px]">
                    <Select value={newRule.serviceId} onChange={(e) => setNewRule((c) => ({ ...c, serviceId: e.target.value }))}>
                      <option value="">Pick a service…</option>
                      {services.filter((s) => s.enabled).map((s) => (
                        <option key={s.id} value={s.id}>{s.carrier} — {s.name}</option>
                      ))}
                    </Select>
                  </div>
                </td>
                <td className="px-2.5 py-2.5 text-right">
                  <button
                    type="button"
                    onClick={() => void saveNewRule()}
                    className="inline-flex items-center gap-1 rounded-xl bg-[#1f150c] px-3 py-1.5 text-[11.5px] font-semibold text-white transition hover:bg-[#412d15]"
                  >
                    <FiPlus className="h-3 w-3" /> Add rule
                  </button>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>

      {/* destination-zone modal */}
      {zoneFor ? (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center bg-slate-950/45 p-4 backdrop-blur-sm"
          role="dialog"
          aria-modal="true"
          onClick={() => setZoneFor(null)}
        >
          <div
            className="w-full max-w-2xl rounded-2xl border border-slate-200 bg-white p-5 shadow-[0_30px_80px_rgba(15,23,42,0.35)]"
            onClick={(e) => e.stopPropagation()}
          >
            <div className="mb-1 flex items-start justify-between">
              <h3 className="inline-flex items-center gap-2 text-[15px] font-semibold text-slate-950">
                <span className="inline-flex h-7 w-7 items-center justify-center rounded-lg bg-sky-50 text-sky-700"><FiGlobe className="h-4 w-4" /></span>
                Ships to — destination zone
              </h3>
              <button
                type="button"
                onClick={() => setZoneFor(null)}
                className="rounded-xl border border-slate-200 bg-white p-2 text-slate-500 transition hover:bg-slate-50"
                aria-label="Close"
              >
                <FiX className="h-4 w-4" />
              </button>
            </div>
            <p className="text-[12px] text-slate-500">
              {zoneFor !== 'new' ? (
                <>Rule <span className="font-mono font-bold text-slate-700">{zoneFor.shipviaCd}</span>
                {zoneFor.clientCode ? <> · {zoneFor.clientCode}</> : null} — </>
              ) : null}
where the parcel is going — pick any mix of destination regions and countries.{' '}
              <span className="font-semibold text-slate-700">Empty = anywhere.</span>
            </p>

            <div className="mt-3">
              <RegionCountryPicker value={zoneCodes} onChange={setZoneCodes} multiRegion />
            </div>

            <div className="mt-4 flex items-center justify-between gap-2 border-t border-slate-100 pt-4">
              <p className="text-[11.5px] font-semibold text-slate-500">
                {zoneCodes.length ? `${zoneCodes.length} countr${zoneCodes.length === 1 ? 'y' : 'ies'} in this zone` : 'Anywhere (no restriction)'}
              </p>
              <div className="flex items-center gap-2">
                <button
                  type="button"
                  onClick={() => setZoneFor(null)}
                  className="rounded-xl border border-slate-200 bg-white px-4 py-2 text-[13px] font-semibold text-slate-600 transition hover:bg-slate-50"
                >
                  Cancel
                </button>
                <button
                  type="button"
                  onClick={() => void saveZone()}
                  className="rounded-xl bg-[#1f150c] px-5 py-2 text-[13px] font-semibold text-white transition hover:bg-[#412d15]"
                >
                  {zoneFor === 'new' ? 'Use this zone' : 'Save zone'}
                </button>
              </div>
            </div>
          </div>
        </div>
      ) : null}

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
    </div>
  )
}
