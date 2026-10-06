import { useCallback, useEffect, useMemo, useState } from 'react'
import { useOutletContext } from 'react-router-dom'
import { FiCheck, FiEdit2, FiPlus, FiTrash2, FiX } from 'react-icons/fi'
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
import { clientWarehouseService, type ClientWarehouse } from '../api/warehouseService'
import { formatCarrierName, normalizeCarrierCode } from '../utils/carrierUtils'
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
/**
 * Settings → Code Maps, also embeddable in the client Edit page's mapping step.
 * - initialClientFilter: lock to one client and hide the client picker.
 * - embedded: drop the outer page header + Refresh registration so it sits
 *   inside the client wizard tab (mirrors CarrierConnections).
 */
export interface CodeMapsPageProps {
  initialClientFilter?: string
  embedded?: boolean
}

export default function CodeMapsPage({ initialClientFilter, embedded = false }: CodeMapsPageProps = {}) {
  const [clients, setClients] = useState<Client[]>([])
  const [selectedClient, setSelectedClient] = useState<string>('')
  const [tab, setTab] = useState<TabKind>('SHIPVIA')
  const [rows, setRows] = useState<ClientCodeMap[]>([])
  const [loading, setLoading] = useState(false)

  // Catalog data for the target pickers.
  const [services, setServices] = useState<ShippingServiceItem[]>([])
  const [presets, setPresets] = useState<PackagePreset[]>([])
  /** V126 — warehouses available for the SHIPVIA warehouse picker. Reloaded
   *  when the selected client changes. Default (platform) warehouses that
   *  are attached to the client are the natural options. */
  const [clientWarehouses, setClientWarehouses] = useState<ClientWarehouse[]>([])
  /** Canonical carrier codes the selected client has an account for — the service/package pickers filter to these. */
  const [clientCarriers, setClientCarriers] = useState<string[]>([])

  // Inline add form state (shared shape; interpretation depends on the tab).
  const [erpCode, setErpCode] = useState('')
  const [targetId, setTargetId] = useState<string>('')
  const [iso2, setIso2] = useState<string>('')
  /** Audit R2 #368 — optional per-alias destination scoping.
   *  Backend has always supported destCountry (ISO-2) + destRegion CSV
   *  on the SHIPVIA / SERVICE / PACKAGE tabs (DEST_COUNTRY tab has no
   *  scoping — the alias IS the mapping). Pre-fix, the add form never
   *  exposed either, so the "any destination" alias was the only
   *  reachable kind via UI. Now advanced-mode: hidden behind a
   *  disclosure so the common case stays simple. */
  const [destCountry, setDestCountry] = useState<string>('')
  const [destRegion, setDestRegion] = useState<string>('')
  /** V126 — SHIPVIA tab only: optional origin-warehouse scope. '' = any. */
  const [warehouseId, setWarehouseId] = useState<string>('')
  /** V127 — SHIPVIA tab only: packaging allowlist. Empty = unrestricted. */
  const [allowedPresetIds, setAllowedPresetIds] = useState<number[]>([])
  const [showDestScope, setShowDestScope] = useState<boolean>(false)
  const [saving, setSaving] = useState(false)
  /** Audit R2 #369 — show-inactive toggle for the client picker.
   *  Pre-fix, aliases for deactivated clients became invisible in the
   *  admin UI (list filtered ACTIVE only). Toggle flips the filter so
   *  ops can still edit/remove aliases on paused clients. */
  const [showInactiveClients, setShowInactiveClients] = useState<boolean>(false)
  /** Audit R2 #373 — client-side substring filter on the row list.
   *  A client with 100+ aliases becomes tedious to scroll; filter
   *  narrows by erpCode OR targetLabel. Client-side so no round-trip. */
  const [rowFilter, setRowFilter] = useState<string>('')
  /** Audit R2 #370 — inline-edit state. id of the row currently in edit
   *  mode (null = none). editTargetId holds the picker's working value;
   *  editIso2 the DEST_COUNTRY tab's working value. Saving flips to
   *  true while the upsert is in flight so the Save button is disabled. */
  const [editingRowId, setEditingRowId] = useState<number | null>(null)
  const [editTargetId, setEditTargetId] = useState<string>('')
  const [editIso2, setEditIso2] = useState<string>('')
  /** V126/V127 — per-row edit working state for SHIPVIA tab only. */
  const [editWarehouseId, setEditWarehouseId] = useState<string>('')
  const [editAllowedPresetIds, setEditAllowedPresetIds] = useState<number[]>([])
  const [editSaving, setEditSaving] = useState(false)

  // Bootstrap the catalogs once.
  useEffect(() => {
    let alive = true
    Promise.all([
      shippingConfigService.catalog(),
      shippingConfigService.listPresets(),
    ])
      .then(([catalog, presetList]) => {
        if (!alive) return
        setServices((catalog.services ?? []).filter((s) => s.enabled))
        setPresets(presetList)
      })
      .catch(() => { /* covered by page-level loading */ })
    return () => { alive = false }
  }, [])

  // Audit R2 #369 — reload the client picker whenever the include-inactive
  // toggle flips. Separate effect so the initial mount + toggle share
  // exactly the same fetch path.
  useEffect(() => {
    // Embedded in the client Edit page the picker is hidden and the client is
    // fixed, so the full client list is never shown — skip the fetch.
    if (embedded) return
    let alive = true
    const params: { status?: 'ACTIVE'; size: number } = { size: 200 }
    if (!showInactiveClients) params.status = 'ACTIVE'
    clientService.listClients(params)
      .then((clientPage) => {
        if (!alive) return
        const list = clientPage.data?.content ?? []
        setClients(list)
        if (!embedded && list.length && !selectedClient) setSelectedClient(list[0].clientCode)
      })
      .catch(() => { /* covered by page-level loading */ })
    return () => { alive = false }
    // eslint-disable-next-line react-hooks/exhaustive-deps -- selectedClient intentionally omitted; only re-fetch on the inactive toggle (or first mount) to avoid re-loading the client list on every selection change
  }, [showInactiveClients])

  // V126 — reload the per-client warehouse list whenever the client picks
  // changes. Empty clientCode = no fetch; keeps the picker's option set
  // scoped to warehouses the client is actually attached to.
  useEffect(() => {
    if (!selectedClient) { setClientWarehouses([]); setClientCarriers([]); return }
    let alive = true
    clientWarehouseService.listForClient(selectedClient)
      .then((resp) => { if (alive) setClientWarehouses(resp.data ?? []) })
      .catch(() => { if (alive) setClientWarehouses([]) })
    clientService.listClientAccounts(selectedClient)
      .then((accts) => {
        if (!alive) return
        setClientCarriers([...new Set(accts.filter((a) => a.active !== false)
          .map((a) => (normalizeCarrierCode(a.carrierCode) ?? "").toUpperCase()).filter(Boolean))])
      })
      .catch(() => { if (alive) setClientCarriers([]) })
    return () => { alive = false }
  }, [selectedClient])

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
    // eslint-disable-next-line react-hooks/set-state-in-effect -- data fetch on client/tab change; load() sets loading + rows; also resets the inline form which is response-to-input, not derivable
    void load()
    // reset the inline form + row filter when either dimension shifts
    setErpCode(''); setTargetId(''); setIso2('')
    // Audit R2 #368 — reset dest scoping fields too so a switched tab
    // doesn't carry over a stale destCountry from the previous kind.
    setDestCountry(''); setDestRegion(''); setShowDestScope(false)
    // V126/V127 — reset SHIPVIA-only form fields.
    setWarehouseId(''); setAllowedPresetIds([])
    // Audit R2 #373 — clear the row filter so switching client/tab
    // shows the fresh row set unfiltered.
    setRowFilter('')
  }, [load])

  // Embedded in the client Edit page: lock to that client.
  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- lock to the client the wizard embeds for
    if (embedded && initialClientFilter) setSelectedClient(initialClientFilter)
  }, [embedded, initialClientFilter])

  // The outer page owns the Refresh button; an embedded instance must not hijack it.
  // Optional: present on the Settings route, absent if mounted elsewhere.
  const outlet = useOutletContext<SettingsOutletContext | undefined>()
  const registerRefresh = outlet?.registerRefresh
  useEffect(() => {
    if (embedded || !registerRefresh) return
    registerRefresh(load)
    return () => registerRefresh(null)
  }, [embedded, registerRefresh, load])

  const submitAdd = async () => {
    if (!selectedClient || !erpCode.trim()) return
    setSaving(true)
    try {
      // Audit R2 #368 — thread destCountry / destRegion through when the
      // operator opened the advanced-scope panel. Backend already accepts
      // them on SHIPVIA / SERVICE / PACKAGE payloads; DEST_COUNTRY tab
      // has its own iso2 field and no per-destination scoping.
      const payload =
        tab === 'DEST_COUNTRY'
          ? { erpCode: erpCode.trim(), iso2: iso2.trim().toUpperCase() }
          : {
              erpCode: erpCode.trim(),
              targetId: Number(targetId),
              destCountry: destCountry.trim() ? destCountry.trim().toUpperCase() : null,
              destRegion: destRegion.trim() || null,
              // V126/V127 — SHIPVIA tab exposes origin + packaging allowlist.
              // Other tabs omit (backend ignores the fields for them anyway).
              ...(tab === 'SHIPVIA' ? {
                warehouseId: warehouseId ? Number(warehouseId) : null,
                allowedPresetIds,
              } : {}),
            }
      await clientCodeMapService.upsert(selectedClient, tab, payload)
      notify.success(`${TAB_META[tab].label} alias saved.`)
      setErpCode(''); setTargetId(''); setIso2('')
      setDestCountry(''); setDestRegion('')
      setWarehouseId(''); setAllowedPresetIds([])
      await load()
    } catch (error) {
      notify.apiError(error, 'Failed to save alias.')
    } finally {
      setSaving(false)
    }
  }

  const beginEdit = (row: ClientCodeMap) => {
    // Audit R2 #370 — load the existing target into the picker working
    // state. DEST_COUNTRY tab uses iso2 instead of targetId.
    setEditingRowId(row.id)
    setEditTargetId(row.targetId != null ? String(row.targetId) : '')
    setEditIso2(row.iso2 ?? '')
    // V126/V127 — load existing SHIPVIA scope so an edit doesn't nuke them.
    setEditWarehouseId(row.warehouseId != null ? String(row.warehouseId) : '')
    setEditAllowedPresetIds(row.allowedPresetIds ?? [])
  }

  const cancelEdit = () => {
    setEditingRowId(null)
    setEditTargetId('')
    setEditIso2('')
    setEditWarehouseId('')
    setEditAllowedPresetIds([])
  }

  const saveEdit = async (row: ClientCodeMap) => {
    // Backend upsert matches on (clientCode, erpCode, destCountry,
    // destRegion) — same keys as add — so posting with the same erpCode
    // + per-tab target update IS the edit. No new endpoint needed.
    const payload =
      tab === 'DEST_COUNTRY'
        ? { erpCode: row.erpCode, iso2: editIso2.trim().toUpperCase() }
        : {
            erpCode: row.erpCode,
            targetId: Number(editTargetId),
            destCountry: row.destCountry ?? null,
            destRegion: row.destRegion ?? null,
            ...(tab === 'SHIPVIA' ? {
              warehouseId: editWarehouseId ? Number(editWarehouseId) : null,
              allowedPresetIds: editAllowedPresetIds,
            } : {}),
          }
    if (tab !== 'DEST_COUNTRY' && 'targetId' in payload && !Number.isFinite(payload.targetId)) {
      notify.error('Pick a target before saving.')
      return
    }
    if (tab === 'DEST_COUNTRY' && 'iso2' in payload && !payload.iso2) {
      notify.error('Enter an ISO-2 country before saving.')
      return
    }
    setEditSaving(true)
    try {
      await clientCodeMapService.upsert(selectedClient, tab, payload)
      notify.success(`${TAB_META[tab].label} alias updated.`)
      cancelEdit()
      await load()
    } catch (error) {
      notify.apiError(error, 'Failed to update alias.')
    } finally {
      setEditSaving(false)
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

  // Pickers show only what the client can actually use: services from the
  // client's own carriers, and CARRIER packages from those carriers (CUSTOM
  // packages have no carrier, so they always show). No carriers known yet
  // (still loading, or the client has none) falls back to the full catalog.
  const visibleServices = useMemo(
    () => (clientCarriers.length ? services.filter((sv) => clientCarriers.includes((normalizeCarrierCode(sv.carrier) ?? "").toUpperCase())) : services),
    [services, clientCarriers],
  )
  const visiblePresets = useMemo(
    () => (clientCarriers.length ? presets.filter((pp) => !pp.carrier || clientCarriers.includes((normalizeCarrierCode(pp.carrier) ?? "").toUpperCase())) : presets),
    [presets, clientCarriers],
  )

  const meta = TAB_META[tab]
  /** Audit R2 #373 — client-side substring filter. Matches on erpCode
   *  OR targetLabel (whichever column the operator recognises). */
  const filteredRows = useMemo(() => {
    if (!rowFilter.trim()) return rows
    const needle = rowFilter.trim().toLowerCase()
    return rows.filter((r) =>
      (r.erpCode ?? '').toLowerCase().includes(needle)
      || (r.targetLabel ?? '').toLowerCase().includes(needle),
    )
  }, [rows, rowFilter])
  const canSubmit =
    !!selectedClient &&
    erpCode.trim().length > 0 &&
    (tab === 'DEST_COUNTRY' ? iso2.trim().length === 2 : !!targetId)

  return (
    <div className="space-y-4">
      <section className={embedded ? '' : 'rounded-2xl border border-slate-200 bg-white p-5 shadow-sm'}>
        {!embedded ? (
        <div className="flex flex-col gap-3 sm:flex-row sm:items-end sm:justify-between">
          <div>
            <h2 className="text-[14.5px] font-semibold text-slate-950">Code maps</h2>
            <p className="mt-0.5 text-[11.5px] text-slate-500">
              Per-client aliases for the raw codes ERPs send us. Order intake reads these before rule resolution.
            </p>
            {/* Resolution pipeline — V126 merge collapsed SSM into this
                page's SHIPVIA tab, so the chain is now 2 steps. */}
            <nav aria-label="Routing resolution pipeline" className="mt-2 flex items-center gap-1 text-[10.5px]">
              <span className="rounded bg-[var(--e-1f150c)] px-2 py-0.5 font-semibold text-white">Code maps</span>
              <span className="text-slate-400">·</span>
              <span className="text-slate-500">translate ERP strings on intake, then resolve to a carrier service</span>
              <span className="mx-1 text-slate-300">→</span>
              <a href="/settings/routing-rules" className="rounded border border-slate-200 bg-white px-2 py-0.5 font-semibold text-slate-600 hover:bg-slate-50">
                Routing Rules
              </a>
            </nav>
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
            {/* Audit R2 #369 — include-inactive toggle so aliases on
                deactivated clients aren't hidden forever. */}
            <label className="mt-1.5 flex cursor-pointer items-center gap-1.5 text-[11px] text-slate-500">
              <input
                type="checkbox"
                checked={showInactiveClients}
                onChange={(e) => setShowInactiveClients(e.target.checked)}
                className="h-3 w-3 accent-[var(--e-1f150c)]"
              />
              Include deactivated clients
            </label>
          </div>
        </div>
        ) : null}

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
                className="w-full rounded-xl border border-slate-200 bg-white px-3 py-2 text-[13px] text-slate-950 outline-none transition focus:border-[var(--e-412d15)]"
              />
            </div>
            <div>
              <label className="mb-1 block text-[10px] font-bold uppercase tracking-[0.14em] text-slate-400">
                {meta.targetLabel}
              </label>
              <TargetPicker
                tab={tab}
                services={visibleServices}
                presets={visiblePresets}
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
                className="inline-flex items-center gap-1.5 rounded-xl bg-[var(--e-1f150c)] px-4 py-2 text-[12px] font-semibold text-white transition hover:bg-[var(--e-412d15)] disabled:cursor-not-allowed disabled:opacity-50"
              >
                <FiPlus className="h-3.5 w-3.5" />
                {saving ? 'Saving…' : 'Add alias'}
              </button>
            </div>
            {/* Audit R2 #368 — advanced-scope disclosure. Hidden by default
                so the common case stays a two-field form; expand to add
                per-destination scoping (SHIPVIA / SERVICE / PACKAGE tabs
                only — DEST_COUNTRY tab is the mapping itself, no scope). */}
            {tab !== 'DEST_COUNTRY' ? (
              <div className="sm:col-span-3">
                <button
                  type="button"
                  onClick={() => setShowDestScope((v) => !v)}
                  className="text-[10.5px] font-semibold text-slate-500 hover:text-slate-950"
                >
                  {showDestScope ? '− Hide destination scope' : '+ Add destination scope (advanced)'}
                </button>
                {showDestScope ? (
                  <div className="mt-1.5 grid grid-cols-1 gap-2 rounded-lg border border-dashed border-slate-200 bg-white p-2 sm:grid-cols-2">
                    <div>
                      <label className="mb-0.5 block text-[9.5px] font-bold uppercase tracking-wide text-slate-400">
                        Dest country (ISO-2, optional)
                      </label>
                      <input
                        value={destCountry}
                        onChange={(e) => setDestCountry(e.target.value.toUpperCase())}
                        placeholder="US"
                        maxLength={2}
                        className="w-full rounded-lg border border-slate-200 px-2 py-1 text-[12px] outline-none focus:border-[var(--e-412d15)]"
                      />
                    </div>
                    <div>
                      <label className="mb-0.5 block text-[9.5px] font-bold uppercase tracking-wide text-slate-400">
                        Dest region (optional; e.g. Europe)
                      </label>
                      <input
                        value={destRegion}
                        onChange={(e) => setDestRegion(e.target.value)}
                        placeholder="Europe"
                        className="w-full rounded-lg border border-slate-200 px-2 py-1 text-[12px] outline-none focus:border-[var(--e-412d15)]"
                      />
                    </div>
                    <p className="col-span-full text-[10.5px] text-slate-400">
                      Leave both blank for an &quot;any destination&quot; alias (matches everywhere).
                      Country wins over region when both are set.
                    </p>
                    {tab === 'SHIPVIA' ? (
                      <>
                        <div>
                          <label className="mb-0.5 block text-[9.5px] font-bold uppercase tracking-wide text-slate-400">
                            Origin warehouse (optional)
                          </label>
                          <Select
                            value={warehouseId}
                            onChange={(e) => setWarehouseId(e.target.value)}
                            aria-label="Origin warehouse"
                          >
                            <option value="">Any origin</option>
                            {clientWarehouses.map((cw) => cw.warehouse ? (
                              <option key={cw.warehouse.id} value={String(cw.warehouse.id)}>
                                {cw.warehouse.code} — {cw.warehouse.name}
                                {cw.isDefault ? ' (default)' : ''}
                              </option>
                            ) : null)}
                          </Select>
                        </div>
                        <div>
                          <label className="mb-0.5 block text-[9.5px] font-bold uppercase tracking-wide text-slate-400">
                            Allowed packages (optional)
                          </label>
                          <PackagingMultiSelect
                            presets={visiblePresets}
                            value={allowedPresetIds}
                            onChange={setAllowedPresetIds}
                          />
                        </div>
                        <p className="col-span-full text-[10.5px] text-slate-400">
                          Warehouse: null = any origin. Allowed packages: empty = any
                          package the client is otherwise cleared for.
                        </p>
                      </>
                    ) : null}
                  </div>
                ) : null}
              </div>
            ) : null}
          </div>
        ) : null}

        {/* Audit R2 #373 — client-side row filter for clients with many aliases. */}
        {selectedClient && rows.length > 0 ? (
          <div className="mt-3">
            <input
              type="search"
              value={rowFilter}
              onChange={(e) => setRowFilter(e.target.value)}
              placeholder="Filter by ERP code or target…"
              className="w-full rounded-lg border border-slate-200 bg-white px-3 py-1.5 text-[12px] outline-none focus:border-[var(--e-412d15)]"
            />
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
          ) : filteredRows.length === 0 ? (
            /* Audit R2 #373 — non-empty rows but the filter matched nothing.
               Distinct message so operator knows to clear the filter. */
            <p className="rounded-xl border border-dashed border-slate-200 bg-white px-3 py-3 text-center text-[11.5px] text-slate-500">
              No aliases match &quot;{rowFilter}&quot;.
            </p>
          ) : (
            filteredRows.map((row) => {
              const isEditing = editingRowId === row.id
              return (
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
                      {isEditing ? (
                        /* Audit R2 #370 — inline target picker reuses the
                           same TargetPicker component as the add form. */
                        <span className="inline-flex items-center gap-2 align-middle">
                          <TargetPicker
                            tab={tab}
                            services={visibleServices}
                            presets={visiblePresets}
                            targetId={editTargetId}
                            onTargetId={setEditTargetId}
                            iso2={editIso2}
                            onIso2={setEditIso2}
                          />
                        </span>
                      ) : (
                        <span className="font-semibold text-slate-950">
                          {row.targetLabel || (row.iso2 ?? row.targetId ?? '—')}
                        </span>
                      )}
                    </p>
                    {/* V126/V127 — SHIPVIA tab scope chips (warehouse +
                        packaging allowlist). Row-edit mode shows the pickers
                        inline instead so the operator can retarget them. */}
                    {tab === 'SHIPVIA' && isEditing ? (
                      <div className="mt-1.5 grid grid-cols-1 items-start gap-1.5 sm:grid-cols-2">
                        <Select
                          value={editWarehouseId}
                          onChange={(e) => setEditWarehouseId(e.target.value)}
                          aria-label="Origin warehouse"
                        >
                          <option value="">Any origin warehouse</option>
                          {clientWarehouses.map((cw) => cw.warehouse ? (
                            <option key={cw.warehouse.id} value={String(cw.warehouse.id)}>
                              {cw.warehouse.code} — {cw.warehouse.name}
                              {cw.isDefault ? ' (default)' : ''}
                            </option>
                          ) : null)}
                        </Select>
                        <PackagingMultiSelect
                          presets={visiblePresets}
                          value={editAllowedPresetIds}
                          onChange={setEditAllowedPresetIds}
                        />
                      </div>
                    ) : tab === 'SHIPVIA' && (row.warehouseLabel || (row.allowedPresetIds?.length ?? 0) > 0) ? (
                      <p className="mt-0.5 flex flex-wrap items-center gap-1 text-[10.5px] text-slate-500">
                        {row.warehouseLabel ? (
                          <span className="rounded bg-slate-100 px-1.5 py-0.5">from {row.warehouseLabel}</span>
                        ) : null}
                        {(row.allowedPresetIds?.length ?? 0) > 0 ? (
                          <span className="rounded bg-slate-100 px-1.5 py-0.5">
                            {row.allowedPresetIds!.length} allowed {row.allowedPresetIds!.length === 1 ? 'package' : 'packages'}
                          </span>
                        ) : null}
                      </p>
                    ) : null}
                  </div>
                  {isEditing ? (
                    <>
                      <button
                        type="button"
                        onClick={() => void saveEdit(row)}
                        disabled={editSaving}
                        aria-label={`Save ${row.erpCode}`}
                        className="inline-flex h-7 w-7 shrink-0 items-center justify-center rounded-lg border border-emerald-200 text-emerald-700 transition hover:bg-emerald-50 disabled:opacity-50"
                      >
                        <FiCheck className="h-3.5 w-3.5" />
                      </button>
                      <button
                        type="button"
                        onClick={cancelEdit}
                        disabled={editSaving}
                        aria-label="Cancel edit"
                        className="inline-flex h-7 w-7 shrink-0 items-center justify-center rounded-lg border border-transparent text-slate-400 transition hover:border-slate-200 disabled:opacity-50"
                      >
                        <FiX className="h-3.5 w-3.5" />
                      </button>
                    </>
                  ) : (
                    <>
                      <button
                        type="button"
                        onClick={() => beginEdit(row)}
                        aria-label={`Edit ${row.erpCode}`}
                        title="Change target without delete + re-add"
                        className="inline-flex h-7 w-7 shrink-0 items-center justify-center rounded-lg border border-transparent text-slate-400 transition hover:border-slate-200 hover:text-slate-700"
                      >
                        <FiEdit2 className="h-3.5 w-3.5" />
                      </button>
                      <button
                        type="button"
                        onClick={() => void remove(row)}
                        aria-label={`Remove ${row.erpCode}`}
                        className="inline-flex h-7 w-7 shrink-0 items-center justify-center rounded-lg border border-transparent text-slate-400 transition hover:border-rose-100 hover:text-rose-600"
                      >
                        <FiTrash2 className="h-3.5 w-3.5" />
                      </button>
                    </>
                  )}
                </div>
              )
            })
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

/** V127 — multi-select over PackagePreset for the SHIPVIA per-lane allowlist.
 *  Chip-list UI; click to add, click an added chip to remove. Empty = any. */
function PackagingMultiSelect({
  presets,
  value,
  onChange,
}: {
  presets: PackagePreset[]
  value: number[]
  onChange: (next: number[]) => void
}) {
  const options = useMemo(
    () => presets.slice().sort((a, b) => a.name.localeCompare(b.name)),
    [presets],
  )
  const selectedSet = useMemo(() => new Set(value), [value])
  return (
    <div className="rounded-lg border border-slate-200 bg-white p-1.5">
      {options.length === 0 ? (
        <p className="px-1.5 py-1 text-[10.5px] text-slate-400">No package presets available.</p>
      ) : (
        <div className="flex flex-wrap gap-1">
          {options.map((p) => {
            if (p.id == null) return null
            const id = p.id
            const on = selectedSet.has(id)
            return (
              <button
                key={id}
                type="button"
                onClick={() => onChange(on ? value.filter((v) => v !== id) : [...value, id])}
                className={`rounded-full px-2 py-0.5 text-[10.5px] font-semibold transition ${
                  on
                    ? 'bg-[var(--e-1f150c)] text-white hover:bg-[var(--e-412d15)]'
                    : 'bg-slate-100 text-slate-600 hover:bg-slate-200'
                }`}
              >
                {p.name}{p.carrier ? ` · ${formatCarrierName(p.carrier)}` : ''}
              </button>
            )
          })}
        </div>
      )}
    </div>
  )
}
