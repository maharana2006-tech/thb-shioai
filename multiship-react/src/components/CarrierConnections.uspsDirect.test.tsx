import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, cleanup, within, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, Outlet } from 'react-router-dom'
import { Provider } from 'react-redux'
import { combineReducers, configureStore } from '@reduxjs/toolkit'
import type { ComponentType } from 'react'
import carrierReducer from '../store/carrierSlice'
import orderReducer from '../store/orderSlice'

/**
 * PR-A · Agent D · USPS Direct integration.
 *
 * Drawer-visibility coverage for CarrierConnections when the site-wide
 * USPS_PROVIDER value flips between STAMPS_COM / PROVISIONING_USPS_DIRECT /
 * USPS_DIRECT. Every test picks USPS in the carrier selector and then
 * asserts which credential surface renders.
 *
 *  - STAMPS_COM       → Stamps.com IntegrationID / Password visible; USPS
 *                       Direct fields (EPS #, CRID, MID) NOT rendered.
 *  - PROVISIONING     → Both surfaces visible; the Stamps section carries
 *                       an "Active provider · Stamps.com" pill and the
 *                       USPS Direct section shows "Prepare for provider
 *                       switch".
 *  - USPS_DIRECT      → Only USPS Direct fields; the Stamps.com credential
 *                       inputs are NOT rendered.
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
    authorizeStampsSera: (id: number) => `http://stub/authorize/${id}`,
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
vi.mock('../utils/notify', () => ({
  notify: {
    success: (...a: unknown[]) => notifySuccess(...a),
    error: (...a: unknown[]) => notifyError(...a),
    apiError: (...a: unknown[]) => notifyApiError(...a),
    info: vi.fn(),
    confirm: vi.fn().mockResolvedValue(true),
  },
}))

// Site-wide USPS_PROVIDER lookup — mocked per test.
const systemSettingsList = vi.fn()
vi.mock('../api/systemSettingsService', () => ({
  systemSettingsService: {
    list: (...a: unknown[]) => systemSettingsList(...a),
    update: vi.fn(),
    getUspsProviderReadiness: vi.fn(),
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

const client = { clientCode: 'ACME', name: 'ACME Corp' }

function providerSetting(currentValue: string) {
  return [
    {
      key: 'USPS_PROVIDER',
      hasValue: true,
      maskedValue: currentValue,
      description: 'USPS provider strategy',
      kind: 'CHOICE' as const,
      options: ['STAMPS_COM', 'PROVISIONING_USPS_DIRECT', 'USPS_DIRECT'],
      currentValue,
      defaultValue: 'STAMPS_COM',
    },
  ]
}

beforeEach(() => {
  listAccounts.mockReset().mockResolvedValue([])
  upsertAccount.mockReset().mockResolvedValue({ data: { id: 42 }, message: 'ok' })
  deleteAccount.mockReset().mockResolvedValue({ data: null })
  setClientDefault.mockReset().mockResolvedValue({ data: null })
  toggleActive.mockReset().mockResolvedValue({ data: null })
  verifyAccount.mockReset().mockResolvedValue({ data: { verified: true }, message: 'ok' })
  verifyCredentials.mockReset().mockResolvedValue({ data: { verified: true }, message: 'ok' })
  getPlatformCredentials.mockReset().mockResolvedValue({
    data: {
      carrierCode: 'USPS',
      clientId: '',
      clientSecretMasked: null,
      hasClientSecret: false,
      found: false,
    },
  })
  resolveOrders.mockReset().mockResolvedValue([])

  listClients.mockReset().mockResolvedValue({
    data: { content: [client], pageNumber: 0, pageSize: 25, totalElements: 1, totalPages: 1 },
  })

  notifySuccess.mockReset()
  notifyError.mockReset()
  notifyApiError.mockReset()
  systemSettingsList.mockReset()

  vi.spyOn(globalThis, 'fetch').mockImplementation(() => {
    throw new Error('un-mocked fetch forbidden')
  })
})

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
})

async function openUspsDrawer(user: ReturnType<typeof userEvent.setup>) {
  const addBtn = await screen.findByRole('button', { name: /add account/i })
  await user.click(addBtn)
  const dialog = await screen.findByRole('dialog', { name: /add carrier account/i })
  const carrierGroup = within(dialog).getByRole('radiogroup', { name: /^carrier$/i })
  await user.click(within(carrierGroup).getByRole('radio', { name: /usps/i }))
  return dialog
}

/** Field-label lookup restricted to <label> descendants (matches the
 *  Field-wrapper pattern in CarrierConnections). Disambiguates from
 *  helper text and Step-4 verify summaries that share the same strings. */
