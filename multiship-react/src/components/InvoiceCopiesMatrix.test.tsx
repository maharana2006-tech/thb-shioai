import { describe, expect, it, vi, afterEach } from 'vitest'
import { render, screen, cleanup, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'

/** BUG-131 — an out-of-range copies number is refused and the box shows the stored value again. */

const upsert = vi.fn()
vi.mock('../api/printerService', () => ({
  printerService: {
    listInvoiceCopies: vi.fn().mockResolvedValue({ data: [{ clientCode: 'MKL246', carrierCode: 'FEDEX', copies: 3 }] }),
    upsertInvoiceCopies: (...a: unknown[]) => upsert(...a),
    deleteInvoiceCopiesTenantDefault: vi.fn(),
    deleteInvoiceCopiesClientRule: vi.fn(),
  },
}))
vi.mock('../api/accountRefService', () => ({
  accountRefService: {
    listAccounts: vi.fn().mockResolvedValue([{ id: 1, carrierCode: 'FEDEX', active: true, complete: true }]),
  },
}))
const notifyError = vi.fn()
vi.mock('../utils/notify', () => ({
  notify: { error: (...a: unknown[]) => notifyError(...a), apiError: vi.fn(), success: vi.fn(), info: vi.fn() },
}))

afterEach(() => cleanup())

describe('InvoiceCopiesMatrix', () => {
  it('BUG-131: 99 is refused with a toast and the box goes back to the saved 3', async () => {
    const { default: Matrix } = await import('./InvoiceCopiesMatrix')
    render(<Matrix clients={['MKL246']} />)
    const box = await screen.findByDisplayValue('3') as HTMLInputElement
    await userEvent.clear(box)
    await userEvent.type(box, '99')
    await userEvent.tab()
    await waitFor(() => expect(notifyError).toHaveBeenCalledWith(expect.objectContaining({ title: 'Invalid copies' })))
    expect(upsert).not.toHaveBeenCalled()
    expect(box.value).toBe('3')
  })
})
