import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { Provider } from 'react-redux'
import { combineReducers, configureStore } from '@reduxjs/toolkit'
import type { ComponentType } from 'react'
import carrierReducer from '../store/carrierSlice'
import orderReducer from '../store/orderSlice'
import { ApiError } from '../api/apiClient'

/**
 * Sprint 51 wizard test suite — Summary step + Submit cascade of the
 * client add/edit wizard (create mode only).
 *
 * <p>The component under test (`ClientEditorPage`) is ~3.4k LOC. Reaching
 * the Summary step by clicking through the wizard takes ~2s per test —
 * multiplied over a whole suite that timed out our predecessor. Strategy
 * here: seed the per-user localStorage draft BEFORE render so the wizard
 * mounts directly on the Summary step with every upstream state slice
 * (identity, shipFrom, return, carriers, mapping, importerBroker) already
 * populated. That collapses the render cost to a single mount per test.
 *
 * <p>Covers:
 *   Positive — 6 SummaryCards render, READY pills, Identity/carriers/mapping
 *   titles + counts, importer SKIPPED vs READY, Fix button jumpTo, Submit
 *   label + payload normalization, cascade wiring (createClient →
 *   accountRefService/customsProfileService), post-create navigate.
 *
 *   Negative — invalid identity blocks Submit, zero carriers/mappings turn
 *   cards red, filled+invalid importer NEEDS FIX, CLIENT_CODE_TAKEN error
 *   surfaces via notify.error.
 */

// ---------- Module mocks (must precede the dynamic import below) ----------

const createClientMock = vi.fn()
const upsertAccountMock = vi.fn()
const customsSaveMock = vi.fn()
const attachMock = vi.fn()
const listWarehousesMock = vi.fn()
const listForClientMock = vi.fn()
const listAccountsMock = vi.fn()
const listClientAccountsMock = vi.fn()
const listClientsMock = vi.fn()
const catalogMock = vi.fn()
const customsListProfilesMock = vi.fn()
const customsListMock = vi.fn()
const getClientMock = vi.fn()
const saveRuleMock = vi.fn()

const notifyErrorMock = vi.fn().mockResolvedValue(undefined)
const notifySuccessMock = vi.fn().mockResolvedValue(undefined)
const notifyApiErrorMock = vi.fn().mockResolvedValue(undefined)

const navigateMock = vi.fn()

vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual<typeof import('react-router-dom')>('react-router-dom')
  return {
    ...actual,
    useNavigate: () => navigateMock,
  }
})

vi.mock('../api/warehouseService', () => ({
  warehouseService: {
    listWarehouses: (...args: unknown[]) => listWarehousesMock(...args),
    createWarehouse: vi.fn(),
    updateWarehouse: vi.fn(),
    getWarehouse: vi.fn(),
    toggleActive: vi.fn(),
    deleteWarehouse: vi.fn(),
  },
  clientWarehouseService: {
    listForClient: (...args: unknown[]) => listForClientMock(...args),
    attach: (...args: unknown[]) => attachMock(...args),
    detach: vi.fn(),
    setDefault: vi.fn(),
  },
}))

vi.mock('../api/addressValidationService', () => ({
  addressValidationService: {
    validate: vi.fn(),
  },
}))

vi.mock('../api/clientService', () => ({
  clientService: {
    getClient: (...args: unknown[]) => getClientMock(...args),
    listClients: (...args: unknown[]) => listClientsMock(...args),
    listClientAccounts: (...args: unknown[]) => listClientAccountsMock(...args),
    createClient: (...args: unknown[]) => createClientMock(...args),
    updateClient: vi.fn(),
    cascadePreview: vi.fn(),
    toggleActive: vi.fn(),
    deleteClient: vi.fn(),
    exportClientsCsv: vi.fn(),
  },
}))

