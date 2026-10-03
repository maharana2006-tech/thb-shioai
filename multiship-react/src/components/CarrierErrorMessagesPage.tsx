/**
 * V118 — /settings/carrier-error-messages. Read-only view of the pattern
 * → humanized sentence rules CarrierErrorMessages.humanize applies to raw
 * carrier payloads. Edit via SQL today; inline editor deferred.
 */
import { useCallback, useEffect, useState } from 'react'
import { useOutletContext } from 'react-router-dom'
import type { SettingsOutletContext } from './layout/SettingsLayout'
import { notify } from '../utils/notify'
import {
  carrierErrorMessagesService,
  type CarrierErrorMessageRow,
} from '../api/carrierErrorMessagesService'

export default function CarrierErrorMessagesPage() {
  const outlet = useOutletContext<SettingsOutletContext>()

  const [rows, setRows] = useState<CarrierErrorMessageRow[]>([])
  const [loading, setLoading] = useState(true)

  const load = useCallback(async () => {
    setLoading(true)
    try { setRows(await carrierErrorMessagesService.list()) }
    catch (err) { notify.apiError(err, 'Failed to load carrier error messages.') }
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
          No rules seeded. V118 seeds 5 patterns (NOTSERVED, PHONENUMBER, UNAUTHORIZED, POSTAL, CUSTOMS).
        </div>
      ) : (
        <table className="w-full text-[13px]">
          <thead>
            <tr className="text-left text-slate-500">
              <th className="px-3 py-2 font-semibold">#</th>
              <th className="px-3 py-2 font-semibold">Carrier</th>
              <th className="px-3 py-2 font-semibold">Match any of</th>
              <th className="px-3 py-2 font-semibold">Humanized message</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((row) => (
              <tr key={row.id} className="border-t border-slate-100 align-top">
                <td className="px-3 py-2 tabular-nums text-slate-500">{row.sortOrder ?? '—'}</td>
                <td className="px-3 py-2 font-mono text-[12px] text-slate-700">
                  {row.carrier ?? <span className="italic text-slate-400">any</span>}
                </td>
                <td className="px-3 py-2 font-mono text-[11.5px] text-slate-700">
                  {row.matchAnyOf.split('|').map((tok) => (
                    <span key={tok} className="mr-1 inline-block rounded bg-slate-100 px-1.5 py-0.5 text-slate-700">
                      {tok}
                    </span>
                  ))}
                </td>
                <td className="px-3 py-2 text-slate-700">{row.humanized}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  )
}
