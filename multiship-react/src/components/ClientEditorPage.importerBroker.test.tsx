import { beforeEach, describe, expect, it, vi } from 'vitest'
import { act, render, screen, waitFor, within, fireEvent } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { Provider } from 'react-redux'
import { combineReducers, configureStore } from '@reduxjs/toolkit'
import type { ComponentType } from 'react'
import carrierReducer from '../store/carrierSlice'
import orderReducer from '../store/orderSlice'

/**
 * Sprint 51 wizard-tests (Importer / Broker step).
 *
 * <p>Covers the create-mode {@code ImporterBrokerDraftStep} (default,
 * unchecked → skipped; checked → BUSINESS identity required; RECEIVER
 * needs nothing) and a hydrate smoke for the edit-mode
 * {@code ImporterBrokerStep} (customs-profile list).
 *
 * <p>Isolation: every network dep is mocked at module scope. localStorage
 * is scrubbed before each test so the create-mode draft never bleeds
 * across cases. To land the operator directly on the Importer step in
 * create mode we pre-seed a well-formed draft snapshot (all mandatory
 * upstream steps visited + valid, activeStep = 'importerBroker') — this
 * bypasses the sequential-lock gate and lets us drive the target step
 * without walking the whole wizard on every test.
 */

// ------- module mocks -----------------------------------------------------

const listCustomsProfiles = vi.fn().mockResolvedValue([])
const saveCustomsProfile = vi.fn().mockResolvedValue({ data: { id: 1 } })
const removeCustomsProfile = vi.fn().mockResolvedValue({ data: null })

vi.mock('../api/customsProfileService', () => ({
  customsProfileService: {
    list: (...args: unknown[]) => listCustomsProfiles(...args),
    save: (...args: unknown[]) => saveCustomsProfile(...args),
    remove: (...args: unknown[]) => removeCustomsProfile(...args),
    listProfiles: vi.fn().mockResolvedValue({ data: { content: [], pageNumber: 0, pageSize: 25, totalElements: 0, totalPages: 0 } }),
    stats: vi.fn().mockResolvedValue({ profiles: 0, destinationsCovered: 0, clientsConfigured: 0 }),
    exportProfilesCsv: vi.fn().mockResolvedValue(undefined),
  },
}))

// `getClient` doubles as (a) the edit-mode loader and (b) the create-mode
// duplicate-code probe. Resolve for the known edit-mode code; reject with a
// 404-shaped error for everything else so `checkClientCodeAvailable` treats
// the create-mode code as available and identity validates.
const getClient = vi.fn((code: string) => {
  if (code === 'EDIT1') {
    return Promise.resolve({
      data: {
        clientCode: 'EDIT1',
        name: 'Edit-mode Client',
        email: '',
        phone: '',
        shipFrom: null,
        returnAddress: null,
        returnSameAsShipFrom: true,
        carrierAccounts: [],
      },
    })
  }
  return Promise.reject(Object.assign(new Error('Not found'), { status: 404 }))
})

vi.mock('../api/clientService', () => ({
  clientService: {
    listClients: vi.fn().mockResolvedValue({ data: { content: [] } }),
    getClient: (code: string) => getClient(code),
    createClient: vi.fn().mockResolvedValue({ data: { clientCode: 'NEW1', name: 'new' } }),
    updateClient: vi.fn().mockResolvedValue({ data: { clientCode: 'EDIT1', name: 'edit' } }),
    cascadePreview: vi.fn().mockResolvedValue({ data: {} }),
    toggleActive: vi.fn().mockResolvedValue({ data: {} }),
    deleteClient: vi.fn().mockResolvedValue({ data: null }),
    listClientAccounts: vi.fn().mockResolvedValue([]),
    exportClientsCsv: vi.fn().mockResolvedValue(undefined),
  },
}))

