import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { useNavigate } from 'react-router-dom'
import {
  FiAlertTriangle,
  FiArrowDownRight,
  FiArrowRight,
  FiArrowUpRight,
  FiCheckCircle,
  FiClock,
  FiChevronRight,
  FiGlobe,
  FiRefreshCw,
  FiSettings,
  FiTag,
  FiTrendingUp,
  FiTruck,
  FiZap,
} from 'react-icons/fi'
import { dashboardService, type DashboardData } from '../api/dashboardService'
import { isAbortError } from '../api/apiClient'
import { useAppSession } from '../hooks/useAppSession'
import { canManageCarriers, normalizeRole } from '../utils/roles'
import { countryName } from '../utils/countries'

const CARD = 'rounded-2xl border border-slate-200 bg-white shadow-sm'
const POLL_MS = 45_000
/**
 * A queue board on the operator's second monitor is a common failure mode:
 * poll fails silently in the background, operator glances at 3-minute-old
 * data + trusts it. STALE_THRESHOLD triggers a persistent "data is stale"
 * warning that survives subsequent silent failures (unlike the transient
 * "Couldn't refresh" badge, which only reflects the MOST RECENT attempt).
 * 3× POLL = we've missed ~2 refresh cycles.
 */
const STALE_THRESHOLD_MS = POLL_MS * 3
/**
 * Refresh-button debounce. Prior UI let a rapid double-click fire 3+
 * requests; the AbortController cancels the older ones but the backend
 * still processed them. Disable the button for a short cooldown after a
 * click so operator can't spam. In-flight is separately guarded by
 * `inFlightRef`.
 */
const REFRESH_DEBOUNCE_MS = 1_500

type QueueShape = DashboardData['queue']

/** Pipeline stages in flow order — colors match the Orders workspace tabs. */
const PIPELINE = [
  { key: 'ready', label: 'Ready', view: 'ready', dot: 'bg-emerald-500', bar: 'from-emerald-500 to-emerald-400', node: 'border-emerald-400' },
  { key: 'needsDetails', label: 'Needs details', view: 'details', dot: 'bg-amber-400', bar: 'from-amber-400 to-amber-300', node: 'border-amber-400' },
  { key: 'clientMissing', label: 'No client', view: 'client', dot: 'bg-violet-500', bar: 'from-violet-500 to-violet-400', node: 'border-violet-400' },
  { key: 'chooseAccount', label: 'Pick account', view: 'choose', dot: 'bg-sky-400', bar: 'from-sky-400 to-sky-300', node: 'border-sky-400' },
  { key: 'failed', label: 'Failed', view: 'failed', dot: 'bg-rose-500', bar: 'from-rose-500 to-rose-400', node: 'border-rose-400' },
] as const

const CARRIER_AVATAR: Record<string, { bg: string; mono: string }> = {
  UPS: { bg: 'bg-[#351C15]', mono: 'UPS' },
  FEDEX: { bg: 'bg-[#4D148C]', mono: 'FDX' },
  USPS: { bg: 'bg-[#1F5AA6]', mono: 'USP' },
}

const initials = (name?: string | null) =>
  (name || '?')
    .split(/[\s._-]+/)
    .filter(Boolean)
    .slice(0, 2)
    .map((p) => p[0]?.toUpperCase() ?? '')
    .join('') || '?'

const relTime = (iso?: string | null) => {
  if (!iso) return ''
  const mins = Math.max(0, Math.round((Date.now() - new Date(iso).getTime()) / 60000))
  if (mins < 1) return 'just now'
  if (mins < 60) return `${mins}m ago`
  const h = Math.round(mins / 60)
  if (h < 24) return `${h}h ago`
  return `${Math.round(h / 24)}d ago`
}

