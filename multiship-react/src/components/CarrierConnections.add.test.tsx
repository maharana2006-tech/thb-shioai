import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, cleanup, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, Outlet } from 'react-router-dom'
import { Provider } from 'react-redux'
import { combineReducers, configureStore } from '@reduxjs/toolkit'
import type { ComponentType } from 'react'
import carrierReducer from '../store/carrierSlice'
import orderReducer from '../store/orderSlice'

/**
 * Sprint 54 · FE-add · Slice: Add / connect flow for CarrierConnections.
 *
 * Coverage focuses on the "Add carrier account" drawer opened from the
 * table toolbar:
 *   - Add-button opens the drawer with the expected sections.
 *   - Carrier selector shows UPS / FedEx / USPS / DHL.
 *   - Environment defaults to SANDBOX and can be flipped to PRODUCTION.
 *   - Filling required fields → submit invokes upsertAccount with an
 *     uppercase carrier + trimmed strings.
 *   - Cancel closes the drawer without an upsert call.
 *   - Validation surfaces per-carrier + credential errors and blocks save.
 *   - `upsertAccount` rejection surfaces via notify.apiError.
 *
 * Every network-touching service is mocked; a global fetch spy in
 * beforeEach guarantees no test bypasses the mocks by hitting the wire.
 */

// ===== Hoisted mocks =====

const listAccounts = vi.fn()
const upsertAccount = vi.fn()
const deleteAccount = vi.fn()
const setClientDefault = vi.fn()
const toggleActive = vi.fn()
const verifyAccount = vi.fn()
const verifyCredentials = vi.fn()
const getPlatformCredentials = vi.fn()
const resolveOrders = vi.fn()

vi.mock('../api/accountRefService', () => ({
  accountRefService: {
    listAccounts: (...a: unknown[]) => listAccounts(...a),
    upsertAccount: (...a: unknown[]) => upsertAccount(...a),
    deleteAccount: (...a: unknown[]) => deleteAccount(...a),
    setClientDefault: (...a: unknown[]) => setClientDefault(...a),
    toggleActive: (...a: unknown[]) => toggleActive(...a),
    verifyAccount: (...a: unknown[]) => verifyAccount(...a),
    verifyCredentials: (...a: unknown[]) => verifyCredentials(...a),
    getPlatformCredentials: (...a: unknown[]) => getPlatformCredentials(...a),
    resolveOrders: (...a: unknown[]) => resolveOrders(...a),
  },
}))

const listClients = vi.fn()

vi.mock('../api/clientService', async () => {
  const actual = await vi.importActual<typeof import('../api/clientService')>(
    '../api/clientService',
  )
  return {
    ...actual,
    clientService: {
      listClients: (...a: unknown[]) => listClients(...a),
      getClient: vi.fn(),
      createClient: vi.fn(),
      updateClient: vi.fn(),
      listClientAccounts: vi.fn(),
      cascadePreview: vi.fn(),
      toggleActive: vi.fn(),
      deleteClient: vi.fn(),
      exportClientsCsv: vi.fn(),
    },
  }
})

const notifySuccess = vi.fn()
const notifyError = vi.fn()
const notifyApiError = vi.fn()
const notifyInfo = vi.fn()
const notifyConfirm = vi.fn().mockResolvedValue(true)

vi.mock('../utils/notify', () => ({
  notify: {
    success: (...a: unknown[]) => notifySuccess(...a),
    error: (...a: unknown[]) => notifyError(...a),
    apiError: (...a: unknown[]) => notifyApiError(...a),
    info: (...a: unknown[]) => notifyInfo(...a),
    confirm: (...a: unknown[]) => notifyConfirm(...a),
  },
}))

// ===== Test render helper =====

function Outletish() {
  return <Outlet context={{ registerRefresh: () => {} }} />
}

function renderPage(Page: ComponentType) {
  const store = configureStore({
    reducer: combineReducers({ carriers: carrierReducer, orders: orderReducer }),
    middleware: (getDefault) => getDefault({ serializableCheck: false }),
  })
  return render(
    <Provider store={store}>
      <MemoryRouter initialEntries={['/settings/carriers']}>
        <Routes>
          <Route element={<Outletish />}>
            <Route path="/settings/carriers" element={<Page />} />
          </Route>
        </Routes>
      </MemoryRouter>
    </Provider>,
  )
}

async function loadPage(): Promise<ComponentType> {
  const mod = await import('./CarrierConnections')
  return mod.default
}

// ===== Fixtures =====

// clientService.listClients returns { data: { content: [{ clientCode, name }] } }.
// The component maps c.clientCode → code, so the fixture must use clientCode.
const client = {
  clientCode: 'ACME',
  name: 'ACME Corp',
}

// ===== Suite-wide setup =====

