/**
 * V117 — /settings/reasons-for-export. Read-only view of the reason-for-
 * export registry; edit via SQL today (inline edit deferred).
 */
import { useCallback, useEffect, useState } from 'react'
import { useOutletContext } from 'react-router-dom'
import type { SettingsOutletContext } from './layout/SettingsLayout'
import { notify } from '../utils/notify'
import { refdataService, type ReasonForExportRow } from '../api/refdataService'

export default function ReasonsForExportPage() {
  const outlet = useOutletContext<SettingsOutletContext>()

  const [rows, setRows] = useState<ReasonForExportRow[]>([])
  const [loading, setLoading] = useState(true)

  const load = useCallback(async () => {
    setLoading(true)
    try { setRows(await refdataService.reasons()) }
    catch (err) { notify.apiError(err, 'Failed to load reasons for export.') }
    finally { setLoading(false) }
  }, [])

  useEffect(() => { void load() }, [load])
  useEffect(() => {
    outlet.registerRefresh(load)
    return () => outlet.registerRefresh(null)
  }, [outlet, load])

  return (
    <section className="rounded-xl border border-slate-200 bg-white shadow-sm">
      {loading ? (
        <div className="px-4 py-6 text-center text-[13px] text-slate-500">Loading…</div>
      ) : rows.length === 0 ? (
        <div className="px-4 py-6 text-center text-[13px] text-slate-500">No reasons seeded.</div>
      ) : (
        <table className="w-full text-[13px]">
          <thead>
            <tr className="text-left text-slate-500">
              <th className="px-3 py-2 font-semibold">Code</th>
              <th className="px-3 py-2 font-semibold">Label</th>
              <th className="px-3 py-2 text-right font-semibold">Sort</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((row) => (
              <tr key={row.code} className="border-t border-slate-100">
                <td className="px-3 py-2 font-mono text-[12px] text-slate-700">{row.code}</td>
                <td className="px-3 py-2 text-slate-700">{row.label}</td>
                <td className="px-3 py-2 text-right tabular-nums text-slate-500">{row.sortOrder ?? '—'}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  )
}
