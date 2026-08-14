import { useCallback, useEffect, useRef, useState } from 'react'
import {
  FiAlertCircle,
  FiCheckCircle,
  FiExternalLink,
  FiMapPin,
  FiRefreshCw,
  FiTruck,
  FiX,
} from 'react-icons/fi'
import { orderService, type TrackingResponseDTO } from '../../api/orderService'
import { notify } from '../../utils/notify'

/**
 * Live tracking timeline for one order. Fetches from
 * {@code /api/v1/orders/{n}/tracking/live} on mount, renders the events
 * oldest-first (backend already sorts), and shows a freshness badge based
 * on {@code source}:
 *   LIVE  — just checked, green tick
 *   CACHE — served from backend cache (< 5 min), subtle muted badge
 *   STUB  — no live credentials on this account, only the URL is available
 *
 * <p>Rendering strategy:
 *   - LIVE / CACHE with events[] → vertical timeline dominant, URL as a
 *     secondary link at the bottom.
 *   - STUB or empty events[] → "no live events" copy dominant, URL as the
 *     primary CTA button.
 *
 * <p>Refresh button re-hits the endpoint; backend serves cached until 5 min
 * pass, so this only produces a fresh call at most once every 5 min per
 * package.
 */
export interface TrackingTimelineModalProps {
  orderNo: number
  onClose: () => void
  /**
   * Auto-refresh cadence in seconds. Poll pauses on delivered shipments
   * and while the tab is hidden. Pass 0 to disable polling entirely
   * (manual refresh button still works). Default 60s — backend caches
   * for 5 min anyway, so every fifth poll is a real check while the
   * others hit the cache cheaply.
   */
  refreshIntervalSeconds?: number
}

const DEFAULT_REFRESH_INTERVAL = 60

