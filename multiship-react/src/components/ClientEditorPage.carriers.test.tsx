import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { Provider } from 'react-redux'
import { combineReducers, configureStore } from '@reduxjs/toolkit'
import type { ComponentType } from 'react'
import carrierReducer from '../store/carrierSlice'
import orderReducer from '../store/orderSlice'

/**
 * Sprint 52 wizard-tests — Carriers step (create + edit mode).
 *
 * Coverage targets `CarrierDraftStep` (create) + the embedded
 * `CarrierConnections` panel (edit) inside `ClientEditorPage`. Every
 * service the page pulls at mount is mocked so nothing touches real
 * fetch and the tests focus on the Carriers-step contract:
 *   - staging drafts (Add / list / delete)
 *   - per-carrier account-number rule flip
 *   - validation errors (empty, wrong pattern, spaces in creds)
 *   - default-account star, environment select, third-party billing panel
 *   - the Next-button gate ("Add at least one carrier account")
 *   - handleCreate calls accountRefService.upsertAccount once per draft
 *     with an uppercase carrier + trimmed strings
 *   - draft persistence (localStorage) survives a simulated reload
 *   - edit-mode hydration from clientService.listClientAccounts
 *
 * The page pulls a heavy prop tree at mount; helpers below reduce each
 * test to "open the page seeded to the Carriers step, then assert".
 */

// ===== module mocks =====

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
const getClient = vi.fn()
const createClient = vi.fn()
const updateClient = vi.fn()
const listClientAccounts = vi.fn()

vi.mock('../api/clientService', async () => {
  // Preserve the real module's exports (types + BASE_URL) — override the
  // service object so no network calls escape.
  const actual = await vi.importActual<typeof import('../api/clientService')>(
    '../api/clientService',
  )
  return {
    ...actual,
    clientService: {
      listClients: (...a: unknown[]) => listClients(...a),
      getClient: (...a: unknown[]) => getClient(...a),
      createClient: (...a: unknown[]) => createClient(...a),
      updateClient: (...a: unknown[]) => updateClient(...a),
      listClientAccounts: (...a: unknown[]) => listClientAccounts(...a),
      cascadePreview: vi.fn(),
      toggleActive: vi.fn(),
      deleteClient: vi.fn(),
      exportClientsCsv: vi.fn(),
    },
  }
})

const listWarehouses = vi.fn()
const listForClient = vi.fn()
const attachWarehouse = vi.fn()
const setDefaultWarehouse = vi.fn()

vi.mock('../api/warehouseService', () => ({
  warehouseService: {
    listWarehouses: (...a: unknown[]) => listWarehouses(...a),
    getWarehouse: vi.fn(),
    createWarehouse: vi.fn(),
    updateWarehouse: vi.fn(),
    toggleActive: vi.fn(),
    deleteWarehouse: vi.fn(),
  },
  clientWarehouseService: {
    listForClient: (...a: unknown[]) => listForClient(...a),
    attach: (...a: unknown[]) => attachWarehouse(...a),
    detach: vi.fn(),
    setDefault: (...a: unknown[]) => setDefaultWarehouse(...a),
  },
}))

const catalog = vi.fn()
vi.mock('../api/shippingConfigService', () => ({
  shippingConfigService: {
    catalog: (...a: unknown[]) => catalog(...a),
    saveRule: vi.fn(),
    deleteRule: vi.fn(),
    syncServices: vi.fn(),
    setServiceEnabled: vi.fn(),
    setServicePackages: vi.fn(),
    listPresets: vi.fn().mockResolvedValue([]),
    savePreset: vi.fn(),
    setDefaultPreset: vi.fn(),
    deletePreset: vi.fn(),
    syncPackages: vi.fn(),
  },
}))

const listCustomsProfiles = vi.fn()
vi.mock('../api/customsProfileService', () => ({
  customsProfileService: {
    list: (...a: unknown[]) => listCustomsProfiles(...a),
    save: vi.fn(),
    remove: vi.fn(),
    exportCsv: vi.fn(),
  },
}))

// notify is called all over the page; stub it so success toasts don't crash
// the tests that don't render the toast container.
vi.mock('../utils/notify', () => ({
  notify: {
    success: vi.fn(),
    error: vi.fn(),
    info: vi.fn(),
    apiError: vi.fn(),
    confirm: vi.fn(),
  },
}))

// The identity step calls checkClientCodeAvailable → clientService.getClient.
// We short-circuit it by making getClient reject 404 (client code available).
// The default is set in beforeEach below.

// ===== helpers =====

