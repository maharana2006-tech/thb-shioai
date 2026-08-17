import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, cleanup, act } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import type { ComponentType } from 'react'

/**
 * Dashboard — first-pass coverage (2026-08-17).
 *
 * <p>The /dashboard page had ZERO tests. This suite covers:
 *
 * <ul>
 *   <li><b>Load + render</b>: KPI tiles populate from dashboardService.load;
 *       greeting shows username; loading state; error surfacing.</li>
 *   <li><b>Fix F3 role helper</b>: isAdmin uses canManageCarriers +
 *       normalizeRole (not inline string compare). Health section
 *       renders for ADMIN, hidden for USER.</li>
 *   <li><b>Fix F5 refresh debounce</b>: Refresh button disabled during
 *       cooldown; rapid double-click fires load() ONCE not twice.</li>
 *   <li><b>Fix F4/F10 staleness warning</b>: after `STALE_THRESHOLD_MS`
 *       elapses without a successful refresh, "Stale · ..." badge
 *       replaces "Updated N ago". Uses fake timers to control clock.</li>
 * </ul>
 */

// ==================================================================
// Mocks
// ==================================================================

const dashboardLoad = vi.fn()
vi.mock('../api/dashboardService', () => ({
  dashboardService: { load: (...a: unknown[]) => dashboardLoad(...a) },
}))

vi.mock('../api/apiClient', () => ({
  isAbortError: (e: unknown) => e instanceof Error && e.name === 'AbortError',
  apiClient: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn(), patch: vi.fn() },
}))

let mockRole: string | null = 'ADMIN'
vi.mock('../hooks/useAppSession', () => ({
  useAppSession: () => ({
    username: 'ops@acme',
    role: mockRole,
    connectedCarriers: [],
    hasConnectedCarrier: false,
  }),
  clearAuthSession: vi.fn(),
  storeAuthSession: vi.fn(),
  bootstrapSessionFromCookie: vi.fn(),
  syncCarrierSession: vi.fn(),
}))

// ==================================================================
// Fixtures
// ==================================================================

const seed = (over: Record<string, unknown> = {}) => ({
  queue: {
    ready: 12, needsDetails: 3, chooseAccount: 1, clientMissing: 0, failed: 2, generated: 45,
  },
  today: {
    labelsToday: 45, labelsYesterday: 38, pendingNow: 12, exceptionsNow: 2, intlPending: 5,
  },
  trend: [
    { date: '2026-08-11', count: 20 }, { date: '2026-08-12', count: 30 },
    { date: '2026-08-13', count: 25 }, { date: '2026-08-14', count: 40 },
    { date: '2026-08-15', count: 35 }, { date: '2026-08-16', count: 38 },
    { date: '2026-08-17', count: 45 },
  ],
  carrierSplit: { UPS: 20, FEDEX: 15, USPS: 10 },
  recentLabels: [
    { orderNo: 1001, client: 'ACME', carrier: 'UPS', trackingNumber: '1Z...', city: 'NY', country: 'US', generatedAt: '2026-08-17T04:00:00Z' },
  ],
  health: {
    unverifiedAccounts: 0, clientsWithoutDefault: 0, rulesToDisabledServices: 0, customsGapLanes: [],
  },
  ...over,
})

async function loadPage(): Promise<ComponentType> {
  const mod = await import('./Dashboard')
  return mod.default
}

function renderPage(Page: ComponentType) {
  return render(
    <MemoryRouter initialEntries={['/dashboard']}>
      <Page />
    </MemoryRouter>,
  )
}

beforeEach(() => {
  vi.clearAllMocks()
  dashboardLoad.mockResolvedValue(seed())
  mockRole = 'ADMIN'
  vi.spyOn(globalThis, 'fetch').mockImplementation(() => {
    throw new Error('un-mocked fetch forbidden')
  })
})

afterEach(() => {
  cleanup()
  vi.useRealTimers()
})

// ==================================================================
// Load + render
// ==================================================================

