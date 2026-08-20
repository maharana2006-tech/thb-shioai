import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, cleanup, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import type { ComponentType } from 'react'

/**
 * Sprint 53 wizard-tests — Ship From step of the client add/edit wizard.
 *
 * Scope: the inner ShipFromStep in ClientEditorPage plus the
 * WarehouseEditorModal that opens from "Add warehouse". We drive edit
 * mode almost exclusively so the wizard's step-visit gating doesn't stand
 * between the tests and the panel we want to exercise.
 *
 * All network-touching services are fully mocked; nothing hits real fetch.
 * The router is a MemoryRouter so useParams / useNavigate resolve.
 *
 * CI-flake note: every `userEvent.setup()` in this file uses
 * `{ delay: null }`. Default per-character setTimeout(0) races React 19's
 * automatic batching + Formik's synchronous validation on slow CI runners
 * — under load, trailing characters of multi-char `user.type` calls were
 * dropping and this file went red intermittently for 24h on dev. Microtask
 * flushing (delay: null) is deterministic.
 */

// ===== Service mocks (top level; vi.mock is hoisted before dynamic import
// of ClientEditorPage below) =====

const getClient = vi.fn()
const createClient = vi.fn()
const updateClient = vi.fn()
const listClients = vi.fn()
const listClientAccounts = vi.fn()

const listWarehouses = vi.fn()
const updateWarehouse = vi.fn()
const createWarehouse = vi.fn()

const listForClient = vi.fn()
const attachWarehouse = vi.fn()
const setDefaultWarehouse = vi.fn()

const validateAddress = vi.fn()

vi.mock('../api/clientService', () => ({
  clientService: {
    getClient: (...args: unknown[]) => getClient(...args),
    createClient: (...args: unknown[]) => createClient(...args),
    updateClient: (...args: unknown[]) => updateClient(...args),
    listClients: (...args: unknown[]) => listClients(...args),
    listClientAccounts: (...args: unknown[]) => listClientAccounts(...args),
    cascadePreview: vi.fn(),
    toggleActive: vi.fn(),
    deleteClient: vi.fn(),
  },
}))

vi.mock('../api/accountRefService', () => ({
  accountRefService: {
    listAccounts: vi.fn().mockResolvedValue([]),
    upsertAccount: vi.fn(),
    resolveOrders: vi.fn().mockResolvedValue([]),
  },
}))

vi.mock('../api/warehouseService', () => ({
  warehouseService: {
    listWarehouses: (...args: unknown[]) => listWarehouses(...args),
    updateWarehouse: (...args: unknown[]) => updateWarehouse(...args),
    createWarehouse: (...args: unknown[]) => createWarehouse(...args),
    getWarehouse: vi.fn(),
    toggleActive: vi.fn(),
    deleteWarehouse: vi.fn(),
  },
  clientWarehouseService: {
    listForClient: (...args: unknown[]) => listForClient(...args),
    attach: (...args: unknown[]) => attachWarehouse(...args),
    setDefault: (...args: unknown[]) => setDefaultWarehouse(...args),
    detach: vi.fn(),
  },
}))

vi.mock('../api/addressValidationService', () => ({
  addressValidationService: {
    validate: (...args: unknown[]) => validateAddress(...args),
  },
}))

vi.mock('../api/shippingConfigService', () => ({
  shippingConfigService: {
    catalog: vi.fn().mockResolvedValue({ services: [] }),
    saveRule: vi.fn(),
  },
}))

vi.mock('../api/customsProfileService', () => ({
  customsProfileService: {
    list: vi.fn().mockResolvedValue([]),
    save: vi.fn(),
    remove: vi.fn(),
  },
}))

// Notify — quiet the toasts so DOM queries don't stumble on leftover text.
const notifyError = vi.fn()
const notifySuccess = vi.fn()
const notifyApiError = vi.fn()
vi.mock('../utils/notify', () => ({
  notify: {
    success: (...args: unknown[]) => notifySuccess(...args),
    error: (...args: unknown[]) => notifyError(...args),
    apiError: (...args: unknown[]) => notifyApiError(...args),
    info: vi.fn(),
    confirm: vi.fn().mockResolvedValue(true),
  },
}))

// Dynamic import so vi.mock calls land before the component evaluates.
async function loadPage(): Promise<ComponentType> {
  const mod = await import('./ClientEditorPage')
  return mod.default
}

