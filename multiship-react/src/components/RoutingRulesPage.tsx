import { useCallback, useEffect, useMemo, useState } from 'react'
import { useOutletContext } from 'react-router-dom'
import {
  FiChevronDown,
  FiChevronUp,
  FiEdit3,
  FiPlay,
  FiPlus,
  FiTrash2,
  FiX,
} from 'react-icons/fi'
import { notify } from '../utils/notify'
import { ApiError } from '../api/apiClient'
import { clientService, type Client } from '../api/clientService'
import {
  routingRuleService,
  type RoutingActionType,
  type RoutingEvaluationRequest,
  type RoutingEvaluationResult,
  type RoutingRule,
} from '../api/routingRuleService'
import {
  shippingConfigService,
  type ShippingServiceItem,
} from '../api/shippingConfigService'
import { clientWarehouseService, type ClientWarehouse } from '../api/warehouseService'
import { REGIONS } from '../utils/countries'
import { formatCarrierName } from '../utils/carrierUtils'
import Select from './workspace/Select'
import type { SettingsOutletContext } from './layout/SettingsLayout'

const CARRIER_OPTIONS = ['UPS', 'FEDEX', 'USPS', 'DHL'] as const
const SOURCE_OPTIONS = ['MANUAL', 'WMS', 'API', 'ERP'] as const

/**
 * Sprint 44 — Routing Rules settings page. Per-client rule CRUD +
 * dry-run panel. Rules run after rate-shop / manual selection but
 * before label generation; first-match-wins by priority.
 */
