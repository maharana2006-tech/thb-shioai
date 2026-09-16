import { useCallback, useEffect, useRef, useState } from 'react'
import {
  uspsLabelQueueService,
  type UspsMpsProgress,
} from '../api/uspsLabelQueueService'
import { ApiError, isAbortError } from '../api/apiClient'

/**
 * PR-F2 — polling hook for USPS Direct MPS progress.
 *
 * <p>Behavior:
 * <ul>
 *   <li>Polls {@link uspsLabelQueueService.getMpsProgress} every
 *       {@link UseMpsProgressOptions#pollIntervalMs} (default 15s).</li>
 *   <li>Slows to a 60s cadence once {@code percentComplete >= 100}
 *       OR after 3 consecutive 404 responses (no rows for this order
 *       — either it's not an MPS or every child row has been GC'd).
 *       Slowing down prevents a burnt-in polling loop on a finished
 *       shipment left open in a background tab.</li>
 *   <li>Stops the timer when the component unmounts.</li>
 *   <li>{@link UseMpsProgressOptions#enabled} = false disables polling
 *       entirely and clears the current progress; useful when the
 *       card is conditionally rendered.</li>
 *   <li>A 404 resolves to {@code progress = null} + {@code error = null}
 *       (nothing to show, no toast); every other error is surfaced via
 *       {@code error} so the card can render a friendly line.</li>
 * </ul>
 *
 * <p>Poll cadence + backoff live here (not in the card) so a future
 * caller — an admin dashboard, an SSE-fallback banner — can reuse the
 * exact same behavior without re-deriving the timing rules.
 */
export interface UseMpsProgressOptions {
  /** Base poll interval in ms. Default 15_000 (15s). */
  pollIntervalMs?: number
  /**
   * Master enable switch. When false the hook does not fetch, clears
   * any current progress, and cancels the in-flight timer. Defaults
   * to true.
   */
  enabled?: boolean
}

export interface UseMpsProgressResult {
  progress: UspsMpsProgress | null
  loading: boolean
  error: string | null
  /** Fire a fresh fetch immediately (bypasses the current interval). */
  refresh: () => void
}

/** Slow-cadence multiplier applied at 100% or after {@link NOT_FOUND_LIMIT} 404s. */
const SLOW_INTERVAL_MS = 60_000
/** Consecutive 404 count that trips the slow cadence. */
const NOT_FOUND_LIMIT = 3

export function useMpsProgress(
  orderNo: number | null,
  opts?: UseMpsProgressOptions,
): UseMpsProgressResult {
  const { pollIntervalMs = 15_000, enabled = true } = opts ?? {}
  const [progress, setProgress] = useState<UspsMpsProgress | null>(null)
  const [loading, setLoading] = useState<boolean>(false)
  const [error, setError] = useState<string | null>(null)

  // The manual-refresh callback needs a stable reference (consumers
  // dep on it in effects); we bump this counter to force the polling
  // effect to re-run its `load` immediately.
  const [refreshTick, setRefreshTick] = useState(0)
  const refresh = useCallback(() => setRefreshTick((n) => n + 1), [])

  // Consecutive-404 counter kept in a ref so the polling effect can
  // read the latest value without re-running (state would trigger a
  // re-render + reset the interval on every 404).
  const notFoundCountRef = useRef(0)

  // Keep the timer id in a ref so `refresh` and cleanup can clear it
  // without racing with the effect's local `cancelled` flag.
  const timerRef = useRef<number | null>(null)

  useEffect(() => {
    // Disabled / orderNo=null path — cancel any pending timer and
    // reset state. Functional updaters make the resets no-ops when
    // state is already clean (no cascading render), which satisfies
    // react-hooks/set-state-in-effect for the loading/error paths
    // and is documented for setProgress.
    if (!enabled || orderNo == null) {
      if (timerRef.current != null) {
        window.clearTimeout(timerRef.current)
        timerRef.current = null
      }
      notFoundCountRef.current = 0
      // eslint-disable-next-line react-hooks/set-state-in-effect -- clear stale progress when the parent disables the hook or unbinds the orderNo; functional updater skips the render when state is already the reset value
      setProgress((prev) => (prev == null ? prev : null))
      setLoading((prev) => (prev === false ? prev : false))
      setError((prev) => (prev == null ? prev : null))
      return
    }

    let cancelled = false

    const scheduleNext = (isTerminalOrMissing: boolean) => {
      // At 100% or after the 404 streak, slow the cadence. Otherwise
      // use whichever is longer between the caller's interval and 1s
      // (defensive floor — a caller passing 0 should not busy-loop).
      const delay = isTerminalOrMissing
        ? Math.max(SLOW_INTERVAL_MS, pollIntervalMs)
        : Math.max(pollIntervalMs, 1_000)
      timerRef.current = window.setTimeout(() => {
        void load()
      }, delay)
    }

    const load = async () => {
      if (cancelled) return
      setLoading(true)
      try {
        const resp = await uspsLabelQueueService.getMpsProgress(orderNo)
        if (cancelled) return
        const data = resp?.data ?? null
        notFoundCountRef.current = 0
        setProgress(data)
        setError(null)
        const isComplete = (data?.percentComplete ?? 0) >= 100
        scheduleNext(isComplete)
      } catch (err) {
        if (isAbortError(err)) return
        if (cancelled) return
        const status =
          err instanceof ApiError ? err.status : undefined
        if (status === 404) {
          // Not an MPS (or every child row already reaped) — treat as
          // "nothing to show". Card renders its empty state; hook
          // slows the cadence once we've seen 3 in a row so a stale
          // tab isn't hammering the endpoint.
          notFoundCountRef.current += 1
          setProgress(null)
          setError(null)
          const shouldSlow = notFoundCountRef.current >= NOT_FOUND_LIMIT
          scheduleNext(shouldSlow)
        } else {
          const msg = err instanceof Error ? err.message : String(err)
          // Log at debug — the card surfaces the error inline; the
          // apiClient already logged at the appropriate level.
          console.debug('useMpsProgress fetch failed', err)
          setError(msg)
          // Keep polling on the base cadence — the next tick may
          // recover (backend restart, transient 500). No exponential
          // backoff here; the endpoint is cheap.
          scheduleNext(false)
        }
      } finally {
        if (!cancelled) setLoading(false)
      }
    }

    void load()

    return () => {
      cancelled = true
      if (timerRef.current != null) {
        window.clearTimeout(timerRef.current)
        timerRef.current = null
      }
    }
  }, [orderNo, enabled, pollIntervalMs, refreshTick])

  return { progress, loading, error, refresh }
}
