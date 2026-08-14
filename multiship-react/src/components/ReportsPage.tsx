import { useCallback, useEffect, useMemo, useState } from 'react'
import { useOutletContext } from 'react-router-dom'
import {
  FiCalendar,
  FiDownload,
  FiEdit3,
  FiPlay,
  FiPlus,
  FiSearch,
  FiTrash2,
  FiX,
} from 'react-icons/fi'
import { notify } from '../utils/notify'
import { clientService, type Client } from '../api/clientService'
import {
  reportService,
  type Dataset,
  type DeliveryType,
  type Frequency,
  type GeneratedReport,
  type ReportFilters,
  type ScheduledReport,
} from '../api/reportService'
import Select from './workspace/Select'
import type { SettingsOutletContext } from './layout/SettingsLayout'

const DATASETS: { value: Dataset; label: string; hint: string }[] = [
  { value: 'ORDERS', label: 'Orders + labels', hint: 'Row per order/label with cost, tracking, dest.' },
  { value: 'TRACKING', label: 'Tracking snapshots', hint: 'Current status per order.' },
  { value: 'RATE_SHOP', label: 'Rate-shop history', hint: 'Cheapest quote per shop call.' },
  { value: 'BILLING', label: 'Billing rollup', hint: 'Aggregated spend by client × month.' },
]

/**
 * Sprint 45 — Reports settings page. Two tabs: Data explorer (filters
 * + Download CSV per dataset) and Scheduled exports (CRUD + generated
 * download list).
 */
export default function ReportsPage() {
  const { registerRefresh } = useOutletContext<SettingsOutletContext>()
  const [tab, setTab] = useState<'explorer' | 'schedules'>('explorer')
  const [clients, setClients] = useState<Client[]>([])

  useEffect(() => {
    clientService
      .listClients({ size: 200, status: 'ACTIVE', sortBy: 'code' })
      .then((r) => setClients(r.data?.content ?? []))
      .catch(() => setClients([]))
  }, [])

  useEffect(() => {
    registerRefresh(null)
    return () => registerRefresh(null)
  }, [registerRefresh])

  return (
    <div className="space-y-4">
      <div className="flex gap-1 rounded-xl border border-slate-200 bg-slate-50 p-1" role="tablist">
        <TabButton active={tab === 'explorer'} onClick={() => setTab('explorer')}>
          <FiSearch /> Data explorer
        </TabButton>
        <TabButton active={tab === 'schedules'} onClick={() => setTab('schedules')}>
          <FiCalendar /> Scheduled exports
        </TabButton>
      </div>

      {tab === 'explorer' ? <DataExplorer clients={clients} /> : <Schedules clients={clients} />}
    </div>
  )
}

function TabButton({
  active, onClick, children,
}: {
  active: boolean
  onClick: () => void
  children: React.ReactNode
}) {
  return (
    <button
      type="button"
      role="tab"
      aria-selected={active}
      onClick={onClick}
      className={`inline-flex flex-1 items-center justify-center gap-1.5 rounded-lg px-3 py-2 text-[12.5px] font-semibold transition ${
        active ? 'bg-white text-[#1f150c] shadow-sm' : 'text-slate-500 hover:text-slate-800'
      }`}
    >
      {children}
    </button>
  )
}

// ==========================================================================
// Data explorer
// ==========================================================================