export default function RoutingRulesPage() {
  const { registerRefresh } = useOutletContext<SettingsOutletContext>()
  const [clients, setClients] = useState<Client[]>([])
  const [clientCode, setClientCode] = useState('')
  const [rules, setRules] = useState<RoutingRule[]>([])
  const [services, setServices] = useState<ShippingServiceItem[]>([])
  const [warehouses, setWarehouses] = useState<ClientWarehouse[]>([])
  const [loading, setLoading] = useState(false)
  const [editing, setEditing] = useState<RoutingRule | null>(null)
  const [dryRunOpen, setDryRunOpen] = useState(false)
  const [reloadToken, setReloadToken] = useState(0)

  useEffect(() => {
    clientService
      .listClients({ size: 200, status: 'ACTIVE', sortBy: 'code' })
      .then((r) => setClients(r.data?.content ?? []))
      .catch(() => setClients([]))
    shippingConfigService
      .catalog()
      .then((c) => setServices(c.services ?? []))
      .catch(() => setServices([]))
  }, [])

  const load = useCallback(async () => {
    if (!clientCode) {
      setRules([])
      setWarehouses([])
      return
    }
    setLoading(true)
    try {
      const [rulesRes, whRes] = await Promise.all([
        routingRuleService.list(clientCode),
        clientWarehouseService.listForClient(clientCode),
      ])
      setRules(rulesRes)
      // Only warehouses where the embedded Warehouse resolved — filter defensively.
      setWarehouses((whRes.data ?? []).filter((cw) => cw.warehouse !== null))
    } catch (err: any) {
      notify.error(err?.message ?? 'Failed to load rules.')
    } finally {
      setLoading(false)
    }
  }, [clientCode])

  useEffect(() => {
    load()
  }, [load, reloadToken])

  const refresh = useCallback(() => setReloadToken((t) => t + 1), [])
  useEffect(() => {
    registerRefresh(refresh)
    return () => registerRefresh(null)
  }, [registerRefresh, refresh])

  const moveRule = async (rule: RoutingRule, delta: -1 | 1) => {
    const nextPriority = Math.max(0, rule.priority + delta * 10)
    try {
      await routingRuleService.save(clientCode, { ...rule, priority: nextPriority })
      refresh()
    } catch (err: any) {
      notify.error(err?.message ?? 'Failed to reorder rule.')
    }
  }

  const delRule = async (rule: RoutingRule) => {
    if (!rule.id) return
    if (!window.confirm(`Delete rule "${rule.name}"? Historic orders keep their routing.`)) return
    try {
      await routingRuleService.remove(clientCode, rule.id)
      notify.success('Rule deleted.')
      refresh()
    } catch (err: any) {
      notify.error(err?.message ?? 'Failed to delete rule.')
    }
  }

  const serviceLabel = (id?: number | null): string => {
    if (!id) return '—'
    const s = services.find((v) => v.id === id)
    return s ? `${formatCarrierName(s.carrier)} · ${s.serviceCode} — ${s.name}` : `#${id}`
  }

  const warehouseLabel = (id?: number | null): string => {
    if (!id) return '—'
    const w = warehouses.find((cw) => cw.warehouse?.id === id)?.warehouse
    return w ? `${w.code} — ${w.name}` : `#${id}`
  }

  const clientOptions = useMemo(
    () => [
      { value: '', label: '— pick a client —' },
      ...clients.map((c) => ({ value: c.clientCode, label: `${c.clientCode} — ${c.name}` })),
    ],
    [clients],
  )

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div className="min-w-[300px]">
          <label className={fieldLabel}>Client scope</label>
          <Select value={clientCode} onChange={(e) => setClientCode(e.target.value)}>
            {clientOptions.map((o) => (
              <option key={o.value} value={o.value}>{o.label}</option>
            ))}
          </Select>
        </div>
        {clientCode ? (
          <div className="flex items-center gap-2">
            <button
              type="button"
              onClick={() => setDryRunOpen(true)}
              className="inline-flex items-center gap-1.5 rounded-md border border-slate-200 bg-white px-3 py-1.5 text-[12.5px] font-semibold text-slate-700 hover:bg-slate-50"
            >
              <FiPlay /> Dry-run
            </button>
            <button
              type="button"
              onClick={() => setEditing({
                clientCode,
                name: '',
                priority: (rules[rules.length - 1]?.priority ?? 100) + 10,
                active: true,
                actionType: 'REROUTE',
              })}
              className="inline-flex items-center gap-1.5 rounded-md bg-[#1f150c] px-3 py-1.5 text-[12.5px] font-semibold text-white hover:bg-black"
            >
              <FiPlus /> New rule
            </button>
          </div>
        ) : null}
      </div>

      {clientCode ? (
        <div className="overflow-hidden rounded-2xl border border-slate-200 bg-white shadow-sm">
          <table className="min-w-full text-[12.5px]">
            <thead className="bg-slate-50 text-[10.5px] font-bold uppercase tracking-[0.14em] text-slate-500">
              <tr>
                <th className="px-4 py-2 text-left">Order</th>
                <th className="px-4 py-2 text-left">Name</th>
                <th className="px-4 py-2 text-left">Conditions</th>
                <th className="px-4 py-2 text-left">Action</th>
                <th className="px-4 py-2 text-left">Active</th>
                <th className="px-4 py-2 text-right">Actions</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100">
              {loading ? (
                <tr><td colSpan={6} className="px-4 py-6 text-center text-slate-400">Loading rules…</td></tr>
              ) : rules.length === 0 ? (
                <tr><td colSpan={6} className="px-4 py-6 text-center text-slate-400">
                  No rules configured for this client yet.
                </td></tr>
              ) : (
                rules.map((r, i) => (
                  <tr key={r.id ?? r.name} className="hover:bg-slate-50 align-top">
                    <td className="px-4 py-2">
                      <div className="inline-flex items-center gap-1">
                        <button type="button" disabled={i === 0} onClick={() => moveRule(r, -1)}
                          className="text-slate-400 hover:text-slate-700 disabled:opacity-30">
                          <FiChevronUp />
                        </button>
                        <button type="button" disabled={i === rules.length - 1} onClick={() => moveRule(r, 1)}
                          className="text-slate-400 hover:text-slate-700 disabled:opacity-30">
                          <FiChevronDown />
                        </button>
                        <span className="ml-1 text-[11px] tabular-nums text-slate-400">{r.priority}</span>
                      </div>
                    </td>
                    <td className="px-4 py-2 font-semibold text-slate-800">{r.name}</td>
                    <td className="px-4 py-2 text-[11.5px] text-slate-600">
                      {conditionSummary(r) || <em className="text-slate-400">Any shipment</em>}
                    </td>
                    <td className="px-4 py-2 text-[11.5px]">
                      {r.actionType === 'REROUTE' ? (
                        <>
                          <span className="rounded bg-sky-100 px-1.5 py-0.5 text-[10.5px] font-bold text-sky-700">REROUTE</span>
                          {r.targetServiceId ? (
                            <span className="ml-1 text-slate-600">→ svc {serviceLabel(r.targetServiceId)}</span>
                          ) : null}
                          {r.targetWarehouseId ? (
                            <span className="ml-1 text-slate-600">→ wh {warehouseLabel(r.targetWarehouseId)}</span>
                          ) : null}
                        </>
                      ) : (
                        <>
                          <span className="rounded bg-rose-100 px-1.5 py-0.5 text-[10.5px] font-bold text-rose-700">BLOCK</span>
                          <span className="ml-1 text-slate-600">— {r.blockReason}</span>
                        </>
                      )}
                    </td>
                    <td className="px-4 py-2 text-[11.5px]">
                      {r.active === false ? <span className="text-rose-600">Inactive</span> : <span className="text-emerald-700">Active</span>}
                    </td>
                    <td className="px-4 py-2 text-right">
                      <div className="inline-flex items-center gap-1">
                        <button type="button" onClick={() => setEditing({ ...r })}
                          className="rounded p-1 text-slate-500 hover:bg-slate-100 hover:text-slate-800" title="Edit">
                          <FiEdit3 />
                        </button>
                        <button type="button" onClick={() => delRule(r)}
                          className="rounded p-1 text-rose-500 hover:bg-rose-50" title="Delete">
                          <FiTrash2 />
                        </button>
                      </div>
                    </td>
                  </tr>
                ))
              )}
            </tbody>
          </table>
        </div>
      ) : (
        <div className="rounded-2xl border border-dashed border-slate-200 bg-white p-8 text-center text-[13px] text-slate-500">
          Pick a client above to view or configure its routing rules.
        </div>
      )}

      {editing ? (
        <RuleEditorModal
          rule={editing}
          services={services}
          warehouses={warehouses}
          onClose={() => setEditing(null)}
          onSaved={() => { setEditing(null); refresh() }}
        />
      ) : null}

      {dryRunOpen ? (
        <DryRunPanel
          clientCode={clientCode}
          services={services}
          warehouses={warehouses}
          onClose={() => setDryRunOpen(false)}
        />
      ) : null}
    </div>
  )
}