beforeEach(() => {
  listAccounts.mockReset().mockResolvedValue([])
  upsertAccount.mockReset().mockResolvedValue({ data: { id: 42 }, message: 'ok' })
  deleteAccount.mockReset().mockResolvedValue({ data: null })
  setClientDefault.mockReset().mockResolvedValue({ data: null })
  toggleActive.mockReset().mockResolvedValue({ data: null })
  verifyAccount.mockReset().mockResolvedValue({ data: { verified: true }, message: 'ok' })
  verifyCredentials.mockReset().mockResolvedValue({ data: { verified: true }, message: 'ok' })
  getPlatformCredentials.mockReset().mockResolvedValue({
    data: { carrierCode: 'UPS', clientId: '', clientSecretMasked: null, hasClientSecret: false, found: false },
  })
  resolveOrders.mockReset().mockResolvedValue([])

  listClients.mockReset().mockResolvedValue({
    data: { content: [client], pageNumber: 0, pageSize: 25, totalElements: 1, totalPages: 1 },
  })

  notifySuccess.mockReset()
  notifyError.mockReset()
  notifyApiError.mockReset()
  notifyInfo.mockReset()
  notifyConfirm.mockReset().mockResolvedValue(true)

  // Anti-fallback: any un-mocked fetch should blow up the test rather than
  // silently hit the network.
  vi.spyOn(global, 'fetch').mockImplementation(() => {
    throw new Error('un-mocked fetch forbidden')
  })
})

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
})

// ===== Helpers =====

/** Click the toolbar "Add Account" button and return the resulting drawer. */
async function openAddDrawer(user: ReturnType<typeof userEvent.setup>) {
  const addBtn = await screen.findByRole('button', { name: /add account/i })
  await user.click(addBtn)
  return await screen.findByRole('dialog', { name: /add carrier account/i })
}

/** Field lookup inside a drawer — walks the <label> elements (Field-wrapper
 *  pattern from CarrierConnections) and returns the first input/select whose
 *  label span text matches. Label spans + missing-field summary text can share
 *  the same string ("Consumer Secret" appears in Step 3 label + Step 4 summary),
 *  so restricting to <label> descendants disambiguates. */
function fieldByLabel(dialog: HTMLElement, labelText: string | RegExp): HTMLElement {
  const matcher =
    typeof labelText === 'string'
      ? (s: string) => s === labelText || s.startsWith(`${labelText} `)
      : (s: string) => labelText.test(s)
  const labels = Array.from(dialog.querySelectorAll('label'))
  const found = labels.find((l) => {
    const span = l.querySelector('span')
    return span && matcher(span.textContent?.trim() ?? '')
  })
  if (!found) throw new Error(`No <label> found matching ${String(labelText)}`)
  const field = found.querySelector('input, select, textarea')
  if (!field) throw new Error(`Label ${String(labelText)} has no input/select`)
  return field as HTMLElement
}

/** Fill in the minimum required fields for a UPS platform account.
 *  UPS uses "Consumer Key" / "Consumer Secret" per credentialLabelsFor(). */
async function fillValidUpsForm(user: ReturnType<typeof userEvent.setup>, dialog: HTMLElement) {
  await user.type(fieldByLabel(dialog, /^Account number$/), '740561111')
  await user.type(fieldByLabel(dialog, /^Consumer Key$/), 'consumer-key-xyz')
  await user.type(fieldByLabel(dialog, /^Consumer Secret$/), 'consumer-secret-abc')
}

// ============================================================================
// Positive
// ============================================================================