/** Tiny inline area sparkline — no chart library, stays in the design system. */
function Sparkline({ points }: { points: number[] }) {
  const w = 260
  const h = 64
  const max = Math.max(1, ...points)
  const step = w / Math.max(1, points.length - 1)
  const xy = points.map((v, i) => [i * step, h - 8 - (v / max) * (h - 20)] as const)
  const line = xy.map(([x, y], i) => `${i === 0 ? 'M' : 'L'}${x.toFixed(1)},${y.toFixed(1)}`).join(' ')
  const area = `${line} L${w},${h} L0,${h} Z`
  return (
    <svg viewBox={`0 0 ${w} ${h}`} className="h-16 w-full" preserveAspectRatio="none" aria-hidden="true">
      <defs>
        <linearGradient id="sparkFill" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0%" stopColor="#412d15" stopOpacity="0.22" />
          <stop offset="100%" stopColor="#412d15" stopOpacity="0.02" />
        </linearGradient>
      </defs>
      <path d={area} fill="url(#sparkFill)" />
      <path d={line} fill="none" stroke="#412d15" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" />
      {xy.length ? (
        <circle cx={xy[xy.length - 1][0]} cy={xy[xy.length - 1][1]} r="3.5" fill="#1f150c" stroke="#fff" strokeWidth="1.5" />
      ) : null}
    </svg>
  )
}

/**
 * The operations dashboard: every number is a DOOR into a pre-filtered page.
 * Row 1 today's pulse · Row 2 the resolution pipeline · Row 3 setup health
 * (ADMIN) · Row 4 activity. Self-refreshes — it's a queue board.
 */