vi.mock('../api/accountRefService', () => ({
  accountRefService: {
    listAccounts: vi.fn().mockResolvedValue([]),
    upsertAccount: vi.fn().mockResolvedValue({ data: { id: 1 } }),
  },
}))

vi.mock('../api/warehouseService', () => ({
  warehouseService: {
    listWarehouses: vi.fn().mockResolvedValue({ data: { content: [] } }),
    getWarehouse: vi.fn().mockResolvedValue({ data: null }),
    createWarehouse: vi.fn(),
    updateWarehouse: vi.fn(),
    toggleActive: vi.fn(),
    deleteWarehouse: vi.fn(),
  },
  clientWarehouseService: {
    listForClient: vi.fn().mockResolvedValue({ data: [] }),
    attach: vi.fn().mockResolvedValue({ data: null }),
    detach: vi.fn(),
    setDefault: vi.fn(),
  },
}))

vi.mock('../api/shippingConfigService', () => ({
  shippingConfigService: {
    catalog: vi.fn().mockResolvedValue({
      services: [],
      rules: [],
      links: [],
      rulePackages: [],
      ruleWarehouses: [],
      originCountries: [],
    }),
    syncServices: vi.fn(),
    setServiceEnabled: vi.fn(),
    saveRule: vi.fn().mockResolvedValue({ data: null }),
    deleteRule: vi.fn(),
    setServicePackages: vi.fn(),
    listPresets: vi.fn().mockResolvedValue([]),
    savePreset: vi.fn(),
  },
}))

// Notify toasts would otherwise touch the store; keep them silent so the
// tests don't have to render a toast portal. `confirm` defaults to accept
// so remove flows resolve; individual tests override when needed.
const notifyError = vi.fn().mockResolvedValue(undefined)
const notifySuccess = vi.fn().mockResolvedValue(undefined)
const notifyInfo = vi.fn().mockResolvedValue(undefined)
const notifyApiError = vi.fn().mockResolvedValue(undefined)
const notifyConfirm = vi.fn().mockResolvedValue(true)

vi.mock('../utils/notify', () => ({
  notify: {
    success: (...a: unknown[]) => notifySuccess(...a),
    error: (...a: unknown[]) => notifyError(...a),
    info: (...a: unknown[]) => notifyInfo(...a),
    apiError: (...a: unknown[]) => notifyApiError(...a),
    confirm: (...a: unknown[]) => notifyConfirm(...a),
  },
  notifyStore: { subscribe: () => () => {}, get: () => [] },
}))

// The edit-mode step opens CustomsProfileModal; stub it to a bare marker so
// we don't drag the full modal (with its own effects + focus trap) into the
// smoke test. We only assert that Add / Edit surfaces the modal.
vi.mock('./modals/CustomsProfileModal', () => ({
  default: () => <div data-testid="customs-profile-modal-stub" />,
}))

// WarehouseEditorModal is a heavy child pulled in by the outer wizard.
// Never opens in Importer-step tests but the outer wizard imports it —
// keep it lightweight.
vi.mock('./modals/WarehouseEditorModal', () => ({
  default: () => null,
}))

// ------- helpers ----------------------------------------------------------

const DRAFT_KEY = 'clientEditorDraft:anonymous'

/**
 * Build a draft snapshot that lands the operator on the Importer / Broker
 * step with every mandatory upstream step marked complete. Mandatory steps
 * without persisted data (carriers / mapping) don't need real drafts here —
 * we override the fields that gate `stepComplete`. But we DO seed at least
 * one carrier + mapping draft so the sequential lock treats those steps
 * as done.
 */