function labelSpanTexts(dialog: HTMLElement): string[] {
  return Array.from(dialog.querySelectorAll('label'))
    .map((l) => l.querySelector('span')?.textContent?.trim() ?? '')
}

// ============================================================================
// STAMPS_COM — default
// ============================================================================

describe('CarrierConnections · USPS drawer · STAMPS_COM mode', () => {
  it('renders Stamps.com credential fields and hides the USPS Direct panel', async () => {
    systemSettingsList.mockResolvedValue(providerSetting('STAMPS_COM'))
    const Page = await loadPage()
    const user = userEvent.setup()
    renderPage(Page)

    const dialog = await openUspsDrawer(user)
    await waitFor(() => expect(systemSettingsList).toHaveBeenCalled())

    // Stamps IntegrationID + Password labels are the Stamps-mode credential
    // labels — restrict lookup to <label> descendants so the helper text
    // doesn't false-positive.
    await waitFor(() => {
      const labels = labelSpanTexts(dialog)
      expect(labels).toContain('IntegrationID')
      expect(labels).toContain('Password')
    })

    // USPS Direct sub-panel and its fields must NOT be rendered.
    expect(within(dialog).queryByText(/USPS Direct fields/i)).toBeNull()
    const labels = labelSpanTexts(dialog)
    expect(labels).not.toContain('USPS EPS account #')
    expect(labels).not.toContain('CRID (Customer Registration ID)')
    expect(labels).not.toContain('MID (Mailer ID)')
  })
})

// ============================================================================
// PROVISIONING — parallel-run state
// ============================================================================

describe('CarrierConnections · USPS drawer · PROVISIONING mode', () => {
  it('renders BOTH Stamps.com and USPS Direct fields with the appropriate pills', async () => {
    systemSettingsList.mockResolvedValue(providerSetting('PROVISIONING_USPS_DIRECT'))
    const Page = await loadPage()
    const user = userEvent.setup()
    renderPage(Page)

    const dialog = await openUspsDrawer(user)
    await waitFor(() => expect(systemSettingsList).toHaveBeenCalled())

    // Wait for the USPS Direct fields effect to bring the sub-panel in.
    await waitFor(() =>
      expect(within(dialog).getByText(/USPS Direct fields/i)).toBeTruthy(),
    )

    // Stamps-mode credential fields still present.
    const labels = labelSpanTexts(dialog)
    expect(labels).toContain('IntegrationID')
    expect(labels).toContain('Password')

    // Stamps pill + USPS Direct pill both present.
    expect(within(dialog).getByText(/Active provider · Stamps\.com/i)).toBeTruthy()
    expect(within(dialog).getByText(/Prepare for provider switch/i)).toBeTruthy()

    // All three USPS Direct field labels visible.
    expect(labels).toContain('USPS EPS account #')
    expect(labels).toContain('CRID (Customer Registration ID)')
    expect(labels).toContain('MID (Mailer ID)')
  })
})

// ============================================================================
// USPS_DIRECT — cutover complete
// ============================================================================