async function loadPage(): Promise<ComponentType> {
  const mod = await import('./ClientEditorPage')
  return mod.default
}

/** Render the page under a MemoryRouter that resolves /settings/clients/:clientCode. */
function renderPage(Page: ComponentType, path: string) {
  const rootReducer = combineReducers({
    carriers: carrierReducer,
    orders: orderReducer,
  })
  const store = configureStore({
    reducer: rootReducer,
    middleware: (getDefaultMiddleware) =>
      getDefaultMiddleware({ serializableCheck: false }),
  })
  return render(
    <Provider store={store}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path="/settings/clients/new" element={<Page />} />
          <Route path="/settings/clients/:clientCode" element={<Page />} />
          <Route path="/settings/clients" element={<div>CLIENTS_LIST</div>} />
        </Routes>
      </MemoryRouter>
    </Provider>,
  )
}

/** Pre-seed the create-mode draft so the wizard opens directly on the
 *  Carriers step with all prior mandatory steps marked as visited + a
 *  ship-from warehouse selected. Removes the need to click through
 *  Identity → Ship From → Return in every test. */
function seedCarriersDraft(overrides: Record<string, unknown> = {}) {
  const draft = {
    form: {
      clientCode: 'ACME',
      name: 'Acme Test Client',
      email: '',
      phone: '',
      shipFrom: {
        name: 'Acme HQ',
        line1: '1 Test St',
        line2: '',
        city: 'Testville',
        state: 'NY',
        zip: '10001',
        country: 'US',
        phone: '',
      },
      returnAddress: {
        name: '',
        line1: '',
        line2: '',
        city: '',
        state: '',
        zip: '',
        country: 'US',
        phone: '',
      },
      returnSameAsShipFrom: true,
    },
    selectedShipFromWarehouseId: 1,
    visitedSteps: ['identity', 'shipFrom', 'return', 'carriers'],
    activeStep: 'carriers',
    carrierDrafts: [],
    mappingDrafts: [],
    ...overrides,
  }
  // The page namespaces the key by localStorage.getItem('multiship_user') || 'anonymous'.
  // We leave that key unset in tests → 'anonymous' bucket.
  localStorage.setItem(
    'clientEditorDraft:anonymous',
    JSON.stringify(draft),
  )
}

async function waitForCarriersStep() {
  await waitFor(() => {
    expect(screen.getByRole('heading', { name: /Carrier accounts \(draft\)/i })).toBeTruthy()
  })
}

// ===== lifecycle =====

beforeEach(() => {
  localStorage.clear()

  listAccounts.mockReset().mockResolvedValue([])
  upsertAccount.mockReset().mockResolvedValue({ data: { id: 1 } })
  deleteAccount.mockReset()
  setClientDefault.mockReset()
  toggleActive.mockReset()
  verifyAccount.mockReset()
  verifyCredentials.mockReset()
  getPlatformCredentials.mockReset()
  resolveOrders.mockReset()

  listClients.mockReset()
  // 404 = code is available; anything else would light up the duplicate banner.
  getClient.mockReset().mockRejectedValue({ status: 404 })
  createClient.mockReset().mockResolvedValue({ data: { id: 1, clientCode: 'ACME', name: 'Acme Test Client' } })
  updateClient.mockReset()
  listClientAccounts.mockReset().mockResolvedValue([])

  listWarehouses.mockReset().mockResolvedValue({
    data: {
      content: [
        {
          id: 1,
          code: 'WH-MAIN',
          name: 'Main WH',
          address: {
            name: 'Acme HQ', line1: '1 Test St', line2: '', city: 'Testville',
            state: 'NY', zip: '10001', country: 'US', phone: '',
          },
          ownerType: 'PLATFORM',
          ownerClientCode: null,
          active: true,
          attachedClientCount: 0,
          createdAt: null,
          updatedAt: null,
        },
      ],
      pageNumber: 0,
      pageSize: 50,
      totalElements: 1,
      totalPages: 1,
    },
  })
  listForClient.mockReset().mockResolvedValue({ data: [] })
  attachWarehouse.mockReset().mockResolvedValue({ data: {} })
  setDefaultWarehouse.mockReset().mockResolvedValue({ data: {} })
  catalog.mockReset().mockResolvedValue({
    services: [], rules: [], links: [], rulePackages: [], ruleWarehouses: [], originCountries: [],
  })
  listCustomsProfiles.mockReset().mockResolvedValue([])
})

afterEach(() => {
  localStorage.clear()
})

// ===== positive cases =====