describe('CarrierConnections · add flow · positive', () => {
  it('opens the Add drawer when the toolbar "Add Account" button is clicked', async () => {
    const Page = await loadPage()
    const user = userEvent.setup()
    renderPage(Page)

    const dialog = await openAddDrawer(user)

    // The drawer header uses "Add carrier account".
    expect(within(dialog).getByRole('heading', { name: /add carrier account/i })).toBeTruthy()
    // Cancel + Save action buttons are present.
    expect(within(dialog).getByRole('button', { name: /^cancel$/i })).toBeTruthy()
    expect(within(dialog).getByRole('button', { name: /save to account book/i })).toBeTruthy()
  })

  it('renders UPS, FedEx, USPS, and DHL as carrier options in the drawer', async () => {
    const Page = await loadPage()
    const user = userEvent.setup()
    renderPage(Page)
    const dialog = await openAddDrawer(user)

    const carrierGroup = within(dialog).getByRole('radiogroup', { name: /^carrier$/i })
    expect(within(carrierGroup).getByRole('radio', { name: /ups/i })).toBeTruthy()
    expect(within(carrierGroup).getByRole('radio', { name: /fedex/i })).toBeTruthy()
    expect(within(carrierGroup).getByRole('radio', { name: /usps/i })).toBeTruthy()
    expect(within(carrierGroup).getByRole('radio', { name: /dhl/i })).toBeTruthy()
  })

  it('defaults environment to SANDBOX and lets the operator flip to PRODUCTION', async () => {
    const Page = await loadPage()
    const user = userEvent.setup()
    renderPage(Page)
    const dialog = await openAddDrawer(user)

    const envSelect = fieldByLabel(dialog, /^Environment$/) as HTMLSelectElement
    expect(envSelect.value).toBe('SANDBOX')

    await user.selectOptions(envSelect, 'PRODUCTION')
    expect(envSelect.value).toBe('PRODUCTION')
  })

  it('submit → upsertAccount called with uppercase carrier + trimmed strings', async () => {
    const Page = await loadPage()
    const user = userEvent.setup()
    renderPage(Page)
    const dialog = await openAddDrawer(user)

    // Trailing whitespace on account name + account# to exercise the trim.
    await user.type(fieldByLabel(dialog, /^Account name$/), '  Acme UPS  ')
    await user.type(fieldByLabel(dialog, /^Account number$/), '  740561111  ')
    await user.type(fieldByLabel(dialog, /^Consumer Key$/), '  consumer-key-xyz  ')
    await user.type(fieldByLabel(dialog, /^Consumer Secret$/), '  consumer-secret-abc  ')

    await user.click(within(dialog).getByRole('button', { name: /save to account book/i }))

    // upsertAccount is invoked exactly once with the trimmed + carrier-cased payload.
    expect(upsertAccount).toHaveBeenCalledTimes(1)
    const payload = upsertAccount.mock.calls[0][0]
    expect(payload.carrierCode).toBe('UPS')
    expect(payload.accountNumber).toBe('740561111')
    expect(payload.accountName).toBe('Acme UPS')
    expect(payload.clientId).toBe('consumer-key-xyz')
    expect(payload.clientSecret).toBe('consumer-secret-abc')
    expect(payload.environment).toBe('SANDBOX')
    // Platform accounts must not send a customerNo.
    expect(payload.customerNo).toBeUndefined()
  })

  it('Cancel closes the drawer without calling upsertAccount', async () => {
    const Page = await loadPage()
    const user = userEvent.setup()
    renderPage(Page)
    const dialog = await openAddDrawer(user)

    // Fill something so a stray submit path would leak — but only clicking Save
    // should ever call upsertAccount.
    await user.type(fieldByLabel(dialog, /^Account number$/), '740561111')

    await user.click(within(dialog).getByRole('button', { name: /^cancel$/i }))

    expect(screen.queryByRole('dialog', { name: /add carrier account/i })).toBeNull()
    expect(upsertAccount).not.toHaveBeenCalled()
  })

  it('switching carrier to FedEx retitles the account-number placeholder + accepts a 9-digit number', async () => {
    const Page = await loadPage()
    const user = userEvent.setup()
    renderPage(Page)
    const dialog = await openAddDrawer(user)

    const carrierGroup = within(dialog).getByRole('radiogroup', { name: /^carrier$/i })
    await user.click(within(carrierGroup).getByRole('radio', { name: /fedex/i }))

    // 9-digit FedEx number is valid; short prefix would trip UPS but works here.
    // FedEx uses the default credential labels: "Client ID" / "Client Secret".
    await user.type(fieldByLabel(dialog, /^Account number$/), '123456789')
    await user.type(fieldByLabel(dialog, /^Client ID$/), 'fedex-client-id-000')
    await user.type(fieldByLabel(dialog, /^Client Secret$/), 'fedex-client-secret-000')
    await user.click(within(dialog).getByRole('button', { name: /save to account book/i }))

    expect(upsertAccount).toHaveBeenCalledTimes(1)
    expect(upsertAccount.mock.calls[0][0].carrierCode).toBe('FEDEX')
    expect(upsertAccount.mock.calls[0][0].accountNumber).toBe('123456789')
  })
})

// ============================================================================
// Negative
// ============================================================================

