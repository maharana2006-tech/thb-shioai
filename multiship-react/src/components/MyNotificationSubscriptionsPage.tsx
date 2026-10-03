/**
 * A4.5 — /settings/notifications self-serve page.
 *
 * Every authenticated user sees ONLY the templates whose
 * opt_out_allowed is true. Transactional templates (invite, verify,
 * password reset) are never listed because opting out of your own
 * password reset would be a footgun.
 */
import { useCallback, useEffect, useState } from 'react'
import { useOutletContext } from 'react-router-dom'
import type { SettingsOutletContext } from './layout/SettingsLayout'
import { notify } from '../utils/notify'
import {
  mySubscriptionsService,
  type MyNotificationSubscription,
} from '../api/mySubscriptionsService'

export default function MyNotificationSubscriptionsPage() {
  const outlet = useOutletContext<SettingsOutletContext>()

  const [rows, setRows] = useState<MyNotificationSubscription[]>([])
  const [loading, setLoading] = useState(true)
  const [pending, setPending] = useState<Set<string>>(new Set())

  const load = useCallback(async () => {
    setLoading(true)
    try {
      setRows(await mySubscriptionsService.list())
    } catch (err) {
      notify.apiError(err, 'Failed to load notification preferences.')
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    void load()
  }, [load])

  useEffect(() => {
    outlet.registerRefresh(load)
    return () => outlet.registerRefresh(null)
  }, [outlet, load])

  const toggle = async (row: MyNotificationSubscription) => {
    const next = !row.subscribed
    setPending((s) => new Set(s).add(row.templateKey))
    // Optimistic — flip locally; roll back on error.
    setRows((rs) => rs.map((r) => r.templateKey === row.templateKey ? { ...r, subscribed: next } : r))
    try {
      await mySubscriptionsService.set(row.templateKey, next)
    } catch (err) {
      setRows((rs) => rs.map((r) => r.templateKey === row.templateKey ? { ...r, subscribed: row.subscribed } : r))
      notify.apiError(err, 'Failed to update preference.')
    } finally {
      setPending((s) => {
        const copy = new Set(s)
        copy.delete(row.templateKey)
        return copy
      })
    }
  }

  return (
    <div className="space-y-3">
      <section className="rounded-xl border border-slate-200 bg-white p-4 shadow-sm">
        <div className="text-[13px] font-semibold text-slate-800">Notification preferences</div>
        <p className="mt-1 text-[12px] text-slate-500">
          Turn off any alert-style email you don't want to receive. Transactional emails
          (invites, verification, password reset) always send.
        </p>
      </section>

      <section className="rounded-xl border border-slate-200 bg-white shadow-sm">
        {loading ? (
          <div className="px-4 py-6 text-center text-[13px] text-slate-500">Loading…</div>
        ) : rows.length === 0 ? (
          <div className="px-4 py-8 text-center text-[13px] text-slate-500">
            No opt-out-able notifications configured yet. An admin can mark a template as
            opt-out-allowed at <span className="font-mono">/settings/notification-templates</span>.
          </div>
        ) : (
          <ul className="divide-y divide-slate-100">
            {rows.map((row) => (
              <li key={row.templateKey} className="flex items-start justify-between gap-4 px-4 py-3">
                <div className="min-w-0">
                  <div className="font-mono text-[12px] font-semibold text-slate-800">{row.templateKey}</div>
                  {row.description && (
                    <div className="mt-0.5 text-[12px] text-slate-500">{row.description}</div>
                  )}
                </div>
                <label className="flex shrink-0 items-center gap-2 text-[12px] font-semibold">
                  <span className={row.subscribed ? 'text-emerald-700' : 'text-slate-500'}>
                    {row.subscribed ? 'On' : 'Off'}
                  </span>
                  <input
                    type="checkbox"
                    checked={row.subscribed}
                    disabled={pending.has(row.templateKey)}
                    onChange={() => void toggle(row)}
                    className="h-5 w-5 rounded border-slate-400 text-emerald-600 focus:ring-emerald-500"
                  />
                </label>
              </li>
            ))}
          </ul>
        )}
      </section>
    </div>
  )
}
