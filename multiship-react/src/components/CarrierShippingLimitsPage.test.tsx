import { describe, expect, it, vi, beforeEach } from 'vitest'
import { screen, waitFor, within, act, fireEvent } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { renderWithProviders } from '../test/renderWithProviders'

/**
 * Sprint 53 /settings/carriers page-tests (slice: FE-limits).
 *
 * <p>Expands the smoke coverage that previously lived here into a
 * full positive + negative suite for CarrierShippingLimitsPage:
 *
 * <ul>
 *   <li>Mount + load + row rendering (active vs inactive styling,
 *       null service-code fallback, null commodities/weight/free-DV
 *       fallback).</li>
 *   <li>Loading state; empty state; API-error toast (defensive).</li>
 *   <li>Client-side filters — carrier substring, scope dropdown.</li>
 *   <li>Row actions — toggle active, delete (both confirm-YES and
 *       confirm-NO paths).</li>
 *   <li>Editor dialog — opens for New + Edit; client-side validation
 *       (blank carrier, invalid max-packages); save happy → API +
 *       refetch; cancel → dialog closes; Escape-to-close.</li>
 *   <li>Anti-fallback: fetch is fail-loud spy'd on globalThis so any
 *       un-mocked network call throws immediately with a discoverable
 *       message.</li>
 * </ul>
 */

const listMock = vi.fn()
const createMock = vi.fn()
const updateMock = vi.fn()
const removeMock = vi.fn()
const confirmMock = vi.fn()
const successMock = vi.fn()
const apiErrorMock = vi.fn()

vi.mock('../api/carrierShippingLimitService', () => ({
  carrierShippingLimitService: {
    list: (...args: unknown[]) => listMock(...args),
    get: vi.fn(),
    create: (...args: unknown[]) => createMock(...args),
    update: (...args: unknown[]) => updateMock(...args),
    remove: (...args: unknown[]) => removeMock(...args),
  },
}))

vi.mock('../utils/notify', () => ({
  notify: {
    success: (...args: unknown[]) => successMock(...args),
    apiError: (...args: unknown[]) => apiErrorMock(...args),
    error: vi.fn(),
    confirm: (...args: unknown[]) => confirmMock(...args),
  },
}))

const seedRows = () => [
  {
    id: 1,
    carrierCode: 'UPS',
    serviceCode: 'UPS_GROUND',
    scope: 'DOMESTIC',
    direction: 'FORWARD',
    maxPackages: 20,
    maxCommodities: 50,
    maxTotalWeightLb: 150,
    freeDeclaredValue: 100,
    effectiveFrom: '2026-08-01T00:00:00',
    effectiveUntil: null,
    active: true,
    notes: 'seeded',
  },
  {
    id: 2,
    carrierCode: 'FEDEX',
    serviceCode: null,
    scope: 'INTERNATIONAL',
    direction: null,
    maxPackages: 40,
    maxCommodities: null,
    maxTotalWeightLb: null,
    freeDeclaredValue: null,
    effectiveFrom: '2026-08-01T00:00:00',
    effectiveUntil: null,
    active: false,
    notes: null,
  },
]

const loadPage = async () => {
  const { default: CarrierShippingLimitsPage } = await import('./CarrierShippingLimitsPage')
  return CarrierShippingLimitsPage
}

beforeEach(() => {
  vi.clearAllMocks()
  listMock.mockResolvedValue({ data: seedRows() })
  // Fail-loud: any test that accidentally triggers a real fetch throws.
  vi.spyOn(globalThis, 'fetch').mockImplementation(() => {
    throw new Error('un-mocked fetch forbidden')
  })
})