export default function Dashboard() {
  const navigate = useNavigate()
  const { username, role } = useAppSession()
  // Fix F3 — use the shared role helper so a future role rename or
  // multi-tier privilege check (e.g., an OPS role that can also manage
  // carriers) is applied consistently across the app.
  const isAdmin = canManageCarriers(normalizeRole(role))

  const [data, setData] = useState<DashboardData | null>(null)
  const [updatedAt, setUpdatedAt] = useState<number | null>(null)
  /** Last load failure. Previously every error was swallowed, so a broken
   *  /dashboard looked identical to "still loading" — forever. */
  const [loadError, setLoadError] = useState<string | null>(null)
  /** Fix F5 — debounce the Refresh button so a rapid double-click can't
   *  spam the backend even if the AbortController cancels the old
   *  request client-side. */
  const [refreshCooldown, setRefreshCooldown] = useState(false)
  /** Fix F4/F10 — `now` is stored in state and re-sampled every 30s by
   *  the staleTicker effect below. Reading Date.now() during render is
   *  impure (react-hooks/purity lint rule); reading it inside a
   *  setInterval that stores into state is fine. isStale becomes a
   *  pure function of updatedAt + now. */
  const [now, setNow] = useState<number>(() => Date.now())
  const isStale = updatedAt != null && now - updatedAt > STALE_THRESHOLD_MS
  const timer = useRef<number | null>(null)
  const staleTicker = useRef<number | null>(null)
  // Sprint 49 Tier 4 Fix 3 — in-flight guard + AbortController.
  //   inFlightRef skips a new tick when the previous one is still pending
  //   (prevents a slow-carrier request from queueing behind another).
  //   currentAbortRef holds the AbortController for the pending request so
  //   we can cancel it on unmount or when a fresh tick supersedes it.
  const inFlightRef = useRef(false)
  const currentAbortRef = useRef<AbortController | null>(null)
  const mountedRef = useRef(true)

  // Hoisted to component scope (via useCallback) so the Refresh button's
  // onClick can call it too — previously `load` lived inside the effect and
  // the JSX reference threw "load is not defined". Refs are stable, so the
  // callback identity never changes ([] deps).
  const load = useCallback(() => {
    // Supersede rather than skip. The old code bailed out whenever a
    // request was in flight, which deadlocked the first paint: React's
    // dev double-mount aborts the first request, the effect immediately
    // calls load() again, and the aborted promise's `finally` hasn't run
    // yet — so the guard was still true and the retry silently did
    // nothing. The board then sat empty until the 45s poll tick. Same
    // race hits production any time two loads land close together (a
    // double-clicked Refresh, or a tick arriving during a manual one).
    currentAbortRef.current?.abort()  // cancel the straggler
    const controller = new AbortController()
    currentAbortRef.current = controller
    inFlightRef.current = true

    dashboardService
      .load(controller.signal)
      .then((d) => {
        if (!mountedRef.current || controller.signal.aborted) return
        if (d) {
          setData(d)
          setUpdatedAt(Date.now())
          setLoadError(null)
        }
      })
      .catch((err) => {
        // Aborted requests are expected on cancellation — silence them.
        if (isAbortError(err)) return
        if (!mountedRef.current) return
        // Everything else is real and must be visible: swallowing it left
        // the board showing "Loading…" forever with no way to tell a slow
        // network from a broken endpoint.
        setLoadError(err instanceof Error ? err.message : 'Could not load dashboard data.')
      })
      .finally(() => {
        // Only the newest request owns the flag — a late straggler
        // resolving must not clear the guard for a live request.
        if (currentAbortRef.current === controller) inFlightRef.current = false
      })
  }, [])

  useEffect(() => {
    mountedRef.current = true
    load()
    timer.current = window.setInterval(load, POLL_MS)
    // Fix F4/F10 — re-sample Date.now() every 30s so the derived `isStale`
    // flag and "Updated N ago" badge stay accurate even when no fetch
    // completes. Without this, a background-failing poll leaves the badge
    // frozen. Reading Date.now() here (side-effect) is lint-safe;
    // reading during render is not.
    staleTicker.current = window.setInterval(
      () => { if (mountedRef.current) setNow(Date.now()) },
      30_000,
    )

    // A queue board should be current the moment you look at it. Polling a
    // hidden tab just burns requests, and coming back to one meant staring
    // at numbers up to a full interval old — so stop the clock while the
    // tab is hidden and refresh immediately on return.
    const onVisibility = () => {
      if (document.visibilityState === 'hidden') {
        if (timer.current) {
          window.clearInterval(timer.current)
          timer.current = null
        }
        // Fix #306 — also cancel any in-flight fetch so it doesn't
        // resolve while the tab is hidden and burn network. Prior UI
        // cleared the interval but let the pending request complete,
        // wasting response bytes + a state update the user won't see.
        currentAbortRef.current?.abort()
        return
      }
      load()
      if (!timer.current) timer.current = window.setInterval(load, POLL_MS)
    }
    document.addEventListener('visibilitychange', onVisibility)
    window.addEventListener('focus', load)

    return () => {
      mountedRef.current = false
      if (timer.current) window.clearInterval(timer.current)
      if (staleTicker.current) window.clearInterval(staleTicker.current)
      document.removeEventListener('visibilitychange', onVisibility)
      window.removeEventListener('focus', load)
      currentAbortRef.current?.abort()  // don't leak a pending fetch on unmount
    }
  }, [load])

  const hour = new Date().getHours()
  const greeting = hour < 12 ? 'Good morning' : hour < 17 ? 'Good afternoon' : 'Good evening'
  const dateLine = new Date().toLocaleDateString('en-US', { weekday: 'long', month: 'long', day: 'numeric' })

  const queue = data?.queue
  const today = data?.today
  const exceptions = today?.exceptionsNow ?? 0
  const ready = queue?.ready ?? 0
  const delta = (today?.labelsToday ?? 0) - (today?.labelsYesterday ?? 0)

  const pipelineTotal = useMemo(
    () => PIPELINE.reduce((s, p) => s + ((queue?.[p.key as keyof QueueShape] as number) ?? 0), 0),
    [queue]
  )

  const health = data?.health
  const healthItems = useMemo(() => {
    if (!health) return []
    return [
      {
        ok: health.customsGapLanes.length === 0,
        okText: 'Every international lane has a customs profile',
        warnText: `${health.customsGapLanes.length} lane${health.customsGapLanes.length === 1 ? '' : 's'} missing customs profiles`,
        detail: health.customsGapLanes
          .slice(0, 3)
          .map((l) => `${l.client} → ${countryName(l.country)}`)
          .join(' · '),
        to: '/settings/importer-broker',
      },
      {
        ok: health.unverifiedAccounts === 0,
        okText: 'All carrier accounts verified',
        warnText: `${health.unverifiedAccounts} carrier account${health.unverifiedAccounts === 1 ? '' : 's'} unverified`,
        detail: '',
        to: '/settings/carriers',
      },
      {
        ok: health.clientsWithoutDefault === 0,
        okText: 'Every client has a default account',
        warnText: `${health.clientsWithoutDefault} client${health.clientsWithoutDefault === 1 ? '' : 's'} without a default account`,
        detail: '',
        to: '/settings/carriers',
      },
      {
        ok: health.rulesToDisabledServices === 0,
        okText: 'Every ship-method rule maps to an enabled service',
        warnText: `${health.rulesToDisabledServices} rule${health.rulesToDisabledServices === 1 ? '' : 's'} point at disabled services`,
        detail: '',
        to: '/settings/shipping-catalog?tab=services',
      },
    ]
  }, [health])
  const healthWarnings = healthItems.filter((i) => !i.ok).length

  const splitEntries = Object.entries(data?.carrierSplit ?? {}).sort((a, b) => b[1] - a[1])
  const splitTotal = splitEntries.reduce((s, [, c]) => s + c, 0)

  const pulseCards: Array<{
    label: string
    value: ReactNode
    unit: string
    sub: ReactNode
    icon: ReactNode
    tone: string
    to: string
  }> = [
    {
      label: 'Labels today',
      value: today?.labelsToday ?? '—',
      unit: 'labels',
      sub: (
        <span className={`inline-flex items-center gap-0.5 ${delta > 0 ? 'text-emerald-600' : delta < 0 ? 'text-rose-500' : 'text-slate-400'}`}>
          {delta > 0 ? <FiArrowUpRight className="h-3 w-3" /> : delta < 0 ? <FiArrowDownRight className="h-3 w-3" /> : null}
          {delta === 0 ? 'level with yesterday' : `${delta > 0 ? '+' : ''}${delta} vs yesterday`}
        </span>
      ),
      icon: <FiTag className="h-4 w-4" />,
      tone: 'border-[#412d15]/25 bg-[#412d15]/[0.06] text-[#412d15]',
      to: '/orders?view=generated',
    },
    {
      label: 'Pending now',
      value: today?.pendingNow ?? '—',
      unit: 'orders',
      sub: `${ready} ready to generate`,
      icon: <FiClock className="h-4 w-4" />,
      tone: 'border-sky-200 bg-sky-50 text-sky-700',
      to: '/orders?view=all',
    },
    {
      label: 'Exceptions',
      value: exceptions,
      unit: 'orders',
      sub: exceptions === 0 ? 'nothing blocked' : 'need attention',
      icon: <FiAlertTriangle className="h-4 w-4" />,
      tone: exceptions === 0 ? 'border-emerald-200 bg-emerald-50 text-emerald-600' : 'border-amber-300 bg-amber-50 text-amber-600',
      to: '/orders?view=details',
    },
    {
      label: 'International pending',
      value: today?.intlPending ?? '—',
      unit: 'orders',
      sub: 'customs auto-resolved',
      icon: <FiGlobe className="h-4 w-4" />,
      tone: 'border-violet-200 bg-violet-50 text-violet-600',
      to: '/orders?view=all',
    },
  ]

  return (
    <div className="space-y-4 pb-16">
      {/* ===== greeting header ===== */}
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="flex items-center gap-3">
          <span className="flex h-12 w-12 items-center justify-center rounded-2xl bg-[#1f150c] text-[16px] font-bold text-[#e1dcc9] shadow-sm">
            {initials(username)}
          </span>
          <div>
            <h2 className="text-[19px] font-semibold tracking-tight text-slate-950">
              {greeting}, {username || 'there'}
            </h2>
            <p className="text-[12.5px] text-slate-500">{dateLine}</p>
          </div>
        </div>
        <div className="flex items-center gap-2">
          {/* Fix F4/F10 — persistent stale-data warning that survives
              subsequent silent poll failures. Prior "Couldn't refresh" only
              reflected the MOST RECENT attempt; if load-2/3/4 all failed
              after load-1 succeeded, the badge stayed silent. */}
          {(() => {
            if (loadError) {
              return (
                <span
                  title={loadError}
                  className="inline-flex items-center gap-1.5 rounded-full bg-rose-50 px-2.5 py-1 text-[11px] font-semibold text-rose-700 ring-1 ring-rose-200"
                >
                  <FiAlertTriangle className="h-3 w-3" />
                  {updatedAt ? "Couldn't refresh" : "Couldn't load"}
                </span>
              )
            }
            if (isStale && updatedAt != null) {
              return (
                <span
                  title={`Last successful refresh was ${relTime(new Date(updatedAt).toISOString())}. Automatic polling may be blocked.`}
                  className="inline-flex items-center gap-1.5 rounded-full bg-amber-50 px-2.5 py-1 text-[11px] font-semibold text-amber-800 ring-1 ring-amber-200"
                >
                  <FiAlertTriangle className="h-3 w-3" />
                  Stale · {relTime(new Date(updatedAt).toISOString())}
                </span>
              )
            }
            return (
              <span className="text-[11.5px] text-slate-400">
                {updatedAt ? `Updated ${relTime(new Date(updatedAt).toISOString())}` : 'Loading…'}
              </span>
            )
          })()}
          <button
            type="button"
            onClick={() => {
              // Fix F5 — cooldown so rapid clicks can't spam the backend.
              // Kicks in AFTER the click so the first press always fires.
              if (refreshCooldown) return
              setRefreshCooldown(true)
              window.setTimeout(() => {
                if (mountedRef.current) setRefreshCooldown(false)
              }, REFRESH_DEBOUNCE_MS)
              load()
            }}
            disabled={refreshCooldown}
            className="inline-flex items-center gap-1.5 rounded-xl border border-slate-200 bg-white px-3 py-2 text-[12.5px] font-semibold text-slate-600 transition hover:bg-slate-50 disabled:cursor-not-allowed disabled:opacity-60"
          >
            <FiRefreshCw className={`h-3.5 w-3.5 ${refreshCooldown ? 'animate-spin' : ''}`} /> Refresh
          </button>
          <button
            type="button"
            onClick={() => navigate('/orders?view=ready')}
            disabled={!ready}
            className="inline-flex items-center gap-1.5 rounded-xl bg-[#1f150c] px-3.5 py-2 text-[12.5px] font-semibold text-white transition hover:bg-[#412d15] disabled:cursor-not-allowed disabled:bg-slate-300"
          >
            <FiZap className="h-3.5 w-3.5" /> Generate ready ({ready})
          </button>
        </div>
      </div>

      {/* ===== row 1 · today's pulse — dock tally tickets ===== */}
      <section className="grid grid-cols-2 gap-3 lg:grid-cols-4">
        {pulseCards.map((c, idx) => (
          <button
            key={c.label}
            type="button"
            onClick={() => navigate(c.to)}
            className={`${CARD} group text-left transition hover:-translate-y-0.5 hover:border-[#412d15]/30 hover:shadow-md`}
          >
            <div className="flex items-start justify-between gap-2 px-4 pt-3.5">
              <p className="flex min-w-0 items-baseline gap-2">
                <span className="shrink-0 font-mono text-[9px] font-bold tracking-widest text-slate-300">
                  {String(idx + 1).padStart(2, '0')}
                </span>
                <span className="truncate text-[10px] font-bold uppercase tracking-[0.16em] text-slate-400">
                  {c.label}
                </span>
              </p>
              <span
                className={`inline-flex h-8 w-8 shrink-0 items-center justify-center rounded-full border transition group-hover:scale-105 ${c.tone}`}
              >
                {c.icon}
              </span>
            </div>
            <p className="flex items-baseline gap-1.5 px-4 pt-1">
              <span className="text-[32px] font-semibold leading-none tabular-nums tracking-tight text-slate-950">
                {c.value}
              </span>
              <span className="font-mono text-[9.5px] font-semibold uppercase tracking-[0.18em] text-slate-400">
                {c.unit}
              </span>
            </p>
            <div className="mt-3 flex items-center justify-between gap-2 border-t border-dashed border-slate-200 px-4 py-2.5">
              <p className="min-w-0 truncate text-[11.5px] font-medium text-slate-500">{c.sub}</p>
              <FiArrowRight className="h-3.5 w-3.5 shrink-0 text-slate-300 transition group-hover:translate-x-0.5 group-hover:text-[#412d15]" />
            </div>
          </button>
        ))}
      </section>

      {/* ===== row 2 · the pipeline ===== */}
      <section className={`${CARD} p-5`}>
        <div className="mb-3 flex items-center justify-between">
          <h3 className="inline-flex items-center gap-2 text-[13.5px] font-semibold text-slate-950">
            <span className="inline-flex h-6 w-6 items-center justify-center rounded-lg bg-[#412d15]/10 text-[#412d15]">
              <FiTruck className="h-3.5 w-3.5" />
            </span>
            Label pipeline
          </h3>
          <button
            type="button"
            onClick={() => navigate('/orders')}
            className="inline-flex items-center gap-1 text-[12px] font-semibold text-[#412d15] transition hover:gap-1.5"
          >
            Open workspace <FiArrowRight className="h-3.5 w-3.5" />
          </button>
        </div>

        {/* the sorting belt: a dashed conveyor with the five queues as stations */}
        <div className="relative mt-5 pb-1">
          {/* belt line — runs edge to edge, out to the archive */}
          <span
            aria-hidden="true"
            className="pointer-events-none absolute left-0 right-4 top-[21px] border-t-2 border-dashed border-slate-200"
          />
          <FiChevronRight
            aria-hidden="true"
            className="pointer-events-none absolute -right-1 top-[14px] h-4 w-4 text-slate-300"
          />
          {/* a parcel traveling the belt */}
          <span
            aria-hidden="true"
            className="mb-belt-parcel pointer-events-none absolute top-[18.5px] h-[7px] w-[7px] rotate-45 rounded-[1px] bg-[#412d15]/35"
          />

          <div className="grid grid-cols-5 gap-2">
            {PIPELINE.map((p) => {
              const count = (queue?.[p.key as keyof QueueShape] as number) ?? 0
              const share = pipelineTotal ? Math.max(6, Math.round((count / pipelineTotal) * 100)) : 0
              return (
                <button
                  key={p.key}
                  type="button"
                  onClick={() => navigate(`/orders?view=${p.view}`)}
                  title={`${p.label}: ${count}`}
                  className="group relative flex flex-col items-center gap-1.5 pt-0.5 text-center"
                >
                  {/* station node — solid bg masks the belt behind it */}
                  <span
                    className={`relative z-10 flex h-10 w-10 items-center justify-center rounded-full border-2 bg-white text-[15px] font-semibold tabular-nums shadow-sm transition group-hover:scale-105 ${
                      count > 0 ? `${p.node} text-slate-950` : 'border-slate-200 text-slate-300'
                    }`}
                  >
                    {count}
                  </span>
                  <span className="flex items-center gap-1.5">
                    <span className={`h-1.5 w-1.5 rounded-full ${count > 0 ? p.dot : 'bg-slate-200'}`} />
                    <span
                      className={`font-mono text-[9px] font-bold uppercase tracking-[0.14em] ${
                        count > 0 ? 'text-slate-500' : 'text-slate-300'
                      }`}
                    >
                      {p.label}
                    </span>
                  </span>
                  <span className="h-1 w-full max-w-[72px] overflow-hidden rounded-full bg-slate-100">
                    <span
                      className={`block h-full rounded-full bg-gradient-to-r ${p.bar}`}
                      style={{ width: count ? `${share}%` : '0%' }}
                    />
                  </span>
                </button>
              )
            })}
          </div>
        </div>

        {exceptions === 0 ? (
          <div className="mt-4 flex items-center gap-3 rounded-xl border border-emerald-100 bg-emerald-50/70 px-4 py-3">
            <FiCheckCircle className="h-5 w-5 shrink-0 text-emerald-600" />
            <p className="text-[13px] text-emerald-900">
              <span className="font-semibold">Belt clear.</span>{' '}
              {ready > 0
                ? `${ready} order${ready === 1 ? '' : 's'} ready to generate — nothing blocked.`
                : 'Nothing waiting, nothing blocked.'}
            </p>
            {ready > 0 ? (
              <button
                type="button"
                onClick={() => navigate('/orders?view=ready')}
                className="ml-auto inline-flex shrink-0 items-center gap-1 rounded-lg bg-emerald-600 px-3 py-1.5 text-[11.5px] font-semibold text-white transition hover:bg-emerald-700"
              >
                <FiZap className="h-3 w-3" /> Generate
              </button>
            ) : null}
          </div>
        ) : null}
      </section>

      {/* ===== row 3 · setup health (ADMIN) ===== */}
      {isAdmin && health ? (
        <section className={`${CARD} p-5`}>
          <div className="mb-3 flex items-center justify-between">
            <h3 className="inline-flex items-center gap-2 text-[13.5px] font-semibold text-slate-950">
              <span className="inline-flex h-6 w-6 items-center justify-center rounded-lg bg-sky-50 text-sky-700">
                <FiSettings className="h-3.5 w-3.5" />
              </span>
              Setup health
            </h3>
            {healthWarnings === 0 ? (
              <span className="inline-flex items-center gap-1 rounded-full bg-emerald-50 px-2.5 py-1 text-[10.5px] font-bold text-emerald-700 ring-1 ring-emerald-100">
                <FiCheckCircle className="h-3 w-3" /> All clear
              </span>
            ) : (
              <span className="inline-flex items-center gap-1 rounded-full bg-amber-50 px-2.5 py-1 text-[10.5px] font-bold text-amber-700 ring-1 ring-amber-100">
                <FiAlertTriangle className="h-3 w-3" /> {healthWarnings} to fix
              </span>
            )}
          </div>
          <ul className="grid gap-2 sm:grid-cols-2">
            {healthItems.map((item) => (
              <li key={item.okText}>
                <button
                  type="button"
                  onClick={() => navigate(item.to)}
                  className={`flex w-full items-start gap-2.5 rounded-xl border px-3.5 py-3 text-left transition ${
                    item.ok
                      ? 'border-slate-100 bg-white hover:border-slate-200'
                      : 'border-amber-200 bg-amber-50/60 hover:border-amber-300'
                  }`}
                >
                  {item.ok ? (
                    <FiCheckCircle className="mt-0.5 h-4 w-4 shrink-0 text-emerald-500" />
                  ) : (
                    <FiAlertTriangle className="mt-0.5 h-4 w-4 shrink-0 text-amber-500" />
                  )}
                  <span className="min-w-0 flex-1">
                    <span className={`block text-[12.5px] font-semibold ${item.ok ? 'text-slate-600' : 'text-amber-900'}`}>
                      {item.ok ? item.okText : item.warnText}
                    </span>
                    {!item.ok && item.detail ? (
                      <span className="mt-0.5 block truncate text-[11px] text-amber-700/80">{item.detail}</span>
                    ) : null}
                  </span>
                  {!item.ok ? <FiArrowRight className="mt-1 h-3.5 w-3.5 shrink-0 text-amber-500" /> : null}
                </button>
              </li>
            ))}
          </ul>
        </section>
      ) : null}

      {/* ===== row 4 · activity ===== */}
      <section className="grid gap-3 lg:grid-cols-3">
        {/* 7-day trend */}
        <div className={`${CARD} p-5`}>
          <h3 className="inline-flex items-center gap-2 text-[13.5px] font-semibold text-slate-950">
            <span className="inline-flex h-6 w-6 items-center justify-center rounded-lg bg-emerald-50 text-emerald-600">
              <FiTrendingUp className="h-3.5 w-3.5" />
            </span>
            Labels · last 7 days
          </h3>
          <div className="mt-3">
            <Sparkline points={(data?.trend ?? []).map((t) => t.count)} />
          </div>
          <div className="mt-1 flex justify-between text-[10px] font-semibold uppercase tracking-wide text-slate-400">
            {(data?.trend ?? []).map((t) => (
              <span key={t.date}>{new Date(t.date + 'T00:00:00').toLocaleDateString('en-US', { weekday: 'narrow' })}</span>
            ))}
          </div>
        </div>

        {/* carrier split */}
        <div className={`${CARD} p-5`}>
          <h3 className="inline-flex items-center gap-2 text-[13.5px] font-semibold text-slate-950">
            <span className="inline-flex h-6 w-6 items-center justify-center rounded-lg bg-[#412d15]/10 text-[#412d15]">
              <FiTruck className="h-3.5 w-3.5" />
            </span>
            Carrier split
          </h3>
          <ul className="mt-3.5 space-y-3">
            {splitEntries.length ? (
              splitEntries.map(([carrier, count]) => {
                const av = CARRIER_AVATAR[carrier] ?? { bg: 'bg-slate-600', mono: carrier.slice(0, 3) }
                const pct = splitTotal ? Math.round((count / splitTotal) * 100) : 0
                return (
                  <li key={carrier} className="flex items-center gap-2.5">
                    <span className={`flex h-8 w-8 shrink-0 items-center justify-center rounded-lg ${av.bg} text-[9px] font-black tracking-wide text-white shadow-sm`}>
                      {av.mono}
                    </span>
                    <div className="min-w-0 flex-1">
                      <div className="flex items-baseline justify-between">
                        <span className="text-[12px] font-semibold text-slate-800">{carrier}</span>
                        <span className="text-[11.5px] tabular-nums text-slate-500">
                          {count} · {pct}%
                        </span>
                      </div>
                      <div className="mt-1 h-1.5 overflow-hidden rounded-full bg-slate-100">
                        <div className={`h-full rounded-full ${av.bg}`} style={{ width: `${Math.max(4, pct)}%` }} />
                      </div>
                    </div>
                  </li>
                )
              })
            ) : (
              <li className="py-6 text-center text-[12px] text-slate-400">No labels yet.</li>
            )}
          </ul>
        </div>

        {/* recent labels */}
        <div className={`${CARD} p-5`}>
          <div className="flex items-center justify-between">
            <h3 className="inline-flex items-center gap-2 text-[13.5px] font-semibold text-slate-950">
              <span className="inline-flex h-6 w-6 items-center justify-center rounded-lg bg-sky-50 text-sky-700">
                <FiTag className="h-3.5 w-3.5" />
              </span>
              Recent labels
            </h3>
            <button
              type="button"
              onClick={() => navigate('/orders?view=generated')}
              className="text-[11.5px] font-semibold text-[#412d15] hover:underline"
            >
              View all
            </button>
          </div>
          <ul className="mt-2.5 divide-y divide-slate-100">
            {(data?.recentLabels ?? []).slice(0, 5).map((l) => {
              const av = CARRIER_AVATAR[l.carrier] ?? { bg: 'bg-slate-600', mono: l.carrier?.slice(0, 3) ?? '?' }
              return (
                <li key={`${l.orderNo}-${l.trackingNumber}`}>
                  <button
                    type="button"
                    onClick={() => navigate(`/label/${l.orderNo}`)}
                    className="flex w-full items-center gap-2.5 rounded-lg py-2.5 text-left transition hover:bg-slate-50/70"
                  >
                    <span className="flex h-8 w-8 shrink-0 items-center justify-center rounded-full bg-[#412d15]/10 text-[10.5px] font-bold text-[#412d15]">
                      {initials(l.client)}
                    </span>
                    <span className="min-w-0 flex-1">
                      <span className="block truncate text-[12.5px] font-semibold text-slate-900">
                        #{l.orderNo} · {l.client}
                      </span>
                      <span className="block truncate font-mono text-[10.5px] text-slate-400">{l.trackingNumber || '—'}</span>
                    </span>
                    <span className="shrink-0 text-right">
                      <span className={`inline-flex h-5 items-center rounded px-1.5 text-[8.5px] font-black tracking-wide text-white ${av.bg}`}>
                        {av.mono}
                      </span>
                      <span className="block text-[10px] text-slate-400">{relTime(l.generatedAt)}</span>
                    </span>
                  </button>
                </li>
              )
            })}
            {!data?.recentLabels?.length ? (
              <li className="py-6 text-center text-[12px] text-slate-400">No labels generated yet.</li>
            ) : null}
          </ul>
        </div>
      </section>
    </div>
  )
}