// ==========================================================================
// Rule editor modal
// ==========================================================================

function RuleEditorModal({
  rule, services, warehouses, onClose, onSaved,
}: {
  rule: RoutingRule
  services: ShippingServiceItem[]
  warehouses: ClientWarehouse[]
  onClose: () => void
  onSaved: () => void
}) {
  const [draft, setDraft] = useState<RoutingRule>({ ...rule })
  const [saving, setSaving] = useState(false)

  const set = <K extends keyof RoutingRule>(k: K, v: RoutingRule[K]) =>
    setDraft((d) => ({ ...d, [k]: v }))
  const setNum = (k: keyof RoutingRule) => (v: string) => set(k, (v === '' ? null : Number(v)) as any)
  const setStr = (k: keyof RoutingRule) => (v: string) => set(k, (v === '' ? null : v) as any)

  const save = async () => {
    if (!draft.name.trim()) {
      notify.error('Name is required.')
      return
    }
    if (draft.actionType === 'REROUTE' && !draft.targetServiceId && !draft.targetWarehouseId) {
      notify.error('REROUTE rules need a target service, warehouse, or both.')
      return
    }
    if (draft.actionType === 'BLOCK' && !draft.blockReason?.trim()) {
      notify.error('BLOCK rules need a reason.')
      return
    }
    setSaving(true)
    try {
      await routingRuleService.save(draft.clientCode, draft)
      notify.success('Rule saved.')
      onSaved()
    } catch (err) {
      notify.error(err instanceof ApiError ? err.message : 'Failed to save rule.')
    } finally {
      setSaving(false)
    }
  }

  return (
    <div className="fixed inset-0 z-40 flex items-center justify-center">
      <div className="absolute inset-0 bg-slate-950/45 backdrop-blur-sm" onClick={onClose} />
      <div className="relative z-10 w-full max-w-2xl overflow-hidden rounded-2xl bg-white shadow-2xl">
        <div className="flex items-center justify-between border-b border-slate-200 px-6 py-4">
          <h3 className="text-base font-semibold text-slate-900">
            {draft.id ? 'Edit routing rule' : 'New routing rule'}
          </h3>
          <button onClick={onClose} className="text-slate-400 hover:text-slate-800"><FiX /></button>
        </div>

        <div className="max-h-[70vh] space-y-4 overflow-y-auto px-6 py-5">
          {/* Basics */}
          <div className="grid grid-cols-4 gap-3">
            <div className="col-span-2">
              <label className={fieldLabel}>Name</label>
              <input type="text" value={draft.name} onChange={(e) => set('name', e.target.value)}
                placeholder="EU over 5kg — DHL" className={inputCls} />
            </div>
            <div>
              <label className={fieldLabel}>Priority</label>
              <input type="number" min={0} value={draft.priority}
                onChange={(e) => set('priority', Number(e.target.value))} className={inputCls} />
              <p className="mt-1 text-[10.5px] text-slate-400">Lower = earlier</p>
            </div>
            <div className="pt-6">
              <label className="inline-flex items-center gap-2 text-[13px] text-slate-800">
                <input type="checkbox" checked={draft.active !== false}
                  onChange={(e) => set('active', e.target.checked)} />
                Active
              </label>
            </div>
          </div>

          {/* Conditions */}
          <section>
            <h4 className="mb-2 text-[11px] font-bold uppercase tracking-[0.14em] text-slate-500">Conditions (all optional — leave blank to match anything)</h4>
            <div className="grid grid-cols-2 gap-3">
              <div>
                <label className={fieldLabel}>Weight range (LB)</label>
                <div className="flex gap-2">
                  <input type="number" step="0.1" placeholder="min" value={draft.minWeightLb ?? ''}
                    onChange={(e) => setNum('minWeightLb')(e.target.value)} className={inputCls} />
                  <input type="number" step="0.1" placeholder="max" value={draft.maxWeightLb ?? ''}
                    onChange={(e) => setNum('maxWeightLb')(e.target.value)} className={inputCls} />
                </div>
              </div>
              <div>
                <label className={fieldLabel}>Declared value range</label>
                <div className="flex gap-2">
                  <input type="number" step="0.01" placeholder="min" value={draft.minDeclaredValue ?? ''}
                    onChange={(e) => setNum('minDeclaredValue')(e.target.value)} className={inputCls} />
                  <input type="number" step="0.01" placeholder="max" value={draft.maxDeclaredValue ?? ''}
                    onChange={(e) => setNum('maxDeclaredValue')(e.target.value)} className={inputCls} />
                </div>
              </div>
              <div>
                <label className={fieldLabel}>Destination countries (CSV of ISO-2)</label>
                <input type="text" placeholder="DE, FR, IT" value={draft.destCountries ?? ''}
                  onChange={(e) => setStr('destCountries')(e.target.value.toUpperCase())} className={inputCls} />
              </div>
              <div>
                <label className={fieldLabel}>Destination regions (CSV)</label>
                <input type="text" placeholder="Europe, Asia" value={draft.destRegions ?? ''}
                  onChange={(e) => setStr('destRegions')(e.target.value)} className={inputCls} />
                <p className="mt-1 text-[10.5px] text-slate-400">
                  Available: {REGIONS.join(', ')}
                </p>
              </div>
              <div>
                <label className={fieldLabel}>Current carrier</label>
                <Select value={draft.matchCarrier ?? ''} onChange={(e) => setStr('matchCarrier')(e.target.value)}>
                  <option value="">Any carrier</option>
                  {CARRIER_OPTIONS.map((c) => <option key={c} value={c}>{c}</option>)}
                </Select>
              </div>
              <div>
                <label className={fieldLabel}>Current service</label>
                <Select value={draft.matchServiceId?.toString() ?? ''}
                  onChange={(e) => setNum('matchServiceId')(e.target.value)}>
                  <option value="">Any service</option>
                  {services.filter((s) => s.enabled).map((s) => (
                    <option key={s.id} value={s.id}>
                      {formatCarrierName(s.carrier)} · {s.serviceCode} — {s.name}
                    </option>
                  ))}
                </Select>
              </div>
              <div>
                <label className={fieldLabel}>Order source</label>
                <Select value={draft.matchOrderSource ?? ''} onChange={(e) => setStr('matchOrderSource')(e.target.value)}>
                  <option value="">Any source</option>
                  {SOURCE_OPTIONS.map((s) => <option key={s} value={s}>{s}</option>)}
                </Select>
              </div>
              <div>
                <label className={fieldLabel}>Current warehouse</label>
                <Select value={draft.matchWarehouseId?.toString() ?? ''}
                  onChange={(e) => setNum('matchWarehouseId')(e.target.value)}>
                  <option value="">Any warehouse</option>
                  {warehouses.map((cw) => (
                    <option key={cw.warehouse!.id} value={cw.warehouse!.id}>
                      {cw.warehouse!.code} — {cw.warehouse!.name}
                      {cw.isDefault ? ' (default)' : ''}
                    </option>
                  ))}
                </Select>
              </div>
            </div>
          </section>

          {/* Action */}
          <section>
            <h4 className="mb-2 text-[11px] font-bold uppercase tracking-[0.14em] text-slate-500">Action</h4>
            <div className="flex items-center gap-4 pb-2">
              <label className="inline-flex items-center gap-2 text-[13px]">
                <input type="radio" name="actionType" checked={draft.actionType === 'REROUTE'}
                  onChange={() => set('actionType', 'REROUTE' as RoutingActionType)} />
                Reroute to another service
              </label>
              <label className="inline-flex items-center gap-2 text-[13px]">
                <input type="radio" name="actionType" checked={draft.actionType === 'BLOCK'}
                  onChange={() => set('actionType', 'BLOCK' as RoutingActionType)} />
                Block generation
              </label>
            </div>
            {draft.actionType === 'REROUTE' ? (
              <div className="grid grid-cols-2 gap-3">
                <div>
                  <label className={fieldLabel}>Target service</label>
                  <Select value={draft.targetServiceId?.toString() ?? ''}
                    onChange={(e) => setNum('targetServiceId')(e.target.value)}>
                    <option value="">Keep current service</option>
                    {services.filter((s) => s.enabled).map((s) => (
                      <option key={s.id} value={s.id}>
                        {formatCarrierName(s.carrier)} · {s.serviceCode} — {s.name}
                      </option>
                    ))}
                  </Select>
                </div>
                <div>
                  <label className={fieldLabel}>Target warehouse</label>
                  <Select value={draft.targetWarehouseId?.toString() ?? ''}
                    onChange={(e) => setNum('targetWarehouseId')(e.target.value)}>
                    <option value="">Keep current warehouse</option>
                    {warehouses.map((cw) => (
                      <option key={cw.warehouse!.id} value={cw.warehouse!.id}>
                        {cw.warehouse!.code} — {cw.warehouse!.name}
                        {cw.isDefault ? ' (default)' : ''}
                      </option>
                    ))}
                  </Select>
                </div>
                <p className="col-span-2 text-[10.5px] text-slate-400">
                  Pick at least one. Leaving both empty is not a valid REROUTE.
                </p>
              </div>
            ) : (
              <div>
                <label className={fieldLabel}>Block reason (shown to operator)</label>
                <input type="text" value={draft.blockReason ?? ''}
                  onChange={(e) => set('blockReason', e.target.value)}
                  placeholder="e.g. Weight over carrier maximum" className={inputCls} />
              </div>
            )}
          </section>
        </div>

        <div className="flex items-center justify-end gap-2 border-t border-slate-200 px-6 py-3">
          <button type="button" onClick={onClose}
            className="rounded-md border border-slate-200 px-3 py-1.5 text-[12.5px] font-semibold text-slate-700 hover:bg-slate-50">
            Cancel
          </button>
          <button type="button" onClick={save} disabled={saving}
            className="rounded-md bg-[#1f150c] px-3 py-1.5 text-[12.5px] font-semibold text-white hover:bg-black disabled:opacity-50">
            {saving ? 'Saving…' : 'Save'}
          </button>
        </div>
      </div>
    </div>
  )
}