export default function TrackingTimelineModal({
  orderNo,
  onClose,
  refreshIntervalSeconds = DEFAULT_REFRESH_INTERVAL,
}: TrackingTimelineModalProps) {
  const [data, setData] = useState<TrackingResponseDTO | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  // Seconds until the next auto-refresh. Null when polling is paused
  // (delivered, tab hidden, or interval=0). Drives the countdown badge.
  const [secondsUntilRefresh, setSecondsUntilRefresh] = useState<number | null>(null)

  // Track the latest delivered flag inside a ref so the tick loop can
  // freeze immediately without waiting for its closure to update.
  const deliveredRef = useRef(false)

  const load = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      const response = await orderService.getLiveTracking(orderNo)
      setData(response.data ?? null)
      deliveredRef.current = Boolean(response.data?.delivered)
    } catch (e) {
      const msg = e instanceof Error ? e.message : 'Failed to load tracking.'
      setError(msg)
      notify.error(msg)
    } finally {
      setLoading(false)
    }
  }, [orderNo])

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- fetch tracking data on mount / orderNo change; load() populates data + delivered ref that only exist after the async call
    void load()
  }, [load])

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose()
    }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [onClose])

  /**
   * Auto-refresh loop. Ticks every second so the countdown badge decays
   * visibly, and fires a refresh when the countdown hits zero. Pauses
   * (secondsUntilRefresh = null) when:
   *   - the shipment is delivered (terminal state, carrier won't publish
   *     new scans)
   *   - the browser tab is hidden (no point refreshing what nobody sees)
   *   - refreshIntervalSeconds is 0 or negative
   * On resume we reset the countdown so the user sees a full interval
   * before the next fetch.
   */
  useEffect(() => {
    if (refreshIntervalSeconds <= 0) {
      // eslint-disable-next-line react-hooks/set-state-in-effect -- pause countdown when parent disables polling; interval prop change is the trigger, no render-time equivalent
      setSecondsUntilRefresh(null)
      return
    }

    const shouldPoll = () =>
      !deliveredRef.current
      && !document.hidden
      && refreshIntervalSeconds > 0

    // Kick off the countdown when polling should start.
    setSecondsUntilRefresh(shouldPoll() ? refreshIntervalSeconds : null)

    const tick = () => {
      setSecondsUntilRefresh((remaining) => {
        if (!shouldPoll()) return null
        if (remaining == null) return refreshIntervalSeconds
        if (remaining <= 1) {
          void load()
          return refreshIntervalSeconds
        }
        return remaining - 1
      })
    }
    const id = window.setInterval(tick, 1000)

    // Re-check visibility on tab focus so the countdown resumes when
    // the user comes back — no wasted requests while the tab was hidden.
    const onVisibility = () => {
      if (shouldPoll()) {
        setSecondsUntilRefresh(refreshIntervalSeconds)
      } else {
        setSecondsUntilRefresh(null)
      }
    }
    document.addEventListener('visibilitychange', onVisibility)

    return () => {
      window.clearInterval(id)
      document.removeEventListener('visibilitychange', onVisibility)
    }
  }, [refreshIntervalSeconds, load])

  const hasEvents = (data?.events ?? []).length > 0

  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-label={`Tracking for order ${orderNo}`}
      className="fixed inset-0 z-50 flex items-center justify-center bg-slate-950/45 p-4"
      onClick={onClose}
    >
      <div
        className="flex h-[min(720px,90vh)] w-full max-w-[560px] flex-col overflow-hidden rounded-2xl border border-slate-200 bg-white shadow-[0_30px_80px_rgba(15,23,42,0.35)]"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex items-start justify-between gap-3 border-b border-slate-100 px-5 py-4">
          <div>
            <p className="text-[10.5px] font-bold uppercase tracking-[0.16em] text-slate-400">
              Tracking
            </p>
            <h3 className="mt-1 text-[15px] font-semibold text-slate-950">
              Order {orderNo}
              {data?.carrierCode ? (
                <span className="ml-2 rounded-full bg-slate-100 px-2 py-0.5 text-[10.5px] font-bold uppercase tracking-[0.1em] text-slate-500">
                  {data.carrierCode}
                </span>
              ) : null}
            </h3>
            {data?.trackingNumber ? (
              <p className="mt-1 font-mono text-[11.5px] text-slate-500">{data.trackingNumber}</p>
            ) : null}
          </div>
          <div className="flex items-center gap-2">
            {secondsUntilRefresh != null ? (
              <span
                title={`Auto-refresh in ${secondsUntilRefresh}s. Delivered shipments stop polling automatically.`}
                className="hidden items-center gap-1 rounded-full bg-slate-100 px-1.5 py-0.5 text-[9.5px] font-bold uppercase tracking-[0.14em] text-slate-500 sm:inline-flex"
              >
                Next {secondsUntilRefresh}s
              </span>
            ) : null}
            <button
              type="button"
              onClick={() => {
                setSecondsUntilRefresh((cur) =>
                  cur == null ? cur : refreshIntervalSeconds,
                )
                void load()
              }}
              disabled={loading}
              aria-label="Refresh"
              title="Refresh tracking"
              className="inline-flex h-8 w-8 items-center justify-center rounded-lg border border-slate-200 bg-white text-slate-500 transition hover:bg-slate-50 disabled:opacity-40"
            >
              <FiRefreshCw className={`h-3.5 w-3.5 ${loading ? 'animate-spin' : ''}`} />
            </button>
            <button
              type="button"
              onClick={onClose}
              aria-label="Close"
              className="inline-flex h-8 w-8 items-center justify-center rounded-lg border border-slate-200 bg-white text-slate-500 transition hover:bg-slate-50"
            >
              <FiX className="h-3.5 w-3.5" />
            </button>
          </div>
        </div>

        <div className="flex-1 overflow-y-auto px-5 py-4">
          {loading && !data ? (
            <p className="py-10 text-center text-[12.5px] text-slate-500">Loading tracking…</p>
          ) : error && !data ? (
            <div className="rounded-xl border border-rose-200 bg-rose-50 px-3 py-3 text-[12px] text-rose-700">
              <FiAlertCircle className="mr-1.5 inline h-3.5 w-3.5" />
              {error}
            </div>
          ) : data ? (
            <StatusBlock data={data} />
          ) : null}

          {data && hasEvents ? <EventsTimeline events={data.events} /> : null}

          {data && !hasEvents ? <NoEventsFallback data={data} /> : null}
        </div>

        {data?.trackingUrl ? (
          <div className="border-t border-slate-100 px-5 py-3">
            <a
              href={data.trackingUrl}
              target="_blank"
              rel="noreferrer"
              className="inline-flex items-center gap-1.5 text-[12px] font-semibold text-slate-700 hover:text-slate-950"
            >
              <FiExternalLink className="h-3.5 w-3.5" />
              Open on {data.carrierCode ?? 'carrier'} site
            </a>
          </div>
        ) : null}
      </div>
    </div>
  )
}