describe('CarrierShippingLimitsPage — mount + load', () => {
  it('renders the seeded rows after list() resolves', async () => {
    const Page = await loadPage()
    renderWithProviders(<Page />)

    await waitFor(() => expect(screen.getByText('UPS')).toBeTruthy())
    expect(screen.getByText('FEDEX')).toBeTruthy()
    expect(screen.getByText('UPS_GROUND')).toBeTruthy()
    // Null service-code renders as the "(default)" fallback.
    expect(screen.getByText('(default)')).toBeTruthy()
  })

  it('calls list with { size: 200 } on mount (one fetch, not a loop)', async () => {
    const Page = await loadPage()
    renderWithProviders(<Page />)
    await waitFor(() => expect(listMock).toHaveBeenCalled())
    expect(listMock).toHaveBeenCalledTimes(1)
    expect(listMock).toHaveBeenCalledWith({ size: 200 })
  })

  it('shows "Loading…" before the fetch resolves', async () => {
    let resolveIt: (v: unknown) => void = () => {}
    listMock.mockReturnValueOnce(new Promise((r) => { resolveIt = r }))
    const Page = await loadPage()
    renderWithProviders(<Page />)
    expect(screen.getByText(/Loading…/i)).toBeTruthy()
    await act(async () => { resolveIt({ data: [] }) })
  })

  it('renders "No limit rows." when the list is empty', async () => {
    listMock.mockResolvedValueOnce({ data: [] })
    const Page = await loadPage()
    renderWithProviders(<Page />)
    await waitFor(() => expect(screen.getByText(/No limit rows\./i)).toBeTruthy())
  })

  it('calls notify.apiError when list() rejects — no rows shown', async () => {
    listMock.mockRejectedValueOnce(new Error('boom'))
    const Page = await loadPage()
    renderWithProviders(<Page />)
    await waitFor(() => expect(apiErrorMock).toHaveBeenCalled())
    expect(apiErrorMock.mock.calls[0][1]).toBe('Failed to load carrier limits.')
    expect(screen.queryByText('UPS')).toBeNull()
  })
})

describe('CarrierShippingLimitsPage — row rendering', () => {
  it('null commodities / weight / free-DV render as em-dash placeholders', async () => {
    const Page = await loadPage()
    renderWithProviders(<Page />)
    await waitFor(() => expect(screen.getByText('FEDEX')).toBeTruthy())
    // The FEDEX row has 3 null numeric fields → 3 em-dashes in the row.
    const fedexRow = screen.getByText('FEDEX').closest('tr')!
    expect(within(fedexRow).getAllByText('—').length).toBeGreaterThanOrEqual(3)
  })

  it('active row shows the Active pill; inactive row shows Inactive', async () => {
    const Page = await loadPage()
    renderWithProviders(<Page />)
    await waitFor(() => expect(screen.getByText('UPS')).toBeTruthy())
    // At least one of each status pill on the seeded rows.
    expect(screen.getByText(/^Active$/)).toBeTruthy()
    expect(screen.getByText(/^Inactive$/)).toBeTruthy()
  })

  it('null direction renders as "any"', async () => {
    const Page = await loadPage()
    renderWithProviders(<Page />)
    await waitFor(() => expect(screen.getByText('UPS')).toBeTruthy())
    const fedexRow = screen.getByText('FEDEX').closest('tr')!
    expect(within(fedexRow).getByText(/^any$/)).toBeTruthy()
  })
})

describe('CarrierShippingLimitsPage — filters', () => {
  it('carrier filter narrows the visible rows (substring match)', async () => {
    const Page = await loadPage()
    renderWithProviders(<Page />)
    await waitFor(() => expect(screen.getByText('FEDEX')).toBeTruthy())

    const search = screen.getByPlaceholderText(/Filter by carrier/i)
    fireEvent.change(search, { target: { value: 'ups' } })

    expect(screen.getByText('UPS')).toBeTruthy()
    expect(screen.queryByText('FEDEX')).toBeNull()
  })

  it('scope filter narrows to matching rows only', async () => {
    const Page = await loadPage()
    renderWithProviders(<Page />)
    await waitFor(() => expect(screen.getByText('UPS')).toBeTruthy())

    // Scope select is the only <select> in the filter section.
    const select = screen.getAllByRole('combobox')[0]
    await userEvent.selectOptions(select, 'INTERNATIONAL')

    expect(screen.getByText('FEDEX')).toBeTruthy()
    expect(screen.queryByText('UPS')).toBeNull()
  })
})

