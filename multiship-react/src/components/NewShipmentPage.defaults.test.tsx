import { describe, expect, it, vi, beforeEach } from 'vitest'
import { screen, waitFor, fireEvent } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { renderWithProviders } from '../test/renderWithProviders'

/**
 * NewShipmentPage — defaults + intl-gate coverage (2026-08-16).
 *
 * <p>Regression + positive coverage for two fixes shipped alongside:
 *
 * <ol>
 *   <li><b>Intl-gate fix</b>: Reason-for-export + Incoterms fields
 *       previously rendered UNCONDITIONALLY, leaking international
 *       logistics UI onto domestic shipments. Now gated on
 *       {@code isInternational}.</li>
 *   <li><b>applyClient sender-reset fix</b>: switching from Client A
 *       (with shipFrom) to Client B (without) previously left A's
 *       address in the sender block. Now always resets to
 *       {@code defaultSender()} first, then overlays the new client's
 *       ship-from fields on top.</li>
 * </ol>
 *
 * <p>Plus pinning tests for defaults population (auto-pick client's
 * {@code clientDefault=true} account, fall back to first active,
 * safely no-op when the carrier isn't in options) and unit-of-measure
 * hard-coded defaults (LB/IN).
 */

// ==================================================================
// Mocks — every service NewShipmentPage imports at module level.
// ==================================================================

const listAccounts = vi.fn()
const getPlatformCredentials = vi.fn()
vi.mock('../api/accountRefService', () => ({
  accountRefService: {
    listAccounts: (...a: unknown[]) => listAccounts(...a),
    getPlatformCredentials: (...a: unknown[]) => getPlatformCredentials(...a),
  },
}))

const listClients = vi.fn()
vi.mock('../api/clientService', () => ({
  clientService: {
    list: (...a: unknown[]) => listClients(...a),
    listClients: (...a: unknown[]) => listClients(...a),
  },
}))

vi.mock('../api/customsProfileService', () => ({
  customsProfileService: { list: vi.fn().mockResolvedValue({ data: [] }) },
}))

const notifyInfo = vi.fn()
const notifyApiError = vi.fn()
vi.mock('../utils/notify', () => ({
  notify: {
    success: vi.fn(),
    error: vi.fn(),
    info: (...a: unknown[]) => notifyInfo(...a),
    apiError: (...a: unknown[]) => notifyApiError(...a),
    confirm: vi.fn().mockResolvedValue(true),
  },
}))

const catalogMock = vi.fn()
const listPresetsMock = vi.fn()
vi.mock('../api/shippingConfigService', () => ({
  shippingConfigService: {
    catalog: (...a: unknown[]) => catalogMock(...a),
    listPresets: (...a: unknown[]) => listPresetsMock(...a),
    listShippingServices: vi.fn().mockResolvedValue({ data: [] }),
    listPackages: vi.fn().mockResolvedValue({ data: [] }),
    listCarriers: vi.fn().mockResolvedValue({ data: [] }),
  },
}))

vi.mock('../api/addressService', () => ({
  addressService: { validate: vi.fn().mockResolvedValue({ data: null }) },
}))

vi.mock('../api/addressValidationService', () => ({
  addressValidationService: { validate: vi.fn().mockResolvedValue({ data: null }) },
}))

vi.mock('../api/recipientBookService', () => ({
  recipientBookService: {
    search: vi.fn().mockResolvedValue({ data: [] }),
    list: vi.fn().mockResolvedValue({ data: [] }),
  },
}))

const listForClientWH = vi.fn()
vi.mock('../api/warehouseService', () => ({
  clientWarehouseService: {
    list: vi.fn().mockResolvedValue({ data: [] }),
    listForClient: (...a: unknown[]) => listForClientWH(...a),
  },
}))

const svcListForClient = vi.fn()
const pkgListForClient = vi.fn()
vi.mock('../api/clientCatalogService', () => ({
  clientAllowedServicesService: {
    listForClient: (...a: unknown[]) => svcListForClient(...a),
  },
  clientAllowedPackagesService: {
    listForClient: (...a: unknown[]) => pkgListForClient(...a),
  },
}))

const destGet = vi.fn()
vi.mock('../api/clientPolicyService', () => ({
  clientDestinationsService: {
    get: (...a: unknown[]) => destGet(...a),
  },
}))

vi.mock('../api/aiService', () => ({
  aiService: { reviewShipment: vi.fn().mockResolvedValue({ data: null }) },
}))

