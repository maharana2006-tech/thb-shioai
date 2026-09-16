import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import {
  render,
  screen,
  cleanup,
  waitFor,
  act,
  fireEvent,
} from '@testing-library/react'
import type { ComponentType } from 'react'

/**
 * PR-F4 Agent-2 — {@code UspsDirectDashboardPage} integration tests.
 *
 * <p>Scope:
 *  - Mount fires the composite dashboard endpoint once and renders
 *    all four panels.
 *  - Refresh button forces an immediate re-fetch.
 *  - Auto-refresh toggle stops / resumes the 30s interval.
 *  - Auto-refresh interval triggers subsequent fetches.
 *  - Non-ADMIN role renders the access-denied banner instead of the
 *    page body (defense-in-depth — RequireRole is the primary gate).
 *  - Error keeps last snapshot visible + surfaces the stale-warning
 *    banner (operators on the second monitor mustn't miss stale data).
 *
 * <p>Uses REAL timers by default; the auto-refresh test flips to
 * fake timers locally so the 30s interval doesn't stretch the suite.
 * Same trick the PR-F1 BulkLabelQueueBadge test used.
 */

// ---------- Service mocks ----------

const getDashboardMock = vi.fn()

vi.mock('../../../api/uspsLabelQueueService', () => ({
  uspsLabelQueueService: {
    getDashboard: (...args: unknown[]) => getDashboardMock(...args),
    // Untouched by this page but stubbed defensively.
    getMetrics: vi.fn(),
    getItems: vi.fn(),
    cancel: vi.fn(),
    getMpsProgress: vi.fn(),
    getQuotaHeadroom: vi.fn(),
    getRetryBuckets: vi.fn(),
    getReconciliationRollup: vi.fn(),
  },
}))

// isAbortError — treat only AbortError shape as abort so genuine
// rejections go down the error branch.
vi.mock('../../../api/apiClient', () => ({
  isAbortError: (err: unknown) =>
    err != null &&
    typeof err === 'object' &&
    (err as { name?: string }).name === 'AbortError',
  ApiError: class ApiError extends Error {},
  apiClient: {
    get: vi.fn(),
    post: vi.fn(),
    put: vi.fn(),
    delete: vi.fn(),
    patch: vi.fn(),
  },
}))

let mockRole: 'ADMIN' | 'USER' | 'TENANT' = 'ADMIN'
vi.mock('../../../hooks/useAppSession', () => ({
  useAppSession: () => ({
    username: 'ops',
    role: mockRole,
    connectedCarriers: [],
    hasConnectedCarrier: false,
  }),
}))

// ---------- Fixtures ----------

function payload(overrides: Record<string, unknown> = {}) {
  return {
    data: {
      generatedAt: '2026-09-16T15:32:00Z',
      queue: {
        depth: 42,
        processing: 5,
        estimatedWaitSeconds: 45 * 60,
        totalPerHourCap: 55,
        tenantCode: null,
        perTenantDepth: { ACME: 30, GLOBEX: 8 },
      },
      quota: {
        hourlyCap: 55,
        remainingTokens: 13,
        utilizationPercent: 76.4,
        lastReplenishAt: '2026-09-16T15:30:00Z',
        nextReplenishInSeconds: 47,
      },
      retries: {
        hoursLookback: 24,
        buckets: [
          {
            hourStart: '2026-09-16T00:00:00Z',
            attempts: 10,
            retries: 2,
            failures: 1,
          },
          {
            hourStart: '2026-09-16T01:00:00Z',
            attempts: 15,
            retries: 1,
            failures: 0,
          },
        ],
      },
      reconciliation: {
        lookbackDays: 30,
        voidedShipmentsInWindow: 250,
        reconciledApproved: 240,
        reconciledDenied: 3,
        notYetReconciled: 7,
        lastReconciliationAt: '2026-09-16T02:00:00Z',
        pendingRefundValue: 42.5,
        currency: 'USD',
      },
      ...overrides,
    },
  }
}

// ---------- Fail-loud fetch spy + mock reset ----------

beforeEach(() => {
  vi.spyOn(globalThis, 'fetch').mockImplementation(() => {
    throw new Error('un-mocked fetch forbidden in unit tests')
  })
  getDashboardMock.mockReset()
  mockRole = 'ADMIN'
})

afterEach(() => {
  cleanup()
  vi.useRealTimers()
  vi.restoreAllMocks()
})

async function loadPage(): Promise<ComponentType> {
  const mod = await import('../../../pages/UspsDirectDashboardPage')
  return mod.default
}

// ===================== Mount + composite fetch =====================

describe('UspsDirectDashboardPage — mount', () => {
  it('fires the composite dashboard endpoint once on mount', async () => {
    getDashboardMock.mockResolvedValue(payload())

    const Page = await loadPage()
    render(<Page />)

    await waitFor(() => expect(getDashboardMock).toHaveBeenCalledTimes(1))
    // Called with the default lookback params.
    expect(getDashboardMock).toHaveBeenCalledWith({
      lookbackHours: 24,
      lookbackDays: 30,
    })
  })

  it('renders all four panels once data arrives', async () => {
    getDashboardMock.mockResolvedValue(payload())

    const Page = await loadPage()
    render(<Page />)

    // Wait for the page to render post-fetch.
    await waitFor(() =>
      expect(screen.getByTestId('queue-depth-count').textContent).toContain('42'),
    )
    // All four panel test-ids present.
    expect(screen.getByTestId('queue-depth-panel')).toBeInTheDocument()
    expect(screen.getByTestId('quota-headroom-panel')).toBeInTheDocument()
    expect(screen.getByTestId('retry-buckets-panel')).toBeInTheDocument()
    expect(screen.getByTestId('reconciliation-rollup-panel')).toBeInTheDocument()
  })
})