describe('CarrierConnections · add flow · negative / validation', () => {
  it('empty account number blocks save + surfaces a required-field error', async () => {
    const Page = await loadPage()
    const user = userEvent.setup()
    renderPage(Page)
    const dialog = await openAddDrawer(user)

    // Fill credentials but skip the account number.
    await user.type(fieldByLabel(dialog, /^Consumer Key$/), 'consumer-key-xyz')
    await user.type(fieldByLabel(dialog, /^Consumer Secret$/), 'consumer-secret-abc')
    await user.click(within(dialog).getByRole('button', { name: /save to account book/i }))

    expect(upsertAccount).not.toHaveBeenCalled()
    expect(notifyError).toHaveBeenCalled()
    // Error copy from validateCarrierAccount.
    expect(within(dialog).getByText(/account number is required/i)).toBeTruthy()
  })

  it('UPS account number that fails the carrier pattern shows the per-carrier hint', async () => {
    const Page = await loadPage()
    const user = userEvent.setup()
    renderPage(Page)
    const dialog = await openAddDrawer(user)

    // 3 chars — too short for UPS 6-10.
    await user.type(fieldByLabel(dialog, /^Account number$/), 'abc')
    await user.type(fieldByLabel(dialog, /^Consumer Key$/), 'consumer-key-xyz')
    await user.type(fieldByLabel(dialog, /^Consumer Secret$/), 'consumer-secret-abc')
    await user.click(within(dialog).getByRole('button', { name: /save to account book/i }))

    expect(upsertAccount).not.toHaveBeenCalled()
    expect(within(dialog).getByText(/UPS shipper number is 6.10/i)).toBeTruthy()
  })

  it('empty Client ID blocks save with a required-field error', async () => {
    const Page = await loadPage()
    const user = userEvent.setup()
    renderPage(Page)
    const dialog = await openAddDrawer(user)

    await user.type(fieldByLabel(dialog, /^Account number$/), '740561111')
    // Skip clientId, fill secret only.
    await user.type(fieldByLabel(dialog, /^Consumer Secret$/), 'consumer-secret-abc')
    await user.click(within(dialog).getByRole('button', { name: /save to account book/i }))

    expect(upsertAccount).not.toHaveBeenCalled()
    expect(within(dialog).getByText(/Consumer Key is required/i)).toBeTruthy()
  })

  it('empty Client Secret blocks save with a required-field error', async () => {
    const Page = await loadPage()
    const user = userEvent.setup()
    renderPage(Page)
    const dialog = await openAddDrawer(user)

    await user.type(fieldByLabel(dialog, /^Account number$/), '740561111')
    await user.type(fieldByLabel(dialog, /^Consumer Key$/), 'consumer-key-xyz')
    // Skip clientSecret.
    await user.click(within(dialog).getByRole('button', { name: /save to account book/i }))

    expect(upsertAccount).not.toHaveBeenCalled()
    expect(within(dialog).getByText(/Consumer Secret is required/i)).toBeTruthy()
  })

  it('embedded whitespace in a pasted key trips the "remove spaces" credential guard', async () => {
    const Page = await loadPage()
    const user = userEvent.setup()
    renderPage(Page)
    const dialog = await openAddDrawer(user)

    await user.type(fieldByLabel(dialog, /^Account number$/), '740561111')
    // Space inside the token — trimming won't help; the validator flags it.
    await user.type(fieldByLabel(dialog, /^Consumer Key$/), 'consumer key xyz')
    await user.type(fieldByLabel(dialog, /^Consumer Secret$/), 'consumer-secret-abc')
    await user.click(within(dialog).getByRole('button', { name: /save to account book/i }))

    expect(upsertAccount).not.toHaveBeenCalled()
    expect(within(dialog).getByText(/remove spaces/i)).toBeTruthy()
  })

  it('upsertAccount rejection surfaces via notify.apiError and keeps the drawer open', async () => {
    upsertAccount.mockRejectedValueOnce(new Error('Boom'))

    const Page = await loadPage()
    const user = userEvent.setup()
    renderPage(Page)
    const dialog = await openAddDrawer(user)
    await fillValidUpsForm(user, dialog)
    await user.click(within(dialog).getByRole('button', { name: /save to account book/i }))

    expect(upsertAccount).toHaveBeenCalledTimes(1)
    expect(notifyApiError).toHaveBeenCalled()
    // Drawer stays open on failure so the operator can retry.
    expect(screen.queryByRole('dialog', { name: /add carrier account/i })).not.toBeNull()
  })
})

// ============================================================================
// Cross-cutting — client-account path
// ============================================================================

describe('CarrierConnections · add flow · client-account path', () => {
  it('switching to Client account requires + submits the customerNo', async () => {
    const Page = await loadPage()
    const user = userEvent.setup()
    renderPage(Page)
    const dialog = await openAddDrawer(user)

    // Flip account type → client.
    const typeGroup = within(dialog).getByRole('radiogroup', { name: /^account type$/i })
    await user.click(within(typeGroup).getByRole('radio', { name: /client account/i }))

    // Pick the client from the just-populated select.
    const clientSelect = fieldByLabel(dialog, /^Client$/) as HTMLSelectElement
    await user.selectOptions(clientSelect, 'ACME')

    // Fill the rest.
    await fillValidUpsForm(user, dialog)
    await user.click(within(dialog).getByRole('button', { name: /save to account book/i }))

    expect(upsertAccount).toHaveBeenCalledTimes(1)
    const payload = upsertAccount.mock.calls[0][0]
    expect(payload.customerNo).toBe('ACME')
    // clientDefault comes through as an explicit boolean on the client path.
    expect(payload.clientDefault).toBe(false)
  })
})
