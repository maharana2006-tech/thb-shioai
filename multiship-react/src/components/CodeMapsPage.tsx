import { useCallback, useEffect, useMemo, useState } from 'react'
import { useOutletContext } from 'react-router-dom'
import { FiPlus, FiTrash2 } from 'react-icons/fi'
import { notify } from '../utils/notify'
import {
  clientCodeMapService,
  type ClientCodeMap,
} from '../api/clientCodeMapService'
import { clientService, type Client } from '../api/clientService'
import {
  shippingConfigService,
  type PackagePreset,
  type ShippingServiceItem,
} from '../api/shippingConfigService'
import { formatCarrierName } from '../utils/carrierUtils'
import { COUNTRIES } from '../utils/countries'
import type { SettingsOutletContext } from './layout/SettingsLayout'
import Select from './workspace/Select'

type TabKind = 'SHIPVIA' | 'SERVICE' | 'DEST_COUNTRY' | 'PACKAGE'

const TAB_META: Record<
  TabKind,
  { label: string; blurb: string; codeLabel: string; codePlaceholder: string; targetLabel: string }
> = {
  SHIPVIA: {
    label: 'Shipvia',
    blurb: 'ERP ship-method → carrier service. Direct alias — skips ShipViaMapping rules.',
    codeLabel: 'ERP shipvia code',
    codePlaceholder: 'P80',
    targetLabel: 'Platform service',
  },
  SERVICE: {
    label: 'Service code',
    blurb: 'ERP service-level code → carrier service. Distinct from shipvia; used when the ERP names the exact product.',
    codeLabel: 'ERP service code',
    codePlaceholder: 'F77-2DAY',
    targetLabel: 'Platform service',
  },
  DEST_COUNTRY: {
    label: 'Destination country',
    blurb: "ERP destination string → canonical ISO-2. Applied on order intake before rule resolution.",
    codeLabel: 'ERP country string',
    codePlaceholder: 'USA',
    targetLabel: 'ISO-2 country',
  },
  PACKAGE: {
    label: 'Package',
    blurb: "ERP package SKU → PackagePreset. Applied on order intake before the packagingCode name lookup.",
    codeLabel: 'ERP package code',
    codePlaceholder: 'ACME-BOX-A',
    targetLabel: 'Package preset',
  },
}

const TAB_ORDER: TabKind[] = ['SHIPVIA', 'SERVICE', 'DEST_COUNTRY', 'PACKAGE']

/**
 * Settings > Code Maps — hub for the four per-client ERP↔platform alias
 * tables. A Client filter at the top scopes everything below to one client's
 * aliases; the four tabs share the same CRUD shape.
 */
