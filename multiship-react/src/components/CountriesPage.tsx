/**
 * V111 — /settings/countries. Read-only view of the country table +
 * is_us_territory flag; edit via SQL today (admin UI deferred).
 */
import { useCallback, useEffect, useState } from 'react'
import { useOutletContext } from 'react-router-dom'
import type { SettingsOutletContext } from './layout/SettingsLayout'
import { notify } from '../utils/notify'
import { countriesService, type CountryRow } from '../api/countriesService'

export default function CountriesPage() {
  const outlet = useOutletContext<SettingsOutletContext>()

  const [rows, setRows] = useState<CountryRow[]>([])
  const [loading, setLoading] = useState(true)

  const load = useCallback(async () => {
    setLoading(true)
    try { setRows(await countriesService.list()) }
    catch (err) { notify.apiError(err, 'Failed to load countries.') }
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
          No country rows seeded. V111 seeds the six US-territory codes; broader ISO-3166 fill is a later migration.
        </div>
      ) : (
        <table className="w-full text-[13px]">
          <thead>
            <tr className="text-left text-slate-500">
              <th className="px-3 py-2 font-semibold">Code</th>
              <th className="px-3 py-2 font-semibold">Name</th>
              <th className="px-3 py-2 font-semibold">US territory</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((row) => (
              <tr key={row.countryCode} className="border-t border-slate-100">
                <td className="px-3 py-2 font-mono text-[12px] text-slate-700">{row.countryCode}</td>
                <td className="px-3 py-2 text-slate-700">{row.name ?? '—'}</td>
                <td className="px-3 py-2">
                  {row.isUsTerritory ? (
                    <span className="rounded bg-amber-100 px-2 py-0.5 text-amber-800">yes</span>
                  ) : (
                    <span className="rounded bg-slate-100 px-2 py-0.5 text-slate-700">no</span>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  )
}
