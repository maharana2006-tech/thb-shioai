import { useCallback, useEffect, useMemo, useState } from 'react'
import { FiClock, FiList, FiPlay, FiPlus, FiRefreshCw, FiRotateCcw, FiTrash2, FiX } from 'react-icons/fi'
import {
  dtcSchedulerService,
  type DayCode,
  type SchedulerJob,
  type SchedulerOverview,
  type SchedulerRun,
  type SchedulerWindow,
} from '../api/dtcSchedulerService'
import { notify } from '../utils/notify'

/**
 * V132 — Settings → Integrations → DTC Scheduler.
 * One card per background job (Oracle DTC sync today). Each job runs on its windows: days × time range × every N
 * minutes, or once a day at a set time. Saved to the DB; the backend engine
 * re-reads it every minute, so no restart is needed.
 */

const DAYS: DayCode[] = ['MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT', 'SUN']
const WEEKDAYS: DayCode[] = ['MON', 'TUE', 'WED', 'THU', 'FRI']
const POLL_MS = 30_000

type JobDraft = Pick<SchedulerJob, 'enabled' | 'params' | 'windows'>

const draftOf = (j: SchedulerJob): JobDraft => ({
  enabled: j.enabled,
  params: { ...j.params },
  windows: j.windows.map((w) => ({ ...w, days: [...w.days] })),
})

const sameDraft = (a: JobDraft, b: JobDraft) => JSON.stringify(a) === JSON.stringify(b)

const isDaily = (w: SchedulerWindow) => w.runAt != null

const newWindow = (): SchedulerWindow => ({
  id: null, label: '', days: [...WEEKDAYS], startTime: '08:00', endTime: '17:59',
  intervalMinutes: 15, runAt: null, enabled: true,
})

/** "every 5 min, 06:00–11:59" / "daily at 21:00" */
const describe = (w: SchedulerWindow) =>
  isDaily(w)
    ? `once at ${w.runAt}`
    : `every ${w.intervalMinutes} min, ${w.startTime}–${w.endTime}`

const fmtDateTime = (iso: string | null) => {
  if (!iso) return '—'
  const d = new Date(iso)
  return Number.isNaN(d.getTime()) ? iso : d.toLocaleString()
}

/** Next-run label in the scheduler's own zone ("Mon 14:05"), read straight off the ISO string. */
const fmtNextRun = (iso: string) => {
  const m = iso.match(/^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})/)
  if (!m) return iso
  const day = new Date(Date.UTC(+m[1], +m[2] - 1, +m[3])).toLocaleDateString(undefined, { weekday: 'short', timeZone: 'UTC' })
  return `${day} ${m[4]}:${m[5]}`
}

const fmtDuration = (ms: number | null) => {
  if (ms == null) return ''
  if (ms < 1000) return `${ms} ms`
  return ms < 60_000 ? `${(ms / 1000).toFixed(1)} s` : `${Math.round(ms / 60_000)} min`
}

const statusCls = (s: string | null) => {
  switch (s) {
    case 'SUCCESS': return 'bg-emerald-100 text-emerald-800'
    case 'FAILED': return 'bg-rose-100 text-rose-800'
    case 'RUNNING': return 'bg-sky-100 text-sky-800'
    case 'SKIPPED': return 'bg-amber-100 text-amber-800'
    default: return 'bg-slate-100 text-slate-600'
  }
}

const zoneNames: string[] = (() => {
  try {
    return (Intl as unknown as { supportedValuesOf?: (k: string) => string[] }).supportedValuesOf?.('timeZone') ?? []
  } catch {
    return []
  }
})()

const toMin = (t: string | null) => {
  if (!t) return 0
  const [h, m] = t.split(':').map(Number)
  return h * 60 + m
}

/**
 * Week strip: for each day and hour, the shortest interval any enabled window
 * runs at (0 = a daily run in that hour, null = nothing). Same rules as the
 * backend: a window's days are the days the run happens on.
 */
