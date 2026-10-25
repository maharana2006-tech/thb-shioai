import { afterEach, describe, expect, it, vi } from 'vitest'
import { render, screen, cleanup, fireEvent, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import type { LabelArtifact } from '../../api/labelService'
import { LabelActions } from './LabelActions'

/**
 * BUG-028 — the Commercial invoice button used to render on every label
 * page; clicking it on a domestic order (which has no customs data) hit
 * a 422 and the generic apiError handler surfaced it as a red
 * "Something went wrong" toast. Two things now:
 *  · the button is hidden when artifact.hasCommercialInvoice is false
 *    (no customs data persisted for the order)
 *  · if the button IS clicked (international order whose customs were
 *    deleted after the artifact loaded, say), the 422 shows as an info
 *    toast, not an error.
 */

const notifyInfo = vi.fn()
const notifyApiError = vi.fn()
vi.mock('../../utils/notify', () => ({
  notify: {
    info: (...args: unknown[]) => notifyInfo(...args),
    success: vi.fn(),
    apiError: (...args: unknown[]) => notifyApiError(...args),
  },
}))

const getCommercialInvoicePdf = vi.fn()
vi.mock('../../api/orderService', () => ({
  orderService: {
    getCommercialInvoicePdf: (...args: unknown[]) => getCommercialInvoicePdf(...args),
  },
}))

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
})

function art(overrides: Partial<LabelArtifact> = {}): LabelArtifact {
  return {
    orderNo: 900058,
    state: 'GENERATED',
    carrier: 'UPS',
    environment: 'SANDBOX',
    generatedAt: '2026-10-22T11:19:39',
    packages: [{ seq: 1, format: 'ZPL', source: 'CARRIER_NATIVE', tracking: '1Z999AA10123456784', trackingUrl: null, generatedAt: null }],
    hasCommercialInvoice: false,
    ...overrides,
  }
}

describe('LabelActions · Commercial invoice (BUG-028)', () => {
  it('hides the button on a domestic order', () => {
    render(
      <MemoryRouter>
        <LabelActions artifact={art({ hasCommercialInvoice: false })} activeSeq={1} />
      </MemoryRouter>,
    )
    expect(screen.queryByRole('button', { name: /Commercial invoice/i })).toBeNull()
  })

  it('renders the button on an international order with customs', () => {
    render(
      <MemoryRouter>
        <LabelActions artifact={art({ hasCommercialInvoice: true })} activeSeq={1} />
      </MemoryRouter>,
    )
    expect(screen.getByRole('button', { name: /Commercial invoice/i })).toBeTruthy()
  })

  it('a 422 click surfaces as notify.info, not notify.apiError', async () => {
    const err: Error & { status?: number } = new Error('no customs')
    err.status = 422
    getCommercialInvoicePdf.mockRejectedValueOnce(err)

    render(
      <MemoryRouter>
        <LabelActions artifact={art({ hasCommercialInvoice: true })} activeSeq={1} />
      </MemoryRouter>,
    )
    fireEvent.click(screen.getByRole('button', { name: /Commercial invoice/i }))

    await waitFor(() => expect(notifyInfo).toHaveBeenCalledTimes(1))
    expect(notifyApiError).not.toHaveBeenCalled()
  })

  it('a non-422 error still routes to notify.apiError', async () => {
    const err: Error & { status?: number } = new Error('boom')
    err.status = 500
    getCommercialInvoicePdf.mockRejectedValueOnce(err)

    render(
      <MemoryRouter>
        <LabelActions artifact={art({ hasCommercialInvoice: true })} activeSeq={1} />
      </MemoryRouter>,
    )
    fireEvent.click(screen.getByRole('button', { name: /Commercial invoice/i }))

    await waitFor(() => expect(notifyApiError).toHaveBeenCalledTimes(1))
    expect(notifyInfo).not.toHaveBeenCalled()
  })
})
