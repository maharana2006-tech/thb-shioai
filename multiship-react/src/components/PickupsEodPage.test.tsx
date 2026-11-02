import { describe, expect, it, vi, afterEach } from 'vitest'
import { render, screen, cleanup } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Outlet, Route, Routes } from 'react-router-dom'

/** BUG-027 — Settings > Schedule a pickup lists every problem at once, and sends nothing. */

const schedule = vi.fn()
const closeOutDay = vi.fn()
vi.mock('../api/pickupService', () => ({ pickupService: { schedule: (...a: unknown[]) => schedule(...a) } }))
vi.mock('../api/manifestService', () => ({
  manifestService: { recentEvents: vi.fn().mockResolvedValue({ data: [] }), closeOutDay: (...a: unknown[]) => closeOutDay(...a) },
}))
vi.mock('../api/clientService', () => ({
  clientService: { listClients: vi.fn().mockResolvedValue({ data: { content: [] } }) },
}))
vi.mock('../hooks/useConnectedCarriers', () => ({ useConnectedCarriers: () => new Set(['UPS']) }))
vi.mock('../utils/notify', () => ({
  notify: { success: vi.fn(), error: vi.fn(), apiError: vi.fn(), info: vi.fn(), confirm: vi.fn().mockResolvedValue(true) },
}))

afterEach(() => cleanup())

describe('PickupsEodPage · Schedule a pickup', () => {
  it('shows every problem at once and sends nothing', async () => {
    const { default: PickupsEodPage } = await import('./PickupsEodPage')
    render(
      <MemoryRouter>
        <Routes>
          <Route element={<Outlet context={{ registerRefresh: () => {} }} />}>
            <Route path="*" element={<PickupsEodPage />} />
          </Route>
        </Routes>
      </MemoryRouter>,
    )
    await userEvent.click(await screen.findByRole('button', { name: /^Schedule pickup$/ }))

    const items = screen.getAllByRole('listitem').map((li) => li.textContent)
    expect(items).toEqual(expect.arrayContaining(['Contact name is required.', 'Street address is required.', 'City is required.']))
    expect(items.length).toBeGreaterThanOrEqual(3)
    expect(schedule).not.toHaveBeenCalled()
  })

  it('BUG-098: a made-up 2-letter state is named, a spelled-out state gets the code hint', async () => {
    const { default: PickupsEodPage } = await import('./PickupsEodPage')
    render(<MemoryRouter><Routes><Route element={<Outlet context={{ registerRefresh: () => {} }} />}><Route path="*" element={<PickupsEodPage />} /></Route></Routes></MemoryRouter>)
    const state = screen.getByText('State').parentElement!.querySelector('input')!
    await userEvent.type(state, 'ZZ')
    await userEvent.click(await screen.findByRole('button', { name: /^Schedule pickup$/ }))
    expect(screen.getAllByRole('listitem').map((li) => li.textContent)).toContain('ZZ is not a US state code.')

    await userEvent.clear(state)
    await userEvent.type(state, 'Michigan')
    await userEvent.click(screen.getByRole('button', { name: /^Schedule pickup$/ }))
    expect(screen.getAllByRole('listitem').map((li) => li.textContent)).toContain('Use the US state code (e.g. AL, AK), not the full name.')
  })
})

describe('PickupsEodPage · End-of-day close', () => {
  it('BUG-189: the result box clears when the close scope changes', async () => {
    closeOutDay.mockResolvedValue({ data: { status: 'EMPTY', trackingCount: 0, message: 'No open UPS labels to close.', manifests: [] } })
    const { default: PickupsEodPage } = await import('./PickupsEodPage')
    render(<MemoryRouter><Routes><Route element={<Outlet context={{ registerRefresh: () => {} }} />}><Route path="*" element={<PickupsEodPage />} /></Route></Routes></MemoryRouter>)
    await userEvent.click(await screen.findByRole('button', { name: /^Close out$/ }))
    expect(await screen.findByText('No open UPS labels to close.')).toBeInTheDocument()

    const date = screen.getByText('Close date').parentElement!.querySelector('input')!
    await userEvent.clear(date)
    await userEvent.type(date, '2026-12-01')
    expect(screen.queryByText('No open UPS labels to close.')).toBeNull()
  })
})
