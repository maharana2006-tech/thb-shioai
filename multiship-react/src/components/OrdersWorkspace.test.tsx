import { describe, expect, it, vi } from 'vitest'
import { screen } from '@testing-library/react'
import { renderWithProviders } from '../test/renderWithProviders'

/**
 * Sprint 49 Tier 5 Fix 7 — OrdersWorkspace smoke test.
 *
 * <p>1377 LOC, 15+ modal imports, wired to orderService + clientService +
 * accountRefService. Zero prior coverage.
 *
 * <p>This smoke test mocks the network-touching services with empty
 * responses and asserts the outer chrome renders — proves the module
 * imports cleanly + doesn't crash on mount. Deeper flows (bulk select,
 * modal open, filter interaction) are follow-ups.
 */

vi.mock('../api/orderService', () => ({
  orderService: {
    listOrders: vi.fn().mockResolvedValue({
      data: { content: [], totalElements: 0, totalPages: 1, pageNumber: 0, pageSize: 10 },
    }),
    getQueueStats: vi.fn().mockResolvedValue({ data: null }),
    getDashboardStats: vi.fn().mockResolvedValue({ data: null }),
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

describe('OrdersWorkspace', () => {
  it('renders without crashing within providers', async () => {
    const { default: OrdersWorkspace } = await import('./OrdersWorkspace')
    renderWithProviders(<OrdersWorkspace />)

    // With no orders, some empty-state / heading / control is always
    // present. Assert against a stable filter control that exists in
    // every render mode.
    // Any control with role=combobox / textbox / button proves the
    // toolbar mounted. Fall back to a broad "Orders" text match if
    // structure evolves.
    const somethingRendered =
      screen.queryByPlaceholderText(/search|filter|order/i)
      ?? screen.queryByRole('button')
    expect(somethingRendered).toBeTruthy()
  })
})
