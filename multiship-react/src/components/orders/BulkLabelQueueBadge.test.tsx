import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, cleanup, waitFor, act } from '@testing-library/react'

/**
 * PR-F1 Agent-2 — {@code BulkLabelQueueBadge} unit tests.
 *
 * Scope:
 *  - Renders depth + wait when metrics come back.
 *  - Hides itself when depth === 0 (avoids an always-on chip).
 *  - Auto-refreshes every {@code refreshMs}.
 *  - Silently hides on API error (queue metrics are cosmetic).
 *  - {@code formatQueueDuration} branches: sub-minute / minutes / hours.
 *
 * <p>Timer note: uses REAL timers by default. The badge's setInterval
 * loop trips Vitest's "10000-timer infinite loop" guard on fake timers
 * ({@code vi.runAllTimersAsync}), so tests that need to advance time
 * flip to fake timers locally + use {@link vi.advanceTimersByTimeAsync}.
 * Everything else waits on the natural Promise flush via
 * {@link waitFor}.
 */

// ---------- Service mocks ----------

const getMetricsMock = vi.fn()

vi.mock('../../api/uspsLabelQueueService', () => ({
  uspsLabelQueueService: {
    getMetrics: (...args: unknown[]) => getMetricsMock(...args),
  },
}))

// isAbortError comes from apiClient; keep it a pure passthrough so
// non-abort errors go down the "hide + log" branch.
vi.mock('../../api/apiClient', () => ({
  isAbortError: (err: unknown) =>
    err != null && typeof err === 'object' && (err as { name?: string }).name === 'AbortError',
}))

// ---------- Helpers ----------

async function loadComponent() {
  const mod = await import('./BulkLabelQueueBadge')
  const fmtMod = await import('./uspsQueueFormat')
  return { Badge: mod.default, formatQueueDuration: fmtMod.formatQueueDuration }
}

beforeEach(() => {
  getMetricsMock.mockReset()
})

afterEach(() => {
  cleanup()
  // If a test flipped to fake timers, restore real ones so cleanup
  // doesn't leak state into the next test.
  vi.useRealTimers()
})

describe('BulkLabelQueueBadge — pill display', () => {
  it('renders depth + wait time once metrics resolve', async () => {
    getMetricsMock.mockResolvedValue({
      data: {
        depth: 12,
        processing: 3,
        estimatedWaitSeconds: 45 * 60,
        totalPerHourCap: 55,
        tenantCode: null,
      },
    })

    const { Badge } = await loadComponent()
    render(<Badge refreshMs={0} />)

    const pill = await waitFor(() =>
      screen.getByTestId('usps-queue-badge'),
    )
    expect(pill.textContent).toContain('12')
    // Platform-wide view uses "in USPS queue" (no possessive).
    expect(pill.textContent).toContain('in USPS queue')
    // 45 min stays as minutes (below the 60-min h+m rollover).
    expect(pill.textContent).toContain('45 min')
  })

  it('scopes copy to the tenant when tenantCode is passed', async () => {
    getMetricsMock.mockResolvedValue({
      data: {
        depth: 3,
        processing: 0,
        estimatedWaitSeconds: 30,
        totalPerHourCap: 55,
        tenantCode: 'ACME',
      },
    })

    const { Badge } = await loadComponent()
    render(<Badge tenantCode="ACME" refreshMs={0} />)

    const pill = await waitFor(() =>
      screen.getByTestId('usps-queue-badge'),
    )
    // "in your USPS queue" for the scoped view.
    expect(pill.textContent).toContain('in your USPS queue')
    // Sub-minute wait rendered humanely.
    expect(pill.textContent).toContain('under a minute')
    // Service was called with the tenant.
    expect(getMetricsMock).toHaveBeenCalledWith('ACME')
  })
})

