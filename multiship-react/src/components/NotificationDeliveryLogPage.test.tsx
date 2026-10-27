import { describe, expect, it, vi, afterEach } from 'vitest'
import { render, screen, cleanup, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Outlet, Route, Routes } from 'react-router-dom'

/** BUG-164 — the template-key filter is sent upper-case and offers the keys seen so far. */

const list = vi.fn()
vi.mock('../api/notificationDeliveryLogService', () => ({
  notificationDeliveryLogService: { list: (...a: unknown[]) => list(...a), retry: vi.fn() },
}))
vi.mock('../utils/notify', () => ({ notify: { error: vi.fn(), apiError: vi.fn(), success: vi.fn(), info: vi.fn() } }))

afterEach(() => cleanup())

describe('NotificationDeliveryLogPage', () => {
  it('BUG-164: lower-case input is sent upper-case; the pick-list keeps keys after an empty result', async () => {
    list.mockResolvedValueOnce({ items: [{ id: 1, templateKey: 'AUTH.PASSWORD_RESET', status: 'SENT', recipient: 'a@b.c', createdAt: null }], page: 0, size: 50, totalElements: 1, totalPages: 1 })
    list.mockResolvedValue({ items: [], page: 0, size: 50, totalElements: 0, totalPages: 0 })
    const { default: Page } = await import('./NotificationDeliveryLogPage')
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
    await userEvent.type(screen.getByPlaceholderText('AUTH.VERIFY_EMAIL'), 'auth.x')
    await waitFor(() => expect(list).toHaveBeenLastCalledWith(expect.objectContaining({ templateKey: 'AUTH.X' })))
    expect(document.querySelector('#ndl-template-keys option[value="AUTH.PASSWORD_RESET"]')).not.toBeNull()
  })
})
