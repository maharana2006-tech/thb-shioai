import { useMemo } from 'react'

import { normalizeRole } from '../utils/roles'
import { notify } from '../utils/notify'
import { useAppSession } from './useAppSession'
import { useEventStream } from './useEventStream'

/**
 * PR-D follow-up — real-time operator toast when the USPS_DIRECT
 * reconciliation flips an optimistic void from VOIDED to VOID_FAILED
 * (USPS refused the refund on their eVS Refund report). Subscribes
 * to the {@code 'void-failed'} SSE topic and delivers a themed error
 * toast the instant the platform admin uploads the report.
 *
 * <p>Mount once at the app shell (see {@link App}). The hook is
 * inert for TENANT users — they don't ship labels and shouldn't see
 * void-failed noise; ADMIN + USER (the shipping team) get the toast.
 *
 * <p>Wire payload (from
 * {@code com.multiship.backend.events.VoidFailedEvent}):
 * <ul>
 *   <li>{@code orderNo}         — the order the failed void belongs to.</li>
 *   <li>{@code trackingNumber}  — the carrier tracking number USPS refused.</li>
 *   <li>{@code tenant}          — SSE envelope tenant (already filtered
 *                                  server-side; kept here for future
 *                                  multi-tenant admin dashboards).</li>
 *   <li>{@code uspsReason}      — free-text reason (nullable; "not
 *                                  provided" placeholder rendered
 *                                  when the server omitted it).</li>
 * </ul>
 *
 * <p>Errors from the backend use {@link notify}.error so the toast is
 * sticky (auto-dismiss on non-error would let a failure scroll past
 * unnoticed — the whole point of the real-time push is that ops sees
 * it while the report upload is still on their screen).
 */
interface VoidFailedPayload {
  orderNo?: number | string | null
  trackingNumber?: string | null
  tenant?: string | null
  uspsReason?: string | null
  reconciledAt?: string | null
}

/** SSE topic + event-type — mirrors {@code VoidFailedEvent.TOPIC}
 *  on the backend. Exported so App-level tests can assert the exact
 *  subscription without hard-coding the string in the assertions. */
export const VOID_FAILED_TOPIC = 'void-failed'
export const VOID_FAILED_EVENT_TYPE = 'void-failed'

function toDisplayOrder(orderNo: VoidFailedPayload['orderNo']): string {
  if (orderNo === null || orderNo === undefined) return '?'
  const asString = typeof orderNo === 'string' ? orderNo : String(orderNo)
  return asString.trim() || '?'
}

function toReasonBlurb(reason: VoidFailedPayload['uspsReason']): string {
  const trimmed = typeof reason === 'string' ? reason.trim() : ''
  return trimmed || 'not provided'
}

export function useVoidFailedToast(): void {
  const session = useAppSession()
  const role = normalizeRole(session.role)

  // Ops-facing toast: TENANT users don't ship labels so a void-failed
  // notification is noise for them; ADMIN + USER (the shipping team)
  // are the audience. Gating here (rather than at the mount site)
  // keeps App.tsx free of role-branching for what should be a single
  // top-level hook call.
  const enabled = role !== 'TENANT'

  const handlers = useMemo(
    () => ({
      [VOID_FAILED_EVENT_TYPE]: (payload: unknown) => {
        const evt = (payload ?? {}) as VoidFailedPayload
        const orderLabel = toDisplayOrder(evt.orderNo)
        const trackingLabel =
          typeof evt.trackingNumber === 'string' && evt.trackingNumber.trim()
            ? evt.trackingNumber.trim()
            : 'unknown tracking'
        const reasonBlurb = toReasonBlurb(evt.uspsReason)
        void notify.error({
          title: `Order #${orderLabel}: USPS rejected the void`,
          body:
            `Label ${trackingLabel} was scanned in transit; status is VOID_FAILED. ` +
            `Contact carrier for manual refund. USPS reason: ${reasonBlurb}.`,
        })
      },
    }),
    // Empty deps — the handler itself only depends on module-scope
    // helpers + notify (which is stable). Memoisation matters because
    // useEventStream tears the SSE connection down whenever the keys
    // of its handlers object change; a fresh object every render
    // would re-subscribe on every parent re-render.
    [],
  )

  useEventStream({
    topics: [VOID_FAILED_TOPIC],
    handlers,
    enabled,
  })
}
