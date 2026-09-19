/**
 * Sprint 51 FE-M3 — client-side error telemetry sink.
 *
 * Before this, the RouteErrorBoundary + AppErrorBoundary logged React
 * render errors to the browser console only, so a crash the operator hit
 * mid-workday never showed up on the ops side. reportClientError POSTs
 * the crash to /api/v1/client-errors; the backend logs at WARN and
 * returns 202. Fire-and-forget — the boundary UI has already rendered
 * the fallback by the time this runs, and telemetry must never mask the
 * original error with a network failure of its own.
 *
 * Best-effort de-dup + local rate limit prevents a render loop that
 * crashes on every re-mount from flooding the endpoint from a single tab.
 *
 * <p><b>F1.7 — offline queue.</b> When the fetch itself fails (network
 * down, backend restart, laptop asleep), the payload is enqueued to
 * localStorage under {@code client-error-queue-v1} (bounded, FIFO,
 * cap {@link #QUEUE_CAP}). {@link drainClientErrorQueue} is called at
 * bootstrap from main.tsx; it re-POSTs each queued payload and removes
 * on success, stopping on the first failure so a still-down backend
 * doesn't cost N attempts per boot. All localStorage access is guarded
 * so private-mode / disabled-storage / quota-exceeded degrade to the
 * previous drop-on-failure behavior instead of throwing.
 */
import { BASE_URL } from '../api/apiClient'

interface ClientErrorPayload {
  path: string
  message: string
  stack: string
  componentStack: string
  userAgent: string
  ts: string
}

const MAX_REPORTS_PER_MINUTE = 10
const WINDOW_MS = 60_000
const recentReports: number[] = []
let lastSignature: string | null = null

const QUEUE_KEY = 'client-error-queue-v1'
const QUEUE_CAP = 20

const shouldSend = (signature: string): boolean => {
  const now = Date.now()
  while (recentReports.length && now - recentReports[0] > WINDOW_MS) {
    recentReports.shift()
  }
  if (recentReports.length >= MAX_REPORTS_PER_MINUTE) return false
  if (signature === lastSignature) return false
  lastSignature = signature
  recentReports.push(now)
  return true
}

const readQueue = (): ClientErrorPayload[] => {
  try {
    const raw = window.localStorage.getItem(QUEUE_KEY)
    if (!raw) return []
    const parsed = JSON.parse(raw)
    return Array.isArray(parsed) ? (parsed as ClientErrorPayload[]) : []
  } catch {
    // Malformed JSON / access denied / private mode. Reset silently.
    return []
  }
}

const writeQueue = (queue: ClientErrorPayload[]): void => {
  try {
    if (queue.length === 0) {
      window.localStorage.removeItem(QUEUE_KEY)
    } else {
      window.localStorage.setItem(QUEUE_KEY, JSON.stringify(queue))
    }
  } catch {
    // Quota exceeded / storage disabled. Nothing to fall back to —
    // dropping is strictly better than throwing into the boundary.
  }
}

const enqueueForRetry = (payload: ClientErrorPayload): void => {
  const queue = readQueue()
  queue.push(payload)
  // Drop from the FRONT so the freshest crashes (most useful for ops)
  // survive an overrun instead of stale ones from a prior session.
  while (queue.length > QUEUE_CAP) queue.shift()
  writeQueue(queue)
}

const postPayload = (payload: ClientErrorPayload): Promise<Response> =>
  fetch(`${BASE_URL}/client-errors`, {
    method: 'POST',
    credentials: 'include',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
    keepalive: true,
  })

export const reportClientError = (
  error: Error,
  info: { componentStack?: string | null } = {},
): void => {
  if (typeof window === 'undefined') return

  const stack = (error.stack ?? '').slice(0, 4000)
  const componentStack = (info.componentStack ?? '').slice(0, 4000)
  const signature = `${error.name}:${error.message}:${window.location.pathname}`

  if (!shouldSend(signature)) return

  const payload: ClientErrorPayload = {
    path: window.location.pathname + window.location.search,
    message: error.message || error.name || 'Unknown error',
    stack,
    componentStack,
    userAgent: window.navigator?.userAgent ?? '',
    ts: new Date().toISOString(),
  }

  // No CSRF token needed — the endpoint is permitAll and CSRF-ignored
  // (errors happen pre-login and on public routes too). credentials:
  // 'include' still lets the backend associate the report with a
  // logged-in session when one exists.
  postPayload(payload).catch(() => {
    // Sink is best-effort — never let telemetry rethrow into the
    // boundary that already handled the original crash. Persist the
    // payload for the next bootstrap so a crash that hit during an
    // outage still reaches ops once connectivity returns.
    enqueueForRetry(payload)
  })
}

/**
 * F1.7 — bootstrap-time drain of the offline queue. Called once from
 * main.tsx. Iterates FIFO; on the first fetch failure, stops draining
 * and leaves the remainder for the next boot (a still-down backend
 * doesn't need N attempts in a tight loop). Successfully-drained
 * entries are removed from storage.
 */
export const drainClientErrorQueue = async (): Promise<void> => {
  if (typeof window === 'undefined') return
  const queue = readQueue()
  if (queue.length === 0) return

  const remaining = [...queue]
  while (remaining.length > 0) {
    const next = remaining[0]
    try {
      await postPayload(next)
      remaining.shift()
      writeQueue(remaining)
    } catch {
      // Backend still unreachable — leave `remaining` (and anything
      // enqueued after it) intact for the next bootstrap.
      writeQueue(remaining)
      return
    }
  }
}