describe('CarrierShippingLimitsPage — row actions', () => {
  it('Deactivate on an active row → update() then success + refetch', async () => {
    updateMock.mockResolvedValueOnce({})
    const Page = await loadPage()
    renderWithProviders(<Page />)
    await waitFor(() => expect(screen.getByText('UPS')).toBeTruthy())
    listMock.mockClear()
    listMock.mockResolvedValue({ data: seedRows() })

    const upsRow = screen.getByText('UPS').closest('tr')!
    await userEvent.click(within(upsRow).getByRole('button', { name: /Deactivate/i }))

    await waitFor(() => expect(updateMock).toHaveBeenCalledTimes(1))
    expect(updateMock.mock.calls[0][0]).toBe(1)
    expect(updateMock.mock.calls[0][1].active).toBe(false)
    expect(successMock).toHaveBeenCalledWith('Deactivated UPS/UPS_GROUND row.')
    await waitFor(() => expect(listMock).toHaveBeenCalled())
  })

  it('Activate on an inactive row → success message uses the "(default)" service label', async () => {
    updateMock.mockResolvedValueOnce({})
    const Page = await loadPage()
    renderWithProviders(<Page />)
    await waitFor(() => expect(screen.getByText('FEDEX')).toBeTruthy())

    const fedexRow = screen.getByText('FEDEX').closest('tr')!
    await userEvent.click(within(fedexRow).getByRole('button', { name: /^Activate$/i }))

    await waitFor(() => expect(updateMock).toHaveBeenCalledTimes(1))
    expect(updateMock.mock.calls[0][1].active).toBe(true)
    expect(successMock).toHaveBeenCalledWith('Activated FEDEX/(default) row.')
  })

  it('toggle when update() rejects → notify.apiError; no crash', async () => {
    updateMock.mockRejectedValueOnce(new Error('boom'))
    const Page = await loadPage()
    renderWithProviders(<Page />)
    await waitFor(() => expect(screen.getByText('UPS')).toBeTruthy())

    const upsRow = screen.getByText('UPS').closest('tr')!
    await userEvent.click(within(upsRow).getByRole('button', { name: /Deactivate/i }))

    await waitFor(() => expect(apiErrorMock).toHaveBeenCalled())
    expect(apiErrorMock.mock.calls[0][1]).toBe('Failed to update row.')
  })

  it('Delete confirm YES → remove() + success + refetch', async () => {
    confirmMock.mockResolvedValueOnce(true)
    removeMock.mockResolvedValueOnce({})
    const Page = await loadPage()
    renderWithProviders(<Page />)
    await waitFor(() => expect(screen.getByText('UPS')).toBeTruthy())
    listMock.mockClear()
    listMock.mockResolvedValue({ data: [] })

    const upsRow = screen.getByText('UPS').closest('tr')!
    await userEvent.click(within(upsRow).getByTitle('Delete row'))

    await waitFor(() => expect(removeMock).toHaveBeenCalledTimes(1))
    expect(removeMock).toHaveBeenCalledWith(1)
    expect(successMock).toHaveBeenCalledWith('Row deleted.')
    await waitFor(() => expect(listMock).toHaveBeenCalled())
  })

  it('Delete confirm NO → remove() is NOT called', async () => {
    confirmMock.mockResolvedValueOnce(false)
    const Page = await loadPage()
    renderWithProviders(<Page />)
    await waitFor(() => expect(screen.getByText('UPS')).toBeTruthy())

    const upsRow = screen.getByText('UPS').closest('tr')!
    await userEvent.click(within(upsRow).getByTitle('Delete row'))

    await waitFor(() => expect(confirmMock).toHaveBeenCalled())
    expect(removeMock).not.toHaveBeenCalled()
  })

  it('Delete when remove() rejects → notify.apiError', async () => {
    confirmMock.mockResolvedValueOnce(true)
    removeMock.mockRejectedValueOnce(new Error('boom'))
    const Page = await loadPage()
    renderWithProviders(<Page />)
    await waitFor(() => expect(screen.getByText('UPS')).toBeTruthy())

    const upsRow = screen.getByText('UPS').closest('tr')!
    await userEvent.click(within(upsRow).getByTitle('Delete row'))

    await waitFor(() => expect(apiErrorMock).toHaveBeenCalled())
    expect(apiErrorMock.mock.calls[0][1]).toBe('Failed to delete row.')
  })
})