vi.mock('../api/accountRefService', () => ({
  accountRefService: {
    listAccounts: (...args: unknown[]) => listAccountsMock(...args),
    upsertAccount: (...args: unknown[]) => upsertAccountMock(...args),
    setClientDefault: vi.fn(),
    toggleActive: vi.fn(),
    deleteAccount: vi.fn(),
    verifyAccount: vi.fn(),
    verifyCredentials: vi.fn(),
    getPlatformCredentials: vi.fn(),
    resolveOrders: vi.fn(),
  },
}))

vi.mock('../api/shippingConfigService', () => ({
  shippingConfigService: {
    catalog: (...args: unknown[]) => catalogMock(...args),
    syncServices: vi.fn(),
    setServiceEnabled: vi.fn(),
    saveRule: (...args: unknown[]) => saveRuleMock(...args),
    deleteRule: vi.fn(),
    setServicePackages: vi.fn(),
    listPresets: vi.fn(),
    savePreset: vi.fn(),
    setDefaultPreset: vi.fn(),
    deletePreset: vi.fn(),
    syncPackages: vi.fn(),
  },
  dimWeightOf: vi.fn(),
  oversizeOf: vi.fn(),
  limitsOf: vi.fn(),
  fitAgainstService: vi.fn(),
}))

vi.mock('../api/customsProfileService', () => ({
  customsProfileService: {
    listProfiles: (...args: unknown[]) => customsListProfilesMock(...args),
    stats: vi.fn(),
    exportProfilesCsv: vi.fn(),
    list: (...args: unknown[]) => customsListMock(...args),
    save: (...args: unknown[]) => customsSaveMock(...args),
    remove: vi.fn(),
  },
}))

vi.mock('../utils/notify', () => ({
  notify: {
    success: (...args: unknown[]) => notifySuccessMock(...args),
    error: (...args: unknown[]) => notifyErrorMock(...args),
    info: vi.fn(),
    apiError: (...args: unknown[]) => notifyApiErrorMock(...args),
    confirm: vi.fn().mockResolvedValue(false),
  },
}))

// ---------- Helpers ----------

async function loadPage(): Promise<ComponentType> {
  const mod = await import('./ClientEditorPage')
  return mod.default
}

function makeStore() {
  const rootReducer = combineReducers({
    carriers: carrierReducer,
    orders: orderReducer,
  })
  return configureStore({
    reducer: rootReducer,
    middleware: (getDefaultMiddleware) =>
      getDefaultMiddleware({ serializableCheck: false }),
  })
}

function renderPage(Page: ComponentType) {
  return render(
    <Provider store={makeStore()}>
      <MemoryRouter initialEntries={['/settings/clients/new']}>
        <Routes>
          <Route path="/settings/clients/new" element={<Page />} />
          <Route path="/settings/clients/:clientCode" element={<Page />} />
          <Route path="/settings/clients" element={<div>CLIENTS_LIST</div>} />
        </Routes>
      </MemoryRouter>
    </Provider>,
  )
}

// A single PLATFORM warehouse whose id matches the seeded selectedShipFromWarehouseId
// so the cascade's "attach warehouse" step has a warehouse to look up.
const platformWh = {
  id: 100,
  code: 'PLAT-EAST',
  name: 'Platform East',
  address: {
    name: 'Platform East',
    line1: '10 Warehouse Row',
    line2: '',
    city: 'Newark',
    state: 'NJ',
    zip: '07102',
    country: 'US',
    phone: '+15551110001',
  },
  ownerType: 'PLATFORM' as const,
  ownerClientCode: null,
  active: true,
  attachedClientCount: 0,
  createdAt: null,
  updatedAt: null,
}