vi.mock('../api/customFieldService', () => ({
  customFieldService: {
    list: vi.fn().mockResolvedValue({ data: [] }),
    listForClient: vi.fn().mockResolvedValue({ data: [] }),
    listApplicable: vi.fn().mockResolvedValue({ data: [] }),
    applicable: vi.fn().mockResolvedValue([]),
    values: vi.fn().mockResolvedValue({ data: {} }),
    loadValues: vi.fn().mockResolvedValue({}),
    upsertValues: vi.fn().mockResolvedValue({}),
  },
}))

// ==================================================================
// Fixtures
// ==================================================================

const acc = (over: Record<string, unknown> = {}) => ({
  id: 1,
  accountNumber: 'ACC1',
  carrierCode: 'UPS',
  accountName: 'A',
  environment: 'SANDBOX',
  isDefault: false,
  clientDefault: false,
  active: true,
  complete: true,
  verified: true,
  ...over,
})

/** A client with a full shipFrom (address prefill fires). */
const clientWithShipFrom = {
  id: 1,
  clientCode: 'ACME',
  name: 'ACME Co',
  active: true,
  phone: '2135551000',
  email: 'ops@acme.test',
  shipFrom: {
    line1: '1 ACME Way',
    line2: null,
    city: 'Los Angeles',
    state: 'CA',
    zip: '90001',
    country: 'US',
  },
  returnAddress: null,
  carrierAccounts: [
    acc({ id: 10, accountNumber: 'ACME-UPS', carrierCode: 'UPS', clientDefault: true }),
    acc({ id: 11, accountNumber: 'ACME-FDX', carrierCode: 'FEDEX' }),
  ],
}

/** A client withOUT a shipFrom — resets to defaultSender() after fix. */
const clientNoShipFrom = {
  id: 2,
  clientCode: 'BAREBONES',
  name: 'BareBones Inc',
  active: true,
  phone: null,
  email: null,
  shipFrom: null,
  returnAddress: null,
  carrierAccounts: [
    acc({ id: 20, accountNumber: 'BB-UPS', carrierCode: 'UPS' }),
  ],
}

/** Client whose only default account is for a carrier NOT in options. */
const clientWithMissingCarrierDefault = {
  id: 3,
  clientCode: 'MISSING',
  name: 'Missing Carrier Co',
  active: true,
  phone: null,
  email: null,
  shipFrom: null,
  returnAddress: null,
  carrierAccounts: [
    acc({ id: 30, accountNumber: 'M-DHL', carrierCode: 'DHL', clientDefault: true }),
  ],
}

// ==================================================================
// Test setup
// ==================================================================

const loadPage = async () => {
  const mod = await import('./NewShipmentPage')
  return mod.default
}

beforeEach(() => {
  vi.clearAllMocks()

  // Default: platform has UPS + FEDEX accounts loaded so carrierOptions
  // renders {UPS, FEDEX}.
  listAccounts.mockResolvedValue([
    acc({ id: 100, accountNumber: 'PLAT-UPS', carrierCode: 'UPS' }),
    acc({ id: 101, accountNumber: 'PLAT-FDX', carrierCode: 'FEDEX' }),
  ])
  getPlatformCredentials.mockResolvedValue({ data: { found: false } })

  // clientService.listClients returns a paged shape: { data: { content: [...] } }
  listClients.mockResolvedValue({
    data: {
      content: [clientWithShipFrom, clientNoShipFrom, clientWithMissingCarrierDefault],
    },
  })

  // carrierOptions = accounts.carriers ∩ services.carriers (from catalog).
  // Must intersect for the page to leave empty-state and render the form.
  catalogMock.mockResolvedValue({
    services: [
      { id: 1, carrier: 'UPS', code: 'UPS_GROUND', name: 'Ground', scope: 'DOMESTIC', enabled: true },
      { id: 2, carrier: 'FEDEX', code: 'FDX_HD', name: 'Home Delivery', scope: 'DOMESTIC', enabled: true },
    ],
    carriers: [],
    rulePackages: [],
    ruleWarehouses: [],
    originCountries: ['US'],
  })
  listPresetsMock.mockResolvedValue([
    { id: 1, carrier: 'UPS', code: 'CUSTOM', name: 'Custom', scope: 'DOMESTIC' },
  ])
  listForClientWH.mockResolvedValue({ data: [] })
  svcListForClient.mockResolvedValue({ data: [] })
  pkgListForClient.mockResolvedValue({ data: [] })
  destGet.mockResolvedValue({ data: null })

  vi.spyOn(globalThis, 'fetch').mockImplementation(() => {
    throw new Error('un-mocked fetch forbidden')
  })
})

