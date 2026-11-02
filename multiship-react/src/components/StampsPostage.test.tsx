import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, cleanup, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { CarrierAccountRef } from '../api/accountRefService'

/** The Add funds dialog: what it shows, what it refuses, and that a retry is charged once. */

const balance = vi.fn()
const addFunds = vi.fn()
const history = vi.fn()
const pageUrl = vi.fn()
vi.mock('../api/stampsPostageService', () => ({
  stampsPostageService: {
    balance: (...a: unknown[]) => balance(...a),
    addFunds: (...a: unknown[]) => addFunds(...a),
    history: (...a: unknown[]) => history(...a),
    pageUrl: (...a: unknown[]) => pageUrl(...a),
  },
}))

const notifySuccess = vi.fn()
vi.mock('../utils/notify', () => ({
  notify: { success: (...a: unknown[]) => notifySuccess(...a), error: vi.fn(), apiError: vi.fn(), info: vi.fn() },
}))

const account = {
  id: 9, accountNumber: '874157', carrierCode: 'USPS', accountName: 'usps_malaya', environment: 'SANDBOX',
} as CarrierAccountRef

async function openDialog() {
  const { AddFundsForm } = await import('./StampsPostage')
  const onDone = vi.fn()
  render(<AddFundsForm account={account} onDone={onDone} />)
  await screen.findByText('$18.40')
  return onDone
}

beforeEach(() => {
  balance.mockReset().mockResolvedValue({ available: 18.4, maxBalance: 500, currency: 'USD' })
  addFunds.mockReset()
  history.mockReset().mockResolvedValue([])
  notifySuccess.mockReset()
})

afterEach(() => cleanup())

describe('StampsAddFundsDialog', () => {
  it('shows the balance and how much fits, and adds the picked amount', async () => {
    addFunds.mockResolvedValue({ funding: { id: 1 }, balance: { available: 68.4, maxBalance: 500, currency: 'USD' } })
    await openDialog()

    expect(screen.getByText('$481.60')).toBeTruthy()
    expect(screen.getByText('$68.40')).toBeTruthy() // new balance with the default $50
    await userEvent.click(screen.getByRole('button', { name: 'Add $50.00' }))

    await waitFor(() => expect(addFunds).toHaveBeenCalledTimes(1))
    const [id, amount, key] = addFunds.mock.calls[0]
    expect([id, amount]).toEqual([9, 50])
    expect(key).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/)
    await waitFor(() => expect(notifySuccess).toHaveBeenCalledWith('Added $50.00. New balance $68.40.'))
  })

  it('refuses less than $10 and more than the limit allows, without calling Stamps.com', async () => {
    await openDialog()
    const other = screen.getByRole('spinbutton', { name: 'Other amount' })

    await userEvent.type(other, '5')
    expect(screen.getByText('Add at least $10.00.')).toBeTruthy()

    await userEvent.clear(other)
    await userEvent.type(other, '490')
    expect(screen.getByText("That would go over Stamps.com's $500.00 balance limit. You can add up to $481.60.")).toBeTruthy()

    // BUG-080 — the money button is off while the amount is invalid.
    expect((screen.getByRole('button', { name: 'Add funds' }) as HTMLButtonElement).disabled).toBe(true)
    await userEvent.click(screen.getByRole('button', { name: 'Add funds' }))
    expect(addFunds).not.toHaveBeenCalled()
  })

  it('a retry after a failure reuses the same key, so it is charged once', async () => {
    addFunds
      .mockRejectedValueOnce(new Error('Network error'))
      .mockResolvedValueOnce({ funding: { id: 1 }, balance: null })
    await openDialog()

    await userEvent.click(screen.getByRole('button', { name: 'Add $50.00' }))
    expect(await screen.findByText('Network error')).toBeTruthy()
    await userEvent.click(screen.getByRole('button', { name: 'Add $50.00' }))

    await waitFor(() => expect(addFunds).toHaveBeenCalledTimes(2))
    expect(addFunds.mock.calls[1][2]).toBe(addFunds.mock.calls[0][2])
  })
})