const weekGrid = (windows: SchedulerWindow[]) =>
  DAYS.map((day) =>
    Array.from({ length: 24 }, (_, hour) => {
      let best: number | null = null
      for (const w of windows) {
        if (!w.enabled || !w.days.includes(day)) continue
        if (isDaily(w)) {
          if (Math.floor(toMin(w.runAt) / 60) === hour && best == null) best = 0
          continue
        }
        const s = toMin(w.startTime)
        const e = toMin(w.endTime)
        const h0 = hour * 60
        const h1 = h0 + 59
        const overlaps = s <= e ? h1 >= s && h0 <= e : h1 >= s || h0 <= e
        if (overlaps && w.intervalMinutes && (best == null || best === 0 || w.intervalMinutes < best)) {
          best = w.intervalMinutes
        }
      }
      return best
    }),
  )

const cellCls = (v: number | null) => {
  if (v == null) return 'bg-slate-100'
  if (v === 0) return 'bg-violet-400'
  if (v <= 1) return 'bg-emerald-700'
  if (v <= 5) return 'bg-emerald-500'
  if (v <= 15) return 'bg-emerald-300'
  return 'bg-emerald-200'
}

const inputCls = 'w-full rounded-lg border border-slate-300 bg-white px-2.5 py-1.5 text-[12.5px] focus:border-slate-400 focus:outline-none focus:ring-2 focus:ring-slate-200'
const btnCls = 'inline-flex items-center gap-1 rounded-lg border border-slate-200 bg-white px-2.5 py-1.5 text-[12px] font-semibold text-slate-700 hover:bg-slate-50 disabled:opacity-40'
const primaryCls = 'inline-flex items-center gap-1 rounded-lg bg-slate-900 px-2.5 py-1.5 text-[12px] font-semibold text-[#f4eede] hover:bg-slate-800 disabled:opacity-40'

function Toggle({ on, onChange, disabled, label }: { on: boolean; onChange: (v: boolean) => void; disabled?: boolean; label: string }) {
  return (
    <button type="button" role="switch" aria-checked={on} aria-label={label} disabled={disabled}
            onClick={() => onChange(!on)}
            className={`relative inline-flex h-5 w-9 shrink-0 items-center rounded-full transition-colors disabled:opacity-40 ${on ? 'bg-emerald-600' : 'bg-slate-300'}`}>
      <span className={`inline-block h-4 w-4 rounded-full bg-white shadow transition-transform ${on ? 'translate-x-4' : 'translate-x-0.5'}`} />
    </button>
  )
}

