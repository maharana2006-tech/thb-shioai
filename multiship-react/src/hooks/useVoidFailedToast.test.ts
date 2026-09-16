import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, renderHook } from '@testing-library/react'

/**
 * PR-D follow-up — {@code useVoidFailedToast} hook contract:
 *
 *  - Subscribes to the {@code 'void-failed'} SSE topic with a handler
 *    keyed to the same event type.
 *  - On a matching event, calls {@code notify.error} with a
 *    human-readable title + body containing the tracking + USPS reason.
 *  - The handler object passed to {@code useEventStream} is stable
 *    across re-renders (no re-subscribe / connection tear-down).
 *  - Disabled for TENANT users (opts out of the subscription).
 *
 * <p>Pure unit test — the SSE endpoint isn't hit, {@code useEventStream}
 * is mocked at the module boundary so we can capture the handlers it
 * was passed and drive them directly.
 */

// --- Mocked collaborators ---------------------------------------------

const useEventStreamMock = vi.fn<
  (opts: {
    topics?: string[]
    handlers: Record<string, (payload: unknown) => void>
    enabled?: boolean
  }) => { status: 'closed' | 'connecting' | 'open' | 'error' }
>()

vi.mock('./useEventStream', () => ({
  useEventStream: (opts: {
    topics?: string[]
    handlers: Record<string, (payload: unknown) => void>
    enabled?: boolean
  }) => useEventStreamMock(opts),
}))

const notifyErrorMock = vi.fn<
  (opts: { title?: string; body: string } | string) => Promise<void>
>()

vi.mock('../utils/notify', () => ({
  notify: {
    error: (opts: { title?: string; body: string } | string) =>
      notifyErrorMock(opts),
  },
}))

let sessionRole: string | null = 'USER'

vi.mock('./useAppSession', () => ({
  useAppSession: () => ({
    username: 'ops-user',
    role: sessionRole,
    connectedCarriers: [],
    hasConnectedCarrier: false,
  }),
}))

// --- Test-run scaffolding ---------------------------------------------

async function loadHook() {
  const mod = await import('./useVoidFailedToast')
  return mod
}

beforeEach(() => {
  useEventStreamMock.mockReset()
  useEventStreamMock.mockReturnValue({ status: 'open' })
  notifyErrorMock.mockReset()
  notifyErrorMock.mockResolvedValue(undefined)
  sessionRole = 'USER'
})

afterEach(() => {
  cleanup()
})

// --- Subscription shape ------------------------------------------------

describe('useVoidFailedToast — subscription', () => {
  it('subscribes to the void-failed topic with a matching handler key', async () => {
    const { useVoidFailedToast, VOID_FAILED_TOPIC, VOID_FAILED_EVENT_TYPE } =
      await loadHook()

    renderHook(() => useVoidFailedToast())

    expect(useEventStreamMock).toHaveBeenCalled()
    const opts = useEventStreamMock.mock.calls[0][0]
    expect(opts.topics).toEqual([VOID_FAILED_TOPIC])
    expect(Object.keys(opts.handlers)).toEqual([VOID_FAILED_EVENT_TYPE])
    expect(typeof opts.handlers[VOID_FAILED_EVENT_TYPE]).toBe('function')
    expect(opts.enabled).toBe(true)
  })

  it('memoises the handlers object across re-renders (no re-subscribe)', async () => {
    const { useVoidFailedToast } = await loadHook()

    const { rerender } = renderHook(() => useVoidFailedToast())
    const firstHandlers = useEventStreamMock.mock.calls[0][0].handlers

    // Force a re-render — a fresh object every render would tear the
    // SSE connection down every time the parent re-renders, which
    // was the specific bug this memoisation guards against.
    rerender()
    rerender()

    const lastHandlers =
      useEventStreamMock.mock.calls[useEventStreamMock.mock.calls.length - 1][0]
        .handlers
    expect(lastHandlers).toBe(firstHandlers)
  })
})

// --- Toast delivery ----------------------------------------------------

describe('useVoidFailedToast — toast delivery', () => {
  it('renders an error toast with order + tracking + reason', async () => {
    const { useVoidFailedToast, VOID_FAILED_EVENT_TYPE } = await loadHook()
    renderHook(() => useVoidFailedToast())

    const handler =
      useEventStreamMock.mock.calls[0][0].handlers[VOID_FAILED_EVENT_TYPE]

    handler({
      orderNo: 5002,
      trackingNumber: 'TRK-DENIED-42',
      tenant: 'ACME',
      uspsReason: 'label scanned in transit',
      reconciledAt: '2026-09-16T09:00:00Z',
    })

    expect(notifyErrorMock).toHaveBeenCalledTimes(1)
    const arg = notifyErrorMock.mock.calls[0][0] as {
      title?: string
      body: string
    }
    expect(arg.title).toBe('Order #5002: USPS rejected the void')
    expect(arg.body).toContain('TRK-DENIED-42')
    expect(arg.body).toContain('VOID_FAILED')
    expect(arg.body).toContain('label scanned in transit')
  })

  it('falls back to "not provided" when the USPS reason is missing', async () => {
    const { useVoidFailedToast, VOID_FAILED_EVENT_TYPE } = await loadHook()
    renderHook(() => useVoidFailedToast())

    const handler =
      useEventStreamMock.mock.calls[0][0].handlers[VOID_FAILED_EVENT_TYPE]

    handler({
      orderNo: 42,
      trackingNumber: 'TRK-X',
      // no uspsReason — server didn't populate the column
    })

    const arg = notifyErrorMock.mock.calls[0][0] as { body: string }
    expect(arg.body).toContain('not provided')
  })

  it('renders a stable placeholder when order/tracking are missing', async () => {
    const { useVoidFailedToast, VOID_FAILED_EVENT_TYPE } = await loadHook()
    renderHook(() => useVoidFailedToast())

    const handler =
      useEventStreamMock.mock.calls[0][0].handlers[VOID_FAILED_EVENT_TYPE]

    // Malformed payload — defensive: still delivers a toast so ops
    // sees SOMETHING went sideways, doesn't crash the SSE stream.
    handler({})

    expect(notifyErrorMock).toHaveBeenCalledTimes(1)
    const arg = notifyErrorMock.mock.calls[0][0] as {
      title?: string
      body: string
    }
    expect(arg.title).toBe('Order #?: USPS rejected the void')
    expect(arg.body).toContain('unknown tracking')
  })
})

// --- Role gating -------------------------------------------------------

describe('useVoidFailedToast — role gating', () => {
  it('disables the subscription for TENANT users', async () => {
    sessionRole = 'TENANT'
    const { useVoidFailedToast } = await loadHook()

    renderHook(() => useVoidFailedToast())

    const opts = useEventStreamMock.mock.calls[0][0]
    expect(opts.enabled).toBe(false)
  })

  it('keeps the subscription enabled for ADMIN', async () => {
    sessionRole = 'ADMIN'
    const { useVoidFailedToast } = await loadHook()

    renderHook(() => useVoidFailedToast())

    const opts = useEventStreamMock.mock.calls[0][0]
    expect(opts.enabled).toBe(true)
  })
})
