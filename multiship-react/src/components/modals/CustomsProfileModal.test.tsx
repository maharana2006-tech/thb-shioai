import { describe, expect, it, vi, afterEach, beforeEach } from 'vitest'
import { render, screen, cleanup, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'

/** BUG-103 / BUG-104 — a failing Create brings the first error into view; the page lifts the toast stack. */

const save = vi.fn()
vi.mock('../../api/customsProfileService', () => ({
  customsProfileService: { save: (...a: unknown[]) => save(...a), remove: vi.fn() },
}))
vi.mock('../../api/apiClient', () => ({ isAbortError: () => false }))
vi.mock('../../utils/notify', () => ({
  notify: { success: vi.fn(), error: vi.fn(), apiError: vi.fn(), info: vi.fn(), confirm: vi.fn().mockResolvedValue(true) },
}))
vi.mock('../../hooks/useFocusTrap', () => ({ useFocusTrap: vi.fn() }))

beforeEach(() => { save.mockReset() })
afterEach(() => { cleanup(); document.documentElement.style.removeProperty('--toast-bottom') })

async function renderPage() {
  const { default: Modal } = await import('./CustomsProfileModal')
  return render(<Modal clients={[]} onClose={vi.fn()} asPage />)
}

describe('CustomsProfileModal', () => {
  it('BUG-103: a failing Create scrolls to the first error and sends nothing', async () => {
    const scrolled = vi.fn()
    Element.prototype.scrollIntoView = scrolled
    await renderPage()
    await userEvent.click(screen.getByRole('button', { name: 'Create profile' }))
    await waitFor(() => expect(scrolled).toHaveBeenCalled())
    expect(screen.getByText('Choose a client.')).toBeInTheDocument()
    expect(save).not.toHaveBeenCalled()
  })

  it('BUG-104: the page form lifts the toast stack above its sticky footer', async () => {
    const { unmount } = await renderPage()
    expect(document.documentElement.style.getPropertyValue('--toast-bottom')).toBe('6rem')
    unmount()
    expect(document.documentElement.style.getPropertyValue('--toast-bottom')).toBe('')
  })
})