// ==========================================================================
// Dry-run panel
// ==========================================================================

function DryRunPanel({
  clientCode, services, warehouses, onClose,
}: {
  clientCode: string
  services: ShippingServiceItem[]
  warehouses: ClientWarehouse[]
  onClose: () => void
}) {
  const [req, setReq] = useState<RoutingEvaluationRequest>({})
  const [result, setResult] = useState<RoutingEvaluationResult | null>(null)
  const [running, setRunning] = useState(false)

  const run = async () => {
    setRunning(true)
    try {
      setResult(await routingRuleService.dryRun(clientCode, req))
    } catch (err) {
      notify.error(err instanceof ApiError ? err.message : 'Dry-run failed.')
    } finally {
      setRunning(false)
    }
  }

  const set = <K extends keyof RoutingEvaluationRequest>(k: K, v: RoutingEvaluationRequest[K]) =>
    setReq((r) => ({ ...r, [k]: v }))
  const setNum = (k: keyof RoutingEvaluationRequest) => (v: string) =>
    set(k, (v === '' ? null : Number(v)) as any)
  const setStr = (k: keyof RoutingEvaluationRequest) => (v: string) =>
    set(k, (v === '' ? null : v) as any)

  return (
    <div className="fixed inset-0 z-40 flex items-center justify-end">
      <div className="absolute inset-0 bg-slate-950/45 backdrop-blur-sm" onClick={onClose} />
      <div className="relative z-10 flex h-full w-full max-w-lg flex-col bg-white shadow-2xl">
        <div className="flex items-center justify-between border-b border-slate-200 px-5 py-4">
          <h3 className="text-base font-semibold text-slate-900">Dry-run — {clientCode}</h3>
          <button onClick={onClose} className="text-slate-400 hover:text-slate-800"><FiX /></button>
        </div>

        <div className="flex-1 space-y-3 overflow-y-auto px-5 py-4">
          <p className="text-[12.5px] text-slate-500">
            Enter a synthetic shipment; the engine returns the first matching rule and a
            per-rule trace showing why the others were skipped.
          </p>

          <div className="grid grid-cols-2 gap-3">
            <div>
              <label className={fieldLabel}>Weight (LB)</label>
              <input type="number" step="0.1" value={req.weightLb ?? ''}
                onChange={(e) => setNum('weightLb')(e.target.value)} className={inputCls} />
            </div>
            <div>
              <label className={fieldLabel}>Declared value</label>
              <input type="number" step="0.01" value={req.declaredValue ?? ''}
                onChange={(e) => setNum('declaredValue')(e.target.value)} className={inputCls} />
            </div>
            <div>
              <label className={fieldLabel}>Destination country (ISO-2)</label>
              <input type="text" value={req.destCountry ?? ''}
                onChange={(e) => setStr('destCountry')(e.target.value.toUpperCase())} className={inputCls} />
            </div>
            <div>
              <label className={fieldLabel}>Destination region</label>
              <Select value={req.destRegion ?? ''} onChange={(e) => setStr('destRegion')(e.target.value)}>
                <option value="">Any</option>
                {REGIONS.map((r) => <option key={r} value={r}>{r}</option>)}
              </Select>
            </div>
            <div>
              <label className={fieldLabel}>Current carrier</label>
              <Select value={req.currentCarrier ?? ''} onChange={(e) => setStr('currentCarrier')(e.target.value)}>
                <option value="">Any</option>
                {CARRIER_OPTIONS.map((c) => <option key={c} value={c}>{c}</option>)}
              </Select>
            </div>
            <div>
              <label className={fieldLabel}>Current service</label>
              <Select value={req.currentServiceId?.toString() ?? ''}
                onChange={(e) => setNum('currentServiceId')(e.target.value)}>
                <option value="">Any</option>
                {services.filter((s) => s.enabled).map((s) => (
                  <option key={s.id} value={s.id}>
                    {formatCarrierName(s.carrier)} · {s.serviceCode} — {s.name}
                  </option>
                ))}
              </Select>
            </div>
            <div>
              <label className={fieldLabel}>Order source</label>
              <Select value={req.orderSource ?? ''} onChange={(e) => setStr('orderSource')(e.target.value)}>
                <option value="">Any</option>
                {SOURCE_OPTIONS.map((s) => <option key={s} value={s}>{s}</option>)}
              </Select>
            </div>
            <div>
              <label className={fieldLabel}>Current warehouse</label>
              <Select value={req.currentWarehouseId?.toString() ?? ''}
                onChange={(e) => setNum('currentWarehouseId')(e.target.value)}>
                <option value="">Any</option>
                {warehouses.map((cw) => (
                  <option key={cw.warehouse!.id} value={cw.warehouse!.id}>
                    {cw.warehouse!.code} — {cw.warehouse!.name}
                    {cw.isDefault ? ' (default)' : ''}
                  </option>
                ))}
              </Select>
            </div>
          </div>

          <button type="button" onClick={run} disabled={running}
            className="inline-flex items-center gap-1.5 rounded-md bg-[#1f150c] px-3 py-1.5 text-[12.5px] font-semibold text-white hover:bg-black disabled:opacity-50">
            <FiPlay /> {running ? 'Running…' : 'Preview'}
          </button>

          {result ? (
            <div className="rounded-lg border border-slate-200 bg-slate-50 p-3">
              <div className="mb-2 text-[13px] font-semibold text-slate-900">
                {result.status === 'MATCH'
                  ? <>Matched: <span className="text-emerald-700">{result.matchedRuleName}</span></>
                  : 'No rule matched — current selection kept.'}
              </div>
              {result.status === 'MATCH' ? (
                <div className="mb-3 text-[12.5px]">
                  Action: <span className="font-bold">{result.actionType}</span>
                  {result.actionType === 'REROUTE' && result.targetServiceId ? (
                    <span> → service id {result.targetServiceId}
                      {result.targetCarrier ? ` (${result.targetCarrier})` : ''}</span>
                  ) : null}
                  {result.actionType === 'REROUTE' && result.targetWarehouseId ? (
                    <span> → warehouse id {result.targetWarehouseId}</span>
                  ) : null}
                  {result.actionType === 'BLOCK' ? (
                    <span> — {result.blockReason}</span>
                  ) : null}
                </div>
              ) : null}
              <h4 className="mb-1 text-[11px] font-bold uppercase tracking-[0.14em] text-slate-500">Trace</h4>
              <ul className="space-y-0.5 text-[11.5px]">
                {result.trace.map((t) => (
                  <li key={t.ruleId} className="font-mono text-slate-700">
                    <span className={
                      t.outcome === 'MATCHED' ? 'text-emerald-700 font-bold'
                      : t.outcome === 'SKIPPED_INACTIVE' ? 'text-slate-400'
                      : 'text-amber-600'
                    }>[{t.outcome}]</span>
                    {' '}#{t.ruleId} ({t.priority}) {t.ruleName}
                  </li>
                ))}
              </ul>
            </div>
          ) : null}
        </div>
      </div>
    </div>
  )
}