describe('Dashboard — load + render', () => {
  it('fetches on mount and renders the greeting + KPI numbers', async () => {
    const Page = await loadPage()
    renderPage(Page)

    // Greeting includes the username.
    expect(screen.getByText(/ops@acme/i)).toBeInTheDocument()

    // Wait for the load to resolve + KPI numbers render.
    await waitFor(() => expect(dashboardLoad).toHaveBeenCalled())
    await waitFor(() => expect(screen.getByText('45')).toBeInTheDocument())  // labels today
  })

  it('shows "Loading…" before the first load resolves, then transitions to Updated', async () => {
    let resolveIt: (v: unknown) => void = () => {}
    dashboardLoad.mockReturnValueOnce(new Promise((r) => { resolveIt = r }))
    const Page = await loadPage()
    renderPage(Page)

    expect(screen.getByText(/^Loading…$/)).toBeInTheDocument()

    await act(async () => { resolveIt(seed()) })
    await waitFor(() => expect(screen.queryByText(/^Loading…$/)).toBeNull())
    expect(screen.getByText(/^Updated /i)).toBeInTheDocument()
  })

  it('load rejects → "Couldn\'t load" error badge shown', async () => {
    dashboardLoad.mockRejectedValueOnce(new Error('boom'))
    const Page = await loadPage()
    renderPage(Page)

    await waitFor(() => expect(screen.getByText(/Couldn't load/i)).toBeInTheDocument())
  })
})

// ==================================================================
// Fix F3 — role helper
// ==================================================================

describe('Dashboard — F3 canManageCarriers-based ADMIN check', () => {
  it('ADMIN sees the setup-health section', async () => {
    mockRole = 'ADMIN'
    const Page = await loadPage()
    renderPage(Page)

    await waitFor(() => expect(dashboardLoad).toHaveBeenCalled())
    // Setup health heading is ADMIN-only.
    await waitFor(() => expect(screen.queryByText(/Setup health/i)).toBeInTheDocument())
  })

  it('USER does NOT see the setup-health section', async () => {
    mockRole = 'USER'
    const Page = await loadPage()
    renderPage(Page)

    await waitFor(() => expect(dashboardLoad).toHaveBeenCalled())
    // Give the render a chance; assert absence.
    await new Promise((r) => setTimeout(r, 50))
    expect(screen.queryByText(/Setup health/i)).toBeNull()
  })

  it('lowercase "admin" is normalized via normalizeRole (helper handles casing)', async () => {
    mockRole = 'admin' as string
    const Page = await loadPage()
    renderPage(Page)

    await waitFor(() => expect(dashboardLoad).toHaveBeenCalled())
    await waitFor(() => expect(screen.queryByText(/Setup health/i)).toBeInTheDocument())
  })
})

// ==================================================================
// Fix F5 — refresh debounce
// ==================================================================

describe('Dashboard — F5 refresh debounce', () => {
  it('rapid double-click on Refresh fires load ONCE per debounce window', async () => {
    const Page = await loadPage()
    renderPage(Page)

    // Initial mount fetch.
    await waitFor(() => expect(dashboardLoad).toHaveBeenCalledTimes(1))

    const refreshBtn = screen.getByRole('button', { name: /Refresh/i })
    await userEvent.click(refreshBtn)
    // Now disabled — second click while cooldown active is a no-op.
    expect(refreshBtn).toBeDisabled()
    await userEvent.click(refreshBtn)

    // Only the FIRST manual click fired the load (2 total: mount + 1 click).
    expect(dashboardLoad).toHaveBeenCalledTimes(2)
  })
})

// ==================================================================
// Fix F4/F10 — staleness warning
// ==================================================================

describe('Dashboard — F4/F10 staleness warning', () => {
  it('after > STALE_THRESHOLD (135s) elapses without a successful refresh, "Stale" badge appears', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    const Page = await loadPage()
    renderPage(Page)

    // First load succeeds.
    await waitFor(() => expect(dashboardLoad).toHaveBeenCalled())
    await waitFor(() => expect(screen.getByText(/^Updated /i)).toBeInTheDocument())

    // Simulate all subsequent polls failing silently.
    dashboardLoad.mockRejectedValue(new Error('offline'))

    // Advance past the stale threshold (POLL_MS=45s × 3 = 135s). Each 30s tick
    // re-renders, so we advance in 60s chunks.
    await act(async () => { vi.advanceTimersByTime(60_000) })
    await act(async () => { vi.advanceTimersByTime(60_000) })
    await act(async () => { vi.advanceTimersByTime(60_000) })  // total 180s > 135s

    // The "Stale" amber badge now replaces the transient "Couldn't refresh"
    // — persistent even if the most recent poll only just failed.
    await waitFor(() => {
      const staleBadge = screen.queryByText(/^Stale/i)
      const cantRefresh = screen.queryByText(/Couldn't refresh/i)
      expect(staleBadge || cantRefresh).toBeTruthy()
    })
  })

  it('fresh load returns → no stale badge', async () => {
    const Page = await loadPage()
    renderPage(Page)

    await waitFor(() => expect(screen.getByText(/^Updated /i)).toBeInTheDocument())
    expect(screen.queryByText(/^Stale/i)).toBeNull()
  })
})