export default function DtcSchedulerPage() {
  const [data, setData] = useState<SchedulerOverview | null>(null)
  const [drafts, setDrafts] = useState<Record<string, JobDraft>>({})
  const [loading, setLoading] = useState(false)
  const [savingKey, setSavingKey] = useState<string | null>(null)
  const [tzDraft, setTzDraft] = useState('')
  const [historyJob, setHistoryJob] = useState<SchedulerJob | null>(null)

  const apply = useCallback((o: SchedulerOverview, keepDirty: boolean) => {
    setData(o)
    setTzDraft(o.settings.timezone ?? '')
    setDrafts((prev) => {
      const next: Record<string, JobDraft> = {}
      for (const j of o.jobs) {
        const old = prev[j.jobKey]
        const server = draftOf(j)
        // A background refresh must not wipe an edit the admin hasn't saved yet.
        next[j.jobKey] = keepDirty && old && !sameDraft(old, server) ? old : server
      }
      return next
    })
  }, [])

  const reload = useCallback(async (keepDirty = true) => {
    setLoading(true)
    try {
      apply(await dtcSchedulerService.overview(), keepDirty)
    } catch (e) {
      notify.apiError(e, 'Failed to load the scheduler.')
    } finally {
      setLoading(false)
    }
  }, [apply])

  useEffect(() => { void reload(false) }, [reload])

  // Keep last-run / next-run fresh without touching unsaved edits.
  useEffect(() => {
    const t = window.setInterval(() => {
      dtcSchedulerService.overview().then((o) => apply(o, true)).catch(() => {})
    }, POLL_MS)
    return () => window.clearInterval(t)
  }, [apply])

  const serverJobs = useMemo(() => new Map((data?.jobs ?? []).map((j) => [j.jobKey, j])), [data])
  const dirtyKeys = useMemo(
    () => Object.keys(drafts).filter((k) => {
      const j = serverJobs.get(k)
      return j && !sameDraft(drafts[k], draftOf(j))
    }),
    [drafts, serverJobs],
  )

  useEffect(() => {
    if (dirtyKeys.length === 0) return
    const warn = (e: BeforeUnloadEvent) => { e.preventDefault() }
    window.addEventListener('beforeunload', warn)
    return () => window.removeEventListener('beforeunload', warn)
  }, [dirtyKeys.length])

  const setDraft = (key: string, patch: Partial<JobDraft>) =>
    setDrafts((d) => ({ ...d, [key]: { ...d[key], ...patch } }))

  const setWindow = (key: string, idx: number, patch: Partial<SchedulerWindow>) =>
    setDrafts((d) => ({
      ...d,
      [key]: { ...d[key], windows: d[key].windows.map((w, i) => (i === idx ? { ...w, ...patch } : w)) },
    }))

  const saveJob = async (key: string) => {
    setSavingKey(key)
    try {
      await dtcSchedulerService.updateJob(key, drafts[key])
      notify.success('Saved. The new timings apply from the next minute.')
      const o = await dtcSchedulerService.overview()
      setData(o)
      setDrafts((d) => {
        const j = o.jobs.find((x) => x.jobKey === key)
        return j ? { ...d, [key]: draftOf(j) } : d
      })
    } catch (e) {
      notify.apiError(e, 'Save failed.')
    } finally {
      setSavingKey(null)
    }
  }

  const discardJob = (key: string) => {
    const j = serverJobs.get(key)
    if (j) setDrafts((d) => ({ ...d, [key]: draftOf(j) }))
  }

  const updateSettings = async (req: { enabled?: boolean; timezone?: string }) => {
    try {
      await dtcSchedulerService.updateSettings(req)
      notify.success(req.enabled === undefined ? 'Time zone saved.' : req.enabled ? 'Scheduler turned on.' : 'Scheduler turned off.')
      await reload()
    } catch (e) {
      notify.apiError(e, 'Save failed.')
    }
  }

  const runNow = async (j: SchedulerJob) => {
    try {
      await dtcSchedulerService.runNow(j.jobKey)
      notify.success(`${j.name} started.`)
      window.setTimeout(() => { void reload() }, 3000)
    } catch (e) {
      notify.apiError(e, 'Could not start the job.')
    }
  }

  const resetDefaults = async () => {
    if (!confirm('Restore the ShipXSync default timings on every job? Unsaved edits are lost. The master switch and time zone are kept.')) return
    try {
      apply(await dtcSchedulerService.resetDefaults(), false)
      notify.success('Defaults restored.')
    } catch (e) {
      notify.apiError(e, 'Reset failed.')
    }
  }

  const settings = data?.settings
  const masterOn = !!settings?.enabled
  const zoneLabel = settings?.timezone || `server time (${settings?.serverTimezone ?? '…'})`

  return (
    <div className="space-y-6 p-5">
      {/* ── Master settings ─────────────────────────────────────────── */}
      <section className="rounded-2xl border border-slate-200 bg-white shadow-sm">
        <header className="flex flex-wrap items-center justify-between gap-3 p-4">
          <div className="flex items-center gap-3">
            <Toggle on={masterOn} disabled={!settings} label="Scheduler master switch"
                    onChange={(v) => void updateSettings({ enabled: v })} />
            <div>
              <h2 className="text-[14px] font-semibold text-slate-900">
                Scheduler is {masterOn ? 'on' : 'off'}
              </h2>
              <p className="mt-0.5 text-[11.5px] text-slate-500">
                {masterOn
                  ? 'Jobs run on the windows below. Run now works either way.'
                  : 'No job runs on its own while this is off. Run now still works for testing.'}
              </p>
            </div>
          </div>
          <div className="flex flex-wrap items-end gap-2">
            <label className="text-[11px] font-semibold text-slate-500">
              Time zone
              <div className="mt-0.5 flex gap-1">
                <input className={`${inputCls} w-56`} list="dtc-scheduler-zones" value={tzDraft}
                       placeholder={`Server time (${settings?.serverTimezone ?? ''})`}
                       onChange={(e) => setTzDraft(e.target.value)} />
                <button type="button" className={btnCls}
                        disabled={!settings || tzDraft === (settings.timezone ?? '')}
                        onClick={() => void updateSettings({ timezone: tzDraft })}>
                  Save
                </button>
              </div>
              <datalist id="dtc-scheduler-zones">
                {zoneNames.map((z) => <option key={z} value={z} />)}
              </datalist>
            </label>
            <button type="button" onClick={() => void reload()} disabled={loading} className={btnCls}>
              <FiRefreshCw className={`h-3.5 w-3.5 ${loading ? 'animate-spin' : ''}`} /> Reload
            </button>
            <button type="button" onClick={() => void resetDefaults()} disabled={!data} className={btnCls}>
              <FiRotateCcw className="h-3.5 w-3.5" /> Reset to defaults
            </button>
          </div>
        </header>
      </section>

      {!data && loading ? (
        <p className="text-center text-[12px] text-slate-400">Loading…</p>
      ) : null}

      {/* ── One card per job ────────────────────────────────────────── */}
      {data?.jobs.map((job) => {
        const draft = drafts[job.jobKey]
        if (!draft) return null
        const dirty = dirtyKeys.includes(job.jobKey)
        const numericParams = Object.entries(draft.params).filter(([, v]) => typeof v === 'number')
        const grid = weekGrid(draft.windows)
        return (
          <section key={job.jobKey} className="rounded-2xl border border-slate-200 bg-white shadow-sm">
            <header className="flex flex-wrap items-start justify-between gap-3 border-b border-slate-100 p-4">
              <div className="flex items-start gap-3">
                <div className="pt-0.5">
                  <Toggle on={draft.enabled} label={`${job.name} on/off`}
                          onChange={(v) => setDraft(job.jobKey, { enabled: v })} />
                </div>
                <div>
                  <h2 className="flex items-center gap-2 text-[14px] font-semibold text-slate-900">
                    {job.name}
                    {job.running ? <span className={`rounded-full px-2 py-0.5 text-[10.5px] font-semibold ${statusCls('RUNNING')}`}>running</span> : null}
                    {dirty ? <span className="rounded-full bg-amber-100 px-2 py-0.5 text-[10.5px] font-semibold text-amber-800">unsaved</span> : null}
                  </h2>
                  {job.description ? <p className="mt-0.5 text-[11.5px] text-slate-500">{job.description}</p> : null}
                  <p className="mt-1.5 flex flex-wrap items-center gap-x-2 gap-y-1 text-[11.5px] text-slate-600">
                    <span>Last run:</span>
                    {job.lastRunAt ? (
                      <>
                        <span>{fmtDateTime(job.lastRunAt)}</span>
                        <span className={`rounded-full px-2 py-0.5 text-[10.5px] font-semibold ${statusCls(job.lastStatus)}`}>
                          {job.lastStatus?.toLowerCase()}
                        </span>
                        <span className="text-slate-400">{fmtDuration(job.lastDurationMs)}</span>
                        {job.lastMessage ? <span className="text-slate-500" title={job.lastMessage}>· {job.lastMessage.length > 90 ? `${job.lastMessage.slice(0, 90)}…` : job.lastMessage}</span> : null}
                      </>
                    ) : <span className="text-slate-400">never</span>}
                  </p>
                  <p className="mt-1 flex flex-wrap items-center gap-1.5 text-[11.5px] text-slate-600">
                    <FiClock className="h-3.5 w-3.5 text-slate-400" />
                    {!masterOn ? <span className="text-slate-400">Not scheduled — master switch is off.</span>
                      : !job.enabled ? <span className="text-slate-400">Not scheduled — job is off.</span>
                      : job.nextRuns.length === 0 ? <span className="text-slate-400">No upcoming runs in the next week.</span>
                      : <>Next: {job.nextRuns.map((t) => (
                          <span key={t} className="rounded bg-slate-100 px-1.5 py-0.5 font-mono text-[11px]">{fmtNextRun(t)}</span>
                        ))}<span className="text-slate-400">({zoneLabel})</span></>}
                  </p>
                </div>
              </div>
              <div className="flex items-center gap-2">
                <button type="button" className={btnCls} onClick={() => setHistoryJob(job)}>
                  <FiList className="h-3.5 w-3.5" /> History
                </button>
                <button type="button" className={btnCls} disabled={job.running} onClick={() => void runNow(job)}>
                  <FiPlay className="h-3.5 w-3.5" /> Run now
                </button>
              </div>
            </header>

            {/* Windows */}
            <div className="overflow-x-auto">
              <table className="w-full min-w-[760px] text-[12.5px]">
                <thead className="bg-slate-50 text-[10.5px] font-bold uppercase tracking-[0.08em] text-slate-500">
                  <tr>
                    <th className="px-3 py-2 text-left">On</th>
                    <th className="px-3 py-2 text-left">Name</th>
                    <th className="px-3 py-2 text-left">Days</th>
                    <th className="px-3 py-2 text-left">Runs</th>
                    <th className="px-3 py-2 text-left">Time</th>
                    <th className="px-3 py-2 text-left">Every</th>
                    <th className="px-3 py-2 text-right" />
                  </tr>
                </thead>
                <tbody className="divide-y divide-slate-100">
                  {draft.windows.map((w, idx) => (
                    <tr key={w.id ?? `new-${idx}`} className={w.enabled ? '' : 'opacity-50'}>
                      <td className="px-3 py-2">
                        <input type="checkbox" checked={w.enabled} aria-label="Window on"
                               onChange={(e) => setWindow(job.jobKey, idx, { enabled: e.target.checked })} />
                      </td>
                      <td className="px-3 py-2">
                        <input className={`${inputCls} w-36`} value={w.label} placeholder={`Window ${idx + 1}`}
                               onChange={(e) => setWindow(job.jobKey, idx, { label: e.target.value })} />
                      </td>
                      <td className="px-3 py-2">
                        <div className="flex gap-0.5">
                          {DAYS.map((d) => {
                            const on = w.days.includes(d)
                            return (
                              <button key={d} type="button" aria-pressed={on} title={d}
                                      onClick={() => setWindow(job.jobKey, idx, {
                                        days: on ? w.days.filter((x) => x !== d) : DAYS.filter((x) => x === d || w.days.includes(x)),
                                      })}
                                      className={`h-6 w-7 rounded text-[10.5px] font-semibold ${on ? 'bg-slate-900 text-[#f4eede]' : 'bg-slate-100 text-slate-500 hover:bg-slate-200'}`}>
                                {d.slice(0, 2)}
                              </button>
                            )
                          })}
                        </div>
                      </td>
                      <td className="px-3 py-2">
                        <select className={`${inputCls} w-36`} value={isDaily(w) ? 'daily' : 'interval'}
                                onChange={(e) => setWindow(job.jobKey, idx, e.target.value === 'daily'
                                  ? { runAt: w.startTime ?? '21:00', startTime: null, endTime: null, intervalMinutes: null }
                                  : { runAt: null, startTime: w.runAt ?? '00:00', endTime: '23:59', intervalMinutes: 15 })}>
                          <option value="interval">Repeating</option>
                          <option value="daily">Once a day</option>
                        </select>
                      </td>
                      <td className="px-3 py-2">
                        {isDaily(w) ? (
                          <div className="flex items-center gap-1 text-[11.5px] text-slate-500">
                            at <input type="time" className={`${inputCls} w-28`} value={w.runAt ?? ''}
                                      onChange={(e) => setWindow(job.jobKey, idx, { runAt: e.target.value })} />
                          </div>
                        ) : (
                          <div className="flex items-center gap-1 text-[11.5px] text-slate-500">
                            <input type="time" className={`${inputCls} w-28`} value={w.startTime ?? ''}
                                   onChange={(e) => setWindow(job.jobKey, idx, { startTime: e.target.value })} />
                            to
                            <input type="time" className={`${inputCls} w-28`} value={w.endTime ?? ''}
                                   onChange={(e) => setWindow(job.jobKey, idx, { endTime: e.target.value })} />
                          </div>
                        )}
                      </td>
                      <td className="px-3 py-2">
                        {isDaily(w) ? <span className="text-[11.5px] text-slate-400">—</span> : (
                          <div className="flex items-center gap-1 text-[11.5px] text-slate-500">
                            <input type="number" min={1} max={1440} className={`${inputCls} w-20`}
                                   value={w.intervalMinutes ?? ''}
                                   onChange={(e) => setWindow(job.jobKey, idx, {
                                     intervalMinutes: e.target.value === '' ? null : Number(e.target.value),
                                   })} />
                            min
                          </div>
                        )}
                      </td>
                      <td className="px-3 py-2 text-right">
                        <button type="button" title={`Remove "${w.label || describe(w)}"`}
                                onClick={() => setDraft(job.jobKey, { windows: draft.windows.filter((_, i) => i !== idx) })}
                                className="rounded-lg p-1 text-rose-600 hover:bg-rose-50">
                          <FiTrash2 className="h-3.5 w-3.5" />
                        </button>
                      </td>
                    </tr>
                  ))}
                  {draft.windows.length === 0 ? (
                    <tr><td colSpan={7} className="px-3 py-6 text-center text-[12px] text-slate-400">No windows — this job never runs on its own.</td></tr>
                  ) : null}
                </tbody>
              </table>
            </div>

            {/* Week strip */}
            <div className="border-t border-slate-100 px-4 py-3">
              <div className="mb-1.5 flex flex-wrap items-center gap-3 text-[10.5px] text-slate-500">
                <span className="font-semibold uppercase tracking-[0.08em]">Week at a glance</span>
                <span className="flex items-center gap-1"><span className="h-2.5 w-2.5 rounded-sm bg-emerald-700" /> every 1 min</span>
                <span className="flex items-center gap-1"><span className="h-2.5 w-2.5 rounded-sm bg-emerald-500" /> ≤ 5 min</span>
                <span className="flex items-center gap-1"><span className="h-2.5 w-2.5 rounded-sm bg-emerald-300" /> ≤ 15 min</span>
                <span className="flex items-center gap-1"><span className="h-2.5 w-2.5 rounded-sm bg-emerald-200" /> longer</span>
                <span className="flex items-center gap-1"><span className="h-2.5 w-2.5 rounded-sm bg-violet-400" /> once a day</span>
                <span className="flex items-center gap-1"><span className="h-2.5 w-2.5 rounded-sm bg-slate-100 ring-1 ring-slate-200" /> off</span>
              </div>
              <div className="overflow-x-auto">
                <div className="min-w-[560px] space-y-0.5">
                  {grid.map((row, di) => (
                    <div key={DAYS[di]} className="flex items-center gap-1">
                      <span className="w-8 text-[10.5px] font-semibold text-slate-500">{DAYS[di]}</span>
                      <div className="grid flex-1 gap-px" style={{ gridTemplateColumns: 'repeat(24, minmax(0, 1fr))' }}>
                        {row.map((v, h) => (
                          <div key={h} className={`h-3 rounded-[2px] ${cellCls(v)}`}
                               title={`${DAYS[di]} ${String(h).padStart(2, '0')}:00 — ${v == null ? 'off' : v === 0 ? 'once' : `every ${v} min`}`} />
                        ))}
                      </div>
                    </div>
                  ))}
                  <div className="flex items-center gap-1">
                    <span className="w-8" />
                    <div className="flex flex-1 justify-between text-[9.5px] text-slate-400">
                      <span>00</span><span>06</span><span>12</span><span>18</span><span>24</span>
                    </div>
                  </div>
                </div>
              </div>
            </div>

            {/* Job settings + actions */}
            <footer className="flex flex-wrap items-end justify-between gap-3 border-t border-slate-100 bg-slate-50/50 p-4">
              <div className="flex flex-wrap items-end gap-3">
                <button type="button" className={btnCls}
                        onClick={() => setDraft(job.jobKey, { windows: [...draft.windows, newWindow()] })}>
                  <FiPlus className="h-3.5 w-3.5" /> Add window
                </button>
                {numericParams.map(([k, v]) => (
                  <label key={k} className="text-[11px] font-semibold text-slate-500">
                    {paramLabel(k)}
                    <input type="number" min={1} className={`${inputCls} mt-0.5 w-28`} value={v as number}
                           onChange={(e) => setDraft(job.jobKey, {
                             params: { ...draft.params, [k]: e.target.value === '' ? 0 : Number(e.target.value) },
                           })} />
                  </label>
                ))}
              </div>
              <div className="flex items-center gap-2">
                {job.updatedBy ? (
                  <span className="text-[11px] text-slate-400">Last changed by {job.updatedBy} · {fmtDateTime(job.updatedAt)}</span>
                ) : null}
                <button type="button" className={btnCls} disabled={!dirty} onClick={() => discardJob(job.jobKey)}>
                  Discard
                </button>
                <button type="button" className={primaryCls} disabled={!dirty || savingKey === job.jobKey}
                        onClick={() => void saveJob(job.jobKey)}>
                  {savingKey === job.jobKey ? 'Saving…' : 'Save changes'}
                </button>
              </div>
            </footer>
          </section>
        )
      })}

      {historyJob ? <RunHistory job={historyJob} onClose={() => setHistoryJob(null)} /> : null}
    </div>
  )
}