/** Wait for the page's client dropdown to populate, then return it.
 *  Field wraps inputs in a plain <label> with a text-only <span>; jsdom's
 *  role+name matcher doesn't find these, so we use getByLabelText. */
const waitForClientSelect = async (): Promise<HTMLSelectElement> => {
  const dropdown = await waitFor(() => {
    const el = screen.getByLabelText(/^Client$/i)
    if (!el) throw new Error('client select not found')
    return el as HTMLSelectElement
  })
  return dropdown
}

// ==================================================================
// 1. Intl-gate regression: Reason + Incoterms
// ==================================================================

describe('NewShipmentPage — intl-gate regression', () => {
  it('domestic shipment (US→US) hides the Reason-for-export field', async () => {
    const Page = await loadPage()
    renderWithProviders(<Page />)

    await waitForClientSelect()

    // Domestic by default — sender + recipient both default to US.
    expect(screen.queryByText(/Reason of export/i)).toBeNull()
  })

  it('domestic shipment (US→US) hides the Incoterms field', async () => {
    const Page = await loadPage()
    renderWithProviders(<Page />)

    await waitForClientSelect()

    expect(screen.queryByText(/^Incoterms$/i)).toBeNull()
  })

  it('customs line-items section stays hidden when domestic (pin correct behavior)', async () => {
    const Page = await loadPage()
    renderWithProviders(<Page />)

    await waitForClientSelect()

    // The customs section header contains "commercial invoice" language.
    expect(screen.queryByText(/commercial invoice/i)).toBeNull()
  })

  it('importer/broker section stays hidden when domestic (pin correct behavior)', async () => {
    const Page = await loadPage()
    renderWithProviders(<Page />)

    await waitForClientSelect()

    expect(screen.queryByText(/importer of record/i)).toBeNull()
  })
})

// ==================================================================
// 2. Intl-gate positive: US→CA shows all intl fields
// ==================================================================

describe('NewShipmentPage — intl fields appear when isInternational=true', () => {
  /** Country is a custom combobox (search input + button list), NOT a
   *  native <select>. To flip the value we focus the input, filter, and
   *  click the "Canada" button — the onMouseDown handler fires onChange('CA'). */
  const flipRecipientCountryToCanada = async () => {
    const countryInputs = screen.getAllByPlaceholderText(/Search country/i) as HTMLInputElement[]
    const recipientInput = countryInputs[countryInputs.length - 1]
    await userEvent.click(recipientInput)  // focus → dropdown opens
    await userEvent.type(recipientInput, 'Canada')
    const canadaBtn = await screen.findByRole('button', { name: /Canada\s*CA/i })
    await userEvent.click(canadaBtn)
  }

  it('changing ship-to country to CA reveals Reason-for-export', async () => {
    const Page = await loadPage()
    renderWithProviders(<Page />)
    await waitForClientSelect()

    await flipRecipientCountryToCanada()

    await waitFor(() => {
      expect(screen.getByText(/Reason of export/i)).toBeInTheDocument()
    })
  })

  it('changing ship-to country to CA reveals Incoterms', async () => {
    const Page = await loadPage()
    renderWithProviders(<Page />)
    await waitForClientSelect()

    await flipRecipientCountryToCanada()

    await waitFor(() => {
      expect(screen.getByText(/^Incoterms$/i)).toBeInTheDocument()
    })
  })
})

// ==================================================================
// 3. applyClient sender-reset regression
// ==================================================================

