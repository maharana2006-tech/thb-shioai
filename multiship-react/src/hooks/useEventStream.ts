import { useEffect, useRef, useState } from 'react'
import { BASE_URL } from '../api/apiClient'

/**
 * Real-time backend→FE push channel via Server-Sent Events. Replaces
 * the 2–4s polling loops on BulkLabelModal + DataHistoryPage with a
 * single long-lived HTTP connection per operator that fans backend
 * events as they happen.
 *
 * <p>Wire endpoint: {@code GET /api/v1/events/stream?topics=<csv>}.
 * The server sends {@code text/event-stream} with per-event
 * {@code id / event / data} lines that the browser's
 * {@link EventSource} parses natively.
 *
 * <p>Auth: EventSource does NOT send Authorization headers, so the
 * JWT-cookie path is the only viable auth for SSE. Our backend
 * already uses httpOnly cookies (Sprint 50 Q1); passing
 * {@code withCredentials: true} lets the browser include the cookie
 * on the long-lived connection.
 *
 * <p>Reconnection: EventSource auto-reconnects on drop (browser
 * default). Every event carries an {@code id:} line so a
 * {@code Last-Event-Id} header is included on reconnect — Phase 4's
 * Redis Streams backend uses this for durable resume. Phase 3's
 * pub/sub backend loses events during reconnect; the consumer
 * components rely on their fallback polling to fill any gaps.
 *
 * <p>Graceful degradation: SSE requires a browser that supports
 * EventSource (all modern browsers do), a dev proxy that forwards
 * {@code text/event-stream} without buffering (Vite does; some
 * corporate proxies don't), and Redis on the backend. When ANY of
 * those fail, this hook stays in {@code status: 'error'} and the
 * consumer component's fallback polling picks up the slack.
 */
export type EventStreamStatus = 'connecting' | 'open' | 'error' | 'closed'

export interface EventStreamOptions {
  /** Comma-separated topics to subscribe to; passed verbatim as the
   *  {@code topics} query param. Missing = server sends all. */
  topics?: string[]
  /** Fired on each event, keyed by the SSE {@code event:} line. Keys
   *  are event types like 'job-updated', 'batch-updated'. Handler
   *  receives the already-JSON.parsed payload; malformed JSON is
   *  skipped silently (logged to console.warn). */
  handlers: Record<string, (payload: unknown) => void>
  /** Turn the whole thing off — the hook is inert. Useful to gate
   *  by a feature flag or a component's mounted state without
   *  unconditionally opening a connection. */
  enabled?: boolean
}

/**
 * Open a long-lived SSE subscription while the enclosing component
 * is mounted (and enabled). Handlers must be memoised by the caller
 * (useCallback / stable refs) — otherwise the hook re-tears the
 * connection on every render.
 */
export function useEventStream(opts: EventStreamOptions): { status: EventStreamStatus } {
  const [status, setStatus] = useState<EventStreamStatus>('closed')
  // Keep the latest handlers in a ref so the effect below can call
  // them without re-subscribing when the parent re-renders.
  const handlersRef = useRef(opts.handlers)
  handlersRef.current = opts.handlers

  const topicsCsv = (opts.topics ?? []).join(',')
  const enabled = opts.enabled ?? true

  useEffect(() => {
    if (!enabled) {
      setStatus('closed')
      return
    }
    // BASE_URL is either an absolute origin (prod, VITE_API_BASE_URL)
    // or "/api/v1" (dev, Vite proxy). EventSource takes both fine.
    const url = topicsCsv
      ? `${BASE_URL}/events/stream?topics=${encodeURIComponent(topicsCsv)}`
      : `${BASE_URL}/events/stream`

    // withCredentials sends the httpOnly JWT cookie. Without it, the
    // backend would 401 the SSE endpoint immediately.
    const source = new EventSource(url, { withCredentials: true })
    setStatus('connecting')

    source.onopen = () => setStatus('open')
    source.onerror = () => {
      // EventSource fires onerror BOTH on transient network drops
      // (before it silently reconnects) and on hard failures (auth
      // rejected, endpoint 404, SSE unsupported). We can't easily
      // tell them apart from the DOM; leave status='error' so
      // consumer components can flip to their fallback polling and
      // let EventSource keep trying in the background. If it
      // eventually reconnects, onopen flips us back to 'open'.
      setStatus('error')
    }

    // Attach one addEventListener per handled event type so the
    // per-event onMessage isn't a giant switch. Also attach 'message'
    // as a catch-all for events without a specific event: line.
    const listeners: Array<[string, EventListener]> = []
    for (const eventType of Object.keys(opts.handlers)) {
      const listener: EventListener = (evt) => {
        const msg = evt as MessageEvent
        try {
          const parsed = JSON.parse(msg.data)
          handlersRef.current[eventType]?.(parsed)
        } catch (err) {
          // Malformed JSON on the wire — log once, keep listening.
          // A single bad event must not kill the whole stream.
          console.warn(`useEventStream: bad JSON on ${eventType}`, err)
        }
      }
      source.addEventListener(eventType, listener)
      listeners.push([eventType, listener])
    }

    return () => {
      // Detach handlers explicitly (belt) — closing the source
      // usually GC's them but explicit removal makes it deterministic
      // across React StrictMode double-mount checks.
      for (const [eventType, listener] of listeners) {
        source.removeEventListener(eventType, listener)
      }
      source.close()
      setStatus('closed')
    }
    // Handlers ref is stable; only re-subscribe when
    // enabled/topics/keys-of-handlers change. The Object.keys().join()
    // gives us a stable dep for shape changes without re-running on
    // every re-render.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [enabled, topicsCsv, Object.keys(opts.handlers).sort().join(',')])

  return { status }
}