function seedDraftOnImporterStep(overrides: Record<string, unknown> = {}) {
  const defaultImporter = {
    filled: false,
    countries: [] as string[],
    importerType: 'BUSINESS',
    importerName: '', importerCountry: '', importerAddress1: '', importerAddress2: '',
    importerCity: '', importerState: '', importerPostcode: '', importerPhone: '',
    importerTaxId: '', importerTaxIdType: '',
    brokerName: '', brokerPhone: '',
    incoterms: '', reasonForExport: '',
  }
  const mergedImporter = overrides.importerBrokerDraft
    ? { ...defaultImporter, ...(overrides.importerBrokerDraft as Record<string, unknown>) }
    : defaultImporter

  const restOverrides: Record<string, unknown> = { ...overrides }
  delete restOverrides.importerBrokerDraft
  const draft = {
    form: {
      clientCode: 'TEST1',
      name: 'Test Client',
      email: '',
      phone: '',
      shipFrom: {
        name: 'Warehouse One', line1: '1 Test St', line2: '',
        city: 'Testville', state: 'CA', zip: '90210', country: 'US', phone: '',
      },
      returnAddress: {
        name: '', line1: '', line2: '', city: '', state: '', zip: '', country: 'US', phone: '',
      },
      returnSameAsShipFrom: true,
    },
    selectedShipFromWarehouseId: 1,
    visitedSteps: ['identity', 'shipFrom', 'return', 'carriers', 'mapping', 'importerBroker', 'summary'],
    activeStep: 'importerBroker',
    carrierDrafts: [{
      id: 1, carrierCode: 'UPS', accountNumber: 'A1', accountName: 'Main',
      clientId: '', clientSecret: '', environment: 'PROD', clientDefault: true,
      shippingPurpose: '', clearanceOption: '',
      thirdPartyAccount: '', thirdPartyName: '', thirdPartyAddress1: '',
      thirdPartyCity: '', thirdPartyState: '', thirdPartyPostcode: '', thirdPartyCountry: '',
    }],
    mappingDrafts: [{ id: 2, shipviaCd: 'UPS-GRND', serviceId: 100 }],
    importerBrokerDraft: mergedImporter,
    ...restOverrides,
  }
  localStorage.setItem(DRAFT_KEY, JSON.stringify(draft))
  return draft
}

function makeStore() {
  const rootReducer = combineReducers({ carriers: carrierReducer, orders: orderReducer })
  return configureStore({
    reducer: rootReducer,
    middleware: (getDefault) => getDefault({ serializableCheck: false }),
  })
}

async function loadPage(): Promise<ComponentType> {
  // Dynamic import so vi.mock() calls above land before the module evaluates.
  const mod = await import('./ClientEditorPage')
  return mod.default
}