describe('CarrierShippingLimitsPage — editor dialog', () => {
  it('New row button opens the "New carrier limit" dialog', async () => {
    const Page = await loadPage()
    renderWithProviders(<Page />)
    await waitFor(() => expect(screen.getByText('UPS')).toBeTruthy())

    await userEvent.click(screen.getByRole('button', { name: /New row/i }))
    expect(screen.getByRole('dialog')).toBeTruthy()
    expect(screen.getByText(/New carrier limit/i)).toBeTruthy()
  })

  it('Edit button opens the "Edit carrier limit" dialog pre-filled with row values', async () => {
    const Page = await loadPage()
    renderWithProviders(<Page />)
    await waitFor(() => expect(screen.getByText('UPS')).toBeTruthy())

    const upsRow = screen.getByText('UPS').closest('tr')!
    await userEvent.click(within(upsRow).getByRole('button', { name: /Edit/i }))
    expect(screen.getByRole('dialog')).toBeTruthy()
    expect(screen.getByText(/Edit carrier limit/i)).toBeTruthy()
    // Carrier-code input pre-filled with 'UPS'.
    const dialog = screen.getByRole('dialog')
    const inputs = within(dialog).getAllByRole('textbox') as HTMLInputElement[]
    expect(inputs[0].value).toBe('UPS')
  })

  it('blank carrier code → validation error banner, no API call', async () => {
    const Page = await loadPage()
    renderWithProviders(<Page />)
    await waitFor(() => expect(screen.getByText('UPS')).toBeTruthy())

    await userEvent.click(screen.getByRole('button', { name: /New row/i }))
    await userEvent.click(screen.getByRole('button', { name: /^Create$/i }))

    expect(screen.getByText(/Carrier code is required\./i)).toBeTruthy()
    expect(createMock).not.toHaveBeenCalled()
  })

  it('max-packages below 1 → validation error, no API call', async () => {
    const Page = await loadPage()
    renderWithProviders(<Page />)
    await waitFor(() => expect(screen.getByText('UPS')).toBeTruthy())

    await userEvent.click(screen.getByRole('button', { name: /New row/i }))
    const dialog = screen.getByRole('dialog')
    const inputs = within(dialog).getAllByRole('textbox') as HTMLInputElement[]
    // fireEvent.change is deterministic in CI where userEvent.type raced.
    fireEvent.change(inputs[0], { target: { value: 'UPS' } })
    // Number inputs are role='spinbutton' in jsdom; grab them all + set pkgs=0.
    const numbers = within(dialog).getAllByRole('spinbutton') as HTMLInputElement[]
    fireEvent.change(numbers[0], { target: { value: '0' } })

    await userEvent.click(screen.getByRole('button', { name: /^Create$/i }))
    expect(screen.getByText(/Max packages must be an integer between 1 and 9999\./i)).toBeTruthy()
    expect(createMock).not.toHaveBeenCalled()
  })

  it('happy save → create() + success + refetch + dialog closes', async () => {
    createMock.mockResolvedValueOnce({})
    const Page = await loadPage()
    renderWithProviders(<Page />)
    await waitFor(() => expect(screen.getByText('UPS')).toBeTruthy())
    listMock.mockClear()
    listMock.mockResolvedValue({ data: seedRows() })

    await userEvent.click(screen.getByRole('button', { name: /New row/i }))
    const dialog = screen.getByRole('dialog')
    const textInputs = within(dialog).getAllByRole('textbox') as HTMLInputElement[]
    // fireEvent.change avoids the userEvent.type-per-char CI race that
    // dropped 'HL' and made the payload carrierCode='D'.
    fireEvent.change(textInputs[0], { target: { value: 'DHL' } })
    const numbers = within(dialog).getAllByRole('spinbutton') as HTMLInputElement[]
    fireEvent.change(numbers[0], { target: { value: '10' } })

    await userEvent.click(screen.getByRole('button', { name: /^Create$/i }))

    await waitFor(() => expect(createMock).toHaveBeenCalledTimes(1))
    expect(createMock.mock.calls[0][0].carrierCode).toBe('DHL')
    expect(createMock.mock.calls[0][0].maxPackages).toBe(10)
    expect(successMock).toHaveBeenCalledWith('Created DHL/(default).')
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
  })

  it('Cancel button closes the dialog without any API call', async () => {
    const Page = await loadPage()
    renderWithProviders(<Page />)
    await waitFor(() => expect(screen.getByText('UPS')).toBeTruthy())

    await userEvent.click(screen.getByRole('button', { name: /New row/i }))
    await userEvent.click(screen.getByRole('button', { name: /^Cancel$/i }))

    expect(screen.queryByRole('dialog')).toBeNull()
    expect(createMock).not.toHaveBeenCalled()
    expect(updateMock).not.toHaveBeenCalled()
  })

  it('Escape key closes the dialog', async () => {
    const Page = await loadPage()
    renderWithProviders(<Page />)
    await waitFor(() => expect(screen.getByText('UPS')).toBeTruthy())

    await userEvent.click(screen.getByRole('button', { name: /New row/i }))
    expect(screen.getByRole('dialog')).toBeTruthy()

    await userEvent.keyboard('{Escape}')
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
  })

  it('edit save reject → notify.apiError, dialog stays open', async () => {
    updateMock.mockRejectedValueOnce(new Error('boom'))
    const Page = await loadPage()
    renderWithProviders(<Page />)
    await waitFor(() => expect(screen.getByText('UPS')).toBeTruthy())

    const upsRow = screen.getByText('UPS').closest('tr')!
    await userEvent.click(within(upsRow).getByRole('button', { name: /Edit/i }))

    await userEvent.click(screen.getByRole('button', { name: /^Save$/i }))

    await waitFor(() => expect(apiErrorMock).toHaveBeenCalled())
    expect(apiErrorMock.mock.calls[0][1]).toBe('Failed to update row.')
    // Dialog remains open for the operator to retry.
    expect(screen.getByRole('dialog')).toBeTruthy()
  })
})
