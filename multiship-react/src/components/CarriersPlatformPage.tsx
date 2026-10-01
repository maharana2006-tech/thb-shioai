/**
 * V112 — /settings/carriers-platform admin page.
 *
 * Platform-wide carrier registry. Flip enabled org-wide to kill-switch
 * a carrier without a redeploy; mode LIVE/TEST is reserved for a future
 * org-wide sandbox switch (per-account env on carrier_account_ref stays
 * authoritative today).
 */
import { useCallback, useEffect, useState } from 'react'
import { useOutletContext } from 'react-router-dom'
import type { SettingsOutletContext } from './layout/SettingsLayout'
import { notify } from '../utils/notify'
import {
  carriersPlatformService,
  type CarrierMode,
  type CarrierPlatformRow,
} from '../api/carriersPlatformService'

export default function CarriersPlatformPage() {
  const outlet = useOutletContext<SettingsOutletContext>()

  const [rows, setRows] = useState<CarrierPlatformRow[]>([])
  const [loading, setLoading] = useState(true)
  const [busyCode, setBusyCode] = useState<string | null>(null)

  const load = useCallback(async () => {
    setLoading(true)
    try {
      setRows(await carriersPlatformService.list())
    } catch (err) {
      notify.apiError(err, 'Failed to load carrier platform rows.')
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => { void load() }, [load])
  useEffect(() => {
    outlet.registerRefresh(load)
    return () => outlet.registerRefresh(null)
  }, [outlet, load])

  const toggleEnabled = async (row: CarrierPlatformRow) => {
    if (!(await notify.confirm(`${row.enabled ? 'Disable' : 'Enable'} '${row.carrierCode}' org-wide?`))) return
    setBusyCode(row.carrierCode)
    try {
      await carriersPlatformService.update(row.carrierCode, !row.enabled, undefined)
      notify.success(`'${row.carrierCode}' is now ${row.enabled ? 'disabled' : 'enabled'}.`)
      await load()
    } catch (err) {
      notify.apiError(err, 'Could not change carrier state.')
    } finally {
      setBusyCode(null)
    }
  }

  const setMode = async (row: CarrierPlatformRow, mode: CarrierMode) => {
    if (mode === row.mode) return
    setBusyCode(row.carrierCode)
    try {
      await carriersPlatformService.update(row.carrierCode, undefined, mode)
      await load()
    } catch (err) {
      notify.apiError(err, 'Could not change mode.')
    } finally {
      setBusyCode(null)
    }
  }

  return (
    <section className="rounded-xl border border-slate-200 bg-white shadow-sm">
      {loading ? (
        <div className="px-4 py-6 text-center text-[13px] text-slate-500">Loading…</div>
      ) : rows.length === 0 ? (
        <div className="px-4 py-6 text-center text-[13px] text-slate-500">No carriers seeded.</div>
      ) : (
        <table className="w-full text-[13px]">
          <thead>
            <tr className="text-left text-slate-500">
              <th className="px-3 py-2 font-semibold">Code</th>
              <th className="px-3 py-2 font-semibold">Display name</th>
              <th className="px-3 py-2 font-semibold">Family</th>
              <th className="px-3 py-2 font-semibold">Mode</th>
              <th className="px-3 py-2 font-semibold">Enabled</th>
              <th className="px-3 py-2 text-right font-semibold">Actions</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((row) => (
              <tr key={row.carrierCode} className="border-t border-slate-100 hover:bg-slate-50">
                <td className="px-3 py-2 font-mono text-[12px] text-slate-700">{row.carrierCode}</td>
                <td className="px-3 py-2 text-slate-700">{row.displayName ?? '—'}</td>
                <td className="px-3 py-2 text-slate-600">
                  {row.family ? (
                    <span className="rounded bg-slate-100 px-2 py-0.5 text-[11px] font-semibold text-slate-700">
                      {row.family}
                    </span>
                  ) : '—'}
                </td>
                <td className="px-3 py-2">
                  <select
                    value={row.mode}
                    disabled={busyCode === row.carrierCode}
                    onChange={(e) => void setMode(row, e.target.value as CarrierMode)}
                    className="rounded border border-slate-300 px-2 py-1 text-[12px]"
                  >
                    <option value="LIVE">LIVE</option>
                    <option value="TEST">TEST</option>
                  </select>
                </td>
                <td className="px-3 py-2">
                  {row.enabled ? (
                    <span className="rounded bg-emerald-100 px-2 py-0.5 text-emerald-800">enabled</span>
                  ) : (
                    <span className="rounded bg-rose-100 px-2 py-0.5 text-rose-800">disabled</span>
                  )}
                </td>
                <td className="px-3 py-2 text-right">
                  <button
                    type="button"
                    disabled={busyCode === row.carrierCode}
                    onClick={() => void toggleEnabled(row)}
                    className="rounded border border-slate-300 px-2 py-1 text-[12px] hover:bg-white disabled:opacity-60"
                  >
                    {row.enabled ? 'Disable' : 'Enable'}
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  )
}
