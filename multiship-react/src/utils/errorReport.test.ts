import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'

/**
 * Sprint 52 verification hardening — FE-M3 client-side error telemetry
 * (Sprint 51 PR #167). Locks in the contract:
 *   · reportClientError POSTs to `${BASE_URL}/client-errors` with the
 *     wire shape backend/ClientErrorReportController.ClientErrorDTO
 *     expects (path, message, stack, componentStack, userAgent, ts).
 *   · fetch is fire-and-forget: a network failure MUST NOT throw into
 *     the ErrorBoundary that already handled the original crash.
 *   · Signature-based dedup drops repeat crashes at the same route
 *     from the same error to avoid a render-loop flooding the sink.
 *   · Rate-limit of 10 reports / rolling minute per tab.
 *
 * <p>The module holds per-tab state (recentReports + lastSignature) at
 * module scope. Each test resets that state by reimporting the module
 * with vi.resetModules() so cases don't leak into each other.
 */

describe('reportClientError (FE-M3 telemetry)', () => {
  let fetchMock: ReturnType<typeof vi.fn>

  beforeEach(() => {
    vi.resetModules()
    // Vitest jsdom already provides window; stub fetch globally.
    fetchMock = vi.fn().mockResolvedValue(new Response(null, { status: 202 }))
    vi.stubGlobal('fetch', fetchMock)
    // Freeze pathname so the signature is deterministic across tests.
    Object.defineProperty(window, 'location', {
      configurable: true,
      value: {
        pathname: '/orders',
        search: '?scope=today',
        origin: 'http://localhost',
        href: 'http://localhost/orders?scope=today',
      },
    })
    Object.defineProperty(window.navigator, 'userAgent', {
      configurable: true,
      get: () => 'vitest-agent/1.0',
    })
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    vi.useRealTimers()
  })

  it('POSTs to /api/v1/client-errors with the ClientErrorDTO shape', async () => {
    const { reportClientError } = await import('./errorReport')
    reportClientError(new Error('kaboom'), {
      componentStack: '\n    at OrdersPage\n    at RouteErrorBoundary',
    })

    expect(fetchMock).toHaveBeenCalledTimes(1)
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toBe('/api/v1/client-errors')
    expect(init.method).toBe('POST')
    expect(init.credentials).toBe('include')
    expect(init.keepalive).toBe(true)
    expect((init.headers as Record<string, string>)['Content-Type'])
      .toBe('application/json')

    const body = JSON.parse(init.body as string)
    // Wire fields the backend controller reads.
    expect(body).toMatchObject({
      path: '/orders?scope=today',
      message: 'kaboom',
      userAgent: 'vitest-agent/1.0',
    })
    // stack + componentStack are strings (possibly empty), never undefined —
    // backend deserializes with defaults but the client should always send them.
    expect(typeof body.stack).toBe('string')
    expect(body.componentStack).toContain('OrdersPage')
    // ISO-8601 timestamp.
    expect(body.ts).toMatch(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}/)
  })

  it('drops a duplicate report with the same signature (dedup)', async () => {
    const { reportClientError } = await import('./errorReport')
    reportClientError(new Error('same crash'))
    reportClientError(new Error('same crash'))
    reportClientError(new Error('same crash'))
    // Only the first one goes through — signature is `name:message:pathname`.
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  it('allows a second report if the message changes', async () => {
    const { reportClientError } = await import('./errorReport')
    reportClientError(new Error('crash A'))
    reportClientError(new Error('crash B'))
    expect(fetchMock).toHaveBeenCalledTimes(2)
  })

  it('caps at 10 reports per rolling minute', async () => {
    const { reportClientError } = await import('./errorReport')
    // 15 distinct signatures — the first 10 send, the last 5 are dropped
    // by the rate limiter (MAX_REPORTS_PER_MINUTE = 10).
    for (let i = 0; i < 15; i++) {
      reportClientError(new Error(`crash-${i}`))
    }
    expect(fetchMock).toHaveBeenCalledTimes(10)
  })

  it('swallows fetch rejection so the caller never rethrows', async () => {
    fetchMock.mockRejectedValueOnce(new TypeError('network down'))
    const { reportClientError } = await import('./errorReport')
    // Must not throw — the ErrorBoundary already handled the original
    // crash; telemetry failure must not create a second cascade.
    expect(() =>
      reportClientError(new Error('crash while offline')),
    ).not.toThrow()
    // Give the microtask a tick so the .catch() actually runs before assert.
    await Promise.resolve()
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  it('is a no-op when window is undefined (SSR guard)', async () => {
    // Simulate SSR by temporarily deleting window; module import already
    // captured window at load time, so this checks the runtime guard in
    // reportClientError itself.
    const { reportClientError } = await import('./errorReport')
    const originalWindow = globalThis.window
    // Runtime removal: reportClientError guards against SSR by checking
    // `typeof window === 'undefined'`. The cast keeps the delete legal.
    delete (globalThis as { window?: unknown }).window
    try {
      expect(() => reportClientError(new Error('ssr crash'))).not.toThrow()
      expect(fetchMock).not.toHaveBeenCalled()
    } finally {
      globalThis.window = originalWindow
    }
  })
})
