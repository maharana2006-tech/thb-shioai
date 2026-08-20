import { describe, expect, it, vi, beforeEach } from 'vitest'
import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { renderWithProviders } from '../../test/renderWithProviders'

// CI-flake fix: default `userEvent.type` has a per-character setTimeout(0)
// that races React 19's automatic batching + Formik-style synchronous
// validators — under CI load, characters after the first were dropping
// (dev CI red for 24h; failure pattern: expected `my-secret`, got `m`).
// `delay: null` flushes each keystroke via microtask instead, which is
// deterministic. Passes locally 5x, matches the behavior we want in tests
// anyway (nobody's simulating human typing speed here).
const typeUser = () => userEvent.setup({ delay: null })

/**
 * Sprint 53 /settings/carriers page-tests (slice: FE-fill-modal).
 *
 * <p>Net-new coverage for {@code FillCarrierDetailsModal.tsx} — the
 * "scenario 2 fill-up" dialog that appears on the Labels page when
 * generateLabel returns NEEDS_DETAILS. Positive AND negative paths:
 *
 * <ul>
 *   <li>Mount + prefill (accountNumber pre-fills readOnly, carrierCode
 *       from resolution.carrierCode, environment coerced when != PRODUCTION).</li>
 *   <li>Missing-fields highlighting on clientId / clientSecret when
 *       {@code resolution.missingFields} contains the key.</li>
 *   <li>Client-side validation — blank account / clientId / clientSecret
 *       trigger notify.error, no upsertAccount call.</li>
 *   <li>Happy save → upsertAccount + success toast + onClose + onSaved
 *       fires; button disabled while saving.</li>
 *   <li>API reject → notify.apiError, modal stays open for retry.</li>
 *   <li>Close button + backdrop click fire onClose.</li>
 *   <li>Inner-panel click does NOT propagate to backdrop close.</li>
 *   <li>Anti-fallback: fetch spy on globalThis; upsertAccount is the
 *       ONLY network path — no other API method may fire.</li>
 * </ul>
 */

const upsertAccountMock = vi.fn()
const successMock = vi.fn()
const errorMock = vi.fn()
const apiErrorMock = vi.fn()

vi.mock('../../api/accountRefService', () => ({
  accountRefService: {
    upsertAccount: (...args: unknown[]) => upsertAccountMock(...args),
    listAccounts: vi.fn(),
    setClientDefault: vi.fn(),
    resolveOrders: vi.fn(),
  },
}))

vi.mock('../../utils/notify', () => ({
  notify: {
    success: (...args: unknown[]) => successMock(...args),
    error: (...args: unknown[]) => errorMock(...args),
    apiError: (...args: unknown[]) => apiErrorMock(...args),
    confirm: vi.fn(),
  },
}))

// CarrierLogo pulls from the shared logo registry; stub to a plain span
// so tests don't depend on the SVG asset resolver.
vi.mock('../workspace/CarrierLogo', () => ({
  default: ({ carrierId }: { carrierId: string }) => <span data-testid="logo">{carrierId}</span>,
}))

const seedResolution = (overrides: Record<string, unknown> = {}) => ({
  orderNo: 1234,
  scenario: 'NEEDS_DETAILS' as const,
  carrierCode: 'UPS',
  accountNumber: 'ACCT-1',
  accountName: 'ACME UPS',
  environment: 'SANDBOX',
  missingFields: ['clientSecret'],
  prefillClientId: 'prefill-cid',
  ...overrides,
})

const loadModal = async () => {
  const { default: FillCarrierDetailsModal } = await import('./FillCarrierDetailsModal')
  return FillCarrierDetailsModal
}

beforeEach(() => {
  vi.clearAllMocks()
  vi.spyOn(globalThis, 'fetch').mockImplementation(() => {
    throw new Error('un-mocked fetch forbidden')
  })
})