const PARAM_LABELS: Record<string, string> = {}

const paramLabel = (k: string) =>
  PARAM_LABELS[k] ?? k.replace(/([A-Z])/g, ' $1').replace(/^./, (c) => c.toUpperCase())

function RunHistory({ job, onClose }: { job: SchedulerJob; onClose: () => void }) {
  const [runs, setRuns] = useState<SchedulerRun[] | null>(null)

  const load = useCallback(async () => {
    try {
      setRuns(await dtcSchedulerService.runs(job.jobKey, 100))
    } catch (e) {
      notify.apiError(e, 'Failed to load run history.')
      setRuns([])
    }
  }, [job.jobKey])

  useEffect(() => { void load() }, [load])

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose() }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])

  return (
    <div className="fixed inset-0 z-50 flex justify-end bg-slate-900/30" onClick={onClose}>
      <aside className="flex h-full w-full max-w-2xl flex-col bg-white shadow-xl" onClick={(e) => e.stopPropagation()}
             role="dialog" aria-label={`${job.name} run history`}>
        <header className="flex items-center justify-between border-b border-slate-100 p-4">
          <div>
            <h2 className="text-[14px] font-semibold text-slate-900">{job.name} — run history</h2>
            <p className="mt-0.5 text-[11.5px] text-slate-500">Last 100 runs. Kept for 30 days.</p>
          </div>
          <div className="flex items-center gap-2">
            <button type="button" className={btnCls} onClick={() => void load()}>
              <FiRefreshCw className="h-3.5 w-3.5" /> Reload
            </button>
            <button type="button" className="rounded-lg p-1.5 text-slate-500 hover:bg-slate-100" onClick={onClose} aria-label="Close">
              <FiX className="h-4 w-4" />
            </button>
          </div>
        </header>
        <div className="flex-1 overflow-y-auto">
          <table className="w-full text-[12px]">
            <thead className="sticky top-0 bg-slate-50 text-[10.5px] font-bold uppercase tracking-[0.08em] text-slate-500">
              <tr>
                <th className="px-3 py-2 text-left">Started</th>
                <th className="px-3 py-2 text-left">Status</th>
                <th className="px-3 py-2 text-left">Took</th>
                <th className="px-3 py-2 text-left">By</th>
                <th className="px-3 py-2 text-left">Result</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100">
              {runs == null ? (
                <tr><td colSpan={5} className="px-3 py-6 text-center text-slate-400">Loading…</td></tr>
              ) : runs.length === 0 ? (
                <tr><td colSpan={5} className="px-3 py-6 text-center text-slate-400">No runs yet.</td></tr>
              ) : runs.map((r) => (
                <tr key={r.id} className="align-top">
                  <td className="whitespace-nowrap px-3 py-2">{fmtDateTime(r.startedAt)}</td>
                  <td className="px-3 py-2">
                    <span className={`rounded-full px-2 py-0.5 text-[10.5px] font-semibold ${statusCls(r.status)}`}>{r.status.toLowerCase()}</span>
                  </td>
                  <td className="whitespace-nowrap px-3 py-2 text-slate-500">{fmtDuration(r.durationMs)}</td>
                  <td className="px-3 py-2 text-slate-500">{r.triggerType === 'MANUAL' ? r.triggeredBy ?? 'manual' : 'schedule'}</td>
                  <td className="px-3 py-2 text-slate-600">{r.message ?? ''}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </aside>
    </div>
  )
}
