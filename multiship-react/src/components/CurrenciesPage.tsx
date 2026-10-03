/**
 * V117 — /settings/currencies. Read-only view of the iso_currency table;
 * edit via SQL today (inline add/edit deferred). Per-tenant allowlist
 * will layer on top via tenant_settings.currency.allowlist (M3-adjacent).
 */
import { useCallback, useEffect, useState } from 'react'
import { useOutletContext } from 'react-router-dom'
import type { SettingsOutletContext } from './layout/SettingsLayout'
import { notify } from '../utils/notify'
import { refdataService, type IsoCurrencyRow } from '../api/refdataService'

export default function CurrenciesPage() {
  const outlet = useOutletContext<SettingsOutletContext>()

  const [rows, setRows] = useState<IsoCurrencyRow[]>([])
  const [loading, setLoading] = useState(true)

  const load = useCallback(async () => {
    setLoading(true)
    try { setRows(await refdataService.currencies()) }
    catch (err) { notify.apiError(err, 'Failed to load currencies.') }
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
        <div className="px-4 py-6 text-center text-[13px] text-slate-500">
          No currencies seeded. V117 seeds 10 codes (USD/EUR/GBP/CAD/INR/AUD/SGD/JPY/CNY/AED).
        </div>
      ) : (
        <table className="w-full text-[13px]">
          <thead>
            <tr className="text-left text-slate-500">
              <th className="px-3 py-2 font-semibold">Code</th>
              <th className="px-3 py-2 font-semibold">Name</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((row) => (
              <tr key={row.code} className="border-t border-slate-100">
                <td className="px-3 py-2 font-mono text-[12px] text-slate-700">{row.code}</td>
                <td className="px-3 py-2 text-slate-700">{row.name ?? '—'}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  )
}
