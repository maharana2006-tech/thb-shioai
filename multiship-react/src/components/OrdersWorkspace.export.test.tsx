import { describe, expect, it, vi, beforeEach } from 'vitest'
import { screen, waitFor, fireEvent } from '@testing-library/react'
import { renderWithProviders } from '../test/renderWithProviders'

/**
 * R2-BUG-013 — Export CSV must cover every row that matches the current
 * filter set, not just the page the operator happens to be looking at.
 *
 * <p>Before the fix, the Export button in AdvancedDataTable fell back to
 * its default `exportRowValues(table)` which only sees the current page's
 * rows. OrdersWorkspace is server-paginated, so page-size 10 → 10 rows in
 * the CSV even when the filter matched 25.
 */

// ==================================================================
// Mocks
// ==================================================================

const listOrders = vi.fn()
const getQueueStats = vi.fn()
const downloadCsv = vi.fn()

vi.mock('../utils/csv', () => ({
  downloadCsv: (...a: unknown[]) => downloadCsv(...a),
  csvCell: (v: unknown) => String(v ?? ''),
}))

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

type Row = {
  orderDetails: {
    orderNo: number; orderSuffix: number; status: string; customerCode: string;
    goodsDescription: string; createdDate: string;
  }
  shippingDetails: { city: string; state: string; zipCode: string; shipVia: string; weight: number; shipViaDescription: string | null }
  labelDetails: { status: string; isGenerated: boolean; trackingNumber: string | null; trackingUrl: string | null; labelFilePath: string | null; generatedAt: string | null }
  errorDetails: { hasError: boolean; errorMessage: string | null }
}

function makeRow(i: number): Row {
  return {
    orderDetails: {
      orderNo: 1000 + i, orderSuffix: 0, status: 'READY',
      customerCode: `CUST${i}`, goodsDescription: 'g',
      createdDate: '2026-10-18',
    },
    shippingDetails: {
      city: 'TORONTO', state: 'ON', zipCode: 'M5V 1A1',
      shipVia: 'UPS_GROUND', weight: 1, shipViaDescription: null,
    },
    labelDetails: {
      status: 'PENDING', isGenerated: false, trackingNumber: null,
      trackingUrl: null, labelFilePath: null, generatedAt: null,
    },
    errorDetails: { hasError: false, errorMessage: null },
  }
}

beforeEach(() => {
  vi.clearAllMocks()
  try { localStorage.clear() } catch { /* ok */ }

  // Default: the page-sized fetch the live grid makes.
  listOrders.mockImplementation((params: { page?: number; size?: number }) => {
    const size = params.size ?? 10
    // Simulate 25 total matching rows under the current filter.
    const total = 25
    const start = (params.page ?? 0) * size
    const slice: Row[] = []
    for (let i = start; i < Math.min(start + size, total); i++) slice.push(makeRow(i))
    const totalPages = Math.max(1, Math.ceil(total / size))
    const pageNumber = params.page ?? 0
    return Promise.resolve({
      data: {
        content: slice,
        pageNumber, pageSize: size,
        totalElements: total, totalPages,
        first: pageNumber === 0, last: pageNumber >= totalPages - 1,
        empty: slice.length === 0,
        sortBy: 'orderNo', sortDirection: 'ASC',
      },
    })
  })
  getQueueStats.mockResolvedValue({
    data: { ready: 25, needsDetails: 0, chooseAccount: 0, clientMissing: 0, failed: 0, generated: 0 },
  })
})

describe('OrdersWorkspace — R2-BUG-013 CSV export covers every filtered row', () => {
  it('clicking Export loops over pages until last=true and writes 25 data rows', async () => {
    const { default: OrdersWorkspace } = await import('./OrdersWorkspace')
    renderWithProviders(<OrdersWorkspace />)

    // Wait for first page (size=10) to come back.
    await waitFor(() => {
      expect(listOrders).toHaveBeenCalled()
    })

    // Find the Export button rendered by AdvancedDataTable's toolbar.
    const exportBtn = await screen.findByRole('button', { name: /^Export$/i })
    fireEvent.click(exportBtn)

    // The export handler should fetch pages until last=true, then call
    // downloadCsv once with a matrix whose data-row count = 25.
    await waitFor(() => {
      expect(downloadCsv).toHaveBeenCalledTimes(1)
    })

    const [, matrix] = downloadCsv.mock.calls[0] as [string, string[][]]
    // header row + 25 data rows = 26.
    expect(matrix.length).toBe(26)

    // Every export fetch asked for a 1000-chunk page (EXPORT_PAGE_SIZE).
    const exportCalls = listOrders.mock.calls.filter((c) => {
      const p = c[0] as { size?: number }
      return p?.size === 1000
    })
    expect(exportCalls.length).toBeGreaterThanOrEqual(1)
  })
})
