import { describe, expect, it, vi, afterEach } from 'vitest'
import { render, screen, cleanup, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Outlet, Route, Routes } from 'react-router-dom'

/** BUG-164 — the Source filter is sent upper-case and offers the sources seen so far. */

const list = vi.fn()
vi.mock('../api/alertHistoryService', () => ({ alertHistoryService: { list: (...a: unknown[]) => list(...a) } }))
vi.mock('../utils/notify', () => ({ notify: { error: vi.fn(), apiError: vi.fn(), success: vi.fn(), info: vi.fn() } }))

afterEach(() => cleanup())

describe('AlertHistoryPage', () => {
  it('BUG-164: lower-case input is sent upper-case; the pick-list keeps sources after an empty result', async () => {
    list.mockResolvedValueOnce({ items: [{ id: 1, firedAt: '2026-10-25T10:00:00', source: 'STAMPS_SERA', templateKey: null, targetOrderNo: null, tenantCode: null, importBatchId: null, reason: null }], page: 0, size: 50, totalElements: 1, totalPages: 1 })
    list.mockResolvedValue({ items: [], page: 0, size: 50, totalElements: 0, totalPages: 0 })
    const { default: Page } = await import('./AlertHistoryPage')
    render(
      <MemoryRouter>
        <Routes>
          <Route element={<Outlet context={{ registerRefresh: () => {} }} />}>
            <Route path="*" element={<Page />} />
          </Route>
        </Routes>
      </MemoryRouter>,
    )
    await waitFor(() => expect(list).toHaveBeenCalledTimes(1))
    await userEvent.type(screen.getByPlaceholderText('USPS_DIRECT'), 'stamps')
    await waitFor(() => expect(list).toHaveBeenLastCalledWith(expect.objectContaining({ source: 'STAMPS' })))
    expect(document.querySelector('#ah-sources option[value="STAMPS_SERA"]')).not.toBeNull()
  })
})
