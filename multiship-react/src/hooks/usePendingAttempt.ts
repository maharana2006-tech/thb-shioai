import { useEffect } from 'react'
import type { NavigateFunction } from 'react-router-dom'
import { orderService } from '../api/orderService'
import { ApiError } from '../api/apiClient'
import { notify } from '../utils/notify'

/**
 * Phase 3 — attempt recovery. The form remembers the Idempotency-Key of a
 * purchase in flight; if the page reloads before the request settles, the
 * next mount asks the server what happened to that attempt and acts on it
 * (open the label, show the failure) instead of leaving a blank form that
 * invites a second purchase.
 */
const KEY = 'ms:pendingAttempt'
const MAX_AGE_MS = 15 * 60 * 1000

export interface PendingAttempt {
  key: string
  fixOrderNo: number | null
  at: number
}

export function rememberPendingAttempt(key: string, fixOrderNo: number | null): void {
  try {
    sessionStorage.setItem(KEY, JSON.stringify({ key, fixOrderNo, at: Date.now() } satisfies PendingAttempt))
  } catch {
    // storage unavailable: recovery is a convenience, the purchase still runs
  }
}

export function forgetPendingAttempt(): void {
  try {
    sessionStorage.removeItem(KEY)
  } catch {
    // ignore
  }
}

/**
 * The request settled with an error. A server answer (ApiError) means the
 * attempt is over, so forget it; a network failure or an aborted request
 * (page reload mid-purchase) means the outcome is unknown, so keep the key
 * for the next mount to look up.
 */
export function settlePendingAttempt(error: unknown): void {
  if (error instanceof ApiError) forgetPendingAttempt()
}

export function readPendingAttempt(now = Date.now()): PendingAttempt | null {
  try {
    const raw = sessionStorage.getItem(KEY)
    if (!raw) return null
    const parsed = JSON.parse(raw) as PendingAttempt
    if (!parsed?.key || typeof parsed.at !== 'number' || now - parsed.at > MAX_AGE_MS) {
      forgetPendingAttempt()
      return null
    }
    return parsed
  } catch {
    return null
  }
}

// React StrictMode mounts twice in dev; both mounts read storage before the
// first has settled, so the key alone cannot dedupe. One lookup per key.
let inFlightKey: string | null = null

/** Resolve a pending attempt against the server. Exported for tests; the hook calls it on mount. */
export async function recoverPendingAttempt(navigate: NavigateFunction): Promise<void> {
  const pending = readPendingAttempt()
  if (!pending || inFlightKey === pending.key) return
  inFlightKey = pending.key
  try {
    await resolve(pending, navigate)
  } finally {
    inFlightKey = null
  }
}

async function resolve(pending: PendingAttempt, navigate: NavigateFunction): Promise<void> {
  let attempt
  try {
    attempt = (await orderService.getAttempt(pending.key)).data
  } catch {
    forgetPendingAttempt()
    return
  }
  if (!attempt) {
    forgetPendingAttempt()
    return
  }
  switch (attempt.state) {
    case 'DONE':
      forgetPendingAttempt()
      notify.success(`Your previous purchase completed — order #${attempt.orderNo}.`)
      if (attempt.orderNo) navigate(`/label/${attempt.orderNo}`)
      return
    case 'PURCHASING':
      notify.info({
        title: 'A purchase is still in progress',
        body: 'Your last Generate click is still being processed. Wait a moment and reload before trying again.',
      })
      return
    case 'FAILED':
    case 'PARTIAL':
    case 'UNKNOWN':
    case 'ABANDONED':
    default:
      forgetPendingAttempt()
      notify.error({
        title: attempt.state === 'PARTIAL' ? 'Your previous purchase partly succeeded' : 'Your previous purchase did not complete',
        body: attempt.errorMessage || `Attempt ${attempt.state.toLowerCase()}${attempt.orderNo ? ` (order #${attempt.orderNo})` : ''}.`,
      })
  }
}

export function usePendingAttempt(navigate: NavigateFunction): void {
  useEffect(() => {
    void recoverPendingAttempt(navigate)
    // mount-only: the pending key is read from storage, not from props
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])
}