describe('NewShipmentPage — applyClient sender-reset', () => {
  it('picking a client without shipFrom resets sender to defaults (no leak from previous client)', async () => {
    const Page = await loadPage()
    renderWithProviders(<Page />)
    const clientSel = await waitForClientSelect()

    // First: pick Client A (has shipFrom → 1 ACME Way, Los Angeles).
    fireEvent.change(clientSel, { target: { value: 'ACME' } })
    await waitFor(() => {
      // Sender's addressLine1 input should now hold "1 ACME Way".
      const line1 = screen.getAllByDisplayValue('1 ACME Way')
      expect(line1.length).toBeGreaterThan(0)
    })

    // Then: pick Client B (no shipFrom → sender must RESET to defaultSender,
    // NOT stay at "1 ACME Way").
    fireEvent.change(clientSel, { target: { value: 'BAREBONES' } })

    await waitFor(() => {
      // Sender's addressLine1 must NOT still be Client A's leaked value.
      expect(screen.queryByDisplayValue('1 ACME Way')).toBeNull()
      // And should now be defaultSender's "350 5th Ave" (Manhattan).
      const defaultAddr = screen.getAllByDisplayValue('350 5th Ave')
      expect(defaultAddr.length).toBeGreaterThan(0)
    })
  })

  it('picking a client WITH shipFrom overlays the client fields on top of defaults', async () => {
    const Page = await loadPage()
    renderWithProviders(<Page />)
    const clientSel = await waitForClientSelect()

    fireEvent.change(clientSel, { target: { value: 'ACME' } })

    await waitFor(() => {
      const line1 = screen.getAllByDisplayValue('1 ACME Way')
      expect(line1.length).toBeGreaterThan(0)
      const city = screen.getAllByDisplayValue('Los Angeles')
      expect(city.length).toBeGreaterThan(0)
      const zip = screen.getAllByDisplayValue('90001')
      expect(zip.length).toBeGreaterThan(0)
    })
  })
})

// ==================================================================
// 4. Defaults pinning: carrier account auto-pick
// ==================================================================

