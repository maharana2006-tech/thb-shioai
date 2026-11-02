import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, cleanup, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'

/** Close out the day: where the list comes from, how lines are read, and what a dead server says. */

const openTrackings = vi.fn()
const closeOut = vi.fn()
vi.mock('../../api/manifestService', () => ({
  manifestService: {
    openTrackings: (...a: unknown[]) => openTrackings(...a),
    closeOut: (...a: unknown[]) => closeOut(...a),
    closeOutDay: vi.fn(),
  },
}))
vi.mock('../../hooks/useConnectedCarriers', () => ({ useConnectedCarriers: () => new Set(['UPS', 'USPS']) }))
vi.mock('../../utils/notify', () => ({
  notify: { success: vi.fn(), error: vi.fn(), apiError: vi.fn(), info: vi.fn(), confirm: vi.fn().mockResolvedValue(true) },
}))

async function open() {
  const { default: CloseOutModal } = await import('./CloseOutModal')
  render(<MemoryRouter><CloseOutModal onClose={vi.fn()} trackingNumbers={[]} defaults={{ carrierCode: 'USPS' }} /></MemoryRouter>)
  return screen.findByPlaceholderText(/One tracking number per line/)
}

beforeEach(() => {
  openTrackings.mockReset().mockResolvedValue({ data: ['9434650899563003347255'] })
  closeOut.mockReset()
})
afterEach(() => cleanup())

describe('CloseOutModal', () => {
  it("lists the carrier's open labels for the day from the server (BUG-011)", async () => {
    const box = await open()
    await waitFor(() => expect((box as HTMLTextAreaElement).value).toBe('9434650899563003347255'))
    expect(openTrackings).toHaveBeenCalledWith(expect.objectContaining({ carrierCode: 'USPS' }))
    expect(screen.getByText('Tracking numbers (1)')).toBeTruthy()
  })

  it('reads one number per line: spaces stripped, case-insensitive duplicates dropped, free text flagged (BUG-001/024)', async () => {
    const box = await open()
    await waitFor(() => expect((box as HTMLTextAreaElement).value).not.toBe(''))
    await userEvent.clear(box)
    await userEvent.type(box, '9400 1000 0000 0000 0000 00{enter}1Z999AA10123456784{enter}  1z999aa10123456784  {enter}not a tracking #')
    expect(screen.getByText('Tracking numbers (2)')).toBeTruthy()
    expect(screen.getByText(/Ignored 1 line/)).toBeTruthy()
  })

  it("clears the list when the open labels can't be loaded (no stale numbers under a new heading)", async () => {
    openTrackings.mockReset().mockRejectedValue(new Error('boom'))
    const box = await open()
    await waitFor(() => expect(screen.getByText(/couldn't load open labels/)).toBeTruthy())
    expect((box as HTMLTextAreaElement).value).toBe('')
  })

  it("says plainly that the server wasn't reached, inside the dialog (BUG-004)", async () => {
    closeOut.mockRejectedValue(new TypeError('Failed to fetch'))
    const box = await open()
    await waitFor(() => expect((box as HTMLTextAreaElement).value).not.toBe(''))
    await userEvent.click(screen.getByRole('button', { name: /Close out selected/ }))
    expect(await screen.findByText(/Couldn't reach the Multiship server — nothing was booked/)).toBeTruthy()
  })

  // BUG-017 — the Close whole day button used to open the irreversible
  // confirm with a bare "? " in the date slot when the input was empty.
  it("disables Close whole day + Close out selected when the date is blank (BUG-017)", async () => {
    await open()
    const dateInput = screen.getByLabelText(/Close date/) as HTMLInputElement
    await userEvent.clear(dateInput)
    expect(dateInput.value).toBe('')
    expect(screen.getByText(/Pick a date before closing/)).toBeTruthy()
    expect((screen.getByRole('button', { name: /Close whole day/ }) as HTMLButtonElement).disabled).toBe(true)
    expect((screen.getByRole('button', { name: /Close out selected/ }) as HTMLButtonElement).disabled).toBe(true)
  })
})
