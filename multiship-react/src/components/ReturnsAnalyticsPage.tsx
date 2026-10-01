/**
 * Returns F11 payoff — /settings/returns-analytics admin page.
 *
 * Reason-by-week table of return counts, grouped from label_batch.
 * Backend returns a flat list of (weekStart, reason, count); this
 * component pivots client-side (small dataset — at most 52 weeks × 6
 * reasons = 312 rows).
 */
import { useCallback, useEffect, useMemo, useState } from 'react'
import { useOutletContext } from 'react-router-dom'
import type { SettingsOutletContext } from './layout/SettingsLayout'
import { notify } from '../utils/notify'
import {
  returnsAnalyticsService,
  type ReasonRollupRow,
} from '../api/returnsAnalyticsService'

const REASON_ORDER = [
  'WRONG_ITEM',
  'DEFECTIVE',
  'NO_LONGER_NEEDED',
  'SIZE',
  'OTHER',
  'UNKNOWN',
] as const

const REASON_LABEL: Record<string, string> = {
  WRONG_ITEM: 'Wrong item',
  DEFECTIVE: 'Defective',
  NO_LONGER_NEEDED: 'No longer needed',
  SIZE: 'Size',
  OTHER: 'Other',
  UNKNOWN: 'Unknown',
}

const WEEKS_OPTIONS = [4, 12, 26, 52] as const

export default function ReturnsAnalyticsPage() {
  const outlet = useOutletContext<SettingsOutletContext>()
  const [weeks, setWeeks] = useState<number>(12)
  const [rows, setRows] = useState<ReasonRollupRow[]>([])
  const [sinceDate, setSinceDate] = useState<string>('')
  const [loading, setLoading] = useState(true)

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const res = await returnsAnalyticsService.reasonRollup(weeks)
      setRows(res.items)
      setSinceDate(res.sinceDate)
    } catch (err) {
      notify.apiError(err, 'Failed to load returns analytics.')
    } finally {
      setLoading(false)
    }
  }, [weeks])

  useEffect(() => { void load() }, [load])

  useEffect(() => {
    outlet.registerRefresh(load)
    return () => outlet.registerRefresh(null)
  }, [outlet, load])

  const { weekStarts, pivot, totals, grandTotal } = useMemo(() => {
    const starts = Array.from(new Set(rows.map((r) => r.weekStart)))
    // Backend already returns weekStart DESC — preserve.
    const pivotMap = new Map<string, Map<string, number>>()
    for (const r of rows) {
      let m = pivotMap.get(r.weekStart)
      if (!m) { m = new Map(); pivotMap.set(r.weekStart, m) }
      m.set(r.reason, (m.get(r.reason) ?? 0) + r.count)
    }
    const tot: Record<string, number> = {}
    for (const reason of REASON_ORDER) tot[reason] = 0
    let grand = 0
    for (const r of rows) {
      tot[r.reason] = (tot[r.reason] ?? 0) + r.count
      grand += r.count
    }
    return { weekStarts: starts, pivot: pivotMap, totals: tot, grandTotal: grand }
  }, [rows])

  return (
    <div className="space-y-3">
      <section className="rounded-xl border border-slate-200 bg-white p-3 shadow-sm">
        <div className="flex items-end justify-between gap-3">
          <label className="text-[12px] font-semibold text-slate-700">
            Lookback window
            <select
              value={weeks}
              onChange={(e) => setWeeks(Number(e.target.value))}
              className="ml-2 rounded-lg border border-slate-300 px-3 py-1.5 text-[13px]"
            >
              {WEEKS_OPTIONS.map((w) => (
                <option key={w} value={w}>Last {w} weeks</option>
              ))}
            </select>
          </label>
          <div className="text-right text-[11px] text-slate-500">
            {sinceDate ? <>Since {sinceDate}</> : null}
            <div className="font-mono text-[14px] font-bold text-slate-800">
              {grandTotal.toLocaleString()} returns
            </div>
          </div>
        </div>
      </section>

      <section className="overflow-x-auto rounded-xl border border-slate-200 bg-white shadow-sm">
        {loading ? (
          <div className="px-4 py-8 text-center text-[12px] text-slate-500">Loading…</div>
        ) : weekStarts.length === 0 ? (
          <div className="px-4 py-8 text-center text-[12px] text-slate-500">
            No returns in the selected window.
          </div>
        ) : (
          <table className="min-w-full border-collapse text-[12px]">
            <thead>
              <tr className="bg-slate-50 text-left text-[11px] font-semibold uppercase tracking-wide text-slate-600">
                <th className="px-3 py-2">Week starting</th>
                {REASON_ORDER.map((reason) => (
                  <th key={reason} className="px-3 py-2 text-right">{REASON_LABEL[reason]}</th>
                ))}
                <th className="px-3 py-2 text-right font-bold">Total</th>
              </tr>
            </thead>
            <tbody>
              {weekStarts.map((week) => {
                const row = pivot.get(week) ?? new Map<string, number>()
                let weekTotal = 0
                row.forEach((v) => { weekTotal += v })
                return (
                  <tr key={week} className="border-t border-slate-100 hover:bg-slate-50/50">
                    <td className="px-3 py-1.5 font-mono text-slate-700">{week}</td>
                    {REASON_ORDER.map((reason) => (
                      <td key={reason} className="px-3 py-1.5 text-right font-mono tabular-nums text-slate-700">
                        {row.get(reason) ?? 0}
                      </td>
                    ))}
                    <td className="px-3 py-1.5 text-right font-mono font-bold tabular-nums text-slate-900">
                      {weekTotal}
                    </td>
                  </tr>
                )
              })}
              <tr className="border-t-2 border-slate-300 bg-slate-50 font-bold">
                <td className="px-3 py-2 text-slate-800">Totals</td>
                {REASON_ORDER.map((reason) => (
                  <td key={reason} className="px-3 py-2 text-right font-mono tabular-nums text-slate-900">
                    {(totals[reason] ?? 0).toLocaleString()}
                  </td>
                ))}
                <td className="px-3 py-2 text-right font-mono tabular-nums text-slate-900">
                  {grandTotal.toLocaleString()}
                </td>
              </tr>
            </tbody>
          </table>
        )}
      </section>
    </div>
  )
}