function seedEmptyDeps() {
  listWarehousesMock.mockImplementation(async (params: { ownerType?: string }) => {
    if (params?.ownerType === 'CLIENT') return { data: { content: [] } }
    return { data: { content: [platformWh] } }
  })
  listForClientMock.mockResolvedValue({ data: [] })
  listAccountsMock.mockResolvedValue([])
  listClientAccountsMock.mockResolvedValue([])
  listClientsMock.mockResolvedValue({ data: { content: [] } })
  catalogMock.mockResolvedValue({
    services: [], rules: [], links: [], rulePackages: [], ruleWarehouses: [], originCountries: [],
  })
  customsListProfilesMock.mockResolvedValue({ data: { content: [] } })
  customsListMock.mockResolvedValue([])
  attachMock.mockResolvedValue({ data: { id: 1 } })
  // 404 = code is free (only fires in create mode on debounced blur — but our
  // Identity is already populated from the draft, so this is defensive).
  getClientMock.mockRejectedValue(new ApiError('Not found', 404, { errorCode: 'CLIENT_NOT_FOUND' }))
}

// The pristine importer draft shape (matches emptyImporterBrokerDraft in the source).
function emptyImporter() {
  return {
    filled: false,
    countries: [],
    importerType: 'BUSINESS',
    importerName: '',
    importerCountry: '',
    importerAddress1: '',
    importerAddress2: '',
    importerCity: '',
    importerState: '',
    importerPostcode: '',
    importerPhone: '',
    importerTaxId: '',
    importerTaxIdType: '',
    brokerName: '',
    brokerPhone: '',
    incoterms: '',
    reasonForExport: '',
  }
}

// A fully-valid BUSINESS importer draft.
function validBusinessImporter() {
  return {
    ...emptyImporter(),
    filled: true,
    importerType: 'BUSINESS',
    importerName: 'ACME Importer',
    importerCountry: 'US',
    importerAddress1: '1 Importer Way',
    importerCity: 'Newark',
    importerPostcode: '07102',
    countries: ['US'],
  }
}

/** Seed the create-mode draft so ClientEditorPage mounts directly on the
 *  summary step with every upstream state populated. Avoids paying the
 *  full click-through cost per test.
 *
 *  Note: the draft is only read on mount (see the useMemo with empty deps
 *  in ClientEditorPage.tsx), so this MUST be called BEFORE `renderPage`.
 */
function seedDraftAtSummary(overrides: {
  form?: Record<string, unknown>
  carrierDrafts?: Array<Record<string, unknown>>
  mappingDrafts?: Array<Record<string, unknown>>
  importerBrokerDraft?: Record<string, unknown>
  selectedShipFromWarehouseId?: number | null
} = {}) {
  localStorage.setItem('multiship_user', 'test-op')
  const draft = {
    form: {
      clientCode: 'ACME',
      name: 'ACME Corp',
      email: 'ops@acme.io',
      phone: '',
      shipFrom: {
        name: 'ACME',
        line1: '1 Main St',
        line2: '',
        city: 'NYC',
        state: 'NY',
        zip: '10001',
        country: 'US',
        phone: '5551234567',
      },
      returnAddress: {
        name: '', line1: '', line2: '', city: '', state: '', zip: '', country: 'US', phone: '',
      },
      returnSameAsShipFrom: true,
      ...(overrides.form ?? {}),
    },
    selectedShipFromWarehouseId:
      overrides.selectedShipFromWarehouseId !== undefined
        ? overrides.selectedShipFromWarehouseId
        : 100,
    visitedSteps: ['identity', 'shipFrom', 'return', 'carriers', 'mapping', 'importerBroker', 'summary'],
    activeStep: 'summary',
    carrierDrafts: overrides.carrierDrafts ?? [{
      id: 1,
      carrierCode: 'UPS',
      accountNumber: 'A1234567',
      clientId: 'client-id-1',
      clientSecret: 'secret-1',
      environment: 'SANDBOX',
      clientDefault: true,
      shippingPurpose: '',
      clearanceOption: '',
      thirdPartyAccount: '',
      thirdPartyName: '',
      thirdPartyAddress1: '',
      thirdPartyCity: '',
      thirdPartyState: '',
      thirdPartyPostcode: '',
      thirdPartyCountry: '',
    }],
    mappingDrafts: overrides.mappingDrafts ?? [{ id: 2, shipviaCd: 'P80', serviceId: 1 }],
    importerBrokerDraft: overrides.importerBrokerDraft ?? emptyImporter(),
  }
  localStorage.setItem('clientEditorDraft:test-op', JSON.stringify(draft))
}