function renderEdit(Page: ComponentType, code = 'ACME') {
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

/** A sample client with a valid shipFrom + returnSameAsShipFrom so hydration
 *  doesn't fail step gates unrelated to this file. */
function sampleClient(overrides: Record<string, unknown> = {}) {
  return {
    id: 42,
    clientCode: 'ACME',
    name: 'Acme Widgets',
    email: 'ops@acme.io',
    phone: '+1 555-123-4567',
    status: 'ACTIVE',
    shipFrom: {
      name: 'Acme HQ',
      line1: '1 Main St',
      line2: '',
      city: 'Boston',
      state: 'MA',
      zip: '02101',
      country: 'US',
      phone: '+15551234567',
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

/** Factory for a Warehouse row (matches src/api/warehouseService.Warehouse). */
function warehouse(overrides: Partial<{
  id: number
  code: string
  name: string
  ownerType: 'PLATFORM' | 'CLIENT'
  ownerClientCode: string | null
  active: boolean
  address: {
    name?: string; line1?: string; line2?: string; city?: string;
    state?: string; zip?: string; country?: string; phone?: string
  } | null
}> = {}) {
  return {
    id: 1,
    code: 'PLAT-1',
    name: 'Platform Warehouse One',
    ownerType: 'PLATFORM' as const,
    ownerClientCode: null,
    active: true,
    attachedClientCount: 0,
    createdAt: null,
    updatedAt: null,
    address: {
      name: 'Platform HQ',
      line1: '10 Platform Way',
      line2: '',
      city: 'Chicago',
      state: 'IL',
      zip: '60601',
      country: 'US',
      phone: '+13125550100',
    },
    ...overrides,
  }
}

/** Stock validate-response factory — override matchLevel / suggested per test. */
function validateOk(overrides: Record<string, unknown> = {}) {
  return {
    data: {
      carrierCode: 'UPS',
      valid: true,
      matchLevel: 'EXACT',
      classification: 'COMMERCIAL',
      suggested: null,
      warnings: [],
      message: 'Address looks good.',
      ...overrides,
    },
  }
}

beforeEach(() => {
  getClient.mockReset()
  createClient.mockReset()
  updateClient.mockReset()
  listClients.mockReset().mockResolvedValue({ data: { content: [] } })
  listClientAccounts.mockReset().mockResolvedValue([])
  listWarehouses.mockReset().mockResolvedValue({ data: { content: [] } })
  updateWarehouse.mockReset()
  createWarehouse.mockReset()
  listForClient.mockReset().mockResolvedValue({ data: [] })
  attachWarehouse.mockReset()
  setDefaultWarehouse.mockReset()
  validateAddress.mockReset().mockResolvedValue(validateOk())
  notifyError.mockReset()
  notifySuccess.mockReset()
  notifyApiError.mockReset()
  // Default sample client hydrates edit-mode; individual tests can override.
  getClient.mockResolvedValue({ data: sampleClient() })
  try { localStorage.clear() } catch { /* ignore */ }
})

afterEach(() => {
  cleanup()
})

/** Click the Ship From step in the rail. Edit mode = every step is free. */
async function gotoShipFrom(user: ReturnType<typeof userEvent.setup>) {
  const shipFromTab = await screen.findByRole('tab', { name: /Ship From/i })
  await user.click(shipFromTab)
  // Header of the ShipFromStep panel — proves we landed on it.
  await waitFor(() => expect(screen.getByText(/Ship From — pick a warehouse/i)).toBeTruthy())
}

// ===== Positive cases =====

describe('ClientEditorPage · ShipFrom · positive', () => {
  // First test carries the cold-start budget for the transformer + 3000-LOC
  // component + mocked services (occasionally trips the default 5s on Windows).
  it('renders picker + "Add warehouse" button + PLATFORM optgroup', async () => {
    // Two PLATFORM rows so the optgroup renders and we can pick one below.
    listWarehouses.mockImplementation((params?: { ownerType?: string }) => {
      if (params?.ownerType === 'PLATFORM') {
        return Promise.resolve({
          data: {
            content: [
              warehouse({ id: 1, code: 'PLAT-1', name: 'Platform One' }),
              warehouse({ id: 2, code: 'PLAT-2', name: 'Platform Two' }),
            ],
          },
        })
      }
      return Promise.resolve({ data: { content: [] } })
    })

    const Page = await loadPage()
    renderEdit(Page)
    const user = userEvent.setup({ delay: null })

    // Wait for hydration (identity fields appear) then hop to Ship From.
    await waitFor(() => expect(screen.getByPlaceholderText('MA1885')).toBeTruthy())
    await gotoShipFrom(user)

    // Picker + Add-warehouse button are both present.
    const picker = screen.getByLabelText(/Ship From warehouse/i) as HTMLSelectElement
    expect(picker).toBeTruthy()
    expect(screen.getByRole('button', { name: /Add warehouse/i })).toBeTruthy()

    // PLATFORM optgroup rendered; both rows show up as options.
    await waitFor(() => {
      expect(within(picker).getByRole('option', { name: /PLAT-1 — Platform One/i })).toBeTruthy()
    })
    expect(within(picker).getByRole('option', { name: /PLAT-2 — Platform Two/i })).toBeTruthy()
  }, 30000)

  it('selecting a warehouse populates form.shipFrom + shows preview card', async () => {
    listWarehouses.mockImplementation((params?: { ownerType?: string }) => {
      if (params?.ownerType === 'PLATFORM') {
        return Promise.resolve({
          data: {
            content: [
              warehouse({
                id: 7,
                code: 'PLAT-7',
                name: 'Preview Warehouse',
                address: {
                  name: 'Preview HQ',
                  line1: '77 Preview Blvd',
                  line2: 'Suite 7',
                  city: 'Denver',
                  state: 'CO',
                  zip: '80202',
                  country: 'US',
                  phone: '+13035550177',
                },
              }),
            ],
          },
        })
      }
      return Promise.resolve({ data: { content: [] } })
    })

    // Fresh client (no pre-selected default) so we can pick from scratch.
    getClient.mockResolvedValue({ data: sampleClient({ shipFrom: null }) })

    const Page = await loadPage()
    renderEdit(Page)
    const user = userEvent.setup({ delay: null })

    await waitFor(() => expect(screen.getByPlaceholderText('MA1885')).toBeTruthy())
    await gotoShipFrom(user)

    const picker = await screen.findByLabelText(/Ship From warehouse/i) as HTMLSelectElement
    await user.selectOptions(picker, '7')

    // Preview card shows the picked warehouse's identity + address lines.
    await waitFor(() => expect(screen.getByText(/PLAT-7 · Preview Warehouse/i)).toBeTruthy())
    expect(screen.getByText(/77 Preview Blvd/i)).toBeTruthy()
    expect(screen.getByText(/Denver, CO, 80202/i)).toBeTruthy()
  })

  it('Edit button appears in preview when picked warehouse is CLIENT-owned', async () => {
    // Server hydrates with the CLIENT-owned warehouse already default-picked.
    listForClient.mockResolvedValue({
      data: [{
        id: 99,
        clientCode: 'ACME',
        isDefault: true,
        warehouse: warehouse({
          id: 55,
          code: 'CLI-55',
          name: 'Client Owned',
          ownerType: 'CLIENT',
          ownerClientCode: 'ACME',
        }),
        createdAt: null,
        updatedAt: null,
      }],
    })
    listWarehouses.mockImplementation((params?: { ownerType?: string }) => {
      if (params?.ownerType === 'CLIENT') {
        return Promise.resolve({
          data: { content: [warehouse({ id: 55, code: 'CLI-55', name: 'Client Owned', ownerType: 'CLIENT', ownerClientCode: 'ACME' })] },
        })
      }
      return Promise.resolve({ data: { content: [] } })
    })

    const Page = await loadPage()
    renderEdit(Page)
    const user = userEvent.setup({ delay: null })

    await waitFor(() => expect(screen.getByPlaceholderText('MA1885')).toBeTruthy())
    await gotoShipFrom(user)

    // Wait for the pre-selection to hydrate + preview card to render.
    await waitFor(() => expect(screen.getByText(/CLI-55 · Client Owned/i)).toBeTruthy())

    // Edit button is present ONLY for CLIENT-owned rows.
    expect(screen.getByRole('button', { name: /^Edit$/i })).toBeTruthy()
  })

  it('Edit button does NOT appear when picked warehouse is PLATFORM-owned', async () => {
    listForClient.mockResolvedValue({
      data: [{
        id: 100,
        clientCode: 'ACME',
        isDefault: true,
        warehouse: warehouse({ id: 3, code: 'PLAT-3', name: 'Platform Three', ownerType: 'PLATFORM' }),
        createdAt: null,
        updatedAt: null,
      }],
    })
    listWarehouses.mockImplementation((params?: { ownerType?: string }) => {
      if (params?.ownerType === 'PLATFORM') {
        return Promise.resolve({
          data: { content: [warehouse({ id: 3, code: 'PLAT-3', name: 'Platform Three', ownerType: 'PLATFORM' })] },
        })
      }
      return Promise.resolve({ data: { content: [] } })
    })

    const Page = await loadPage()
    renderEdit(Page)
    const user = userEvent.setup({ delay: null })

    await waitFor(() => expect(screen.getByPlaceholderText('MA1885')).toBeTruthy())
    await gotoShipFrom(user)

    await waitFor(() => expect(screen.getByText(/PLAT-3 · Platform Three/i)).toBeTruthy())

    // No Edit button inside the preview card — the PLATFORM pill is present
    // but the CLIENT-only inline Edit button isn't rendered.
    expect(screen.queryByRole('button', { name: /^Edit$/i })).toBeNull()
    // Sanity: the PLATFORM badge IS present.
    expect(screen.getAllByText(/PLATFORM/i).length).toBeGreaterThan(0)
  })

  it('Verify button in WarehouseEditorModal fires addressValidationService.validate with the correct payload', async () => {
    // No warehouses; we'll open the "Add warehouse" modal instead.
    listWarehouses.mockResolvedValue({ data: { content: [] } })

    const Page = await loadPage()
    renderEdit(Page)
    const user = userEvent.setup({ delay: null })

    await waitFor(() => expect(screen.getByPlaceholderText('MA1885')).toBeTruthy())
    await gotoShipFrom(user)

    // Open the modal (dialog role appears).
    await user.click(screen.getByRole('button', { name: /Add warehouse/i }))
    const dialog = await screen.findByRole('dialog')

    // Fill just the fields that gate the Verify button (line1 / city / zip /
    // country). Country pre-fills to "US". Name is optional in the payload.
    await user.type(within(dialog).getByPlaceholderText(/1 Warehouse Way/i), '123 Verify Ln')
    // City input has no placeholder — use the label association via Field.
    // The city Field is the 3rd input in the address grid; grab it by looking
    // for the label text then the sibling input in the same Field container.
    const inputs = within(dialog).getAllByRole('textbox')
    // Order in the DOM inside the address section:
    //   [code, name, line1, line2, city, state, zip, country, phone]
    // (code + name in identity, then address block).
    // We'll rely on the sequence being stable for this render.
    const [_code, _name, _line1, _line2, city, state, zip, _country, _phone] = inputs
    void _code; void _name; void _line1; void _line2; void _country; void _phone
    await user.type(city, 'Denver')
    await user.type(state, 'CO')
    await user.type(zip, '80202')

    const verifyBtn = within(dialog).getByRole('button', { name: /^Verify$/i })
    await waitFor(() => expect((verifyBtn as HTMLButtonElement).disabled).toBe(false))
    await user.click(verifyBtn)

    await waitFor(() => expect(validateAddress).toHaveBeenCalledTimes(1))
    const [req] = validateAddress.mock.calls[0]
    expect(req.carrierCode).toBe('UPS')
    expect(req.addressLine1).toBe('123 Verify Ln')
    expect(req.city).toBe('Denver')
    expect(req.state).toBe('CO')
    expect(req.postalCode).toBe('80202')
    expect(req.countryCode).toBe('US')
  })

  it('CORRECTED result shows suggested-address panel with "Use suggestion" button', async () => {
    validateAddress.mockResolvedValue(validateOk({
      matchLevel: 'CORRECTED',
      message: 'Address updated by carrier.',
      suggested: {
        name: 'Suggested Co',
        addressLine1: '124 Verify Ln',
        addressLine2: null,
        city: 'Denver',
        state: 'CO',
        postalCode: '80202-1234',
        countryCode: 'US',
      },
    }))

    const Page = await loadPage()
    renderEdit(Page)
    const user = userEvent.setup({ delay: null })

    await waitFor(() => expect(screen.getByPlaceholderText('MA1885')).toBeTruthy())
    await gotoShipFrom(user)

    await user.click(screen.getByRole('button', { name: /Add warehouse/i }))
    const dialog = await screen.findByRole('dialog')

    await user.type(within(dialog).getByPlaceholderText(/1 Warehouse Way/i), '124 Verify Ln')
    const inputs = within(dialog).getAllByRole('textbox')
    const [, , , , city, state, zip] = inputs
    await user.type(city, 'Denver')
    await user.type(state, 'CO')
    await user.type(zip, '80202')

    await user.click(within(dialog).getByRole('button', { name: /^Verify$/i }))

    // Panel + Use suggestion button appear.
    await waitFor(() => expect(within(dialog).getByText(/Suggested by UPS/i)).toBeTruthy())
    expect(within(dialog).getByRole('button', { name: /Use suggestion/i })).toBeTruthy()
    // Suggested content is rendered.
    expect(within(dialog).getByText(/124 Verify Ln/i)).toBeTruthy()
    expect(within(dialog).getByText(/80202-1234/i)).toBeTruthy()
  })

  it('"Use suggestion" click fills form fields from the suggested address', async () => {
    validateAddress.mockResolvedValue(validateOk({
      matchLevel: 'CORRECTED',
      message: 'Address updated by carrier.',
      suggested: {
        name: null,
        addressLine1: '125 Suggested Ln',
        addressLine2: 'Bay 5',
        city: 'Aurora',
        state: 'CO',
        postalCode: '80014',
        countryCode: 'US',
      },
    }))

    const Page = await loadPage()
    renderEdit(Page)
    const user = userEvent.setup({ delay: null })

    await waitFor(() => expect(screen.getByPlaceholderText('MA1885')).toBeTruthy())
    await gotoShipFrom(user)

    await user.click(screen.getByRole('button', { name: /Add warehouse/i }))
    const dialog = await screen.findByRole('dialog')

    const line1Input = within(dialog).getByPlaceholderText(/1 Warehouse Way/i) as HTMLInputElement
    await user.type(line1Input, '100 Original St')
    const inputs = within(dialog).getAllByRole('textbox')
    const [, , , , city, state, zip] = inputs
    await user.type(city as HTMLInputElement, 'Denver')
    await user.type(state as HTMLInputElement, 'CO')
    await user.type(zip as HTMLInputElement, '80202')

    await user.click(within(dialog).getByRole('button', { name: /^Verify$/i }))
    const useBtn = await within(dialog).findByRole('button', { name: /Use suggestion/i })
    await user.click(useBtn)

    // The suggestion is applied to line1, city, zip.
    await waitFor(() => expect(line1Input.value).toBe('125 Suggested Ln'))
    const inputs2 = within(dialog).getAllByRole('textbox')
    const [, , , , city2, state2, zip2] = inputs2
    expect((city2 as HTMLInputElement).value).toBe('Aurora')
    expect((state2 as HTMLInputElement).value).toBe('CO')
    expect((zip2 as HTMLInputElement).value).toBe('80014')
    // Panel is dismissed after applying.
    expect(within(dialog).queryByRole('button', { name: /Use suggestion/i })).toBeNull()
  })

  it('editing any address field clears a stale verify result panel', async () => {
    validateAddress.mockResolvedValue(validateOk({ matchLevel: 'EXACT' }))

    const Page = await loadPage()
    renderEdit(Page)
    const user = userEvent.setup({ delay: null })

    await waitFor(() => expect(screen.getByPlaceholderText('MA1885')).toBeTruthy())
    await gotoShipFrom(user)

    await user.click(screen.getByRole('button', { name: /Add warehouse/i }))
    const dialog = await screen.findByRole('dialog')

    const line1Input = within(dialog).getByPlaceholderText(/1 Warehouse Way/i) as HTMLInputElement
    await user.type(line1Input, '200 Stale St')
    const inputs = within(dialog).getAllByRole('textbox')
    const [, , , , city, state, zip] = inputs
    await user.type(city as HTMLInputElement, 'Denver')
    await user.type(state as HTMLInputElement, 'CO')
    await user.type(zip as HTMLInputElement, '80202')

    await user.click(within(dialog).getByRole('button', { name: /^Verify$/i }))
    // Wait for the EXACT badge (matchLevel pill) to render.
    await waitFor(() => expect(within(dialog).getByText('EXACT')).toBeTruthy())

    // Any address-field edit invalidates the panel via useEffect.
    await user.type(line1Input, ' extra')
    await waitFor(() => expect(within(dialog).queryByText('EXACT')).toBeNull())
  })
})

// ===== Negative cases =====

describe('ClientEditorPage · ShipFrom · negative', () => {
  it('no warehouses → picker shows the "add one first" empty option + empty-state hint', async () => {
    listWarehouses.mockResolvedValue({ data: { content: [] } })

    const Page = await loadPage()
    renderEdit(Page)
    const user = userEvent.setup({ delay: null })

    await waitFor(() => expect(screen.getByPlaceholderText('MA1885')).toBeTruthy())
    await gotoShipFrom(user)

    const picker = await screen.findByLabelText(/Ship From warehouse/i) as HTMLSelectElement
    // The lone <option value=""> carries the "add one first" copy once
    // loading has settled.
    await waitFor(() => {
      const empty = within(picker).getByRole('option')
      expect(empty.textContent).toMatch(/No warehouses — add one first/i)
    })
    // Empty-state paragraph beneath the picker mirrors the message.
    expect(screen.getByText(/No warehouses in the system yet\. Click Add warehouse above to create one\./i)).toBeTruthy()
  })

  it('Verify button is disabled until line1/city/zip/country are all filled', async () => {
    const Page = await loadPage()
    renderEdit(Page)
    const user = userEvent.setup({ delay: null })

    await waitFor(() => expect(screen.getByPlaceholderText('MA1885')).toBeTruthy())
    await gotoShipFrom(user)

    await user.click(screen.getByRole('button', { name: /Add warehouse/i }))
    const dialog = await screen.findByRole('dialog')

    const verifyBtn = within(dialog).getByRole('button', { name: /^Verify$/i }) as HTMLButtonElement
    // Fresh modal: line1 / city / zip are blank → disabled.
    expect(verifyBtn.disabled).toBe(true)

    // Fill line1 only → still disabled (missing city/zip).
    await user.type(within(dialog).getByPlaceholderText(/1 Warehouse Way/i), '999 Partial Ln')
    expect(verifyBtn.disabled).toBe(true)

    // Fill city + zip → button flips to enabled (country pre-fills to "US").
    const inputs = within(dialog).getAllByRole('textbox')
    const [, , , , city, , zip] = inputs
    await user.type(city as HTMLInputElement, 'Denver')
    await user.type(zip as HTMLInputElement, '80202')
    await waitFor(() => expect(verifyBtn.disabled).toBe(false))
  })

  it('NOT_FOUND validation result renders the red-pill matchLevel badge', async () => {
    validateAddress.mockResolvedValue(validateOk({
      matchLevel: 'NOT_FOUND',
      valid: false,
      message: 'Carrier could not find this address.',
      suggested: null,
    }))

    const Page = await loadPage()
    renderEdit(Page)
    const user = userEvent.setup({ delay: null })

    await waitFor(() => expect(screen.getByPlaceholderText('MA1885')).toBeTruthy())
    await gotoShipFrom(user)

    await user.click(screen.getByRole('button', { name: /Add warehouse/i }))
    const dialog = await screen.findByRole('dialog')

    await user.type(within(dialog).getByPlaceholderText(/1 Warehouse Way/i), '404 Nowhere Ln')
    const inputs = within(dialog).getAllByRole('textbox')
    const [, , , , city, state, zip] = inputs
    await user.type(city as HTMLInputElement, 'Nowhereville')
    await user.type(state as HTMLInputElement, 'ZZ')
    await user.type(zip as HTMLInputElement, '99999')

    await user.click(within(dialog).getByRole('button', { name: /^Verify$/i }))

    // NOT_FOUND matchLevel pill (rose-toned) + carrier message both appear.
    await waitFor(() => expect(within(dialog).getByText('NOT_FOUND')).toBeTruthy())
    expect(within(dialog).getByText(/Carrier could not find this address/i)).toBeTruthy()
    // No "Use suggestion" button on NOT_FOUND (suggested is null, so the
    // branch inside VerifyResultPanel is guarded).
    expect(within(dialog).queryByRole('button', { name: /Use suggestion/i })).toBeNull()
  })
})

// Reference the imports the linter would otherwise flag as unused across
// helper factories.
void warehouse