// ==========================================================================
// Helpers
// ==========================================================================

const fieldLabel = 'mb-1 block text-[10.5px] font-bold uppercase tracking-[0.14em] text-slate-500'
const inputCls =
  'w-full rounded-md border border-slate-200 bg-white px-3 py-1.5 text-[13px] text-slate-800 outline-none focus:border-[#1f150c]'

function conditionSummary(r: RoutingRule): string {
  const parts: string[] = []
  if (r.minWeightLb != null || r.maxWeightLb != null) {
    parts.push(`weight ${r.minWeightLb ?? '-'}–${r.maxWeightLb ?? '-'} lb`)
  }
  if (r.destCountries) parts.push(`dest ${r.destCountries}`)
  if (r.destRegions) parts.push(`region ${r.destRegions}`)
  if (r.matchCarrier) parts.push(`carrier ${r.matchCarrier}`)
  if (r.matchServiceId) parts.push(`service #${r.matchServiceId}`)
  if (r.minDeclaredValue != null || r.maxDeclaredValue != null) {
    parts.push(`val ${r.minDeclaredValue ?? '-'}–${r.maxDeclaredValue ?? '-'}`)
  }
  if (r.matchOrderSource) parts.push(`source ${r.matchOrderSource}`)
  if (r.matchWarehouseId) parts.push(`wh #${r.matchWarehouseId}`)
  return parts.join(' · ')
}