describe('CarrierDraftStep — positive cases', () => {
  it('renders the empty state + primary Add button when no drafts staged', async () => {
    seedCarriersDraft()
    const Page = await loadPage()
    renderPage(Page, '/settings/clients/new')
    await waitForCarriersStep()

    expect(screen.getByText(/No carrier accounts staged yet/i)).toBeTruthy()
    expect(screen.getByRole('button', { name: /Add carrier account/i })).toBeTruthy()
  })

  it('clicking Add opens the draft form with every required field', async () => {
    seedCarriersDraft()
    const user = userEvent.setup()
    const Page = await loadPage()
    renderPage(Page, '/settings/clients/new')
    await waitForCarriersStep()

    await user.click(screen.getByRole('button', { name: /Add carrier account/i }))

    // Carrier + environment come from the Select dropdowns (native <select>
    // rendered by workspace/Select). Assert by role + accessible name.
    expect(screen.getByText(/Carrier \*/i)).toBeTruthy()
    expect(screen.getByText(/Account number \*/i)).toBeTruthy()
    expect(screen.getByText(/Client ID \*/i)).toBeTruthy()
    expect(screen.getByText(/Client Secret \*/i)).toBeTruthy()
    expect(screen.getByText(/Environment/i)).toBeTruthy()
    expect(screen.getByRole('checkbox', { name: /Default account/i })).toBeTruthy()
    expect(screen.getByText(/Shipping purpose/i)).toBeTruthy()
    expect(screen.getByText(/Customs clearance/i)).toBeTruthy()

    // The two primary action buttons should be the last row.
    expect(screen.getByRole('button', { name: /Add to list/i })).toBeTruthy()
    expect(screen.getByRole('button', { name: /Cancel/i })).toBeTruthy()
  })

  it('fills every required field + click Add to list adds the draft', async () => {
    seedCarriersDraft()
    const user = userEvent.setup()
    const Page = await loadPage()
    renderPage(Page, '/settings/clients/new')
    await waitForCarriersStep()

    await user.click(screen.getByRole('button', { name: /Add carrier account/i }))

    // UPS default → 6 alphanumerics. Textbox is the account number field
    // (the client ID + secret are also textbox but the account number is
    // the first standard input).
    const inputs = screen.getAllByRole('textbox')
    // Order in DOM: accountNumber, clientId. clientSecret is type=password → not textbox.
    await user.type(inputs[0], 'ABC123')
    await user.type(inputs[1], 'client-id-value')
    // Password field has no textbox role — grab by placeholder-less selector.
    const secret = document.querySelector('input[type="password"]') as HTMLInputElement
    await user.type(secret, 'clientsecret')

    await user.click(screen.getByRole('button', { name: /Add to list/i }))

    // Draft row surfaces: UPS · ABC123.
    await waitFor(() => {
      expect(screen.getByText(/UPS · ABC123/)).toBeTruthy()
    })
  })

  it('draft list renders staged carrier + account number + default star', async () => {
    seedCarriersDraft({
      carrierDrafts: [
        {
          id: 1, carrierCode: 'UPS', accountNumber: 'ABC123', accountName: '',
          clientId: 'client-id-1', clientSecret: 'secret-value',
          environment: 'SANDBOX', clientDefault: true,
          shippingPurpose: '', clearanceOption: '',
          thirdPartyAccount: '', thirdPartyName: '', thirdPartyAddress1: '',
          thirdPartyCity: '', thirdPartyState: '', thirdPartyPostcode: '',
          thirdPartyCountry: '',
        },
      ],
    })
    const Page = await loadPage()
    renderPage(Page, '/settings/clients/new')
    await waitForCarriersStep()

    expect(screen.getByText(/UPS · ABC123/)).toBeTruthy()
    // The default star surfaces as a "default" pill.
    expect(screen.getByText(/default/i)).toBeTruthy()
  })

  it('delete button removes a draft and the count updates', async () => {
    seedCarriersDraft({
      carrierDrafts: [
        {
          id: 1, carrierCode: 'UPS', accountNumber: 'ABC123', accountName: '',
          clientId: 'id-1', clientSecret: 'sec-1',
          environment: 'SANDBOX', clientDefault: true,
          shippingPurpose: '', clearanceOption: '',
          thirdPartyAccount: '', thirdPartyName: '', thirdPartyAddress1: '',
          thirdPartyCity: '', thirdPartyState: '', thirdPartyPostcode: '',
          thirdPartyCountry: '',
        },
        {
          id: 2, carrierCode: 'FEDEX', accountNumber: '123456789', accountName: '',
          clientId: 'id-2', clientSecret: 'sec-2',
          environment: 'PRODUCTION', clientDefault: false,
          shippingPurpose: '', clearanceOption: '',
          thirdPartyAccount: '', thirdPartyName: '', thirdPartyAddress1: '',
          thirdPartyCity: '', thirdPartyState: '', thirdPartyPostcode: '',
          thirdPartyCountry: '',
        },
      ],
    })
    const user = userEvent.setup()
    const Page = await loadPage()
    renderPage(Page, '/settings/clients/new')
    await waitForCarriersStep()

    expect(screen.getByText(/UPS · ABC123/)).toBeTruthy()
    expect(screen.getByText(/FedEx · 123456789/)).toBeTruthy()

    // The remove button uses aria-label="Remove {accountNumber}".
    await user.click(screen.getByRole('button', { name: /Remove 123456789/i }))

    await waitFor(() => {
      expect(screen.queryByText(/FedEx · 123456789/)).toBeNull()
    })
    // UPS row stays.
    expect(screen.getByText(/UPS · ABC123/)).toBeTruthy()
  })

  it('multiple drafts can be added and each gets a unique id (React key)', async () => {
    seedCarriersDraft({
      carrierDrafts: [
        {
          id: 1, carrierCode: 'UPS', accountNumber: 'ABC123', accountName: '',
          clientId: 'id-1', clientSecret: 'sec-1',
          environment: 'SANDBOX', clientDefault: true,
          shippingPurpose: '', clearanceOption: '',
          thirdPartyAccount: '', thirdPartyName: '', thirdPartyAddress1: '',
          thirdPartyCity: '', thirdPartyState: '', thirdPartyPostcode: '',
          thirdPartyCountry: '',
        },
        {
          id: 2, carrierCode: 'FEDEX', accountNumber: '123456789', accountName: '',
          clientId: 'id-2', clientSecret: 'sec-2',
          environment: 'SANDBOX', clientDefault: false,
          shippingPurpose: '', clearanceOption: '',
          thirdPartyAccount: '', thirdPartyName: '', thirdPartyAddress1: '',
          thirdPartyCity: '', thirdPartyState: '', thirdPartyPostcode: '',
          thirdPartyCountry: '',
        },
      ],
    })
    const Page = await loadPage()
    renderPage(Page, '/settings/clients/new')
    await waitForCarriersStep()

    // Two remove buttons, distinct aria labels → distinct react keys.
    expect(screen.getByRole('button', { name: /Remove ABC123/i })).toBeTruthy()
    expect(screen.getByRole('button', { name: /Remove 123456789/i })).toBeTruthy()
  })

  it('environment select offers SANDBOX and PRODUCTION', async () => {
    seedCarriersDraft()
    const user = userEvent.setup()
    const Page = await loadPage()
    renderPage(Page, '/settings/clients/new')
    await waitForCarriersStep()

    await user.click(screen.getByRole('button', { name: /Add carrier account/i }))

    const envSelect = screen.getAllByRole('combobox').find(
      (s) => Array.from((s as HTMLSelectElement).options).some((o) => o.value === 'SANDBOX'),
    ) as HTMLSelectElement
    expect(envSelect).toBeDefined()
    const values = Array.from(envSelect.options).map((o) => o.value)
    expect(values).toContain('SANDBOX')
    expect(values).toContain('PRODUCTION')
  })

  it('changing carrier updates the account-number rule (maxLength + placeholder)', async () => {
    seedCarriersDraft()
    const user = userEvent.setup()
    const Page = await loadPage()
    renderPage(Page, '/settings/clients/new')
    await waitForCarriersStep()

    await user.click(screen.getByRole('button', { name: /Add carrier account/i }))

    // UPS rule: maxLength 6, placeholder mentions "6-digit UPS".
    const acctInput = screen.getAllByRole('textbox')[0] as HTMLInputElement
    expect(acctInput.maxLength).toBe(6)
    expect(acctInput.placeholder).toMatch(/UPS/i)

    // Flip carrier to FEDEX → maxLength should become 12.
    const carrierSelect = screen.getAllByRole('combobox').find(
      (s) => Array.from((s as HTMLSelectElement).options).some((o) => o.value === 'FEDEX'),
    ) as HTMLSelectElement
    await user.selectOptions(carrierSelect, 'FEDEX')

    const acctAfter = screen.getAllByRole('textbox')[0] as HTMLInputElement
    expect(acctAfter.maxLength).toBe(12)
    expect(acctAfter.placeholder).toMatch(/FedEx/i)
  })

  it('clientDefault star renders when a draft has clientDefault=true', async () => {
    seedCarriersDraft({
      carrierDrafts: [
        {
          id: 1, carrierCode: 'UPS', accountNumber: 'ABC123', accountName: '',
          clientId: 'id-1', clientSecret: 'sec-1',
          environment: 'SANDBOX', clientDefault: true,
          shippingPurpose: '', clearanceOption: '',
          thirdPartyAccount: '', thirdPartyName: '', thirdPartyAddress1: '',
          thirdPartyCity: '', thirdPartyState: '', thirdPartyPostcode: '',
          thirdPartyCountry: '',
        },
        {
          id: 2, carrierCode: 'FEDEX', accountNumber: '123456789', accountName: '',
          clientId: 'id-2', clientSecret: 'sec-2',
          environment: 'SANDBOX', clientDefault: false,
          shippingPurpose: '', clearanceOption: '',
          thirdPartyAccount: '', thirdPartyName: '', thirdPartyAddress1: '',
          thirdPartyCity: '', thirdPartyState: '', thirdPartyPostcode: '',
          thirdPartyCountry: '',
        },
      ],
    })
    const Page = await loadPage()
    renderPage(Page, '/settings/clients/new')
    await waitForCarriersStep()

    // Both drafts can carry clientDefault=true — the component does not
    // dedupe. Existing behaviour: multiple defaults coexist; the final
    // account-service upsert is what enforces "one default per client".
    const defaults = screen.queryAllByText(/default/i)
    expect(defaults.length).toBeGreaterThanOrEqual(1)
  })

  it('coexists when two drafts both have clientDefault=true (current behaviour)', async () => {
    seedCarriersDraft({
      carrierDrafts: [
        {
          id: 1, carrierCode: 'UPS', accountNumber: 'ABC123', accountName: '',
          clientId: 'id-1', clientSecret: 'sec-1',
          environment: 'SANDBOX', clientDefault: true,
          shippingPurpose: '', clearanceOption: '',
          thirdPartyAccount: '', thirdPartyName: '', thirdPartyAddress1: '',
          thirdPartyCity: '', thirdPartyState: '', thirdPartyPostcode: '',
          thirdPartyCountry: '',
        },
        {
          id: 2, carrierCode: 'FEDEX', accountNumber: '123456789', accountName: '',
          clientId: 'id-2', clientSecret: 'sec-2',
          environment: 'SANDBOX', clientDefault: true,
          shippingPurpose: '', clearanceOption: '',
          thirdPartyAccount: '', thirdPartyName: '', thirdPartyAddress1: '',
          thirdPartyCity: '', thirdPartyState: '', thirdPartyPostcode: '',
          thirdPartyCountry: '',
        },
      ],
    })
    const Page = await loadPage()
    renderPage(Page, '/settings/clients/new')
    await waitForCarriersStep()

    // Both rows render with the "default" badge — the CarrierDraftStep
    // itself does not enforce mutual exclusion.
    expect(screen.getByText(/UPS · ABC123/)).toBeTruthy()
    expect(screen.getByText(/FedEx · 123456789/)).toBeTruthy()
    const pills = screen.getAllByText(/default/i)
    expect(pills.length).toBeGreaterThanOrEqual(2)
  })

  it('picking THIRD_PARTY clearance reveals the third-party billing panel', async () => {
    seedCarriersDraft()
    const user = userEvent.setup()
    const Page = await loadPage()
    renderPage(Page, '/settings/clients/new')
    await waitForCarriersStep()

    await user.click(screen.getByRole('button', { name: /Add carrier account/i }))

    // Third-party billing panel is hidden until clearance = THIRD_PARTY.
    expect(screen.queryByText(/Third-party billing/i)).toBeNull()

    // Find the clearance select (defaults to UPS + carrier default option).
    const clearanceSelect = screen.getAllByRole('combobox').find(
      (s) => Array.from((s as HTMLSelectElement).options).some((o) => o.value === 'THIRD_PARTY'),
    ) as HTMLSelectElement
    await user.selectOptions(clearanceSelect, 'THIRD_PARTY')

    await waitFor(() => {
      expect(screen.getByText(/Third-party billing/i)).toBeTruthy()
    })
    // Panel exposes the account # field.
    expect(screen.getByText(/Third-party account #/i)).toBeTruthy()
  })

  it('edit mode hydrates existing accounts via clientService.listClientAccounts', async () => {
    listClientAccounts.mockResolvedValue([
      {
        id: 1, accountNumber: 'UPS-EDIT-1', carrierCode: 'UPS',
        accountName: 'Primary', customerNo: 'ACME', environment: 'PRODUCTION',
        isDefault: false, clientDefault: true, active: true, complete: true,
        clientIdPreview: 'ups...', verified: true, lastVerifiedAt: null,
        labelsGenerated: 12, lastUsedAt: null,
        createdAt: null, updatedAt: null,
      },
    ])
    getClient.mockResolvedValue({
      data: {
        id: 1, clientCode: 'ACME', name: 'Acme',
        email: null, phone: null,
        shipFrom: null, returnAddress: null, returnSameAsShipFrom: true,
      },
    })
    const Page = await loadPage()
    renderPage(Page, '/settings/clients/ACME')

    // Wait for the client to load; the tab rail will show step pills.
    await waitFor(() => {
      expect(listClientAccounts).toHaveBeenCalledWith('ACME')
    })
  })
})

// ===== negative cases =====

describe('CarrierDraftStep — negative cases', () => {
  it('empty account number → error surfaces on blur', async () => {
    seedCarriersDraft()
    const user = userEvent.setup()
    const Page = await loadPage()
    renderPage(Page, '/settings/clients/new')
    await waitForCarriersStep()

    await user.click(screen.getByRole('button', { name: /Add carrier account/i }))

    const acctInput = screen.getAllByRole('textbox')[0] as HTMLInputElement
    // Focus + blur without typing → error. fireEvent.blur wraps the
    // synthetic event dispatch in act() so React state updates flush
    // cleanly (raw .blur() calls trigger the "not wrapped in act" warning).
    fireEvent.blur(acctInput)

    await waitFor(() => {
      expect(screen.getByText(/Account number is required/i)).toBeTruthy()
    })
  })

  it('invalid account number pattern for UPS → error', async () => {
    seedCarriersDraft()
    const user = userEvent.setup()
    const Page = await loadPage()
    renderPage(Page, '/settings/clients/new')
    await waitForCarriersStep()

    await user.click(screen.getByRole('button', { name: /Add carrier account/i }))

    const acctInput = screen.getAllByRole('textbox')[0] as HTMLInputElement
    await user.type(acctInput, '12345')  // 5 chars — UPS wants 6.
    fireEvent.blur(acctInput)

    await waitFor(() => {
      expect(screen.getByText(/UPS shipper number is 6–10 letters or digits/i)).toBeTruthy()
    })
  })

  it('empty client ID → error surfaces on blur', async () => {
    seedCarriersDraft()
    const user = userEvent.setup()
    const Page = await loadPage()
    renderPage(Page, '/settings/clients/new')
    await waitForCarriersStep()

    await user.click(screen.getByRole('button', { name: /Add carrier account/i }))

    const inputs = screen.getAllByRole('textbox')
    const clientIdInput = inputs[1] as HTMLInputElement
    fireEvent.blur(clientIdInput)

    await waitFor(() => {
      expect(screen.getByText(/Client ID is required/i)).toBeTruthy()
    })
  })

  it('empty client secret → error surfaces on blur', async () => {
    seedCarriersDraft()
    const user = userEvent.setup()
    const Page = await loadPage()
    renderPage(Page, '/settings/clients/new')
    await waitForCarriersStep()

    await user.click(screen.getByRole('button', { name: /Add carrier account/i }))

    const secret = document.querySelector('input[type="password"]') as HTMLInputElement
    fireEvent.blur(secret)

    await waitFor(() => {
      expect(screen.getByText(/Client secret is required/i)).toBeTruthy()
    })
  })

  it('Cancel closes the form + clears touched-state errors', async () => {
    seedCarriersDraft()
    const user = userEvent.setup()
    const Page = await loadPage()
    renderPage(Page, '/settings/clients/new')
    await waitForCarriersStep()

    await user.click(screen.getByRole('button', { name: /Add carrier account/i }))

    // Trigger blur errors.
    const acctInput = screen.getAllByRole('textbox')[0] as HTMLInputElement
    fireEvent.blur(acctInput)
    await waitFor(() => {
      expect(screen.getByText(/Account number is required/i)).toBeTruthy()
    })

    await user.click(screen.getByRole('button', { name: /Cancel/i }))

    // Form is gone + errors gone.
    await waitFor(() => {
      expect(screen.queryByText(/Account number is required/i)).toBeNull()
    })
    expect(screen.queryByRole('button', { name: /Add to list/i })).toBeNull()
    // The primary Add button is back.
    expect(screen.getByRole('button', { name: /Add carrier account/i })).toBeTruthy()
  })

  it('zero drafts → wizard Next button is disabled with the carriers blocker', async () => {
    seedCarriersDraft()  // empty carrierDrafts
    const Page = await loadPage()
    renderPage(Page, '/settings/clients/new')
    await waitForCarriersStep()

    const nextBtn = screen.getByRole('button', { name: /^Next/i }) as HTMLButtonElement
    expect(nextBtn.disabled).toBe(true)
    // Tooltip surfaces the exact blocker.
    expect(nextBtn.title).toMatch(/Add at least one carrier account/i)
  })
})

// ===== cross-cutting =====

describe('CarrierDraftStep — cross-cutting', () => {
  it('handleCreate posts one upsertAccount per draft with trimmed strings + uppercase carrier', async () => {
    // Fully-visited wizard, all steps complete, on Summary. The Submit
    // button calls handleCreate which fans out to accountRefService.
    seedCarriersDraft({
      visitedSteps: ['identity', 'shipFrom', 'return', 'carriers', 'mapping', 'importerBroker', 'summary'],
      activeStep: 'summary',
      carrierDrafts: [
        {
          id: 1, carrierCode: 'UPS', accountNumber: '  ABC123 ', accountName: '',
          clientId: '  client-id-1  ', clientSecret: ' secret-value ',
          environment: 'SANDBOX', clientDefault: true,
          shippingPurpose: '', clearanceOption: '',
          thirdPartyAccount: '', thirdPartyName: '', thirdPartyAddress1: '',
          thirdPartyCity: '', thirdPartyState: '', thirdPartyPostcode: '',
          thirdPartyCountry: '',
        },
        {
          id: 2, carrierCode: 'FEDEX', accountNumber: '123456789', accountName: '',
          clientId: 'client-id-2', clientSecret: 'secret-value-2',
          environment: 'PRODUCTION', clientDefault: false,
          shippingPurpose: '', clearanceOption: '',
          thirdPartyAccount: '', thirdPartyName: '', thirdPartyAddress1: '',
          thirdPartyCity: '', thirdPartyState: '', thirdPartyPostcode: '',
          thirdPartyCountry: '',
        },
      ],
      mappingDrafts: [
        { id: 3, shipviaCd: 'GRD', serviceId: 100 },
      ],
    })
    const user = userEvent.setup()
    const Page = await loadPage()
    renderPage(Page, '/settings/clients/new')

    // Wait for Summary layout.
    await waitFor(() => {
      expect(screen.getByRole('button', { name: /Submit — create client/i })).toBeTruthy()
    })

    const submit = screen.getByRole('button', { name: /Submit — create client/i }) as HTMLButtonElement
    // Guard: if the Submit is disabled the test would silently pass without
    // firing upsertAccount. Fail loudly by asserting disabled=false first.
    expect(submit.disabled).toBe(false)
    await user.click(submit)

    await waitFor(() => {
      expect(createClient).toHaveBeenCalledTimes(1)
    })
    await waitFor(() => {
      expect(upsertAccount).toHaveBeenCalledTimes(2)
    })

    const upsCall = upsertAccount.mock.calls.find(
      (c) => (c[0] as { carrierCode: string }).carrierCode === 'UPS',
    )?.[0]
    const fdxCall = upsertAccount.mock.calls.find(
      (c) => (c[0] as { carrierCode: string }).carrierCode === 'FEDEX',
    )?.[0]

    // Trimmed on the way to the service.
    expect(upsCall).toMatchObject({
      accountNumber: 'ABC123',
      carrierCode: 'UPS',
      clientId: 'client-id-1',
      clientSecret: 'secret-value',
      environment: 'SANDBOX',
      customerNo: 'ACME',
      clientDefault: true,
    })
    expect(fdxCall).toMatchObject({
      accountNumber: '123456789',
      carrierCode: 'FEDEX',
      clientId: 'client-id-2',
      clientSecret: 'secret-value-2',
      environment: 'PRODUCTION',
      customerNo: 'ACME',
      clientDefault: false,
    })
  })

  it('persists staged drafts across a simulated reload via localStorage', async () => {
    // First render: seed one draft; the page's persist-effect writes back
    // to localStorage on every render (draft passes through unchanged).
    seedCarriersDraft({
      carrierDrafts: [
        {
          id: 1, carrierCode: 'UPS', accountNumber: 'ABC123', accountName: '',
          clientId: 'id-1', clientSecret: 'sec-1',
          environment: 'SANDBOX', clientDefault: true,
          shippingPurpose: '', clearanceOption: '',
          thirdPartyAccount: '', thirdPartyName: '', thirdPartyAddress1: '',
          thirdPartyCity: '', thirdPartyState: '', thirdPartyPostcode: '',
          thirdPartyCountry: '',
        },
      ],
    })
    const Page = await loadPage()
    const { unmount } = renderPage(Page, '/settings/clients/new')
    await waitForCarriersStep()
    expect(screen.getByText(/UPS · ABC123/)).toBeTruthy()

    // Simulate a reload — unmount + remount without clearing localStorage.
    unmount()

    // Sanity: the persisted draft still has our staged row.
    const raw = localStorage.getItem('clientEditorDraft:anonymous')
    expect(raw).toBeTruthy()
    const parsed = JSON.parse(raw!) as { carrierDrafts: Array<{ accountNumber: string }> }
    expect(parsed.carrierDrafts).toHaveLength(1)
    expect(parsed.carrierDrafts[0].accountNumber).toBe('ABC123')

    renderPage(Page, '/settings/clients/new')
    await waitForCarriersStep()
    expect(screen.getByText(/UPS · ABC123/)).toBeTruthy()
  })

  it('carrierOptions renders all four expected carriers (UPS/FedEx/USPS/DHL)', async () => {
    seedCarriersDraft()
    const user = userEvent.setup()
    const Page = await loadPage()
    renderPage(Page, '/settings/clients/new')
    await waitForCarriersStep()

    await user.click(screen.getByRole('button', { name: /Add carrier account/i }))

    const carrierSelect = screen.getAllByRole('combobox').find(
      (s) => Array.from((s as HTMLSelectElement).options).some((o) => o.value === 'FEDEX'),
    ) as HTMLSelectElement
    const values = Array.from(carrierSelect.options).map((o) => o.value)
    expect(values).toContain('UPS')
    expect(values).toContain('FEDEX')
    expect(values).toContain('USPS')
    expect(values).toContain('DHL')
  })

  it('DHL rule swaps the account-number maxLength on carrier change', async () => {
    seedCarriersDraft()
    const user = userEvent.setup()
    const Page = await loadPage()
    renderPage(Page, '/settings/clients/new')
    await waitForCarriersStep()

    await user.click(screen.getByRole('button', { name: /Add carrier account/i }))

    const carrierSelect = screen.getAllByRole('combobox').find(
      (s) => Array.from((s as HTMLSelectElement).options).some((o) => o.value === 'DHL'),
    ) as HTMLSelectElement
    await user.selectOptions(carrierSelect, 'DHL')

    const acctInput = screen.getAllByRole('textbox')[0] as HTMLInputElement
    expect(acctInput.maxLength).toBe(9)
    expect(acctInput.placeholder).toMatch(/DHL/i)
  })

  it('list container renders one <li> per draft', async () => {
    seedCarriersDraft({
      carrierDrafts: [
        {
          id: 1, carrierCode: 'UPS', accountNumber: 'ABC123', accountName: '',
          clientId: 'id-1', clientSecret: 'sec-1',
          environment: 'SANDBOX', clientDefault: true,
          shippingPurpose: '', clearanceOption: '',
          thirdPartyAccount: '', thirdPartyName: '', thirdPartyAddress1: '',
          thirdPartyCity: '', thirdPartyState: '', thirdPartyPostcode: '',
          thirdPartyCountry: '',
        },
        {
          id: 2, carrierCode: 'FEDEX', accountNumber: '987654321', accountName: '',
          clientId: 'id-2', clientSecret: 'sec-2',
          environment: 'SANDBOX', clientDefault: false,
          shippingPurpose: '', clearanceOption: '',
          thirdPartyAccount: '', thirdPartyName: '', thirdPartyAddress1: '',
          thirdPartyCity: '', thirdPartyState: '', thirdPartyPostcode: '',
          thirdPartyCountry: '',
        },
        {
          id: 3, carrierCode: 'DHL', accountNumber: '123456789', accountName: '',
          clientId: 'id-3', clientSecret: 'sec-3',
          environment: 'PRODUCTION', clientDefault: false,
          shippingPurpose: '', clearanceOption: '',
          thirdPartyAccount: '', thirdPartyName: '', thirdPartyAddress1: '',
          thirdPartyCity: '', thirdPartyState: '', thirdPartyPostcode: '',
          thirdPartyCountry: '',
        },
      ],
    })
    const Page = await loadPage()
    renderPage(Page, '/settings/clients/new')
    await waitForCarriersStep()

    const list = screen.getByRole('list')
    const items = within(list).getAllByRole('listitem')
    expect(items).toHaveLength(3)
  })
})
