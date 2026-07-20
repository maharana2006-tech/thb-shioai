import { useEffect, useMemo, useState } from 'react'
import toast from 'react-hot-toast'
import {
  FiBox,
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
 * offers), each service's ALLOWED PACKAGES (with negotiated discount %), and
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
  const [pkgDraft, setPkgDraft] = useState<Map<number, { on: boolean; discount: string }>>(new Map())

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

  const byCarrier = useMemo(() => {
    const groups = new Map<string, ShippingServiceItem[]>()
    services.forEach((s) => {
      const list = groups.get(s.carrier) ?? []
      list.push(s)
      groups.set(s.carrier, list)
    })
    return [...groups.entries()]
  }, [services])

  const serviceById = useMemo(() => new Map(services.map((s) => [s.id, s])), [services])
  const linksByService = useMemo(() => {
    const m = new Map<number, ServicePackageLink[]>()
    links.forEach((l) => m.set(l.serviceId, [...(m.get(l.serviceId) ?? []), l]))
    return m
  }, [links])
  const enabledCount = services.filter((s) => s.enabled).length

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
    const draft = new Map<number, { on: boolean; discount: string }>()
    presets.forEach((p) => {
      const link = current.find((l) => l.presetId === p.id)
      draft.set(p.id!, { on: !!link, discount: link?.discountPct != null ? String(link.discountPct) : '' })
    })
    setPkgDraft(draft)
    setPkgService(svc)
  }

  const savePackages = async () => {
    if (!pkgService) return
    const payload = [...pkgDraft.entries()]
      .filter(([, v]) => v.on)
      .map(([presetId, v]) => ({ presetId, discountPct: v.discount === '' ? null : Number(v.discount) }))
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
        description="Carrier service levels, their allowed packages, and the ship-method rules that pick the service per client + destination."
        actions={
          <button
            type="button"
            onClick={() => void load()}
            className="inline-flex items-center gap-1.5 rounded-xl border border-slate-200 bg-white px-3 py-2 text-[12.5px] font-semibold text-slate-600 transition hover:bg-slate-50"
          >
            <FiRefreshCw className="h-3.5 w-3.5" /> Refresh
          </button>
        }
      />

      {/* health strip — tally tickets */}
      <section className="grid grid-cols-3 gap-3">
        {[
          { label: 'Services enabled', value: `${enabledCount}/${services.length}`, unit: 'services', icon: FiSend, tone: 'border-[#412d15]/25 bg-[#412d15]/[0.06] text-[#412d15]' },
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
            return (
              <div key={carrier} className="overflow-hidden rounded-2xl border border-slate-200 bg-white shadow-sm">
                {/* document band: carrier plate + manifest title */}
                <div className="flex items-center justify-between gap-2 bg-[#1f150c] px-4 py-2.5">
                  <p className="flex min-w-0 items-center gap-2.5">
                    <span className={`flex h-6 w-9 shrink-0 items-center justify-center rounded ${badge.bg} font-mono text-[9px] font-black tracking-wider text-white`}>
                      {badge.mono}
                    </span>
                    <span className="truncate text-[10px] font-black uppercase tracking-[0.2em] text-[#e1dcc9]">
                      {carrier} · Service manifest
                    </span>
                  </p>
                  <span className="shrink-0 rounded bg-[#e1dcc9]/15 px-2 py-0.5 font-mono text-[10px] font-bold tabular-nums text-[#e1dcc9]">
                    {on}/{list.length} ON
                  </span>
                </div>
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
          An order's ship method (P80, F77…) resolves to a carrier service. Narrow a rule by client and destination —
          the most specific rule wins (client + country → client + region → client → global).
        </p>

        <div className="mt-3 overflow-x-auto">
          <table className="w-full min-w-[760px] text-[13px] text-slate-700">
            <thead className="border-b border-dashed border-slate-300 text-left font-mono text-[9px] font-bold uppercase tracking-[0.18em] text-slate-400">
              <tr>
                <th className="px-2.5 py-2.5">Order method</th>
                <th className="px-2.5 py-2.5">Client</th>
                <th className="px-2.5 py-2.5">Destination</th>
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
                Destination zone
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
              pick any mix of regions and countries. <span className="font-semibold text-slate-700">Empty = anywhere.</span>
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

            <ul className="mt-3 max-h-[300px] divide-y divide-slate-100 overflow-y-auto rounded-xl border border-slate-200">
              {presets
                .filter((p) => !p.carrier || p.carrier === pkgService.carrier)
                .map((p) => {
                  const d = pkgDraft.get(p.id!) ?? { on: false, discount: '' }
                  return (
                    <li key={p.id} className="flex items-center gap-2.5 px-3 py-2.5">
                      <input
                        type="checkbox"
                        checked={d.on}
                        onChange={(e) => setPkgDraft((cur) => new Map(cur).set(p.id!, { ...d, on: e.target.checked }))}
                        className="h-4 w-4 rounded border-slate-300 text-slate-950 focus:ring-slate-300"
                      />
                      <div className="min-w-0 flex-1">
                        <p className="text-[12.5px] font-semibold text-slate-900">{p.name}</p>
                        <p className="text-[10.5px] text-slate-400">
                          {p.length && p.width && p.height ? `${p.length}×${p.width}×${p.height} ${p.dimUnit.toLowerCase()} · ` : ''}
                          max {p.maxWeight ?? '—'} {p.weightUnit.toLowerCase()}
                          {p.carrier ? ` · ${p.carrier}` : ''}
                        </p>
                      </div>
                      <div className="flex items-center gap-1">
                        <input
                          type="number"
                          min="0"
                          max="100"
                          step="0.1"
                          value={d.discount}
                          disabled={!d.on}
                          onChange={(e) => setPkgDraft((cur) => new Map(cur).set(p.id!, { ...d, discount: e.target.value }))}
                          placeholder="—"
                          className="w-16 rounded-lg border border-slate-200 bg-white px-2 py-1.5 text-right text-[12px] text-slate-900 outline-none transition focus:border-[#412d15] disabled:bg-slate-50 disabled:text-slate-300"
                          aria-label={`Discount % for ${p.name}`}
                        />
                        <span className="text-[11px] font-semibold text-slate-400">%</span>
                      </div>
                    </li>
                  )
                })}
            </ul>
            <p className="mt-1.5 text-[10.5px] text-slate-400">% = negotiated rate discount for this service + package (reporting).</p>

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