/** Get the summary tabpanel — every card + submit lives inside it. Also
 *  scopes queries away from the tab rail (which repeats each step's short
 *  label) and the footer step-label. */
function summaryPanel(): HTMLElement {
  const panel = document.getElementById('client-editor-panel-summary')
  if (!panel) throw new Error('Summary tabpanel not present in DOM')
  return panel as HTMLElement
}

/** Convenience: locate a SummaryCard by its title text (scoped to the
 *  summary tabpanel so tab-rail labels don't collide). */
function findCardByTitle(title: string | RegExp): HTMLElement {
  const titleEl = within(summaryPanel()).getByText(title)
  // The card's title <p> lives inside the outer rounded-2xl card div.
  const card = titleEl.closest('div.rounded-2xl.border')
  if (!card) throw new Error(`Could not find card container for title: ${String(title)}`)
  return card as HTMLElement
}

// userEvent typing delay is 0 so we don't waste real time in the summary suite.
const uev = () => userEvent.setup({ delay: null })

/** Per-test timeout — mount + effects need more than the 5s default when the
 *  suite runs many tests back-to-back and the macrotask queue is warm. */
const HOOK_TIMEOUT = 15000

/** Wrapper around it() that passes HOOK_TIMEOUT as the per-test timeout. */
function itLong(name: string, fn: () => void | Promise<void>) {
  return it(name, fn, HOOK_TIMEOUT)
}

// ---------- Suite ----------