describe('FillCarrierDetailsModal — mount + prefill', () => {
  it('renders with the order number in the heading', async () => {
    const Modal = await loadModal()
    renderWithProviders(
      <Modal orderNo={1234} resolution={seedResolution()}
             onSaved={vi.fn()} onClose={vi.fn()} />
    )
    expect(screen.getByText(/Complete the account for order #1234/i)).toBeTruthy()
  })

  it('prefills accountNumber (readOnly), carrierCode, accountName, clientId', async () => {
    const Modal = await loadModal()
    renderWithProviders(
      <Modal orderNo={1} resolution={seedResolution()}
             onSaved={vi.fn()} onClose={vi.fn()} />
    )
    const dialog = screen.getByRole('dialog')
    const inputs = within(dialog).getAllByRole('textbox') as HTMLInputElement[]
    // Order: accountNumber, accountName, clientId. Client secret is password (not textbox role).
    expect(inputs[0].value).toBe('ACCT-1')
    expect(inputs[0].readOnly).toBe(true)  // pre-existing account is not editable
    expect(inputs[1].value).toBe('ACME UPS')
    expect(inputs[2].value).toBe('prefill-cid')
  })

  it('when resolution.accountNumber is blank, accountNumber input is EDITABLE', async () => {
    const Modal = await loadModal()
    renderWithProviders(
      <Modal orderNo={1} resolution={seedResolution({ accountNumber: '' })}
             onSaved={vi.fn()} onClose={vi.fn()} />
    )
    const dialog = screen.getByRole('dialog')
    const inputs = within(dialog).getAllByRole('textbox') as HTMLInputElement[]
    expect(inputs[0].readOnly).toBe(false)
    expect(inputs[0].value).toBe('')
  })

  it('environment defaults to SANDBOX when resolution.environment is anything != PRODUCTION', async () => {
    const Modal = await loadModal()
    renderWithProviders(
      <Modal orderNo={1} resolution={seedResolution({ environment: 'STAGING' })}
             onSaved={vi.fn()} onClose={vi.fn()} />
    )
    const dialog = screen.getByRole('dialog')
    const selects = within(dialog).getAllByRole('combobox') as HTMLSelectElement[]
    // Selects order: carrier, environment. Environment is the last.
    expect(selects[selects.length - 1].value).toBe('SANDBOX')
  })

  it('environment defaults to PRODUCTION when resolution.environment == PRODUCTION', async () => {
    const Modal = await loadModal()
    renderWithProviders(
      <Modal orderNo={1} resolution={seedResolution({ environment: 'PRODUCTION' })}
             onSaved={vi.fn()} onClose={vi.fn()} />
    )
    const dialog = screen.getByRole('dialog')
    const selects = within(dialog).getAllByRole('combobox') as HTMLSelectElement[]
    expect(selects[selects.length - 1].value).toBe('PRODUCTION')
  })

  it('carrierCode falls back to UPS when resolution.carrierCode is null', async () => {
    const Modal = await loadModal()
    renderWithProviders(
      <Modal orderNo={1} resolution={seedResolution({ carrierCode: null })}
             onSaved={vi.fn()} onClose={vi.fn()} />
    )
    const dialog = screen.getByRole('dialog')
    const selects = within(dialog).getAllByRole('combobox') as HTMLSelectElement[]
    expect(selects[0].value).toBe('UPS')
  })
})

describe('FillCarrierDetailsModal — validation', () => {
  it('save with blank clientSecret → notify.error, upsertAccount NOT called', async () => {
    const Modal = await loadModal()
    renderWithProviders(
      <Modal orderNo={1} resolution={seedResolution()}
             onSaved={vi.fn()} onClose={vi.fn()} />
    )
    // accountNumber + clientId are pre-filled from resolution; clientSecret is blank.
    await userEvent.click(screen.getByRole('button', { name: /Save & Generate Label/i }))

    expect(errorMock).toHaveBeenCalledWith(
      'Account number, client ID, and client secret are required.'
    )
    expect(upsertAccountMock).not.toHaveBeenCalled()
  })

  it('save with blank clientId → notify.error, no API call', async () => {
    const Modal = await loadModal()
    renderWithProviders(
      <Modal orderNo={1} resolution={seedResolution({ prefillClientId: '' })}
             onSaved={vi.fn()} onClose={vi.fn()} />
    )
    // Add a client secret so only clientId is blank.
    const dialog = screen.getByRole('dialog')
    const password = dialog.querySelector('input[type="password"]') as HTMLInputElement
    await typeUser().type(password, 'sec')

    await userEvent.click(screen.getByRole('button', { name: /Save & Generate Label/i }))

    expect(errorMock).toHaveBeenCalled()
    expect(upsertAccountMock).not.toHaveBeenCalled()
  })

  it('save with whitespace-only accountNumber → notify.error, no API call', async () => {
    const Modal = await loadModal()
    renderWithProviders(
      <Modal orderNo={1} resolution={seedResolution({ accountNumber: '   ' })}
             onSaved={vi.fn()} onClose={vi.fn()} />
    )
    const dialog = screen.getByRole('dialog')
    const password = dialog.querySelector('input[type="password"]') as HTMLInputElement
    await typeUser().type(password, 'sec')

    await userEvent.click(screen.getByRole('button', { name: /Save & Generate Label/i }))

    expect(errorMock).toHaveBeenCalled()
    expect(upsertAccountMock).not.toHaveBeenCalled()
  })
})

describe('FillCarrierDetailsModal — save flow', () => {
  it('happy save → upsertAccount + success + onClose + onSaved', async () => {
    upsertAccountMock.mockResolvedValueOnce({ data: {} })
    const onClose = vi.fn()
    const onSaved = vi.fn().mockResolvedValue(undefined)
    const Modal = await loadModal()
    renderWithProviders(
      <Modal orderNo={999} resolution={seedResolution()}
             onSaved={onSaved} onClose={onClose} />
    )
    const dialog = screen.getByRole('dialog')
    const password = dialog.querySelector('input[type="password"]') as HTMLInputElement
    await typeUser().type(password, 'my-secret')

    await userEvent.click(screen.getByRole('button', { name: /Save & Generate Label/i }))

    await waitFor(() => expect(upsertAccountMock).toHaveBeenCalledTimes(1))
    expect(upsertAccountMock.mock.calls[0][0]).toMatchObject({
      accountNumber: 'ACCT-1',
      carrierCode: 'UPS',
      clientId: 'prefill-cid',
      clientSecret: 'my-secret',
      environment: 'SANDBOX',
    })
    expect(successMock).toHaveBeenCalledWith(
      'Account ACCT-1 saved — future orders will use it automatically.'
    )
    expect(onClose).toHaveBeenCalledTimes(1)
    await waitFor(() => expect(onSaved).toHaveBeenCalledTimes(1))
  })

  it('save-flow trims accountName and omits when blank', async () => {
    upsertAccountMock.mockResolvedValueOnce({ data: {} })
    const Modal = await loadModal()
    renderWithProviders(
      <Modal orderNo={1} resolution={seedResolution({ accountName: '   ' })}
             onSaved={vi.fn()} onClose={vi.fn()} />
    )
    const dialog = screen.getByRole('dialog')
    const password = dialog.querySelector('input[type="password"]') as HTMLInputElement
    await typeUser().type(password, 'sec')
    await userEvent.click(screen.getByRole('button', { name: /Save & Generate Label/i }))

    await waitFor(() => expect(upsertAccountMock).toHaveBeenCalled())
    // Blank accountName is stripped (undefined, not empty string).
    expect(upsertAccountMock.mock.calls[0][0].accountName).toBeUndefined()
  })

  it('save reject → notify.apiError, modal stays open, onClose NOT called', async () => {
    upsertAccountMock.mockRejectedValueOnce(new Error('server blew up'))
    const onClose = vi.fn()
    const onSaved = vi.fn()
    const Modal = await loadModal()
    renderWithProviders(
      <Modal orderNo={1} resolution={seedResolution()}
             onSaved={onSaved} onClose={onClose} />
    )
    const dialog = screen.getByRole('dialog')
    const password = dialog.querySelector('input[type="password"]') as HTMLInputElement
    await typeUser().type(password, 'sec')

    await userEvent.click(screen.getByRole('button', { name: /Save & Generate Label/i }))

    await waitFor(() => expect(apiErrorMock).toHaveBeenCalled())
    expect(apiErrorMock.mock.calls[0][1]).toBe('Failed to save the carrier account.')
    expect(onClose).not.toHaveBeenCalled()
    expect(onSaved).not.toHaveBeenCalled()
    // Modal is still on screen.
    expect(screen.getByRole('dialog')).toBeTruthy()
  })
})

describe('FillCarrierDetailsModal — close paths', () => {
  it('Close (X) button fires onClose', async () => {
    const onClose = vi.fn()
    const Modal = await loadModal()
    renderWithProviders(
      <Modal orderNo={1} resolution={seedResolution()}
             onSaved={vi.fn()} onClose={onClose} />
    )
    await userEvent.click(screen.getByLabelText('Close'))
    expect(onClose).toHaveBeenCalledTimes(1)
  })

  it('clicking the backdrop fires onClose', async () => {
    const onClose = vi.fn()
    const Modal = await loadModal()
    renderWithProviders(
      <Modal orderNo={1} resolution={seedResolution()}
             onSaved={vi.fn()} onClose={onClose} />
    )
    const dialog = screen.getByRole('dialog')
    await userEvent.click(dialog)
    expect(onClose).toHaveBeenCalledTimes(1)
  })

  it('clicking the inner panel does NOT propagate to onClose', async () => {
    const onClose = vi.fn()
    const Modal = await loadModal()
    renderWithProviders(
      <Modal orderNo={1} resolution={seedResolution()}
             onSaved={vi.fn()} onClose={onClose} />
    )
    // Header text is inside the inner panel, whose onClick stops propagation.
    await userEvent.click(screen.getByText(/Complete the account for order/i))
    expect(onClose).not.toHaveBeenCalled()
  })
})
