import { useCallback, useEffect, useMemo, useState } from 'react'
import { FiPlus, FiRefreshCw, FiTrash2, FiZap } from 'react-icons/fi'
import {
  cutoffService,
  type CutoffRule,
  type Holiday,
} from '../api/cutoffService'
import { notify } from '../utils/notify'

/**
 * G7 — Settings → Cutoffs & Holidays.
 * Two sections on one page:
 *   1. Cutoff rules — (source × carrier × warehouse) matrix with per-row
 *      cutoff_time + timezone. "Seed" button bulk-creates a rule per
 *      (connected warehouse × active carrier × source) combo.
 *   2. Global holiday list — dates the cutoff logic skips when
 *      advancing to the next working day.
 */

const SOURCES = ['MANUAL', 'BULK', 'API', 'WMS', 'DTC']
const CARRIERS = ['FEDEX', 'UPS', 'USPS', 'DHL', 'STAMPS_COM', 'USPS_DIRECT']

export default function CutoffsPage() {
  const [rules, setRules] = useState<CutoffRule[]>([])
  const [holidays, setHolidays] = useState<Holiday[]>([])
  const [loading, setLoading] = useState(false)
  const [seeding, setSeeding] = useState(false)
  const [addingRule, setAddingRule] = useState(false)
  const [addingHoliday, setAddingHoliday] = useState(false)

  // New-rule form state.
  const [newRule, setNewRule] = useState<{
    source: string; carrierCode: string; warehouseId: string;
    cutoffTime: string; timezone: string
  }>({ source: '', carrierCode: '', warehouseId: '', cutoffTime: '20:00', timezone: '' })

  // New-holiday form state.
  const [newHoliday, setNewHoliday] = useState<{ holidayDate: string; name: string }>({
    holidayDate: '', name: '',
  })

  const reload = useCallback(async () => {
    setLoading(true)
    try {
      const [r, h] = await Promise.all([cutoffService.listRules(), cutoffService.listHolidays()])
      setRules(r)
      setHolidays(h)
    } catch (e) {
      notify.apiError(e, 'Failed to load cutoffs.')
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => { void reload() }, [reload])

  const addRule = async () => {
    setAddingRule(true)
    try {
      await cutoffService.createRule({
        source: newRule.source || null,
        carrierCode: newRule.carrierCode || null,
        warehouseId: newRule.warehouseId ? Number(newRule.warehouseId) : null,
        cutoffTime: newRule.cutoffTime + (newRule.cutoffTime.length === 5 ? ':00' : ''),
        timezone: newRule.timezone || null,
        active: true,
      })
      notify.success('Rule added.')
      setNewRule({ source: '', carrierCode: '', warehouseId: '', cutoffTime: '20:00', timezone: '' })
      await reload()
    } catch (e) {
      notify.apiError(e, 'Rule add failed.')
    } finally {
      setAddingRule(false)
    }
  }

  const seed = async () => {
    setSeeding(true)
    try {
      const res = await cutoffService.seedRules()
      notify.success(`Seed: ${res.created} created, ${res.skipped} existed already (${res.warehouses} warehouses × ${res.carriers} carriers × ${res.sources.length} sources).`)
      await reload()
    } catch (e) {
      notify.apiError(e, 'Seed failed.')
    } finally {
      setSeeding(false)
    }
  }

  const toggleRule = async (r: CutoffRule) => {
    try {
      await cutoffService.updateRule(r.id, { active: !r.active })
      await reload()
    } catch (e) {
      notify.apiError(e, 'Toggle failed.')
    }
  }

  const deleteRule = async (r: CutoffRule) => {
    if (!confirm(`Delete rule #${r.id}?`)) return
    try {
      await cutoffService.deleteRule(r.id)
      await reload()
    } catch (e) {
      notify.apiError(e, 'Delete failed.')
    }
  }

  const addHoliday = async () => {
    setAddingHoliday(true)
    try {
      await cutoffService.createHoliday({
        holidayDate: newHoliday.holidayDate,
        name: newHoliday.name,
        active: true,
      })
      notify.success('Holiday added.')
      setNewHoliday({ holidayDate: '', name: '' })
      await reload()
    } catch (e) {
      notify.apiError(e, 'Holiday add failed.')
    } finally {
      setAddingHoliday(false)
    }
  }

  const deleteHoliday = async (h: Holiday) => {
    if (!confirm(`Delete "${h.name}" (${h.holidayDate})?`)) return
    try {
      await cutoffService.deleteHoliday(h.id)
      await reload()
    } catch (e) {
      notify.apiError(e, 'Delete failed.')
    }
  }

  const sortedRules = useMemo(
    () => [...rules].sort((a, b) => a.id - b.id),
    [rules],
  )
  const sortedHolidays = useMemo(
    () => [...holidays].sort((a, b) => a.holidayDate.localeCompare(b.holidayDate)),
    [holidays],
  )

  const inputCls = 'w-full rounded-lg border border-slate-300 bg-white px-2.5 py-1.5 text-[12.5px] focus:border-slate-400 focus:outline-none focus:ring-2 focus:ring-slate-200'

  return (
    <div className="space-y-6 p-5">
      {/* ── Cutoff rules ─────────────────────────────────────────── */}
      <section className="rounded-2xl border border-slate-200 bg-white shadow-sm">
        <header className="flex flex-wrap items-center justify-between gap-2 border-b border-slate-100 p-4">
          <div>
            <h2 className="text-[14px] font-semibold text-slate-900">Cutoff rules</h2>
            <p className="mt-0.5 text-[11.5px] text-slate-500">
              Shipments matching an active rule get their SHIP_DATE pushed to the next
              working day when generated past the cutoff (rule tz) or on a holiday.
            </p>
          </div>
          <div className="flex items-center gap-2">
            <button type="button" onClick={() => void reload()} disabled={loading}
                    className="inline-flex items-center gap-1 rounded-lg border border-slate-200 bg-white px-2.5 py-1.5 text-[12px] font-semibold text-slate-700 hover:bg-slate-50 disabled:opacity-40">
              <FiRefreshCw className={`h-3.5 w-3.5 ${loading ? 'animate-spin' : ''}`} /> Reload
            </button>
            <button type="button" onClick={() => void seed()} disabled={seeding}
                    className="inline-flex items-center gap-1 rounded-lg bg-slate-900 px-2.5 py-1.5 text-[12px] font-semibold text-[var(--e-f4eede)] hover:bg-slate-800 disabled:opacity-40">
              <FiZap className="h-3.5 w-3.5" /> {seeding ? 'Seeding…' : 'Seed for all combos'}
            </button>
          </div>
        </header>

        <table className="w-full text-[12.5px]">
          <thead className="bg-slate-50 text-[10.5px] font-bold uppercase tracking-[0.08em] text-slate-500">
            <tr>
              <th className="px-3 py-2 text-left">#</th>
              <th className="px-3 py-2 text-left">Source</th>
              <th className="px-3 py-2 text-left">Carrier</th>
              <th className="px-3 py-2 text-left">Warehouse</th>
              <th className="px-3 py-2 text-left">Cutoff</th>
              <th className="px-3 py-2 text-left">Timezone</th>
              <th className="px-3 py-2 text-left">Active</th>
              <th className="px-3 py-2 text-right">Actions</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-slate-100">
            {sortedRules.map((r) => (
              <tr key={r.id} className="hover:bg-slate-50/60">
                <td className="px-3 py-2 font-mono text-slate-500">{r.id}</td>
                <td className="px-3 py-2">{r.source ?? <span className="text-slate-400">any</span>}</td>
                <td className="px-3 py-2">{r.carrierCode ?? <span className="text-slate-400">any</span>}</td>
                <td className="px-3 py-2">{r.warehouseId ?? <span className="text-slate-400">any</span>}</td>
                <td className="px-3 py-2 font-mono">{r.cutoffTime}</td>
                <td className="px-3 py-2 text-[11.5px] text-slate-600">{r.timezone ?? <span className="text-slate-400">client tz</span>}</td>
                <td className="px-3 py-2">
                  <button type="button" onClick={() => void toggleRule(r)}
                          className={`rounded-full px-2 py-0.5 text-[10.5px] font-semibold ${r.active ? 'bg-emerald-100 text-emerald-800' : 'bg-slate-200 text-slate-600'}`}>
                    {r.active ? 'active' : 'off'}
                  </button>
                </td>
                <td className="px-3 py-2 text-right">
                  <button type="button" onClick={() => void deleteRule(r)}
                          className="rounded-lg p-1 text-rose-600 hover:bg-rose-50" title="Delete">
                    <FiTrash2 className="h-3.5 w-3.5" />
                  </button>
                </td>
              </tr>
            ))}
            {sortedRules.length === 0 ? (
              <tr><td colSpan={8} className="px-3 py-6 text-center text-[12px] text-slate-400">No rules yet. Click "Seed for all combos" or add one below.</td></tr>
            ) : null}
            {/* Inline add-rule row */}
            <tr className="bg-amber-50/40">
              <td className="px-3 py-2 text-slate-400">new</td>
              <td className="px-3 py-2">
                <select className={inputCls} value={newRule.source} onChange={(e) => setNewRule({ ...newRule, source: e.target.value })}>
                  <option value="">any</option>
                  {SOURCES.map((s) => <option key={s} value={s}>{s}</option>)}
                </select>
              </td>
              <td className="px-3 py-2">
                <select className={inputCls} value={newRule.carrierCode} onChange={(e) => setNewRule({ ...newRule, carrierCode: e.target.value })}>
                  <option value="">any</option>
                  {CARRIERS.map((c) => <option key={c} value={c}>{c}</option>)}
                </select>
              </td>
              <td className="px-3 py-2">
                <input className={inputCls} type="number" placeholder="any" value={newRule.warehouseId}
                       onChange={(e) => setNewRule({ ...newRule, warehouseId: e.target.value })} />
              </td>
              <td className="px-3 py-2">
                <input className={inputCls} type="time" value={newRule.cutoffTime}
                       onChange={(e) => setNewRule({ ...newRule, cutoffTime: e.target.value })} />
              </td>
              <td className="px-3 py-2">
                <input className={inputCls} type="text" placeholder="e.g. America/New_York"
                       value={newRule.timezone} onChange={(e) => setNewRule({ ...newRule, timezone: e.target.value })} />
              </td>
              <td className="px-3 py-2 text-slate-400 text-[11px]">on save</td>
              <td className="px-3 py-2 text-right">
                <button type="button" onClick={() => void addRule()} disabled={addingRule}
                        className="inline-flex items-center gap-1 rounded-lg bg-slate-900 px-2.5 py-1 text-[11.5px] font-semibold text-[var(--e-f4eede)] hover:bg-slate-800 disabled:opacity-40">
                  <FiPlus className="h-3.5 w-3.5" /> Add
                </button>
              </td>
            </tr>
          </tbody>
        </table>
      </section>

      {/* ── Holidays ─────────────────────────────────────────────── */}
      <section className="rounded-2xl border border-slate-200 bg-white shadow-sm">
        <header className="border-b border-slate-100 p-4">
          <h2 className="text-[14px] font-semibold text-slate-900">Global holiday list</h2>
          <p className="mt-0.5 text-[11.5px] text-slate-500">
            Dates the cutoff logic treats as non-working when advancing to the next
            working day. Seeded with Dec 24 + Jan 1 for the current + next year on
            migration; add / remove as needed.
          </p>
        </header>
        <table className="w-full text-[12.5px]">
          <thead className="bg-slate-50 text-[10.5px] font-bold uppercase tracking-[0.08em] text-slate-500">
            <tr>
              <th className="px-3 py-2 text-left">Date</th>
              <th className="px-3 py-2 text-left">Name</th>
              <th className="px-3 py-2 text-left">Active</th>
              <th className="px-3 py-2 text-right">Actions</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-slate-100">
            {sortedHolidays.map((h) => (
              <tr key={h.id} className="hover:bg-slate-50/60">
                <td className="px-3 py-2 font-mono">{h.holidayDate}</td>
                <td className="px-3 py-2">{h.name}</td>
                <td className="px-3 py-2">
                  <span className={`rounded-full px-2 py-0.5 text-[10.5px] font-semibold ${h.active ? 'bg-emerald-100 text-emerald-800' : 'bg-slate-200 text-slate-600'}`}>
                    {h.active ? 'active' : 'off'}
                  </span>
                </td>
                <td className="px-3 py-2 text-right">
                  <button type="button" onClick={() => void deleteHoliday(h)}
                          className="rounded-lg p-1 text-rose-600 hover:bg-rose-50" title="Delete">
                    <FiTrash2 className="h-3.5 w-3.5" />
                  </button>
                </td>
              </tr>
            ))}
            {sortedHolidays.length === 0 ? (
              <tr><td colSpan={4} className="px-3 py-6 text-center text-[12px] text-slate-400">No holidays configured.</td></tr>
            ) : null}
            <tr className="bg-amber-50/40">
              <td className="px-3 py-2">
                <input className={inputCls} type="date" value={newHoliday.holidayDate}
                       onChange={(e) => setNewHoliday({ ...newHoliday, holidayDate: e.target.value })} />
              </td>
              <td className="px-3 py-2">
                <input className={inputCls} type="text" placeholder="e.g. Independence Day"
                       value={newHoliday.name} onChange={(e) => setNewHoliday({ ...newHoliday, name: e.target.value })} />
              </td>
              <td className="px-3 py-2 text-slate-400 text-[11px]">on save</td>
              <td className="px-3 py-2 text-right">
                <button type="button" onClick={() => void addHoliday()}
                        disabled={addingHoliday || !newHoliday.holidayDate || !newHoliday.name.trim()}
                        className="inline-flex items-center gap-1 rounded-lg bg-slate-900 px-2.5 py-1 text-[11.5px] font-semibold text-[var(--e-f4eede)] hover:bg-slate-800 disabled:opacity-40">
                  <FiPlus className="h-3.5 w-3.5" /> Add
                </button>
              </td>
            </tr>
          </tbody>
        </table>
      </section>
    </div>
  )
}
