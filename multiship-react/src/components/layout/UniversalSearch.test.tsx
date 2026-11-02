import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, cleanup, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, useLocation } from 'react-router-dom'

const listOrders = vi.fn()
const listClients = vi.fn()
const listWarehouses = vi.fn()
vi.mock('../../api/orderService', () => ({ orderService: { listOrders: (...a: unknown[]) => listOrders(...a) } }))
vi.mock('../../api/clientService', () => ({ clientService: { listClients: (...a: unknown[]) => listClients(...a) } }))
vi.mock('../../api/warehouseService', () => ({ warehouseService: { listWarehouses: (...a: unknown[]) => listWarehouses(...a) } }))
vi.mock('../../utils/unsavedWorkGuard', () => ({ canLeaveUnsavedWork: () => Promise.resolve(true) }))
vi.mock('../../hooks/useAppSession', () => ({
  useAppSession: () => ({ username: 'ops', role: 'ADMIN', connectedCarriers: [], hasConnectedCarrier: false }),
}))

const page = (content: unknown[], totalElements = content.length) => ({ data: { content, totalElements } })

function LocationProbe() {
  const loc = useLocation()
  return <p data-testid="loc">{loc.pathname}{loc.search}</p>
}

async function renderSearch() {
  const mod = await import('./UniversalSearch')
  const Search = mod.default
  render(<MemoryRouter><Search /><LocationProbe /></MemoryRouter>)
  return screen.getByRole('searchbox', { name: /universal search/i }) as HTMLInputElement
}

beforeEach(() => {
  vi.clearAllMocks()
  listOrders.mockResolvedValue(page([]))
  listClients.mockResolvedValue(page([]))
  listWarehouses.mockResolvedValue(page([]))
})
afterEach(() => cleanup())

describe('UniversalSearch', () => {
  it('BUG-185: the box opts out of browser autofill', async () => {
    const input = await renderSearch()
    expect(input).toHaveAttribute('autocomplete', 'off')
    expect(input).toHaveAttribute('maxlength', '200')
  })

  it('BUG-180: one character shows a hint and sends nothing', async () => {
    const input = await renderSearch()
    await userEvent.type(input, '9')
    expect(await screen.findByText(/type at least 2 characters/i)).toBeInTheDocument()
    await new Promise((r) => setTimeout(r, 300))
    expect(listOrders).not.toHaveBeenCalled()
  })

  it('BUG-180: orders come newest first and a "See all" row links to the full list', async () => {
    listOrders.mockResolvedValue(page(
      [{ orderDetails: { orderNo: 934519, customerCode: 'QA' } }], 14,
    ))
    const input = await renderSearch()
    await userEvent.type(input, 'QA-2026')
    await waitFor(() => expect(listOrders).toHaveBeenCalled())
    expect(listOrders).toHaveBeenCalledWith(expect.objectContaining({ sortBy: 'orderNo', sortDirection: 'DESC' }))
    expect(await screen.findByText('See all 14 matching orders')).toBeInTheDocument()
  })

  it('BUG-179: a client hit opens the client itself', async () => {
    listClients.mockResolvedValue(page([{ clientCode: 'MKL246', name: 'MKL' }]))
    const input = await renderSearch()
    await userEvent.type(input, 'MKL')
    await userEvent.click(await screen.findByRole('button', { name: /MKL246/ }))
    expect(screen.getByTestId('loc').textContent).toBe('/settings/clients/MKL246')
  })

  it('BUG-179: a warehouse hit opens the warehouses list filtered by its code', async () => {
    listWarehouses.mockResolvedValue(page([{ code: 'QA-WH1', name: 'QA', address: {} }]))
    const input = await renderSearch()
    await userEvent.type(input, 'QA-WH')
    await userEvent.click(await screen.findByRole('button', { name: /QA-WH1/ }))
    expect(screen.getByTestId('loc').textContent).toBe('/settings/warehouses?q=QA-WH1')
  })
})