describe('NewShipmentPage — carrier account defaults', () => {
  it('client-default account auto-picks its carrier (clientDefault=true wins)', async () => {
    const Page = await loadPage()
    renderWithProviders(<Page />)
    const clientSel = await waitForClientSelect()

    fireEvent.change(clientSel, { target: { value: 'ACME' } })

    // ACME's clientDefault is the UPS row (id=10 ACME-UPS). The carrier
    // select should now be UPS. Locate the "Carrier" select by label.
    await waitFor(() => {
      const carrierSel = screen.getByLabelText(/^Carrier\s?\*?$/i) as HTMLSelectElement
      expect(carrierSel.value).toBe('UPS')
    })
  })

  it('client with default account for MISSING carrier warns operator + leaves carrier safe', async () => {
    const Page = await loadPage()
    renderWithProviders(<Page />)
    const clientSel = await waitForClientSelect()

    // The MISSING client's only default is DHL — but carrierOptions only
    // has {UPS, FEDEX}. The page must NOT crash + must NOT set carrier=DHL
    // + must toast an operator-facing warning so they know to pick manually.
    fireEvent.change(clientSel, { target: { value: 'MISSING' } })

    await waitFor(() => {
      const carrierSel = screen.getByLabelText(/^Carrier\s?\*?$/i) as HTMLSelectElement
      // DHL is NOT in {UPS, FEDEX} → the guard skips setCarrier.
      expect(carrierSel.value).not.toBe('DHL')
      expect(['UPS', 'FEDEX', '']).toContain(carrierSel.value)
      // Operator MUST see the warning — silent skip was the prior UX bug.
      expect(notifyInfo).toHaveBeenCalledTimes(1)
      const msg = notifyInfo.mock.calls[0][0] as string
      expect(msg).toMatch(/DHL/)
      expect(msg).toMatch(/isn't connected/i)
    })
  })

  it('client with default account for AVAILABLE carrier does NOT toast (no false positive)', async () => {
    const Page = await loadPage()
    renderWithProviders(<Page />)
    const clientSel = await waitForClientSelect()

    // ACME's default is UPS, which IS in carrierOptions → no warning fires.
    fireEvent.change(clientSel, { target: { value: 'ACME' } })

    await waitFor(() => {
      const carrierSel = screen.getByLabelText(/^Carrier\s?\*?$/i) as HTMLSelectElement
      expect(carrierSel.value).toBe('UPS')
    })
    expect(notifyInfo).not.toHaveBeenCalled()
  })
})

// ==================================================================
// 5. Unit-of-measure defaults — pin current hard-coded LB/IN
// ==================================================================

describe('NewShipmentPage — unit-of-measure defaults', () => {
  it('mounts with weightUnit=LB and dimUnit=IN (hard-coded imperial defaults)', async () => {
    const Page = await loadPage()
    renderWithProviders(<Page />)
    await waitForClientSelect()

    // Button labels are lowercased in the DOM: 'lb'/'kg', 'in'/'cm'.
    const lbButton = screen.getAllByRole('button').find((b) => b.textContent === 'lb')
    const inButton = screen.getAllByRole('button').find((b) => b.textContent === 'in')
    expect(lbButton).toBeTruthy()
    expect(inButton).toBeTruthy()
    // Active state uses the dark bg class; the LB + IN buttons are the
    // active defaults on mount.
    expect(lbButton!.className).toContain('bg-[#1f150c]')
    expect(inButton!.className).toContain('bg-[#1f150c]')
  })

  it('picking a client does NOT reset UoM (units are session-level, not client-scoped) — pins current behavior', async () => {
    // Documented gap: units are hard-coded per page mount and never
    // client/warehouse-scoped. Pin so a future refactor doesn't silently
    // reset an operator-toggled unit when they pick a client.
    const Page = await loadPage()
    renderWithProviders(<Page />)
    const clientSel = await waitForClientSelect()

    fireEvent.change(clientSel, { target: { value: 'ACME' } })

    await waitFor(() => {
      // Client picked, but LB button still active → unchanged.
      const lbButton = screen.getAllByRole('button').find((b) => b.textContent === 'lb')
      expect(lbButton).toBeTruthy()
      expect(lbButton!.className).toContain('bg-[#1f150c]')
    })
  })
})

// ==================================================================
// 6. State/region — dropdown for US/CA/AU, free-text elsewhere
// ==================================================================

describe('NewShipmentPage — state/region input', () => {
  /** Sprint 51 — operators were typing full state names ("Delaware")
   *  which failed downstream FedEx/UPS rate + label calls because the
   *  wire needs a 2-letter code. The state field now renders as a
   *  dropdown for US/CA/AU (whose carriers require real codes) and as
   *  free text for every other country (which don't). */

  it('US default renders state as a select with real US codes', async () => {
    const Page = await loadPage()
    renderWithProviders(<Page />)
    await waitForClientSelect()

    // Two state selects (sender + recipient) — both default to US.
    // Filter to native <select> because the page also has custom-combobox
    // inputs (country picker) that match getAllByRole('combobox').
    const selects = (screen.getAllByRole('combobox') as HTMLElement[]).filter(
      (el): el is HTMLSelectElement => el.tagName === 'SELECT',
    )
    // The state selects are the ones whose options include "DE — Delaware".
    const stateSelects = selects.filter((s) =>
      Array.from(s.options).some((o) => o.textContent?.includes('DE — Delaware')),
    )
    expect(stateSelects.length).toBeGreaterThanOrEqual(2)
    // Free-text 'Delaware' is NOT an option — only the code 'DE'.
    stateSelects.forEach((sel) => {
      const optionTexts = Array.from(sel.options).map((o) => o.textContent ?? '')
      expect(optionTexts).toContain('DE — Delaware')
      expect(optionTexts).not.toContain('Delaware')  // no bare full-name option
      // CA (California), not CA (Canada) — codes are US alpha-2 postal codes.
      expect(optionTexts).toContain('CA — California')
    })
  })

  it('CA (Canada) recipient renders province select, not US states', async () => {
    const Page = await loadPage()
    renderWithProviders(<Page />)
    await waitForClientSelect()

    // Flip recipient country to Canada.
    const countryInputs = screen.getAllByPlaceholderText(/Search country/i) as HTMLInputElement[]
    const recipientCountryInput = countryInputs[countryInputs.length - 1]
    await userEvent.click(recipientCountryInput)
    await userEvent.type(recipientCountryInput, 'Canada')
    const canadaBtn = await screen.findByRole('button', { name: /Canada\s*CA/i })
    await userEvent.click(canadaBtn)

    await waitFor(() => {
      const selects = (screen.getAllByRole('combobox') as HTMLElement[]).filter(
        (el): el is HTMLSelectElement => el.tagName === 'SELECT',
      )
      const provinceSel = selects.find((s) =>
        Array.from(s.options).some((o) => o.textContent === 'ON — Ontario'),
      )
      expect(provinceSel).toBeTruthy()
      // BC yes, but no 'DE — Delaware' (that's a US-only option).
      const optionTexts = Array.from(provinceSel!.options).map((o) => o.textContent ?? '')
      expect(optionTexts).toContain('BC — British Columbia')
      expect(optionTexts).not.toContain('DE — Delaware')
    })
  })
})