describe('CarrierConnections · USPS drawer · USPS_DIRECT mode', () => {
  it('renders USPS Direct fields only; Stamps.com credential inputs are gone', async () => {
    systemSettingsList.mockResolvedValue(providerSetting('USPS_DIRECT'))
    const Page = await loadPage()
    const user = userEvent.setup()
    renderPage(Page)

    const dialog = await openUspsDrawer(user)
    await waitFor(() => expect(systemSettingsList).toHaveBeenCalled())

    await waitFor(() =>
      expect(within(dialog).getByText(/USPS Direct fields/i)).toBeTruthy(),
    )
    // Stamps "Active provider" pill absent under USPS_DIRECT.
    expect(within(dialog).queryByText(/Active provider · Stamps\.com/i)).toBeNull()

    // Stamps-mode credential inputs must NOT be rendered as Field labels
    // (Step-4 summary references are OK — they're inside a summary, not
    // a <label>).
    const labels = labelSpanTexts(dialog)
    expect(labels).not.toContain('IntegrationID')
    // Rotate-credentials chrome is Stamps-only too.
    expect(within(dialog).queryByText(/Rotate credentials/i)).toBeNull()
  })
})

// ============================================================================
// Cross-mode payload shaping
// ============================================================================

describe('CarrierConnections · USPS drawer · payload shape', () => {
  it('PROVISIONING save sends null trio when the USPS Direct fields are blank', async () => {
    systemSettingsList.mockResolvedValue(providerSetting('PROVISIONING_USPS_DIRECT'))
    const Page = await loadPage()
    const user = userEvent.setup()
    renderPage(Page)

    const dialog = await openUspsDrawer(user)
    await waitFor(() => expect(systemSettingsList).toHaveBeenCalled())
    await waitFor(() =>
      expect(within(dialog).getByText(/USPS Direct fields/i)).toBeTruthy(),
    )

    const labelInput = (text: string) => {
      const labels = Array.from(dialog.querySelectorAll('label'))
      const found = labels.find(
        (l) => l.querySelector('span')?.textContent?.trim() === text,
      )
      if (!found) throw new Error(`label ${text} missing`)
      return found.querySelector('input, select') as HTMLElement
    }
    await user.type(labelInput('Username'), 'stamps-user-01')
    await user.type(labelInput('IntegrationID'), '01234567-89ab-cdef-0123-456789abcdef')
    await user.type(labelInput('Password'), 'stamps-pass-abc')

    await user.click(
      within(dialog).getByRole('button', { name: /save to account book/i }),
    )

    await waitFor(() => expect(upsertAccount).toHaveBeenCalledTimes(1))
    const payload = upsertAccount.mock.calls[0][0]
    // Under PROVISIONING the trio is always sent (blank -> null clears the
    // persisted value, matching the null-vs-omit convention).
    expect(payload.uspsDirectAccountNumber).toBeNull()
    expect(payload.uspsDirectCrid).toBeNull()
    expect(payload.uspsDirectMid).toBeNull()
  })

  it('non-USPS carrier drawer never includes USPS Direct fields on the payload', async () => {
    systemSettingsList.mockResolvedValue(providerSetting('STAMPS_COM'))
    const Page = await loadPage()
    const user = userEvent.setup()
    renderPage(Page)

    const addBtn = await screen.findByRole('button', { name: /add account/i })
    await user.click(addBtn)
    const dialog = await screen.findByRole('dialog', { name: /add carrier account/i })

    const labelInput = (text: string) => {
      const labels = Array.from(dialog.querySelectorAll('label'))
      const found = labels.find(
        (l) => l.querySelector('span')?.textContent?.trim() === text,
      )
      if (!found) throw new Error(`label ${text} missing`)
      return found.querySelector('input, select') as HTMLElement
    }
    await user.type(labelInput('Account number'), '740561111')
    await user.type(labelInput('Consumer Key'), 'ups-consumer-key')
    await user.type(labelInput('Consumer Secret'), 'ups-consumer-secret')

    await user.click(
      within(dialog).getByRole('button', { name: /save to account book/i }),
    )

    await waitFor(() => expect(upsertAccount).toHaveBeenCalledTimes(1))
    const payload = upsertAccount.mock.calls[0][0]
    // Non-USPS: the drawer never renders the trio; the payload OMITS the
    // fields entirely (undefined), so the backend keeps whatever's persisted.
    expect(payload.uspsDirectAccountNumber).toBeUndefined()
    expect(payload.uspsDirectCrid).toBeUndefined()
    expect(payload.uspsDirectMid).toBeUndefined()
  })
})
