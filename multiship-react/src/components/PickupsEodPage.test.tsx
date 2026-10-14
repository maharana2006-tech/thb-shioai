import { describe, expect, it, vi, afterEach } from 'vitest'
import { render, screen, cleanup } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Outlet, Route, Routes } from 'react-router-dom'

/** BUG-027 — Settings > Schedule a pickup lists every problem at once, and sends nothing. */

const schedule = vi.fn()
vi.mock('../api/pickupService', () => ({ pickupService: { schedule: (...a: unknown[]) => schedule(...a) } }))
vi.mock('../api/manifestService', () => ({
  manifestService: { recentEvents: vi.fn().mockResolvedValue({ data: [] }), closeOutDay: vi.fn() },
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
})