describe('ClientEditorPage — Summary step + Submit cascade (create mode)', () => {
  beforeEach(() => {
    // Wipe every mock so counters don't bleed between tests.
    createClientMock.mockReset()
    upsertAccountMock.mockReset()
    customsSaveMock.mockReset()
    attachMock.mockReset()
    listWarehousesMock.mockReset()
    listForClientMock.mockReset()
    listAccountsMock.mockReset()
    listClientAccountsMock.mockReset()
    listClientsMock.mockReset()
    catalogMock.mockReset()
    customsListProfilesMock.mockReset()
    customsListMock.mockReset()
    getClientMock.mockReset()
    saveRuleMock.mockReset()
    notifyErrorMock.mockClear()
    notifySuccessMock.mockClear()
    notifyApiErrorMock.mockClear()
    navigateMock.mockClear()
    localStorage.clear()
    seedEmptyDeps()
  })

  afterEach(() => {
    localStorage.clear()
  })

  // ===== Positive =====

  itLong('renders 6 SummaryCards with the expected titles', async () => {
    seedDraftAtSummary()
    const Page = await loadPage()
    renderPage(Page)

    // Wait until the summary panel is present (mount + effects settle).
    // "Review & submit" appears twice — as the summary-header <p> and as the
    // footer step-label — so findAllByText avoids the "multiple elements" throw.
    await screen.findAllByText(/Review & submit/i, undefined, { timeout: 4000 })

    // All 6 cards render with title strings — scoped to the summary panel so
    // the tab-rail labels ("Identity", "Ship From", etc.) don't collide.
    const panel = within(summaryPanel())
    expect(panel.getByText(/^Identity$/)).toBeInTheDocument()
    expect(panel.getByText(/^Ship From$/)).toBeInTheDocument()
    expect(panel.getByText(/^Return address$/)).toBeInTheDocument()
    expect(panel.getByText(/^Carrier accounts \(\d+\)$/)).toBeInTheDocument()
    expect(panel.getByText(/^Shipping mappings \(\d+\)$/)).toBeInTheDocument()
    expect(panel.getByText(/^Importer \/ Broker$/)).toBeInTheDocument()
  })

  itLong('shows READY pills on every ready section and enables Submit when the draft is fully valid', async () => {
    seedDraftAtSummary({ importerBrokerDraft: validBusinessImporter() })
    const Page = await loadPage()
    renderPage(Page)

    await screen.findAllByText(/Review & submit/i, undefined, { timeout: 4000 })

    // Every card in this happy-path scenario should render READY (the
    // component renders one READY pill per non-skipped/non-blocked card).
    // Expect six of them.
    await waitFor(() => {
      const readyPills = screen.getAllByText('READY')
      expect(readyPills.length).toBeGreaterThanOrEqual(6)
    })

    // Submit becomes enabled.
    const submit = screen.getByRole('button', { name: /Submit — create client/i }) as HTMLButtonElement
    await waitFor(() => expect(submit.disabled).toBe(false))
  })

  itLong('identity card body shows the client code + name from the draft', async () => {
    seedDraftAtSummary()
    const Page = await loadPage()
    renderPage(Page)

    await screen.findAllByText(/Review & submit/i, undefined, { timeout: 4000 })
    const identityCard = findCardByTitle(/^Identity$/)
    expect(within(identityCard).getByText('ACME')).toBeInTheDocument()
    expect(within(identityCard).getByText(/ACME Corp/)).toBeInTheDocument()
  })

  itLong('carriers card title includes the staged carrier count', async () => {
    seedDraftAtSummary({
      carrierDrafts: [
        {
          id: 1, carrierCode: 'UPS', accountNumber: 'A1', clientId: 'x', clientSecret: 'y',
          environment: 'SANDBOX', clientDefault: true,
          shippingPurpose: '', clearanceOption: '',
          thirdPartyAccount: '', thirdPartyName: '', thirdPartyAddress1: '',
          thirdPartyCity: '', thirdPartyState: '', thirdPartyPostcode: '', thirdPartyCountry: '',
        },
        {
          id: 2, carrierCode: 'FEDEX', accountNumber: 'B2', clientId: 'x2', clientSecret: 'y2',
          environment: 'SANDBOX', clientDefault: false,
          shippingPurpose: '', clearanceOption: '',
          thirdPartyAccount: '', thirdPartyName: '', thirdPartyAddress1: '',
          thirdPartyCity: '', thirdPartyState: '', thirdPartyPostcode: '', thirdPartyCountry: '',
        },
      ],
    })
    const Page = await loadPage()
    renderPage(Page)

    await screen.findAllByText(/Review & submit/i, undefined, { timeout: 4000 })
    expect(screen.getByText('Carrier accounts (2)')).toBeInTheDocument()
  })

  itLong('mapping card title includes the staged mapping count', async () => {
    seedDraftAtSummary({
      mappingDrafts: [
        { id: 1, shipviaCd: 'P80', serviceId: 1 },
        { id: 2, shipviaCd: 'GRD', serviceId: 2 },
        { id: 3, shipviaCd: 'EXP', serviceId: 3 },
      ],
    })
    const Page = await loadPage()
    renderPage(Page)

    await screen.findAllByText(/Review & submit/i, undefined, { timeout: 4000 })
    expect(screen.getByText('Shipping mappings (3)')).toBeInTheDocument()
  })

  itLong('importer card shows SKIPPED (amber) when filled=false', async () => {
    seedDraftAtSummary({ importerBrokerDraft: emptyImporter() })
    const Page = await loadPage()
    renderPage(Page)

    await screen.findAllByText(/Review & submit/i, undefined, { timeout: 4000 })
    const importerCard = findCardByTitle(/^Importer \/ Broker$/)
    expect(within(importerCard).getByText('SKIPPED')).toBeInTheDocument()
    expect(within(importerCard).getByText(/skipped — add profiles later/i)).toBeInTheDocument()
  })

  itLong('importer card shows READY when filled=true + BUSINESS profile is complete', async () => {
    seedDraftAtSummary({ importerBrokerDraft: validBusinessImporter() })
    const Page = await loadPage()
    renderPage(Page)

    await screen.findAllByText(/Review & submit/i, undefined, { timeout: 4000 })
    const importerCard = findCardByTitle(/^Importer \/ Broker$/)
    expect(within(importerCard).getByText('READY')).toBeInTheDocument()
    // BUSINESS pill + importer name render.
    expect(within(importerCard).getByText('BUSINESS')).toBeInTheDocument()
    expect(within(importerCard).getByText(/ACME Importer/)).toBeInTheDocument()
  })

  itLong('clicking a card Edit button jumps back to that step (leaves summary)', async () => {
    seedDraftAtSummary({ importerBrokerDraft: validBusinessImporter() })
    const Page = await loadPage()
    renderPage(Page)

    await screen.findAllByText(/Review & submit/i, undefined, { timeout: 4000 })

    // Grab the Identity card's Edit button and click it.
    const identityCard = findCardByTitle(/^Identity$/)
    const editBtn = within(identityCard).getByRole('button', { name: /Edit/i })
    const user = uev()
    await user.click(editBtn)

    // The Summary panel goes away and the Identity step's inputs render.
    await waitFor(() => {
      expect(screen.queryByText(/Review & submit/i)).not.toBeInTheDocument()
    })
    // The Identity step's client-code input carries the seeded value.
    expect((screen.getByPlaceholderText('MA1885') as HTMLInputElement).value).toBe('ACME')
  })

  itLong('renders the Submit button with the correct label and tooltip when disabled', async () => {
    // Draft is missing carriers → Submit disabled with the carriers blocker.
    seedDraftAtSummary({ carrierDrafts: [] })
    const Page = await loadPage()
    renderPage(Page)

    await screen.findAllByText(/Review & submit/i, undefined, { timeout: 4000 })
    const submit = screen.getByRole('button', { name: /Submit — create client/i }) as HTMLButtonElement
    expect(submit.disabled).toBe(true)
    // Tooltip lists the carriers-missing blocker string.
    expect(submit.title).toMatch(/Cannot submit yet/i)
    expect(submit.title).toMatch(/carrier account/i)
  })

  itLong('Submit click calls createClient with the normalised payload', async () => {
    seedDraftAtSummary({ importerBrokerDraft: validBusinessImporter() })
    createClientMock.mockResolvedValue({
      data: {
        clientCode: 'ACME',
        name: 'ACME Corp',
        email: 'ops@acme.io',
        phone: '',
        shipFrom: null,
        returnAddress: null,
        returnSameAsShipFrom: true,
        carrierAccounts: [],
      },
    })
    upsertAccountMock.mockResolvedValue({ id: 1 })
    customsSaveMock.mockResolvedValue({ id: 1 })
    saveRuleMock.mockResolvedValue({ id: 1 })

    const Page = await loadPage()
    renderPage(Page)

    await screen.findAllByText(/Review & submit/i, undefined, { timeout: 4000 })
    const submit = await screen.findByRole('button', { name: /Submit — create client/i })
    // Wait for it to become enabled (readyToCreate resolves after mount).
    await waitFor(() => expect((submit as HTMLButtonElement).disabled).toBe(false))

    const user = uev()
    await user.click(submit)

    await waitFor(() => expect(createClientMock).toHaveBeenCalledTimes(1))
    const payload = createClientMock.mock.calls[0][0]
    expect(payload).toMatchObject({
      clientCode: 'ACME',       // uppercased + trimmed
      name: 'ACME Corp',
      email: 'ops@acme.io',
      returnSameAsShipFrom: true,
    })
    // returnAddress is omitted when returnSameAsShipFrom is true.
    expect(payload.returnAddress).toBeUndefined()
  })

  itLong('after successful create: accountRefService.upsertAccount is called per carrier draft', async () => {
    seedDraftAtSummary({
      importerBrokerDraft: validBusinessImporter(),
      carrierDrafts: [
        {
          id: 1, carrierCode: 'UPS', accountNumber: 'A1', clientId: 'x', clientSecret: 'y',
          environment: 'SANDBOX', clientDefault: true,
          shippingPurpose: '', clearanceOption: '',
          thirdPartyAccount: '', thirdPartyName: '', thirdPartyAddress1: '',
          thirdPartyCity: '', thirdPartyState: '', thirdPartyPostcode: '', thirdPartyCountry: '',
        },
        {
          id: 2, carrierCode: 'FEDEX', accountNumber: 'B2', clientId: 'x2', clientSecret: 'y2',
          environment: 'SANDBOX', clientDefault: false,
          shippingPurpose: '', clearanceOption: '',
          thirdPartyAccount: '', thirdPartyName: '', thirdPartyAddress1: '',
          thirdPartyCity: '', thirdPartyState: '', thirdPartyPostcode: '', thirdPartyCountry: '',
        },
      ],
    })
    createClientMock.mockResolvedValue({
      data: { clientCode: 'ACME', name: 'ACME Corp', carrierAccounts: [] },
    })
    upsertAccountMock.mockResolvedValue({ id: 1 })
    customsSaveMock.mockResolvedValue({ id: 1 })
    saveRuleMock.mockResolvedValue({ id: 1 })

    const Page = await loadPage()
    renderPage(Page)

    await screen.findAllByText(/Review & submit/i, undefined, { timeout: 4000 })
    const submit = await screen.findByRole('button', { name: /Submit — create client/i })
    await waitFor(() => expect((submit as HTMLButtonElement).disabled).toBe(false))

    const user = uev()
    await user.click(submit)

    await waitFor(() => expect(upsertAccountMock).toHaveBeenCalledTimes(2))
    // customerNo threaded through from the created client's code.
    expect(upsertAccountMock.mock.calls[0][0].customerNo).toBe('ACME')
    expect(upsertAccountMock.mock.calls[1][0].customerNo).toBe('ACME')
  })

  itLong('after successful create: customsProfileService.save fires only when importer draft filled', async () => {
    // Draft with importer skipped → customs save must NOT fire.
    seedDraftAtSummary({ importerBrokerDraft: emptyImporter() })
    createClientMock.mockResolvedValue({
      data: { clientCode: 'ACME', name: 'ACME Corp', carrierAccounts: [] },
    })
    upsertAccountMock.mockResolvedValue({ id: 1 })
    customsSaveMock.mockResolvedValue({ id: 1 })
    saveRuleMock.mockResolvedValue({ id: 1 })

    const Page = await loadPage()
    renderPage(Page)

    await screen.findAllByText(/Review & submit/i, undefined, { timeout: 4000 })
    const submit = await screen.findByRole('button', { name: /Submit — create client/i })
    await waitFor(() => expect((submit as HTMLButtonElement).disabled).toBe(false))

    const user = uev()
    await user.click(submit)

    await waitFor(() => expect(createClientMock).toHaveBeenCalledTimes(1))
    // Give the cascade a beat to run.
    await waitFor(() => expect(navigateMock).toHaveBeenCalled())
    expect(customsSaveMock).not.toHaveBeenCalled()
  })

  itLong('after successful create: navigate is called with the new client URL', async () => {
    seedDraftAtSummary({ importerBrokerDraft: validBusinessImporter() })
    createClientMock.mockResolvedValue({
      data: { clientCode: 'ACME', name: 'ACME Corp', carrierAccounts: [] },
    })
    upsertAccountMock.mockResolvedValue({ id: 1 })
    customsSaveMock.mockResolvedValue({ id: 1 })
    saveRuleMock.mockResolvedValue({ id: 1 })

    const Page = await loadPage()
    renderPage(Page)

    await screen.findAllByText(/Review & submit/i, undefined, { timeout: 4000 })
    const submit = await screen.findByRole('button', { name: /Submit — create client/i })
    await waitFor(() => expect((submit as HTMLButtonElement).disabled).toBe(false))

    const user = uev()
    await user.click(submit)

    await waitFor(() => expect(navigateMock).toHaveBeenCalled())
    // First arg is the URL — matches /settings/clients/ACME (encodeURIComponent
    // is a no-op on ACME).
    const target = navigateMock.mock.calls[0][0]
    expect(target).toBe('/settings/clients/ACME')
  })

  // ===== Negative =====

  itLong('identity invalid → identity card is NEEDS FIX and Submit is disabled', async () => {
    // Blank name violates identity validation → NEEDS FIX.
    seedDraftAtSummary({
      form: {
        clientCode: 'ACME',
        name: '',   // invalid
        email: '',
        phone: '',
        shipFrom: {
          name: 'ACME', line1: '1 Main', line2: '', city: 'NYC', state: 'NY',
          zip: '10001', country: 'US', phone: '5551234567',
        },
        returnAddress: {
          name: '', line1: '', line2: '', city: '', state: '', zip: '', country: 'US', phone: '',
        },
        returnSameAsShipFrom: true,
      },
    })
    const Page = await loadPage()
    renderPage(Page)

    await screen.findAllByText(/Review & submit/i, undefined, { timeout: 4000 })
    const identityCard = findCardByTitle(/^Identity$/)
    expect(within(identityCard).getByText('NEEDS FIX')).toBeInTheDocument()

    const submit = screen.getByRole('button', { name: /Submit — create client/i }) as HTMLButtonElement
    expect(submit.disabled).toBe(true)
  })

  itLong('zero carrier drafts → carriers card is NEEDS FIX with a blocker line', async () => {
    seedDraftAtSummary({ carrierDrafts: [] })
    const Page = await loadPage()
    renderPage(Page)

    await screen.findAllByText(/Review & submit/i, undefined, { timeout: 4000 })
    const carriersCard = findCardByTitle(/^Carrier accounts \(0\)$/)
    expect(within(carriersCard).getByText('NEEDS FIX')).toBeInTheDocument()
    expect(within(carriersCard).getByText(/Add at least one carrier account/i)).toBeInTheDocument()
  })

  itLong('zero mapping drafts → mapping card is NEEDS FIX with a blocker line', async () => {
    seedDraftAtSummary({ mappingDrafts: [] })
    const Page = await loadPage()
    renderPage(Page)

    await screen.findAllByText(/Review & submit/i, undefined, { timeout: 4000 })
    const mappingCard = findCardByTitle(/^Shipping mappings \(0\)$/)
    expect(within(mappingCard).getByText('NEEDS FIX')).toBeInTheDocument()
    expect(within(mappingCard).getByText(/Add at least one shipping-service mapping/i)).toBeInTheDocument()
  })

  itLong('importer filled=true + invalid BUSINESS profile → importer card is NEEDS FIX', async () => {
    seedDraftAtSummary({
      importerBrokerDraft: {
        ...emptyImporter(),
        filled: true,
        importerType: 'BUSINESS',
        importerName: '',       // missing required
        importerCountry: '',    // missing required
      },
    })
    const Page = await loadPage()
    renderPage(Page)

    await screen.findAllByText(/Review & submit/i, undefined, { timeout: 4000 })
    const importerCard = findCardByTitle(/^Importer \/ Broker$/)
    expect(within(importerCard).getByText('NEEDS FIX')).toBeInTheDocument()
  })

  itLong('createClient rejects with CLIENT_CODE_TAKEN → notify.error fires with the code', async () => {
    seedDraftAtSummary({ importerBrokerDraft: validBusinessImporter() })
    createClientMock.mockRejectedValue(
      new ApiError('Code taken', 409, { errorCode: 'CLIENT_CODE_TAKEN' }),
    )

    const Page = await loadPage()
    renderPage(Page)

    await screen.findAllByText(/Review & submit/i, undefined, { timeout: 4000 })
    const submit = await screen.findByRole('button', { name: /Submit — create client/i })
    await waitFor(() => expect((submit as HTMLButtonElement).disabled).toBe(false))

    const user = uev()
    await user.click(submit)

    await waitFor(() => expect(notifyErrorMock).toHaveBeenCalled())
    // Message must include the code so the operator knows what's colliding.
    const args = notifyErrorMock.mock.calls[0]
    expect(String(args[0])).toMatch(/ACME/)
    expect(String(args[0])).toMatch(/already registered/i)
  })
})