// ===================== Refresh button =====================

describe('UspsDirectDashboardPage — refresh button', () => {
  it('forces an immediate additional fetch on click', async () => {
    getDashboardMock.mockResolvedValue(payload())

    const Page = await loadPage()
    render(<Page />)

    await waitFor(() => expect(getDashboardMock).toHaveBeenCalledTimes(1))

    fireEvent.click(screen.getByTestId('usps-direct-dashboard-refresh'))

    await waitFor(() => expect(getDashboardMock).toHaveBeenCalledTimes(2))
  })
})

// ===================== Auto-refresh interval =====================

describe('UspsDirectDashboardPage — auto-refresh interval', () => {
  it('triggers subsequent fetches on the 30s tick', async () => {
    vi.useFakeTimers()
    getDashboardMock.mockResolvedValue(payload())

    const Page = await loadPage()
    render(<Page />)

    // Give the mount fetch a chance to resolve.
    await act(async () => {
      await Promise.resolve()
      await Promise.resolve()
    })
    const firstCount = getDashboardMock.mock.calls.length
    expect(firstCount).toBeGreaterThanOrEqual(1)

    // Advance past 30s.
    await act(async () => {
      await vi.advanceTimersByTimeAsync(31_000)
    })

    expect(getDashboardMock.mock.calls.length).toBeGreaterThan(firstCount)
  })

  it('toggling Auto off stops subsequent fetches', async () => {
    vi.useFakeTimers()
    getDashboardMock.mockResolvedValue(payload())

    const Page = await loadPage()
    render(<Page />)

    // Mount fetch.
    await act(async () => {
      await Promise.resolve()
      await Promise.resolve()
    })
    const initial = getDashboardMock.mock.calls.length

    // Toggle off.
    await act(async () => {
      fireEvent.click(screen.getByTestId('usps-direct-dashboard-auto-toggle'))
    })

    // Advance past what would have been the next tick.
    await act(async () => {
      await vi.advanceTimersByTimeAsync(31_000)
    })

    // No additional fetches beyond the initial mount.
    expect(getDashboardMock.mock.calls.length).toBe(initial)
  })
})

// ===================== Access denied for non-ADMIN =====================

describe('UspsDirectDashboardPage — role gate', () => {
  it('renders access-denied banner for USER role', async () => {
    mockRole = 'USER'
    getDashboardMock.mockResolvedValue(payload())

    const Page = await loadPage()
    render(<Page />)

    expect(
      screen.getByTestId('usps-direct-dashboard-access-denied'),
    ).toBeInTheDocument()
    // Page body not rendered → no fetch fired either.
    expect(getDashboardMock).not.toHaveBeenCalled()
    expect(
      screen.queryByTestId('usps-direct-dashboard-page'),
    ).toBeNull()
  })

  it('renders access-denied banner for TENANT role', async () => {
    mockRole = 'TENANT'
    getDashboardMock.mockResolvedValue(payload())

    const Page = await loadPage()
    render(<Page />)

    expect(
      screen.getByTestId('usps-direct-dashboard-access-denied'),
    ).toBeInTheDocument()
    expect(getDashboardMock).not.toHaveBeenCalled()
  })
})

// ===================== Error handling =====================

describe('UspsDirectDashboardPage — error handling', () => {
  it('surfaces the stale-warning banner while a previous snapshot is on screen', async () => {
    // First call resolves with a snapshot.
    getDashboardMock
      .mockResolvedValueOnce(payload())
      .mockRejectedValueOnce(new Error('boom'))

    const Page = await loadPage()
    render(<Page />)

    // Wait for initial render.
    await waitFor(() =>
      expect(screen.getByTestId('queue-depth-count').textContent).toContain('42'),
    )

    // Force a refresh — second call rejects.
    fireEvent.click(screen.getByTestId('usps-direct-dashboard-refresh'))

    await waitFor(() =>
      expect(
        screen.getByTestId('usps-direct-dashboard-stale-warning'),
      ).toBeInTheDocument(),
    )
    // Previous snapshot still visible.
    expect(screen.getByTestId('queue-depth-count').textContent).toContain('42')
  })

  it('renders panel-level error banners on initial-load failure', async () => {
    getDashboardMock.mockRejectedValue(new Error('server exploded'))

    const Page = await loadPage()
    render(<Page />)

    // Wait for the failed fetch to resolve.
    await waitFor(() =>
      expect(getDashboardMock).toHaveBeenCalledTimes(1),
    )

    // Every panel surfaces its own error banner because no snapshot
    // ever loaded.
    await waitFor(() =>
      expect(screen.getByTestId('queue-depth-error')).toBeInTheDocument(),
    )
    expect(screen.getByTestId('quota-headroom-error')).toBeInTheDocument()
    expect(screen.getByTestId('retry-buckets-error')).toBeInTheDocument()
    expect(screen.getByTestId('reconciliation-rollup-error')).toBeInTheDocument()
  })
})

// ===================== Header metadata =====================

describe('UspsDirectDashboardPage — header', () => {
  it('renders "awaiting first refresh…" before the first snapshot lands', async () => {
    // Never-resolves promise so we can inspect the pre-fetch header.
    getDashboardMock.mockImplementation(() => new Promise(() => {}))

    const Page = await loadPage()
    render(<Page />)

    expect(
      screen.getByTestId('usps-direct-dashboard-generated').textContent,
    ).toMatch(/awaiting first refresh/i)
  })

  it('renders generatedAt after the fetch resolves', async () => {
    getDashboardMock.mockResolvedValue(payload())

    const Page = await loadPage()
    render(<Page />)

    await waitFor(() =>
      expect(
        screen.getByTestId('usps-direct-dashboard-generated').textContent,
      ).toContain('2026-09-16'),
    )
  })
})
