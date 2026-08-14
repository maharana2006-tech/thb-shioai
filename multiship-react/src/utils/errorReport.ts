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
  fetch(`${BASE_URL}/client-errors`, {
    method: 'POST',
    credentials: 'include',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
    keepalive: true,
  }).catch(() => {
    // Sink is best-effort — never let telemetry rethrow into the
    // boundary that already handled the original crash.
  })
}