function renderCreate(Page: ComponentType) {
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

function renderEdit(Page: ComponentType, clientCode = 'EDIT1') {
  return render(
    <Provider store={makeStore()}>
      <MemoryRouter initialEntries={[`/settings/clients/${clientCode}`]}>
        <Routes>
          <Route path="/settings/clients/new" element={<Page />} />
          <Route path="/settings/clients/:clientCode" element={<Page />} />
          <Route path="/settings/clients" element={<div>CLIENTS_LIST</div>} />
        </Routes>
      </MemoryRouter>
    </Provider>,
  )
}

/** Grab the "Fill Importer / Broker now" checkbox — the guardrail toggle. */
function fillCheckbox() {
  return screen.getByRole('checkbox', { name: /Fill Importer \/ Broker now/i })
}

// ------- suites -----------------------------------------------------------

describe('ClientEditorPage · ImporterBrokerDraftStep (create mode)', () => {
  beforeEach(() => {
    localStorage.clear()
    listCustomsProfiles.mockClear()
    saveCustomsProfile.mockClear()
    removeCustomsProfile.mockClear()
    notifyError.mockClear()
    notifySuccess.mockClear()
    notifyApiError.mockClear()
    notifyConfirm.mockClear()
    notifyConfirm.mockResolvedValue(true)
  })

  it('renders the Fill checkbox unchecked by default', async () => {
    seedDraftOnImporterStep()
    const Page = await loadPage()
    renderCreate(Page)
    const cb = await screen.findByRole('checkbox', { name: /Fill Importer \/ Broker now/i })
    expect((cb as HTMLInputElement).checked).toBe(false)
  })

  it('shows only the guidance blurb when the Fill checkbox is unchecked', async () => {
    seedDraftOnImporterStep()
    const Page = await loadPage()
    renderCreate(Page)
    await screen.findByText(/Fill Importer \/ Broker now/i)
    // Guidance body is visible…
    expect(screen.getByText(/Optional\. Captures one primary importer profile/i)).toBeInTheDocument()
    // …but the form-only fields (Importer name, Incoterms picker) are not.
    expect(screen.queryByText(/^Importer name$/i)).not.toBeInTheDocument()
    expect(screen.queryByText(/^Incoterms$/i)).not.toBeInTheDocument()
    expect(screen.queryByText(/^Broker \(optional\)$/i)).not.toBeInTheDocument()
  })

  it('checking Fill reveals the identity block, shipment defaults, and broker section', async () => {
    seedDraftOnImporterStep()
    const Page = await loadPage()
    renderCreate(Page)
    const user = userEvent.setup()
    const cb = await screen.findByRole('checkbox', { name: /Fill Importer \/ Broker now/i })
    await user.click(cb)

    // Importer type radios visible
    expect(screen.getByRole('radio', { name: /BUSINESS · fixed importer/i })).toBeInTheDocument()
    expect(screen.getByRole('radio', { name: /RECEIVER · consignee is IOR/i })).toBeInTheDocument()

    // BUSINESS identity fields
    expect(screen.getByText(/^Importer name$/i)).toBeInTheDocument()
    expect(screen.getByText(/^Country \(ISO-2\)$/i)).toBeInTheDocument()
    expect(screen.getByText(/^Address line 1$/i)).toBeInTheDocument()
    expect(screen.getByText(/^City$/i)).toBeInTheDocument()
    expect(screen.getByText(/^Postal code$/i)).toBeInTheDocument()
    // Shipment defaults
    expect(screen.getByText(/Shipment defaults \(optional\)/i)).toBeInTheDocument()
    expect(screen.getByText(/^Incoterms$/i)).toBeInTheDocument()
    expect(screen.getByText(/Reason for export/i)).toBeInTheDocument()
    // Broker section
    expect(screen.getByText(/^Broker \(optional\)$/i)).toBeInTheDocument()
    expect(screen.getByText(/^Broker name$/i)).toBeInTheDocument()
    expect(screen.getByText(/^Broker phone$/i)).toBeInTheDocument()
  })

  it('BUSINESS is selected by default and switching to RECEIVER hides the identity block', async () => {
    seedDraftOnImporterStep({ importerBrokerDraft: { filled: true } })
    const Page = await loadPage()
    renderCreate(Page)
    const business = await screen.findByRole('radio', { name: /BUSINESS · fixed importer/i })
    expect((business as HTMLInputElement).checked).toBe(true)
    expect(screen.getByText(/^Importer name$/i)).toBeInTheDocument()

    const user = userEvent.setup()
    await user.click(screen.getByRole('radio', { name: /RECEIVER · consignee is IOR/i }))

    // Identity fields drop out; the type-hint blurb remains.
    expect(screen.queryByText(/^Importer name$/i)).not.toBeInTheDocument()
    expect(screen.queryByText(/^Address line 1$/i)).not.toBeInTheDocument()
    // Shipment defaults stay visible (they apply regardless of importer type).
    expect(screen.getByText(/^Incoterms$/i)).toBeInTheDocument()
  })

  it('BUSINESS + all required identity fields → Next is enabled', async () => {
    seedDraftOnImporterStep({
      importerBrokerDraft: {
        filled: true,
        importerType: 'BUSINESS',
        importerName: 'Acme Imports',
        importerCountry: 'US',
        importerAddress1: '10 Wharf',
        importerCity: 'Boston',
        importerPostcode: '02110',
      },
    })
    const Page = await loadPage()
    renderCreate(Page)
    const next = await screen.findByRole('button', { name: /^Next/i })
    await waitFor(() => expect((next as HTMLButtonElement).disabled).toBe(false))
  })

  it('RECEIVER with no identity fields still counts as complete (DAP mode)', async () => {
    seedDraftOnImporterStep({
      importerBrokerDraft: { filled: true, importerType: 'RECEIVER' },
    })
    const Page = await loadPage()
    renderCreate(Page)
    const next = await screen.findByRole('button', { name: /^Next/i })
    await waitFor(() => expect((next as HTMLButtonElement).disabled).toBe(false))
    // Sanity — the identity block is not rendered for RECEIVER.
    expect(screen.queryByText(/^Importer name$/i)).not.toBeInTheDocument()
  })

  it('Country field uppercases the operator input', async () => {
    seedDraftOnImporterStep({ importerBrokerDraft: { filled: true } })
    const Page = await loadPage()
    renderCreate(Page)
    const countryLabel = await screen.findByText(/^Country \(ISO-2\)$/i)
    const countryInput = countryLabel.parentElement!.querySelector('input') as HTMLInputElement
    fireEvent.change(countryInput, { target: { value: 'gb' } })
    expect(countryInput.value).toBe('GB')
  })

  it('Tax ID type uppercases the operator input', async () => {
    seedDraftOnImporterStep({ importerBrokerDraft: { filled: true } })
    const Page = await loadPage()
    renderCreate(Page)
    const label = await screen.findByText(/^Tax ID type$/i)
    const input = label.parentElement!.querySelector('input') as HTMLInputElement
    fireEvent.change(input, { target: { value: 'eori' } })
    expect(input.value).toBe('EORI')
  })

  it('Incoterms dropdown surfaces all 12 canonical codes', async () => {
    seedDraftOnImporterStep({ importerBrokerDraft: { filled: true } })
    const Page = await loadPage()
    renderCreate(Page)
    const incotermsLabel = await screen.findByText(/^Incoterms$/i)
    const select = incotermsLabel.parentElement!.querySelector('select') as HTMLSelectElement
    const values = Array.from(select.options).map((o) => o.value)
    for (const c of ['DDP', 'DAP', 'DDU', 'EXW', 'CIF', 'FOB', 'DPU', 'CPT', 'CIP', 'FCA', 'FAS', 'CFR']) {
      expect(values).toContain(c)
    }
    // Plus the "carrier default" empty option.
    expect(values).toContain('')
    // 12 codes + 1 placeholder = 13 options exactly.
    expect(select.options.length).toBe(13)
  })

  it('Reason for export dropdown surfaces 6 canonical reasons', async () => {
    seedDraftOnImporterStep({ importerBrokerDraft: { filled: true } })
    const Page = await loadPage()
    renderCreate(Page)
    const label = await screen.findByText(/Reason for export/i)
    const select = label.parentElement!.querySelector('select') as HTMLSelectElement
    const values = Array.from(select.options).map((o) => o.value)
    for (const r of ['SALE', 'GIFT', 'SAMPLE', 'RETURN', 'REPAIR', 'DOCUMENTS']) {
      expect(values).toContain(r)
    }
    expect(select.options.length).toBe(7) // 6 + placeholder
  })

  it('Broker section is optional — empty broker + valid BUSINESS still enables Next', async () => {
    seedDraftOnImporterStep({
      importerBrokerDraft: {
        filled: true,
        importerType: 'BUSINESS',
        importerName: 'Ok Co', importerCountry: 'US',
        importerAddress1: '1 Main', importerCity: 'X', importerPostcode: '12345',
        brokerName: '', brokerPhone: '',
      },
    })
    const Page = await loadPage()
    renderCreate(Page)
    const next = await screen.findByRole('button', { name: /^Next/i })
    await waitFor(() => expect((next as HTMLButtonElement).disabled).toBe(false))
  })

  it('unchecking Fill after filling hides the form and preserves the draft (re-check restores)', async () => {
    seedDraftOnImporterStep({
      importerBrokerDraft: {
        filled: true, importerType: 'BUSINESS',
        importerName: 'Preserve Me', importerCountry: 'US',
        importerAddress1: '', importerCity: '', importerPostcode: '',
      },
    })
    const Page = await loadPage()
    renderCreate(Page)
    const user = userEvent.setup()

    // Precondition — the value the operator typed is on-screen.
    const nameInput1 = (await screen.findByText(/^Importer name$/i)).parentElement!
      .querySelector('input') as HTMLInputElement
    expect(nameInput1.value).toBe('Preserve Me')

    // Uncheck → form hides.
    await user.click(fillCheckbox())
    expect(screen.queryByText(/^Importer name$/i)).not.toBeInTheDocument()

    // Re-check → the previously-typed value is still there (draft preserved).
    await user.click(fillCheckbox())
    const nameInput2 = (await screen.findByText(/^Importer name$/i)).parentElement!
      .querySelector('input') as HTMLInputElement
    expect(nameInput2.value).toBe('Preserve Me')
  })

  it('filled=false → Next is enabled (skipping the step is a valid path)', async () => {
    seedDraftOnImporterStep() // default has filled=false
    const Page = await loadPage()
    renderCreate(Page)
    const next = await screen.findByRole('button', { name: /^Next/i })
    await waitFor(() => expect((next as HTMLButtonElement).disabled).toBe(false))
  })

  // ---------- negative cases: missing required identity blocks Next ------
  const missing: Array<{ label: string; overrides: Record<string, string> }> = [
    { label: 'importer name',   overrides: { importerName: '' } },
    { label: 'importer country', overrides: { importerCountry: '' } },
    { label: 'address1',        overrides: { importerAddress1: '' } },
    { label: 'city',            overrides: { importerCity: '' } },
    { label: 'postcode',        overrides: { importerPostcode: '' } },
  ]
  for (const { label, overrides } of missing) {
    it(`BUSINESS + missing ${label} → Next disabled with a fix-hint tooltip`, async () => {
      seedDraftOnImporterStep({
        importerBrokerDraft: {
          filled: true, importerType: 'BUSINESS',
          importerName: 'Acme', importerCountry: 'US',
          importerAddress1: '1 Main', importerCity: 'X', importerPostcode: '12345',
          ...overrides,
        },
      })
      const Page = await loadPage()
      renderCreate(Page)
      const next = await screen.findByRole('button', { name: /^Next/i })
      await waitFor(() => expect((next as HTMLButtonElement).disabled).toBe(true))
      // Tooltip carries the fix-hint mentioning the required fields.
      const tooltip = next.getAttribute('title') || ''
      expect(tooltip).toMatch(/name, country, address, city and postal code/i)
    })
  }

  // ---------- persistence + submit fan-out --------------------------------
  it('draft edits are pushed back to localStorage (checkbox toggle persists across reloads)', async () => {
    seedDraftOnImporterStep()
    const Page = await loadPage()
    const { unmount } = renderCreate(Page)
    const user = userEvent.setup()

    await user.click(fillCheckbox())
    // wait for the persistence useEffect to flush
    await waitFor(() => {
      const raw = localStorage.getItem(DRAFT_KEY)
      expect(raw).toBeTruthy()
      const parsed = JSON.parse(raw as string)
      expect(parsed.importerBrokerDraft.filled).toBe(true)
    })

    unmount()

    // Fresh render — the draft is hydrated back onto the same step with
    // the checkbox already ticked.
    renderCreate(Page)
    const cb = await screen.findByRole('checkbox', { name: /Fill Importer \/ Broker now/i })
    expect((cb as HTMLInputElement).checked).toBe(true)
  })

  it('when Fill=false, submit does NOT call customsProfileService.save', async () => {
    // Land on Summary (the only step that surfaces Submit) with filled=false.
    seedDraftOnImporterStep({ activeStep: 'summary' })
    const Page = await loadPage()
    renderCreate(Page)
    const user = userEvent.setup()

    const submit = await screen.findByRole('button', { name: /Submit — create client/i })
    await waitFor(() => expect((submit as HTMLButtonElement).disabled).toBe(false))
    await act(async () => { await user.click(submit) })

    await waitFor(() => {
      // clientService.createClient WAS called, but save on customs profile was skipped.
      expect(saveCustomsProfile).not.toHaveBeenCalled()
    })
  })

  it('when Fill=true + BUSINESS valid, submit calls customsProfileService.save with normalised payload', async () => {
    seedDraftOnImporterStep({
      activeStep: 'summary',
      importerBrokerDraft: {
        filled: true, importerType: 'BUSINESS',
        importerName: '  Acme Imports  ', // trailing/leading spaces get trimmed
        importerCountry: 'us',            // uppercased
        importerAddress1: '10 Wharf',
        importerCity: 'Boston',
        importerPostcode: '02110',
        importerAddress2: '',             // empty → null in payload
        incoterms: 'ddp',                 // uppercased
        reasonForExport: 'sale',
      },
    })
    const Page = await loadPage()
    renderCreate(Page)
    const user = userEvent.setup()

    const submit = await screen.findByRole('button', { name: /Submit — create client/i })
    await waitFor(() => expect((submit as HTMLButtonElement).disabled).toBe(false))
    await act(async () => { await user.click(submit) })

    await waitFor(() => expect(saveCustomsProfile).toHaveBeenCalledTimes(1))
    const [clientCode, payload] = saveCustomsProfile.mock.calls[0] as [string, Record<string, unknown>]
    expect(clientCode).toBe('NEW1')                // response.data.clientCode from createClient mock
    expect(payload.importerType).toBe('BUSINESS')
    expect(payload.importerName).toBe('Acme Imports')
    expect(payload.importerCountry).toBe('US')
    expect(payload.importerAddress1).toBe('10 Wharf')
    expect(payload.importerAddress2).toBeNull()   // empty optional → null
    expect(payload.incoterms).toBe('DDP')
    expect(payload.reasonForExport).toBe('SALE')
    expect(payload.importerTaxId).toBeNull()
    expect(payload.brokerName).toBeNull()
  })
})

describe('ClientEditorPage · ImporterBrokerStep (edit mode)', () => {
  beforeEach(() => {
    localStorage.clear()
    listCustomsProfiles.mockClear()
    saveCustomsProfile.mockClear()
    removeCustomsProfile.mockClear()
    notifyConfirm.mockClear()
    notifyConfirm.mockResolvedValue(true)
  })

  it('renders profiles fetched via customsProfileService.list(clientCode) on the Importer step', async () => {
    listCustomsProfiles.mockResolvedValueOnce([
      {
        id: 11, clientCode: 'EDIT1', clientName: 'Edit-mode Client',
        countries: ['US', 'CA'],
        importerType: 'BUSINESS',
        importerName: 'Widget Imports LLC',
        brokerName: null, brokerCompany: null,
        incoterms: 'DDP',
      },
    ])
    const Page = await loadPage()
    renderEdit(Page, 'EDIT1')

    // Wait for the client-load effect to settle, then jump to the step.
    const importerTab = await screen.findByRole('tab', { name: /Importer/i })
    const user = userEvent.setup()
    await user.click(importerTab)

    await waitFor(() => {
      expect(listCustomsProfiles).toHaveBeenCalledWith('EDIT1')
    })
    // Panel renders the seeded profile
    const listItem = await screen.findByText(/Widget Imports LLC/)
    expect(listItem).toBeInTheDocument()
    // Both country pills surface within the profile row.
    const row = listItem.closest('li') as HTMLElement
    expect(within(row).getByText('US')).toBeInTheDocument()
    expect(within(row).getByText('CA')).toBeInTheDocument()
  })
})