export default function CodeMapsPage() {
  const [clients, setClients] = useState<Client[]>([])
  const [selectedClient, setSelectedClient] = useState<string>('')
  const [tab, setTab] = useState<TabKind>('SHIPVIA')
  const [rows, setRows] = useState<ClientCodeMap[]>([])
  const [loading, setLoading] = useState(false)

  // Catalog data for the target pickers.
  const [services, setServices] = useState<ShippingServiceItem[]>([])
  const [presets, setPresets] = useState<PackagePreset[]>([])

  // Inline add form state (shared shape; interpretation depends on the tab).
  const [erpCode, setErpCode] = useState('')
  const [targetId, setTargetId] = useState<string>('')
  const [iso2, setIso2] = useState<string>('')
  const [saving, setSaving] = useState(false)

  // Bootstrap the client picker + catalogs once.
  useEffect(() => {
    let alive = true
    Promise.all([
      clientService.listClients({ status: 'ACTIVE', size: 200 }),
      shippingConfigService.catalog(),
      shippingConfigService.listPresets(),
    ])
      .then(([clientPage, catalog, presetList]) => {
        if (!alive) return
        const list = clientPage.data?.content ?? []
        setClients(list)
        setServices((catalog.services ?? []).filter((s) => s.enabled))
        setPresets(presetList)
        if (list.length && !selectedClient) setSelectedClient(list[0].clientCode)
      })
      .catch(() => { /* covered by page-level loading */ })
    return () => { alive = false }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  // Reload rows when either the tab or the client changes.
  const load = useCallback(async () => {
    if (!selectedClient) {
      setRows([])
      return
    }
    setLoading(true)
    try {
      const r = await clientCodeMapService.list(selectedClient, tab)
      setRows(r.data ?? [])
    } catch (error) {
      notify.apiError(error, 'Failed to load aliases.')
    } finally {
      setLoading(false)
    }
  }, [selectedClient, tab])

  useEffect(() => {
    void load()
    // reset the inline form when either dimension shifts
    setErpCode(''); setTargetId(''); setIso2('')
  }, [load])

  const { registerRefresh } = useOutletContext<SettingsOutletContext>()
  useEffect(() => {
    registerRefresh(load)
    return () => registerRefresh(null)
  }, [registerRefresh, load])

  const submitAdd = async () => {
    if (!selectedClient || !erpCode.trim()) return
    setSaving(true)
    try {
      const payload =
        tab === 'DEST_COUNTRY'
          ? { erpCode: erpCode.trim(), iso2: iso2.trim().toUpperCase() }
          : { erpCode: erpCode.trim(), targetId: Number(targetId) }
      await clientCodeMapService.upsert(selectedClient, tab, payload)
      notify.success(`${TAB_META[tab].label} alias saved.`)
      setErpCode(''); setTargetId(''); setIso2('')
      await load()
    } catch (error) {
      notify.apiError(error, 'Failed to save alias.')
    } finally {
      setSaving(false)
    }
  }

  const remove = async (row: ClientCodeMap) => {
    if (!(await notify.confirm(`Remove alias '${row.erpCode}' from ${selectedClient}?`, {
      title: 'Remove alias', confirmLabel: 'Remove', danger: true,
    }))) return
    try {
      await clientCodeMapService.remove(selectedClient, tab, row.id)
      notify.success('Alias removed.')
      await load()
    } catch (error) {
      notify.apiError(error, 'Failed to remove.')
    }
  }

  const meta = TAB_META[tab]
  const canSubmit =
    !!selectedClient &&
    erpCode.trim().length > 0 &&
    (tab === 'DEST_COUNTRY' ? iso2.trim().length === 2 : !!targetId)

  return (
    <div className="space-y-4">
      <section className="rounded-2xl border border-slate-200 bg-white p-5 shadow-sm">
        <div className="flex flex-col gap-3 sm:flex-row sm:items-end sm:justify-between">
          <div>
            <h2 className="text-[14.5px] font-semibold text-slate-950">Code maps</h2>
            <p className="mt-0.5 text-[11.5px] text-slate-500">
              Per-client aliases for the raw codes ERPs send us. Order intake reads these before rule resolution.
            </p>
          </div>
          <div className="min-w-[220px]">
            <label className="mb-1 block text-[10px] font-bold uppercase tracking-[0.14em] text-slate-400">
              Client
            </label>
            <Select value={selectedClient} onChange={(e) => setSelectedClient(e.target.value)} aria-label="Client">
              <option value="">Select client…</option>
              {clients.map((c) => (
                <option key={c.clientCode} value={c.clientCode}>
                  {c.clientCode} — {c.name}
                </option>
              ))}
            </Select>
          </div>
        </div>

        {/* Tab bar */}
        <div role="tablist" aria-label="Code map kinds" className="mt-4 flex gap-1 border-b border-slate-100">
          {TAB_ORDER.map((k) => (
            <button
              key={k}
              type="button"
              role="tab"
              aria-selected={tab === k}
              onClick={() => setTab(k)}
              className={`-mb-px rounded-t-xl px-3 py-2 text-[12px] font-semibold transition ${
                tab === k
                  ? 'border border-slate-200 border-b-white bg-white text-slate-950'
                  : 'text-slate-500 hover:text-slate-700'
              }`}
            >
              {TAB_META[k].label}
            </button>
          ))}
        </div>

        <p className="mt-3 text-[11.5px] leading-5 text-slate-500">{meta.blurb}</p>

        {/* Add form */}
        {selectedClient ? (
          <div className="mt-3 grid grid-cols-1 gap-3 rounded-2xl border border-slate-200 bg-slate-50/60 p-3 sm:grid-cols-[1fr_1fr_auto]">
            <div>
              <label className="mb-1 block text-[10px] font-bold uppercase tracking-[0.14em] text-slate-400">
                {meta.codeLabel}
              </label>
              <input
                value={erpCode}
                onChange={(e) => setErpCode(e.target.value)}
                placeholder={meta.codePlaceholder}
                className="w-full rounded-xl border border-slate-200 bg-white px-3 py-2 text-[13px] text-slate-950 outline-none transition focus:border-[#412d15]"
              />
            </div>
            <div>
              <label className="mb-1 block text-[10px] font-bold uppercase tracking-[0.14em] text-slate-400">
                {meta.targetLabel}
              </label>
              <TargetPicker
                tab={tab}
                services={services}
                presets={presets}
                targetId={targetId}
                onTargetId={setTargetId}
                iso2={iso2}
                onIso2={setIso2}
              />
            </div>
            <div className="sm:self-end">
              <button
                type="button"
                onClick={() => void submitAdd()}
                disabled={!canSubmit || saving}
                className="inline-flex items-center gap-1.5 rounded-xl bg-[#1f150c] px-4 py-2 text-[12px] font-semibold text-white transition hover:bg-[#412d15] disabled:cursor-not-allowed disabled:opacity-50"
              >
                <FiPlus className="h-3.5 w-3.5" />
                {saving ? 'Saving…' : 'Add alias'}
              </button>
            </div>
          </div>
        ) : null}

        {/* Rows */}
        <div className="mt-3 space-y-1.5">
          {!selectedClient ? (
            <p className="rounded-xl border border-dashed border-slate-200 bg-white px-3 py-3 text-center text-[11.5px] text-slate-500">
              Pick a client to see + edit their aliases.
            </p>
          ) : loading ? (
            <p className="rounded-xl border border-dashed border-slate-200 bg-white px-3 py-3 text-center text-[11.5px] text-slate-500">
              Loading…
            </p>
          ) : rows.length === 0 ? (
            <p className="rounded-xl border border-dashed border-slate-200 bg-white px-3 py-3 text-center text-[11.5px] text-slate-500">
              No {meta.label.toLowerCase()} aliases yet — add the first one above.
            </p>
          ) : (
            rows.map((row) => (
              <div
                key={row.id}
                className="flex items-center gap-3 rounded-xl border border-slate-200 bg-white px-3 py-2"
              >
                <div className="min-w-0 flex-1">
                  <p className="truncate text-[12px] text-slate-800">
                    <span className="rounded bg-slate-100 px-1.5 py-0.5 font-mono text-[10.5px] font-semibold text-slate-700">
                      {row.erpCode}
                    </span>
                    <span className="mx-2 text-slate-400">→</span>
                    <span className="font-semibold text-slate-950">
                      {row.targetLabel || (row.iso2 ?? row.targetId ?? '—')}
                    </span>
                  </p>
                </div>
                <button
                  type="button"
                  onClick={() => void remove(row)}
                  aria-label={`Remove ${row.erpCode}`}
                  className="inline-flex h-7 w-7 shrink-0 items-center justify-center rounded-lg border border-transparent text-slate-400 transition hover:border-rose-100 hover:text-rose-600"
                >
                  <FiTrash2 className="h-3.5 w-3.5" />
                </button>
              </div>
            ))
          )}
        </div>
      </section>
    </div>
  )
}

/** Picker for the target column — service dropdown for shipvia/service,
 *  preset dropdown for package, and a country input for dest-country. */
function TargetPicker({
  tab,
  services,
  presets,
  targetId,
  onTargetId,
  iso2,
  onIso2,
}: {
  tab: TabKind
  services: ShippingServiceItem[]
  presets: PackagePreset[]
  targetId: string
  onTargetId: (v: string) => void
  iso2: string
  onIso2: (v: string) => void
}) {
  const svcOptions = useMemo(
    () =>
      services
        .slice()
        .sort((a, b) => a.carrier.localeCompare(b.carrier) || a.name.localeCompare(b.name)),
    [services],
  )
  const pkgOptions = useMemo(
    () => presets.slice().sort((a, b) => a.name.localeCompare(b.name)),
    [presets],
  )
  const countryOptions = useMemo(
    () => COUNTRIES.slice().sort((a, b) => a.name.localeCompare(b.name)),
    [],
  )

  if (tab === 'DEST_COUNTRY') {
    return (
      <Select value={iso2} onChange={(e) => onIso2(e.target.value)} aria-label="ISO-2 country">
        <option value="">Select country…</option>
        {countryOptions.map((c) => (
          <option key={c.code} value={c.code}>
            {c.code} — {c.name}
          </option>
        ))}
      </Select>
    )
  }
  if (tab === 'PACKAGE') {
    return (
      <Select value={targetId} onChange={(e) => onTargetId(e.target.value)} aria-label="Package preset">
        <option value="">Select package…</option>
        {pkgOptions.map((p) => (
          <option key={p.id} value={p.id != null ? String(p.id) : ''}>
            {p.name} {p.carrier ? `· ${formatCarrierName(p.carrier)}` : ''} {p.kind ? `· ${p.kind}` : ''}
          </option>
        ))}
      </Select>
    )
  }
  return (
    <Select value={targetId} onChange={(e) => onTargetId(e.target.value)} aria-label="Shipping service">
      <option value="">Select service…</option>
      {svcOptions.map((s) => (
        <option key={s.id} value={String(s.id)}>
          {formatCarrierName(s.carrier)} · {s.serviceCode} — {s.name}
          {s.originCountry ? ` (${s.originCountry.toUpperCase()})` : ''}
        </option>
      ))}
    </Select>
  )
}