function DataExplorer({ clients }: { clients: Client[] }) {
  const [filters, setFilters] = useState<ReportFilters>({})
  const [running, setRunning] = useState<Dataset | null>(null)

  // Split typed helpers keep the k -> v type-flow sound without an `any` cast: the
  // key literally selects a string-valued (or number-valued) key of ReportFilters.
  type StringKey = NonNullable<{ [K in keyof ReportFilters]: ReportFilters[K] extends string | undefined ? K : never }[keyof ReportFilters]>
  type NumberKey = NonNullable<{ [K in keyof ReportFilters]: ReportFilters[K] extends number | undefined ? K : never }[keyof ReportFilters]>
  const setStr = <K extends StringKey>(k: K) => (v: string) =>
    setFilters((f) => ({ ...f, [k]: v === '' ? undefined : v }))
  const setNum = <K extends NumberKey>(k: K) => (v: string) =>
    setFilters((f) => ({ ...f, [k]: v === '' ? undefined : Number(v) }))

  const runDownload = async (dataset: Dataset) => {
    setRunning(dataset)
    try {
      const filename = `${dataset.toLowerCase()}-${new Date().toISOString().slice(0, 10)}.csv`
      await reportService.downloadCsv(dataset, filters, filename)
    } catch (err: unknown) {
      notify.error(err instanceof Error ? err.message : 'Download failed.')
    } finally {
      setRunning(null)
    }
  }

  return (
    <div className="space-y-4">
      <div className="rounded-2xl border border-slate-200 bg-white p-5 shadow-sm">
        <h3 className="mb-3 text-[13px] font-semibold text-slate-900">Filters</h3>
        <div className="grid grid-cols-3 gap-3">
          <div><label className={fieldLabel}>From</label>
            <input type="date" value={filters.from ?? ''} onChange={(e) => setStr('from')(e.target.value)} className={inputCls} />
          </div>
          <div><label className={fieldLabel}>To</label>
            <input type="date" value={filters.to ?? ''} onChange={(e) => setStr('to')(e.target.value)} className={inputCls} />
          </div>
          <div><label className={fieldLabel}>Client</label>
            <Select value={filters.customerNo ?? ''} onChange={(e) => setStr('customerNo')(e.target.value)}>
              <option value="">Any</option>
              {clients.map((c) => <option key={c.clientCode} value={c.clientCode}>{c.clientCode} — {c.name}</option>)}
            </Select>
          </div>
          <div><label className={fieldLabel}>Carrier</label>
            <input type="text" value={filters.carrier ?? ''} onChange={(e) => setStr('carrier')(e.target.value)} placeholder="UPS / FEDEX / …" className={inputCls} />
          </div>
          <div><label className={fieldLabel}>Status</label>
            <input type="text" value={filters.status ?? ''} onChange={(e) => setStr('status')(e.target.value)} placeholder="GENERATED / PENDING / ERROR" className={inputCls} />
          </div>
          <div><label className={fieldLabel}>Dest country (ISO-2)</label>
            <input type="text" value={filters.destCountry ?? ''} onChange={(e) => setStr('destCountry')(e.target.value.toUpperCase())} className={inputCls} />
          </div>
          <div><label className={fieldLabel}>Weight range (LB)</label>
            <div className="flex gap-2">
              <input type="number" step="0.1" placeholder="min" value={filters.minWeightLb ?? ''} onChange={(e) => setNum('minWeightLb')(e.target.value)} className={inputCls} />
              <input type="number" step="0.1" placeholder="max" value={filters.maxWeightLb ?? ''} onChange={(e) => setNum('maxWeightLb')(e.target.value)} className={inputCls} />
            </div>
          </div>
          <div><label className={fieldLabel}>Declared value range</label>
            <div className="flex gap-2">
              <input type="number" step="0.01" placeholder="min" value={filters.minDeclaredValue ?? ''} onChange={(e) => setNum('minDeclaredValue')(e.target.value)} className={inputCls} />
              <input type="number" step="0.01" placeholder="max" value={filters.maxDeclaredValue ?? ''} onChange={(e) => setNum('maxDeclaredValue')(e.target.value)} className={inputCls} />
            </div>
          </div>
        </div>
      </div>

      <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
        {DATASETS.map((d) => (
          <div key={d.value} className="rounded-2xl border border-slate-200 bg-white p-4 shadow-sm">
            <div className="mb-2">
              <div className="text-[13px] font-semibold text-slate-900">{d.label}</div>
              <div className="text-[11.5px] text-slate-500">{d.hint}</div>
            </div>
            <button
              type="button"
              onClick={() => runDownload(d.value)}
              disabled={running === d.value}
              className="inline-flex items-center gap-1.5 rounded-md bg-[#1f150c] px-3 py-1.5 text-[12.5px] font-semibold text-white hover:bg-black disabled:opacity-50"
            >
              <FiDownload /> {running === d.value ? 'Building…' : 'Download CSV'}
            </button>
          </div>
        ))}
      </div>
    </div>
  )
}

// ==========================================================================
// Scheduled exports
// ==========================================================================

