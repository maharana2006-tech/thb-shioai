import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, cleanup } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import type { ComponentType } from 'react'

/**
 * Sprint 53 wizard-tests — Return address step of the client add/edit wizard.
 *
 * Scope: the `ReturnStep` inner component of `ClientEditorPage`. The step
 * carries a single boolean toggle ("Same as Ship From") plus an editable
 * `AddressGrid` block when the toggle is off. When on, the return address
 * is dropped from the outbound payload (`returnAddress: undefined`).
 *
 * To avoid the cost of clicking through Identity + Ship From on every test
 * we pre-seed the create-mode draft in localStorage so the wizard opens
 * directly on the Return step. Every network-touching service is fully
 * mocked; nothing hits real fetch.
 *
 * Test count is intentionally capped (~10 tests) — Return is a small step
 * and each ClientEditorPage render is expensive.
 */

// ===== Service mocks =====
// Hoisted (vi.mock is hoisted) so they land before ClientEditorPage is
// evaluated on dynamic import inside the tests.

const getClient = vi.fn()
const createClient = vi.fn()
const updateClient = vi.fn()
const listClients = vi.fn()
const listClientAccounts = vi.fn()

vi.mock('../api/clientService', async () => {
  const actual = await vi.importActual<typeof import('../api/clientService')>(
    '../api/clientService',
  )
  return {
    ...actual,
    clientService: {
      getClient: (...a: unknown[]) => getClient(...a),
      createClient: (...a: unknown[]) => createClient(...a),
      updateClient: (...a: unknown[]) => updateClient(...a),
      listClients: (...a: unknown[]) => listClients(...a),
      listClientAccounts: (...a: unknown[]) => listClientAccounts(...a),
      cascadePreview: vi.fn(),
      toggleActive: vi.fn(),
      deleteClient: vi.fn(),
      exportClientsCsv: vi.fn(),
    },
  }
})

vi.mock('../api/accountRefService', () => ({
  accountRefService: {
    listAccounts: vi.fn().mockResolvedValue([]),
    upsertAccount: vi.fn(),
    deleteAccount: vi.fn(),
    setClientDefault: vi.fn(),
    toggleActive: vi.fn(),
    verifyAccount: vi.fn(),
    verifyCredentials: vi.fn(),
    getPlatformCredentials: vi.fn(),
    resolveOrders: vi.fn().mockResolvedValue([]),
  },
}))

vi.mock('../api/warehouseService', () => ({
  warehouseService: {
    listWarehouses: vi.fn().mockResolvedValue({
      data: { content: [], pageNumber: 0, pageSize: 50, totalElements: 0, totalPages: 0 },
    }),
    getWarehouse: vi.fn(),
    createWarehouse: vi.fn(),
    updateWarehouse: vi.fn(),
    toggleActive: vi.fn(),
    deleteWarehouse: vi.fn(),
  },
  clientWarehouseService: {
    listForClient: vi.fn().mockResolvedValue({ data: [] }),
    attach: vi.fn().mockResolvedValue({ data: {} }),
    detach: vi.fn(),
    setDefault: vi.fn().mockResolvedValue({ data: {} }),
  },
}))

