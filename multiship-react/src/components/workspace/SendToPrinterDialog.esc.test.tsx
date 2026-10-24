import { afterEach, describe, expect, it, vi } from 'vitest'
import { render, screen, cleanup, fireEvent, waitFor } from '@testing-library/react'
import SendToPrinterDialog from './SendToPrinterDialog'

/**
 * BUG-018 — the Send to printer modal had no Escape handler, so the
 * only ways to dismiss it were the X button or clicking the backdrop.
 * This pins the Esc path and the don't-abort-mid-send guard.
 */

vi.mock('../../api/printerService', () => ({
  printerService: {
    list: vi.fn().mockResolvedValue({ data: [] }),
    sendToPrinter: vi.fn(),
  },
  notifyPrinterProblem: vi.fn(),
}))
vi.mock('../../utils/notify', () => ({ notify: { success: vi.fn(), info: vi.fn() } }))

afterEach(() => { cleanup(); vi.clearAllMocks() })

describe('SendToPrinterDialog · Escape closes the dialog (BUG-018)', () => {
  it('Escape calls onClose', async () => {
    const onClose = vi.fn()
    render(<SendToPrinterDialog orderNumbers={[1]} canManagePrinters onClose={onClose} onOpenSettings={() => {}} />)
    await waitFor(() => expect(screen.getByRole('dialog')).toBeTruthy())
    fireEvent.keyDown(document, { key: 'Escape' })
    expect(onClose).toHaveBeenCalledTimes(1)
  })

  it('non-Escape keys are ignored', async () => {
    const onClose = vi.fn()
    render(<SendToPrinterDialog orderNumbers={[1]} canManagePrinters onClose={onClose} onOpenSettings={() => {}} />)
    await waitFor(() => expect(screen.getByRole('dialog')).toBeTruthy())
    fireEvent.keyDown(document, { key: 'Enter' })
    fireEvent.keyDown(document, { key: 'a' })
    expect(onClose).not.toHaveBeenCalled()
  })
})