describe('BulkLabelQueueBadge — hidden states', () => {
  it('renders nothing when depth is 0', async () => {
    getMetricsMock.mockResolvedValue({
      data: {
        depth: 0,
        processing: 0,
        estimatedWaitSeconds: 0,
        totalPerHourCap: 55,
        tenantCode: null,
      },
    })

    const { Badge } = await loadComponent()
    const { container } = render(<Badge refreshMs={0} />)

    // Wait long enough for the fetch to resolve; then confirm nothing
    // was ever painted. Waiting on the mock's call count lets us skip
    // the "no assertion → false-green" trap.
    await waitFor(() => expect(getMetricsMock).toHaveBeenCalled())

    // No pill element ever appeared.
    expect(screen.queryByTestId('usps-queue-badge')).toBeNull()
    // Nothing was rendered at all (empty container after Badge returns null).
    expect(container.firstChild).toBeNull()
  })

  it('renders nothing while the initial fetch is pending', async () => {
    // Never-resolving promise so we can inspect the pre-fetch state.
    getMetricsMock.mockImplementation(() => new Promise(() => {}))

    const { Badge } = await loadComponent()
    render(<Badge refreshMs={0} />)

    // No timers to flush; component should stay empty.
    expect(screen.queryByTestId('usps-queue-badge')).toBeNull()
  })

  it('silently hides on API error and logs at debug', async () => {
    const debugSpy = vi.spyOn(console, 'debug').mockImplementation(() => {})
    getMetricsMock.mockRejectedValue(new Error('server exploded'))

    const { Badge } = await loadComponent()
    render(<Badge refreshMs={0} />)

    // Wait for the rejection to flush + the setState to bail.
    await waitFor(() => expect(debugSpy).toHaveBeenCalled())

    // Pill absent; caller shouldn't see a broken UI.
    expect(screen.queryByTestId('usps-queue-badge')).toBeNull()
    debugSpy.mockRestore()
  })

  it('does NOT log AbortError — component unmount is expected', async () => {
    const debugSpy = vi.spyOn(console, 'debug').mockImplementation(() => {})
    const abortErr = Object.assign(new Error('aborted'), { name: 'AbortError' })
    getMetricsMock.mockRejectedValue(abortErr)

    const { Badge } = await loadComponent()
    render(<Badge refreshMs={0} />)

    // Give the effect a chance to fetch + swallow the abort. There's
    // no positive signal to waitFor here, so drain microtasks a few
    // times to give the async body room.
    for (let i = 0; i < 5; i++) {
      await Promise.resolve()
    }
    await waitFor(() => expect(getMetricsMock).toHaveBeenCalled())

    // AbortError swallowed — no debug log; badge stays hidden.
    expect(screen.queryByTestId('usps-queue-badge')).toBeNull()
    expect(debugSpy).not.toHaveBeenCalled()
    debugSpy.mockRestore()
  })
})

describe('BulkLabelQueueBadge — auto-refresh', () => {
  it('calls getMetrics again after the refresh interval', async () => {
    // Flip to fake timers so we can advance without waiting 1s of
    // wall-clock. runAllTimersAsync would busy-loop on setInterval —
    // advanceTimersByTimeAsync bounds the work to the delta we pass.
    vi.useFakeTimers()
    getMetricsMock.mockResolvedValue({
      data: {
        depth: 5,
        processing: 1,
        estimatedWaitSeconds: 120,
        totalPerHourCap: 55,
        tenantCode: null,
      },
    })

    const { Badge } = await loadComponent()
    render(<Badge refreshMs={1_000} />)

    // Give the mount-time fetch a chance to settle.
    await act(async () => {
      await Promise.resolve()
      await Promise.resolve()
    })
    const firstCallCount = getMetricsMock.mock.calls.length
    expect(firstCallCount).toBeGreaterThanOrEqual(1)

    // Fast-forward past one interval — another fetch fires.
    await act(async () => {
      await vi.advanceTimersByTimeAsync(1_100)
    })
    expect(getMetricsMock.mock.calls.length).toBeGreaterThan(firstCallCount)
  })

  it('does not schedule a second fetch when refreshMs=0', async () => {
    getMetricsMock.mockResolvedValue({
      data: {
        depth: 2,
        processing: 0,
        estimatedWaitSeconds: 30,
        totalPerHourCap: 55,
        tenantCode: null,
      },
    })

    const { Badge } = await loadComponent()
    render(<Badge refreshMs={0} />)

    // Wait for the mount fetch.
    await waitFor(() => expect(getMetricsMock).toHaveBeenCalledTimes(1))

    // Give time for any (buggy) interval to fire — under real timers
    // 50ms is enough to expose a leaked setInterval without stretching
    // the suite.
    await new Promise((r) => setTimeout(r, 50))
    expect(getMetricsMock).toHaveBeenCalledTimes(1)
  })
})

describe('formatQueueDuration', () => {
  it('handles sub-minute + minute + hour branches', async () => {
    const { formatQueueDuration } = await loadComponent()
    expect(formatQueueDuration(0)).toBe('under a minute')
    expect(formatQueueDuration(59)).toBe('under a minute')
    expect(formatQueueDuration(90)).toBe('2 min')     // rounds to 2
    expect(formatQueueDuration(45 * 60)).toBe('45 min')
    expect(formatQueueDuration(60 * 60)).toBe('1 h')
    expect(formatQueueDuration(90 * 60)).toBe('1 h 30 min')
    expect(formatQueueDuration(-1)).toBe('unknown')
    expect(formatQueueDuration(Number.NaN)).toBe('unknown')
  })
})