function StatusBlock({ data }: { data: TrackingResponseDTO }) {
  const delivered = Boolean(data.delivered)
  const eta = data.estimatedDelivery ? new Date(data.estimatedDelivery) : null
  return (
    <div className="mb-3 rounded-2xl border border-slate-200 bg-slate-50 p-3">
      <div className="flex items-start justify-between gap-3">
        <div>
          <div className="flex items-center gap-2">
            <span
              className={`inline-flex items-center gap-1 rounded-full px-2.5 py-1 text-[11px] font-bold ${
                delivered
                  ? 'bg-emerald-100 text-emerald-700'
                  : 'bg-sky-100 text-sky-700'
              }`}
            >
              {delivered ? <FiCheckCircle className="h-3 w-3" /> : <FiTruck className="h-3 w-3" />}
              {data.status ?? 'Unknown'}
            </span>
            <SourceBadge source={data.source} />
          </div>
          {data.currentLocation ? (
            <p className="mt-2 flex items-center gap-1 text-[12px] text-slate-600">
              <FiMapPin className="h-3 w-3 text-slate-400" />
              {data.currentLocation}
            </p>
          ) : null}
        </div>
        {eta ? (
          <div className="text-right">
            <p className="text-[9.5px] font-bold uppercase tracking-[0.14em] text-slate-400">
              {delivered ? 'Delivered' : 'ETA'}
            </p>
            <p className="mt-0.5 text-[12.5px] font-semibold text-slate-950">
              {eta.toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' })}
            </p>
          </div>
        ) : null}
      </div>
    </div>
  )
}

function SourceBadge({ source }: { source: TrackingResponseDTO['source'] }) {
  if (source === 'LIVE') {
    return (
      <span
        title="Just fetched from the carrier"
        className="inline-flex items-center rounded-full bg-emerald-50 px-1.5 py-0.5 text-[9.5px] font-bold uppercase tracking-[0.14em] text-emerald-600"
      >
        Live
      </span>
    )
  }
  if (source === 'CACHE') {
    return (
      <span
        title="Cached — refresh to re-check"
        className="inline-flex items-center rounded-full bg-slate-200 px-1.5 py-0.5 text-[9.5px] font-bold uppercase tracking-[0.14em] text-slate-500"
      >
        Cached
      </span>
    )
  }
  return (
    <span
      title="No live credentials on this account — only the carrier tracking URL is available"
      className="inline-flex items-center rounded-full bg-amber-50 px-1.5 py-0.5 text-[9.5px] font-bold uppercase tracking-[0.14em] text-amber-700"
    >
      URL-only
    </span>
  )
}

function EventsTimeline({ events }: { events: TrackingResponseDTO['events'] }) {
  // Newest at the TOP (visual convention for timelines); backend returns
  // oldest-first, so we walk from the end for display.
  const displayEvents = [...events].reverse()
  return (
    <ol className="relative space-y-3 border-l border-slate-200 pl-4">
      {displayEvents.map((event, i) => {
        const ts = event.timestamp ? new Date(event.timestamp) : null
        return (
          <li key={i} className="relative">
            <span
              className={`absolute -left-[19px] top-1 h-2.5 w-2.5 rounded-full border-2 border-white ${
                i === 0 ? 'bg-emerald-500' : 'bg-slate-300'
              }`}
              aria-hidden="true"
            />
            <p className="text-[12.5px] font-semibold text-slate-950">{event.description || 'Update'}</p>
            <div className="mt-0.5 flex flex-wrap items-center gap-x-2 gap-y-0.5 text-[10.5px] text-slate-500">
              {ts ? (
                <span>{ts.toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' })}</span>
              ) : null}
              {event.location ? (
                <span className="inline-flex items-center gap-0.5">
                  <FiMapPin className="h-2.5 w-2.5 text-slate-400" />
                  {event.location}
                </span>
              ) : null}
              {event.status ? (
                <span className="rounded-full bg-slate-100 px-1.5 py-0.5 font-mono uppercase tracking-[0.1em]">
                  {event.status}
                </span>
              ) : null}
            </div>
          </li>
        )
      })}
    </ol>
  )
}

function NoEventsFallback({ data }: { data: TrackingResponseDTO }) {
  if (data.source === 'STUB') {
    return (
      <div className="rounded-xl border border-amber-200 bg-amber-50 px-3 py-3 text-[12px] text-amber-800">
        This account doesn't have live tracking credentials configured. Use the "Open on {data.carrierCode ?? 'carrier'}
        {' '}site" link below to check the parcel's status.
      </div>
    )
  }
  return (
    <div className="rounded-xl border border-slate-200 bg-slate-50 px-3 py-3 text-[12px] text-slate-600">
      No tracking events yet. The carrier will publish scan updates once the package moves.
    </div>
  )
}