vi.mock('../api/shippingConfigService', () => ({
  shippingConfigService: {
    catalog: vi.fn().mockResolvedValue({
      services: [], rules: [], links: [], rulePackages: [], ruleWarehouses: [], originCountries: [],
    }),
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

vi.mock('../api/customsProfileService', () => ({
  customsProfileService: {
    list: vi.fn().mockResolvedValue([]),
    save: vi.fn(),
    remove: vi.fn(),
    exportCsv: vi.fn(),
  },
}))

vi.mock('../api/addressValidationService', () => ({
  addressValidationService: {
    validate: vi.fn().mockResolvedValue({ ok: true, normalized: null, warnings: [] }),
  },
}))

// notify stub — Save path fires notify.success/error; keep the DOM clean.
const notifyError = vi.fn()
const notifySuccess = vi.fn()
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

// ===== helpers =====

async function loadPage(): Promise<ComponentType> {
  const mod = await import('./ClientEditorPage')
  return mod.default
}

function renderCreate(Page: ComponentType) {
  return render(
    <MemoryRouter initialEntries={['/settings/clients/new']}>
      <Routes>
        <Route path="/settings/clients/new" element={<Page />} />
        <Route path="/settings/clients/:clientCode" element={<Page />} />
        <Route path="/settings/clients" element={<div>CLIENTS_LIST</div>} />
      </Routes>
    </MemoryRouter>,
  )
}

function renderEdit(Page: ComponentType, code: string) {
  return render(
    <MemoryRouter initialEntries={[`/settings/clients/${code}`]}>
      <Routes>
        <Route path="/settings/clients/new" element={<Page />} />
        <Route path="/settings/clients/:clientCode" element={<Page />} />
        <Route path="/settings/clients" element={<div>CLIENTS_LIST</div>} />
      </Routes>
    </MemoryRouter>,
  )
}

/**
 * Seed the create-mode draft so the wizard opens directly on the Return
 * step with a valid Ship From already populated. Removes the need to
 * click through Identity + Ship From in each test (a full ClientEditorPage
 * render is expensive on jsdom).
 */
function seedReturnDraft(overrides: {
  returnSameAsShipFrom?: boolean
  returnAddress?: Partial<{
    name: string; line1: string; line2: string; city: string;
    state: string; zip: string; country: string; phone: string
  }>
} = {}) {
  const validShipFrom = {
    name: 'Acme HQ',
    line1: '1 Test St',
    line2: '',
    city: 'Testville',
    state: 'NY',
    zip: '10001',
    country: 'US',
    phone: '',
  }
  const emptyAddr = {
    name: '', line1: '', line2: '', city: '',
    state: '', zip: '', country: 'US', phone: '',
  }
  const draft = {
    form: {
      clientCode: 'ACME',
      name: 'Acme Test Client',
      email: '',
      phone: '',
      shipFrom: validShipFrom,
      returnAddress: { ...emptyAddr, ...(overrides.returnAddress ?? {}) },
      returnSameAsShipFrom: overrides.returnSameAsShipFrom ?? true,
    },
    selectedShipFromWarehouseId: null,
    visitedSteps: ['identity', 'shipFrom', 'return'],
    activeStep: 'return',
    carrierDrafts: [],
    mappingDrafts: [],
  }
  localStorage.setItem('clientEditorDraft:anonymous', JSON.stringify(draft))
}

/** Sample Client shape for edit-mode hydration. */
function sampleClient(overrides: Record<string, unknown> = {}) {
  return {
    id: 42,
    clientCode: 'ACME',
    name: 'Acme Widgets',
    email: 'ops@acme.io',
    phone: '+1 555-123-4567',
    status: 'ACTIVE',
    shipFrom: {
      name: 'Acme HQ', line1: '1 Test St', line2: '',
      city: 'Testville', state: 'NY', zip: '10001', country: 'US', phone: '',
    },
    returnAddress: null,
    returnSameAsShipFrom: true,
    defaultCurrency: 'USD',
    defaultWeightUnit: 'LB',
    defaultDimUnit: 'IN',
    timezone: 'America/New_York',
    defaultOriginCountry: 'US',
    createdAt: '2026-08-01T00:00:00',
    updatedAt: '2026-08-01T00:00:00',
    carrierAccounts: [],
    orderCount: 0,
    ...overrides,
  }
}

/** Wait until the Return step is showing — anchor on its unique heading. */
async function waitForReturnStep() {
  await waitFor(() => {
    expect(screen.getByRole('heading', { name: /Return address/i })).toBeTruthy()
  }, { timeout: 4000 })
}

/**
 * Edit-mode helper. The page always opens on Identity in edit mode
 * (create-mode draft-restore is skipped when isEdit). Click the Return
 * tab in the step rail to switch panels.
 *
 * The tab's accessible name is "3 Return address" (the step-number span
 * gets concatenated by the a11y name algorithm). We look up by the tab
 * button's `aria-controls` attribute instead — every tab carries a stable
 * `client-editor-panel-<stepKey>` value that's easier to key off.
 */
async function navigateToReturnEdit() {
  // Wait for the tab rail to render (the client-load spinner blocks it).
  await waitFor(() => {
    const tab = document.querySelector('[aria-controls="client-editor-panel-return"]')
    expect(tab).not.toBeNull()
  }, { timeout: 4000 })
  const tab = document.querySelector('[aria-controls="client-editor-panel-return"]') as HTMLElement
  const user = userEvent.setup()
  await user.click(tab)
  await waitForReturnStep()
}

// ===== lifecycle =====

beforeEach(() => {
  try { localStorage.clear() } catch { /* ignore */ }
  getClient.mockReset().mockRejectedValue({ status: 404 })
  createClient.mockReset().mockResolvedValue({
    data: { id: 1, clientCode: 'ACME', name: 'Acme Test Client' },
  })
  updateClient.mockReset().mockResolvedValue({
    data: sampleClient(),
  })
  listClients.mockReset().mockResolvedValue({ data: { content: [] } })
  listClientAccounts.mockReset().mockResolvedValue([])
  notifyError.mockReset()
  notifySuccess.mockReset()
  notifyApiError.mockReset()
})

afterEach(() => {
  cleanup()
  try { localStorage.clear() } catch { /* ignore */ }
})

// ===== positive cases =====

describe('ClientEditorPage · Return step · positive', () => {
  // First test carries a longer timeout for cold-start (transformer pulls in
  // the 3000-LOC ClientEditorPage + every mocked service module).
  it('renders "Same as Ship From" toggle (checked by default) + collapsed banner', async () => {
    seedReturnDraft()
    const Page = await loadPage()
    renderCreate(Page)
    await waitForReturnStep()

    const toggle = screen.getByLabelText(/Same as Ship From/i) as HTMLInputElement
    expect(toggle).toBeTruthy()
    expect(toggle.type).toBe('checkbox')
    expect(toggle.checked).toBe(true)

    // Collapsed banner in place of the address grid.
    expect(screen.getByText(/Returns use the Ship From address/i)).toBeTruthy()

    // Address fields are not rendered — probe on a Return-specific label.
    // (Ship From's own AddressGrid is not mounted because the page renders
    // one step at a time.) Attention/company only appears when the toggle
    // is off, so it must be absent here.
    expect(screen.queryByPlaceholderText('Warehouse / contact name')).toBeNull()
  }, 30000)

  it('un-checking the toggle reveals the editable address block', async () => {
    seedReturnDraft()
    const Page = await loadPage()
    renderCreate(Page)
    await waitForReturnStep()

    const toggle = screen.getByLabelText(/Same as Ship From/i) as HTMLInputElement
    const user = userEvent.setup()
    await user.click(toggle)

    expect(toggle.checked).toBe(false)
    // Editable grid fields appear.
    expect(screen.getByPlaceholderText('Warehouse / contact name')).toBeTruthy()
    expect(screen.getByPlaceholderText('123 Industrial Blvd')).toBeTruthy()
    expect(screen.getByPlaceholderText('Chicago')).toBeTruthy()
    expect(screen.getByPlaceholderText('60601')).toBeTruthy()
    // The "collapsed" banner is gone.
    expect(screen.queryByText(/Returns use the Ship From address/i)).toBeNull()
  })

  it('same=true → Next button is enabled (no return-address fields required)', async () => {
    seedReturnDraft()
    const Page = await loadPage()
    renderCreate(Page)
    await waitForReturnStep()

    const next = screen.getByRole('button', { name: /^Next/i }) as HTMLButtonElement
    expect(next.disabled).toBe(false)
  })

  it('filling all required fields (same=false) enables Next', async () => {
    seedReturnDraft({ returnSameAsShipFrom: false })
    const Page = await loadPage()
    renderCreate(Page)
    await waitForReturnStep()

    const user = userEvent.setup()
    // Fill every required address field with a US-valid value.
    await user.type(screen.getByPlaceholderText('Warehouse / contact name'), 'Return Desk')
    await user.type(screen.getByPlaceholderText('123 Industrial Blvd'), '500 Return Rd')
    await user.type(screen.getByPlaceholderText('Chicago'), 'Boston')
    await user.type(screen.getByPlaceholderText('IL'), 'MA')
    await user.type(screen.getByPlaceholderText('60601'), '02110')

    // Country is preset to 'US' by seedReturnDraft — Next should light up.
    await waitFor(() => {
      const next = screen.getByRole('button', { name: /^Next/i }) as HTMLButtonElement
      expect(next.disabled).toBe(false)
    })
  })

  it('typed country lower-case is normalised to upper-case in the payload', async () => {
    // The CountrySelect combobox stores ISO-2 upper-case. Test the payload
    // side: seed a lower-case country and confirm handleUpdate uppercases it.
    getClient.mockResolvedValueOnce({
      data: sampleClient({
        returnSameAsShipFrom: false,
        returnAddress: {
          name: 'Return Desk', line1: '500 Return Rd', line2: '',
          city: 'Boston', state: 'MA', zip: '02110', country: 'us', phone: '',
        },
      }),
    })
    const Page = await loadPage()
    renderEdit(Page, 'ACME')
    await navigateToReturnEdit()

    // Save changes button in the header fires handleUpdate.
    const saveBtn = screen.getByRole('button', { name: /Save changes/i })
    const user = userEvent.setup()
    await user.click(saveBtn)

    await waitFor(() => expect(updateClient).toHaveBeenCalled(), { timeout: 4000 })
    const [, payload] = updateClient.mock.calls[0]
    expect(payload.returnAddress).toBeDefined()
    expect(payload.returnAddress.country).toBe('US')
  })
})

describe('ClientEditorPage · Return step · edit-mode hydration', () => {
  it('hydrates returnAddress + returnSameAsShipFrom=false from server response', async () => {
    getClient.mockResolvedValueOnce({
      data: sampleClient({
        returnSameAsShipFrom: false,
        returnAddress: {
          name: 'Return Center',
          line1: '10 Warehouse Way',
          line2: 'Dock 3',
          city: 'Newark',
          state: 'NJ',
          zip: '07101',
          country: 'US',
          phone: '+1 555-987-6543',
        },
      }),
    })
    const Page = await loadPage()
    renderEdit(Page, 'ACME')
    await navigateToReturnEdit()

    // Toggle should be unchecked (same-as-ship-from was false).
    const toggle = screen.getByLabelText(/Same as Ship From/i) as HTMLInputElement
    expect(toggle.checked).toBe(false)

    // Every hydrated field lands in its input by placeholder.
    expect((screen.getByPlaceholderText('Warehouse / contact name') as HTMLInputElement).value)
      .toBe('Return Center')
    expect((screen.getByPlaceholderText('123 Industrial Blvd') as HTMLInputElement).value)
      .toBe('10 Warehouse Way')
    expect((screen.getByPlaceholderText('Suite 400') as HTMLInputElement).value)
      .toBe('Dock 3')
    expect((screen.getByPlaceholderText('Chicago') as HTMLInputElement).value)
      .toBe('Newark')
    expect((screen.getByPlaceholderText('IL') as HTMLInputElement).value)
      .toBe('NJ')
    expect((screen.getByPlaceholderText('60601') as HTMLInputElement).value)
      .toBe('07101')
    expect((screen.getByPlaceholderText('+1 555-123-4567') as HTMLInputElement).value)
      .toBe('+1 555-987-6543')
  })
})

// ===== negative cases =====

describe('ClientEditorPage · Return step · negative', () => {
  it('same=false + empty required field → touched → inline error visible', async () => {
    seedReturnDraft({ returnSameAsShipFrom: false })
    const Page = await loadPage()
    renderCreate(Page)
    await waitForReturnStep()

    const user = userEvent.setup()
    // Blur the city input without typing to mark it touched.
    const cityInput = screen.getByPlaceholderText('Chicago')
    await user.click(cityInput)
    await user.tab()

    await waitFor(() => {
      expect(screen.getByText(/City is required/i)).toBeTruthy()
    })
  })

  it('same=false + invalid US zip → touched → format error surfaces', async () => {
    // Seed with a bad zip so validation is already computed against the
    // preset US country; we only need to touch the field to reveal it.
    seedReturnDraft({
      returnSameAsShipFrom: false,
      returnAddress: {
        name: 'Return Desk', line1: '500 Rd', city: 'Boston',
        state: 'MA', zip: 'BADZIP', country: 'US',
      },
    })
    const Page = await loadPage()
    renderCreate(Page)
    await waitForReturnStep()

    const user = userEvent.setup()
    const zipInput = screen.getByPlaceholderText('60601')
    // Field is already dirty; blur to mark it touched so the error line shows.
    await user.click(zipInput)
    await user.tab()

    await waitFor(() => {
      expect(screen.getByText(/Postal code doesn't match the US format/i)).toBeTruthy()
    })
  })

  it('same=false + invalid phone (letters) → touched → format error surfaces', async () => {
    seedReturnDraft({
      returnSameAsShipFrom: false,
      returnAddress: {
        name: 'Return Desk', line1: '500 Rd', city: 'Boston',
        state: 'MA', zip: '02110', country: 'US',
        phone: 'not-a-phone!!',
      },
    })
    const Page = await loadPage()
    renderCreate(Page)
    await waitForReturnStep()

    const user = userEvent.setup()
    const phoneInput = screen.getByPlaceholderText('+1 555-123-4567')
    await user.click(phoneInput)
    await user.tab()

    await waitFor(() => {
      expect(screen.getByText(/Enter a valid phone/i)).toBeTruthy()
    })
  })
})

// ===== cross-cutting: outbound payload shape =====

describe('ClientEditorPage · Return step · save payload', () => {
  it('same=true → updateClient payload carries returnAddress=undefined + returnSameAsShipFrom=true', async () => {
    getClient.mockResolvedValueOnce({
      data: sampleClient({
        returnSameAsShipFrom: true,
        returnAddress: null,
      }),
    })
    const Page = await loadPage()
    renderEdit(Page, 'ACME')
    await navigateToReturnEdit()

    // Save changes fires handleUpdate.
    const user = userEvent.setup()
    await user.click(screen.getByRole('button', { name: /Save changes/i }))

    await waitFor(() => expect(updateClient).toHaveBeenCalled(), { timeout: 4000 })
    const [, payload] = updateClient.mock.calls[0]
    expect(payload.returnSameAsShipFrom).toBe(true)
    // `undefined` on the outbound payload — same-as-ship-from mode drops the
    // address so the backend uses shipFrom as the return.
    expect(payload.returnAddress).toBeUndefined()
  })

  it('same=false → payload carries trimmed returnAddress fields', async () => {
    // Seed the server response with padded whitespace on several fields —
    // handleUpdate trims each address field before POSTing. Confirms the
    // "trim on save" contract for the Return address block.
    getClient.mockResolvedValueOnce({
      data: sampleClient({
        returnSameAsShipFrom: false,
        returnAddress: {
          name: '  Return Desk  ',
          line1: '  500 Return Rd  ',
          line2: '',
          city: '  Boston  ',
          state: '  MA  ',
          zip: '  02110  ',
          country: 'us',
          phone: '  ',
        },
      }),
    })
    const Page = await loadPage()
    renderEdit(Page, 'ACME')
    await navigateToReturnEdit()

    // Kick off Save changes to fire handleUpdate.
    const user = userEvent.setup()
    await user.click(screen.getByRole('button', { name: /Save changes/i }))

    await waitFor(() => expect(updateClient).toHaveBeenCalled(), { timeout: 4000 })
    const [, payload] = updateClient.mock.calls[0]
    expect(payload.returnSameAsShipFrom).toBe(false)
    expect(payload.returnAddress).toEqual({
      name: 'Return Desk',
      line1: '500 Return Rd',
      line2: '',
      city: 'Boston',
      state: 'MA',
      zip: '02110',
      country: 'US',  // upper-cased
      phone: '',
    })
  })
})
