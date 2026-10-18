import { describe, expect, it, vi, beforeEach } from 'vitest'
import { screen, waitFor, fireEvent } from '@testing-library/react'
import { renderWithProviders } from '../test/renderWithProviders'

/**
 * OrdersWorkspace — filter-pipeline regression coverage.
 *
 * <p>BUG-012: picking "Shipments only" / "Returns only" in the Filters
 * popover was silently dropped — handler stored to state but the fetch
 * effect's dep array missed it, and clear-all + badge count ignored it.
 *
 * <p>BUG-020: a reversed date range (from > to) silently returned 0
 * rows, so operators thought the orders were gone. An inline hint now
 * flags the swap.
 */

// ==================================================================
// Mocks
// ==================================================================

const listOrders = vi.fn()
const getQueueStats = vi.fn()

vi.mock('../api/orderService', () => ({
  orderService: {
    listOrders: (...a: unknown[]) => listOrders(...a),
    getQueueStats: (...a: unknown[]) => getQueueStats(...a),
    getDashboardStats: vi.fn().mockResolvedValue({ data: null }),
    listBatches: vi.fn().mockResolvedValue({ data: [] }),
  },
}))

vi.mock('../api/clientService', () => ({
  clientService: {
    list: vi.fn().mockResolvedValue({ data: [] }),
    listClients: vi.fn().mockResolvedValue({ data: { content: [] } }),
  },
}))

vi.mock('../api/accountRefService', () => ({
  accountRefService: {
    listAccounts: vi.fn().mockResolvedValue({ data: [] }),
    resolveOrders: vi.fn().mockResolvedValue([]),
  },
}))

vi.mock('../utils/notify', () => ({
  notify: {
    success: vi.fn(), error: vi.fn(), info: vi.fn(),
    apiError: vi.fn(), confirm: vi.fn().mockResolvedValue(true),
  },
}))

vi.mock('../hooks/useAppSession', () => ({
  useAppSession: () => ({ username: 'tester', role: 'ADMIN' }),
}))

beforeEach(() => {
  vi.clearAllMocks()
  try { localStorage.removeItem('advanced-data-table:orders:v1') } catch { /* ok */ }

  listOrders.mockResolvedValue({
    data: { content: [], totalElements: 0, totalPages: 1, pageNumber: 0, pageSize: 10 },
  })
  getQueueStats.mockResolvedValue({
    data: { ready: 0, needsDetails: 0, chooseAccount: 0, clientMissing: 0, failed: 0, generated: 0 },
  })
})

const loadPage = async () => (await import('./OrdersWorkspace')).default

// ==================================================================
// BUG-012 — ship-vs-return filter wired to refetch + badge + clear-all
// ==================================================================

describe('OrdersWorkspace — BUG-012 ship-vs-return filter', () => {
  it('picking "Returns only" refetches orders with isReturn=Y and bumps the filter badge', async () => {
    const Page = await loadPage()
    renderWithProviders(<Page />)

    // Open Filters popover.
    const filtersBtn = await screen.findByRole('button', { name: /^Filters/i })
    fireEvent.click(filtersBtn)

    // The "Shipment vs return" select is in the popover; its default value is '' ("Any").
    const select = await screen.findByDisplayValue('Any')
    fireEvent.change(select, { target: { value: 'Y' } })

    // Fetch effect must re-fire with isReturn=Y — this is the core wire.
    await waitFor(() => {
      const calls = listOrders.mock.calls.map((c) => c[0])
      expect(calls.some((p: Record<string, unknown>) => p?.isReturn === 'Y')).toBe(true)
    })

    // Badge count must reflect the newly-set filter (1).
    await waitFor(() => {
      expect(screen.getByRole('button', { name: /^Filters.*1$/i })).toBeInTheDocument()
    })
  })

  it('clear-all resets the ship-vs-return filter', async () => {
    const Page = await loadPage()
    renderWithProviders(<Page />)

    const filtersBtn = await screen.findByRole('button', { name: /^Filters/i })
    fireEvent.click(filtersBtn)
    const select = await screen.findByDisplayValue('Any')
    fireEvent.change(select, { target: { value: 'N' } })

    // Badge appears → Clear button appears next to Filters.
    const clearBtn = await screen.findByRole('button', { name: /^Clear$/ })
    fireEvent.click(clearBtn)

    // Clear hides the badge.
    await waitFor(() => {
      expect(screen.queryByRole('button', { name: /^Filters.*\d+$/i })).toBeNull()
    })
  })
})

// ==================================================================
// BUG-020 — reversed date range (from > to) shows an inline hint
// ==================================================================

describe('OrdersWorkspace — BUG-020 reversed date range', () => {
  it('shows a hint when From > To', async () => {
    const Page = await loadPage()
    renderWithProviders(<Page />)

    // Both date inputs live in the toolbar row.
    const dates = document.querySelectorAll('input[type="date"]')
    expect(dates.length).toBeGreaterThanOrEqual(2)
    // Set To first (18), then From (19) so from > to.
    fireEvent.change(dates[1], { target: { value: '2026-10-18' } })
    fireEvent.change(dates[0], { target: { value: '2026-10-19' } })

    await waitFor(() => {
      expect(screen.getByText(/From date is after To date/i)).toBeInTheDocument()
    })
  })

  it('no hint when range is valid', async () => {
    const Page = await loadPage()
    renderWithProviders(<Page />)

    const dates = document.querySelectorAll('input[type="date"]')
    fireEvent.change(dates[0], { target: { value: '2026-10-18' } })
    fireEvent.change(dates[1], { target: { value: '2026-10-19' } })

    await waitFor(() => {
      expect(screen.queryByText(/From date is after To date/i)).toBeNull()
    })
  })
})