function Schedules({ clients }: { clients: Client[] }) {
  const [schedules, setSchedules] = useState<ScheduledReport[]>([])
  const [generated, setGenerated] = useState<GeneratedReport[]>([])
  const [loading, setLoading] = useState(false)
  const [editing, setEditing] = useState<ScheduledReport | null>(null)
  const [reloadToken, setReloadToken] = useState(0)

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const [ss, gg] = await Promise.all([
        reportService.listSchedules(),
        reportService.listGenerated(),
      ])
      setSchedules(ss)
      setGenerated(gg)
    } catch (err: unknown) {
      notify.error(err instanceof Error ? err.message : 'Failed to load schedules.')
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    load()
  }, [load, reloadToken])

  const refresh = useCallback(() => setReloadToken((t) => t + 1), [])

  const runNow = async (s: ScheduledReport) => {
    if (!s.id) return
    try {
      await reportService.runNow(s.id)
      notify.success('Run kicked off — check the Generated section shortly.')
      refresh()
    } catch (err: unknown) {
      notify.error(err instanceof Error ? err.message : 'Run failed.')
    }
  }

  const del = async (s: ScheduledReport) => {
    if (!s.id) return
    if (!window.confirm(`Delete schedule "${s.name}"?`)) return
    try {
      await reportService.deleteSchedule(s.id)
      notify.success('Schedule deleted.')
      refresh()
    } catch (err: unknown) {
      notify.error(err instanceof Error ? err.message : 'Delete failed.')
    }
  }

  return (
    <div className="space-y-4">
      {/* ==== Schedules ==== */}
      <div className="rounded-2xl border border-slate-200 bg-white shadow-sm">
        <div className="flex items-center justify-between border-b border-slate-200 px-5 py-3">
          <h3 className="text-[13px] font-semibold text-slate-900">Scheduled exports</h3>
          <button
            type="button"
            onClick={() => setEditing({
              name: '',
              dataset: 'ORDERS',
              frequency: 'DAILY',
              deliveryType: 'DASHBOARD',
              active: true,
            })}
            className="inline-flex items-center gap-1.5 rounded-md bg-[#1f150c] px-3 py-1.5 text-[12.5px] font-semibold text-white hover:bg-black"
          >
            <FiPlus /> New schedule
          </button>
        </div>
        <table className="min-w-full text-[12.5px]">
          <thead className="bg-slate-50 text-[10.5px] font-bold uppercase tracking-[0.14em] text-slate-500">
            <tr>
              <th className="px-4 py-2 text-left">Name</th>
              <th className="px-4 py-2 text-left">Tenant</th>
              <th className="px-4 py-2 text-left">Dataset</th>
              <th className="px-4 py-2 text-left">Cadence</th>
              <th className="px-4 py-2 text-left">Delivery</th>
              <th className="px-4 py-2 text-left">Next run</th>
              <th className="px-4 py-2 text-right">Actions</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-slate-100">
            {loading ? (
              <tr><td colSpan={7} className="px-4 py-6 text-center text-slate-400">Loading…</td></tr>
            ) : schedules.length === 0 ? (
              <tr><td colSpan={7} className="px-4 py-6 text-center text-slate-400">No schedules yet.</td></tr>
            ) : schedules.map((s) => (
              <tr key={s.id} className="hover:bg-slate-50">
                <td className="px-4 py-2 font-semibold text-slate-800">{s.name}</td>
                <td className="px-4 py-2 text-slate-500">{s.tenantId ?? '—'}</td>
                <td className="px-4 py-2"><span className="rounded bg-slate-100 px-1.5 py-0.5 text-[11px] font-bold">{s.dataset}</span></td>
                <td className="px-4 py-2">{s.frequency}</td>
                <td className="px-4 py-2">
                  {s.deliveryType}
                  {s.deliveryType === 'EMAIL' && s.deliveryEmail ? <span className="ml-1 text-[10.5px] text-slate-500">→ {s.deliveryEmail}</span> : null}
                </td>
                <td className="px-4 py-2 text-[11.5px] text-slate-500">{s.nextRunAt ? new Date(s.nextRunAt).toLocaleString() : '—'}</td>
                <td className="px-4 py-2 text-right">
                  <div className="inline-flex gap-1">
                    <button title="Run now" onClick={() => runNow(s)} className="rounded p-1 text-slate-500 hover:bg-slate-100 hover:text-slate-800"><FiPlay /></button>
                    <button title="Edit" onClick={() => setEditing({ ...s })} className="rounded p-1 text-slate-500 hover:bg-slate-100 hover:text-slate-800"><FiEdit3 /></button>
                    <button title="Delete" onClick={() => del(s)} className="rounded p-1 text-rose-500 hover:bg-rose-50"><FiTrash2 /></button>
                  </div>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {/* ==== Generated ==== */}
      <div className="rounded-2xl border border-slate-200 bg-white shadow-sm">
        <div className="border-b border-slate-200 px-5 py-3">
          <h3 className="text-[13px] font-semibold text-slate-900">Recent generated (last 50)</h3>
        </div>
        <table className="min-w-full text-[12.5px]">
          <thead className="bg-slate-50 text-[10.5px] font-bold uppercase tracking-[0.14em] text-slate-500">
            <tr>
              <th className="px-4 py-2 text-left">When</th>
              <th className="px-4 py-2 text-left">Dataset</th>
              <th className="px-4 py-2 text-left">File</th>
              <th className="px-4 py-2 text-left">Size</th>
              <th className="px-4 py-2 text-left">Status</th>
              <th className="px-4 py-2 text-right">Actions</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-slate-100">
            {generated.length === 0 ? (
              <tr><td colSpan={6} className="px-4 py-6 text-center text-slate-400">Nothing generated yet.</td></tr>
            ) : generated.map((g) => (
              <tr key={g.id} className="hover:bg-slate-50">
                <td className="px-4 py-2 text-slate-600">{new Date(g.generatedAt).toLocaleString()}</td>
                <td className="px-4 py-2"><span className="rounded bg-slate-100 px-1.5 py-0.5 text-[11px] font-bold">{g.dataset}</span></td>
                <td className="px-4 py-2 font-mono text-[11.5px] text-slate-700">{g.filename}</td>
                <td className="px-4 py-2 text-[11.5px] text-slate-500">{formatBytes(g.sizeBytes)}</td>
                <td className="px-4 py-2 text-[11.5px]">
                  <span className={
                    g.deliveryStatus === 'DELIVERED' ? 'text-emerald-700' :
                    g.deliveryStatus === 'FAILED' ? 'text-rose-600' : 'text-slate-500'
                  }>{g.deliveryStatus}</span>
                </td>
                <td className="px-4 py-2 text-right">
                  <button
                    type="button"
                    onClick={() => reportService.downloadGenerated(g.id, g.filename).catch((e) => notify.apiError(e, 'Download failed.'))}
                    className="inline-flex items-center gap-1.5 rounded-md border border-slate-200 bg-white px-2 py-1 text-[11.5px] font-semibold text-slate-700 hover:bg-slate-50"
                  >
                    <FiDownload /> CSV
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {editing ? (
        <ScheduleEditor
          schedule={editing}
          clients={clients}
          onClose={() => setEditing(null)}
          onSaved={() => { setEditing(null); refresh() }}
        />
      ) : null}
    </div>
  )
}

function ScheduleEditor({
  schedule, clients, onClose, onSaved,
}: {
  schedule: ScheduledReport
  clients: Client[]
  onClose: () => void
  onSaved: () => void
}) {
  const [draft, setDraft] = useState<ScheduledReport>({ ...schedule })
  const [saving, setSaving] = useState(false)

  const set = <K extends keyof ScheduledReport>(k: K, v: ScheduledReport[K]) =>
    setDraft((d) => ({ ...d, [k]: v }))

  const save = async () => {
    if (!draft.name.trim()) {
      notify.error('Name is required.')
      return
    }
    if (draft.deliveryType === 'EMAIL' && !draft.deliveryEmail?.trim()) {
      notify.error('EMAIL delivery needs a recipient.')
      return
    }
    if (draft.deliveryType === 'WEBHOOK' && !draft.deliveryWebhookUrl?.trim()) {
      notify.error('WEBHOOK delivery needs a URL.')
      return
    }
    setSaving(true)
    try {
      await reportService.saveSchedule(draft)
      notify.success('Schedule saved.')
      onSaved()
    } catch (err) {
      notify.apiError(err, 'Save failed.')
    } finally {
      setSaving(false)
    }
  }

  const tenantOptions = useMemo(() => [
    { value: '', label: 'Platform-wide (any tenant)' },
    ...clients.map((c) => ({ value: c.clientCode, label: `${c.clientCode} — ${c.name}` })),
  ], [clients])

  return (
    <div className="fixed inset-0 z-40 flex items-center justify-center">
      <div className="absolute inset-0 bg-slate-950/45 backdrop-blur-sm" onClick={onClose} />
      <div className="relative z-10 w-full max-w-2xl overflow-hidden rounded-2xl bg-white shadow-2xl">
        <div className="flex items-center justify-between border-b border-slate-200 px-6 py-4">
          <h3 className="text-base font-semibold text-slate-900">{draft.id ? 'Edit schedule' : 'New schedule'}</h3>
          <button onClick={onClose} className="text-slate-400 hover:text-slate-800"><FiX /></button>
        </div>

        <div className="max-h-[70vh] space-y-4 overflow-y-auto px-6 py-5">
          <div className="grid grid-cols-2 gap-3">
            <div><label className={fieldLabel}>Name</label>
              <input value={draft.name} onChange={(e) => set('name', e.target.value)} className={inputCls} placeholder="Weekly EU orders" />
            </div>
            <div><label className={fieldLabel}>Tenant</label>
              <Select value={draft.tenantId ?? ''} onChange={(e) => set('tenantId', e.target.value || null)}>
                {tenantOptions.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
              </Select>
            </div>
            <div><label className={fieldLabel}>Dataset</label>
              <Select value={draft.dataset} onChange={(e) => set('dataset', e.target.value as Dataset)}>
                {DATASETS.map((d) => <option key={d.value} value={d.value}>{d.label}</option>)}
              </Select>
            </div>
            <div><label className={fieldLabel}>Frequency</label>
              <Select value={draft.frequency} onChange={(e) => set('frequency', e.target.value as Frequency)}>
                <option value="DAILY">Daily</option>
                <option value="WEEKLY">Weekly</option>
                <option value="MONTHLY">Monthly</option>
              </Select>
            </div>
            <div className="col-span-2">
              <label className={fieldLabel}>Filters (JSON — leave empty for the whole dataset)</label>
              <textarea value={draft.filtersJson ?? ''} onChange={(e) => set('filtersJson', e.target.value || null)}
                placeholder='{"customerNo":"ARHDEV","destCountry":"US"}'
                className={`${inputCls} font-mono`} rows={3} />
            </div>
            <div><label className={fieldLabel}>Delivery</label>
              <Select
                value={draft.deliveryType === 'EMAIL' ? 'DASHBOARD' : draft.deliveryType}
                onChange={(e) => set('deliveryType', e.target.value as DeliveryType)}
              >
                <option value="DASHBOARD">Dashboard (download here)</option>
                <option value="WEBHOOK">Webhook POST</option>
              </Select>
              {/* G7 — EMAIL delivery was a stub that logged the recipient and
                  marked itself DELIVERED without sending. Hidden from the
                  selector until real SMTP is wired. If a legacy row loads
                  with deliveryType=EMAIL the selector coerces it to DASHBOARD
                  above; save() will then persist DASHBOARD so it stops
                  silently mis-representing itself in the list. */}
              {draft.deliveryType === 'EMAIL' ? (
                <p className="mt-1 text-[10.5px] text-amber-700">
                  Legacy EMAIL delivery is no longer supported — pick a new
                  delivery type before saving. Emails were never actually sent.
                </p>
              ) : null}
            </div>
            <div className="pt-6">
              <label className="inline-flex items-center gap-2 text-[13px]">
                <input type="checkbox" checked={draft.active !== false} onChange={(e) => set('active', e.target.checked)} />
                Active
              </label>
            </div>
            {/* G7 — EMAIL recipients block hidden along with the option. */}
            {draft.deliveryType === 'WEBHOOK' ? (
              <div className="col-span-2">
                <label className={fieldLabel}>Webhook URL</label>
                <input value={draft.deliveryWebhookUrl ?? ''} onChange={(e) => set('deliveryWebhookUrl', e.target.value)} className={inputCls} placeholder="https://api.acme.example/webhooks/reports" />
              </div>
            ) : null}
          </div>
        </div>

        <div className="flex items-center justify-end gap-2 border-t border-slate-200 px-6 py-3">
          <button onClick={onClose} className="rounded-md border border-slate-200 px-3 py-1.5 text-[12.5px] font-semibold text-slate-700 hover:bg-slate-50">Cancel</button>
          <button onClick={save} disabled={saving} className="rounded-md bg-[#1f150c] px-3 py-1.5 text-[12.5px] font-semibold text-white hover:bg-black disabled:opacity-50">{saving ? 'Saving…' : 'Save'}</button>
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

function formatBytes(n: number): string {
  if (n < 1024) return `${n} B`
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`
  return `${(n / 1024 / 1024).toFixed(2)} MB`
}
