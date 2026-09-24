import { describe, expect, it, vi, beforeEach } from 'vitest'
import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { renderWithProviders } from '../test/renderWithProviders'

/**
 * PR2 FE — NewShipmentPage NDS scan / prefill wiring.
 *
 * <p>Pins the observable UX contract of the scan field + lookup handler
 * + banner added on top of backend PR #735:
 *
 * <ol>
 *   <li>Scan field renders and accepts input.</li>
 *   <li>Enter fires {@code ndsShipmentService.lookup}.</li>
 *   <li>OK response paints the emerald banner + applies the client code.</li>
 *   <li>404 paints the rose banner with the "not found" message.</li>
 *   <li>422 paints the rose banner with the backend message.</li>
 *   <li>503 paints the amber "NDS unavailable" banner.</li>
 * </ol>
 *
 * <p>Mock scaffolding cloned from
 * {@code NewShipmentPage.uspsDirectQueue.test.tsx} so the page mounts
 * without hitting real services.
 */

// ==================================================================
// Module-boundary mocks
// ==================================================================

const ndsLookup = vi.fn()
vi.mock('../api/ndsShipmentService', () => ({
  ndsShipmentService: {
    lookup: (...args: unknown[]) => ndsLookup(...args),
  },
}))

vi.mock('../api/orderService', async () => {
  const actual = await vi.importActual<typeof import('../api/orderService')>('../api/orderService')
  return {
    ...actual,
    orderService: {
      generateManualLabel: vi.fn(),
      regenerateOrder: vi.fn(),
    },
  }
})

vi.mock('../api/accountRefService', () => ({
  accountRefService: {
    listAccounts: vi.fn().mockResolvedValue({ data: [] }),
    getPlatformCredentials: vi.fn().mockResolvedValue({ data: { found: false } }),
  },
}))

vi.mock('../api/clientService', () => ({
  clientService: {
    list: vi.fn().mockResolvedValue({ data: { content: [] } }),
    listClients: vi.fn().mockResolvedValue({
      data: {
        content: [
          { clientCode: 'ACME', name: 'Acme Corp' },
        ],
      },
    }),
  },
}))

vi.mock('../api/customsProfileService', () => ({
  customsProfileService: { list: vi.fn().mockResolvedValue({ data: [] }) },
}))

vi.mock('../api/shippingConfigService', () => ({
  shippingConfigService: {
    catalog: vi.fn().mockResolvedValue({
      services: [], carriers: [], links: [], rulePackages: [],
      ruleWarehouses: [], originCountries: ['US'],
    }),
    listPresets: vi.fn().mockResolvedValue([]),
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

vi.mock('../api/warehouseService', () => ({
  clientWarehouseService: {
    list: vi.fn().mockResolvedValue({ data: [] }),
    listForClient: vi.fn().mockResolvedValue({ data: [] }),
  },
}))

vi.mock('../api/clientCatalogService', () => ({
  clientAllowedServicesService: { listForClient: vi.fn().mockResolvedValue({ data: [] }) },
  clientAllowedPackagesService: { listForClient: vi.fn().mockResolvedValue({ data: [] }) },
}))

vi.mock('../api/clientPolicyService', () => ({
  clientDestinationsService: { get: vi.fn().mockResolvedValue({ data: null }) },
}))

vi.mock('../api/aiService', () => ({
  aiService: {
    reviewShipment: vi.fn().mockResolvedValue({ data: null }),
    parseAddress: vi.fn(),
  },
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

const notifyApiError = vi.fn()
vi.mock('../utils/notify', () => ({
  notify: {
    info: vi.fn(), success: vi.fn(), error: vi.fn(),
    apiError: (...a: unknown[]) => notifyApiError(...a),
    confirm: vi.fn().mockResolvedValue(true),
  },
  notifyStore: {
    subscribe: vi.fn(() => () => {}),
    snapshot: vi.fn(() => []),
    dismiss: vi.fn(),
  },
}))

vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual<typeof import('react-router-dom')>('react-router-dom')
  return {
    ...actual,
    useNavigate: () => vi.fn(),
    useSearchParams: () => [new URLSearchParams(), vi.fn()],
  }
})

// ==================================================================
// Harness
// ==================================================================

const loadPage = async () => {
  const mod = await import('./NewShipmentPage')
  return mod.default
}

const okPrefill = () => ({
  status: 'OK' as const,
  messages: [],
  scope: 'DIRECT' as const,
  scannedValue: '.X77',
  clientCode: 'ACME',
  batchId: null,
  orders: [{ orderNo: 12345, orderSuffix: 1, invNo: null, thpAccount: null }],
  recipient: {
    attn: 'Acme Corp', name: 'Wile E Coyote',
    addr1: '1 Anvil Way', addr2: null, addr3: null,
    city: 'Tucson', state: 'AZ', zip: '85701', countryCd: 'US',
    phone: '6165551212', phoneDefaulted: false, sourceOrderNo: 12345,
  },
  shipMethod: { code: 'P80', description: 'UPS Ground', mappedServiceId: 1 },
  packages: [{
    sequence: 1, containerNo: '77', containerIds: [77], orderNos: [12345], orderSuffix: 1,
    weight: 2.5, weightSource: 'OE_SHIP_CONTAINER.BILLABLE_WEIGHT_LB',
    length: 12, width: 6, height: 4, packDt: null, shippedFlag: null, isScanned: true,
  }],
  notifyBlock: { sendTo: 'ops@acme.example', copyTo: null, emailDefaulted: false },
  international: null,
  defaultedFields: [],
})

beforeEach(() => {
  vi.clearAllMocks()
  ndsLookup.mockReset()
})

// ==================================================================
// Tests
// ==================================================================

describe('NewShipmentPage — NDS scan / prefill (PR2)', () => {
  it('renders the scan input on initial mount with no banner', async () => {
    const Page = await loadPage()
    renderWithProviders(<Page />)
    const scanInput = await screen.findByPlaceholderText(/\.X<containerId>/)
    expect(scanInput).toBeInTheDocument()
    // No banner until Enter fires.
    expect(screen.queryByRole('status')).toBeNull()
  })

  it('fires ndsShipmentService.lookup on Enter with the scan value', async () => {
    ndsLookup.mockResolvedValue(okPrefill())
    const Page = await loadPage()
    renderWithProviders(<Page />)
    const scanInput = await screen.findByPlaceholderText(/\.X<containerId>/)
    const user = userEvent.setup()
    await user.type(scanInput, '.X77{Enter}')
    await waitFor(() => expect(ndsLookup).toHaveBeenCalledWith('.X77'))
  })

  it('paints the success banner on OK response', async () => {
    ndsLookup.mockResolvedValue(okPrefill())
    const Page = await loadPage()
    renderWithProviders(<Page />)
    const scanInput = await screen.findByPlaceholderText(/\.X<containerId>/)
    const user = userEvent.setup()
    await user.type(scanInput, '.X77{Enter}')
    const banner = await screen.findByRole('status')
    expect(banner.textContent).toMatch(/succeeded/)
    expect(banner.className).toMatch(/emerald/)
  })

  it('renders WARNING messages inline when status = WARNING', async () => {
    ndsLookup.mockResolvedValue({
      ...okPrefill(),
      status: 'WARNING',
      messages: [{ severity: 'WARNING', text: "Ship method 'XYZ' is not mapped." }],
    })
    const Page = await loadPage()
    renderWithProviders(<Page />)
    const scanInput = await screen.findByPlaceholderText(/\.X<containerId>/)
    const user = userEvent.setup()
    await user.type(scanInput, '.X77{Enter}')
    const banner = await screen.findByRole('status')
    expect(banner.className).toMatch(/amber/)
    expect(banner.textContent).toMatch(/not mapped/)
  })

  it('paints the "not found" banner on 404', async () => {
    ndsLookup.mockRejectedValue({ status: 404, message: 'Not found.' })
    const Page = await loadPage()
    renderWithProviders(<Page />)
    const scanInput = await screen.findByPlaceholderText(/\.X<containerId>/)
    const user = userEvent.setup()
    await user.type(scanInput, '.X0{Enter}')
    const banner = await screen.findByRole('status')
    expect(banner.textContent).toMatch(/No NDS record matched/)
    expect(banner.className).toMatch(/rose/)
  })

  it('paints the "bad format" banner on 422', async () => {
    ndsLookup.mockRejectedValue({
      status: 422,
      message: 'Scan must start with .X or .Y',
    })
    const Page = await loadPage()
    renderWithProviders(<Page />)
    const scanInput = await screen.findByPlaceholderText(/\.X<containerId>/)
    const user = userEvent.setup()
    await user.type(scanInput, '.Zbad{Enter}')
    const banner = await screen.findByRole('status')
    expect(banner.textContent).toMatch(/\.X or \.Y/)
    expect(banner.className).toMatch(/rose/)
  })

  it('paints the "NDS unavailable" amber banner on 503', async () => {
    ndsLookup.mockRejectedValue({ status: 503, message: 'TNS-12541' })
    const Page = await loadPage()
    renderWithProviders(<Page />)
    const scanInput = await screen.findByPlaceholderText(/\.X<containerId>/)
    const user = userEvent.setup()
    await user.type(scanInput, '.X77{Enter}')
    const banner = await screen.findByRole('status')
    expect(banner.textContent).toMatch(/NDS is unavailable/)
    expect(banner.className).toMatch(/amber/)
  })

  it('switches the carrier to the mapped service carrier, not the client default', async () => {
    // Client ACME defaults to FedEx; the NDS order is mapped to a UPS
    // service. Before the fix the carrier stayed FEDEX and the
    // re-validate effect swapped the UPS service for the FedEx default.
    const { accountRefService } = await import('../api/accountRefService')
    const { shippingConfigService } = await import('../api/shippingConfigService')
    const { clientService } = await import('../api/clientService')
    const acct = (id: number, carrierCode: string, accountNumber: string) => ({
      id, carrierCode, accountNumber, accountName: null, customerNo: 'ACME',
      environment: null, isDefault: false, active: true, complete: true,
      clientIdPreview: null, verified: true, lastVerifiedAt: null,
      labelsGenerated: null, lastUsedAt: null,
    })
    vi.mocked(accountRefService.listAccounts).mockResolvedValueOnce(
      [acct(10, 'FEDEX', 'F-1'), acct(11, 'UPS', 'U-1')] as never)
    const svc = (id: number, carrier: string, serviceCode: string, name: string) => ({
      id, carrier, serviceCode, name, scope: 'DOMESTIC', enabled: true,
      sortOrder: id, originCountry: 'US',
    })
    vi.mocked(shippingConfigService.catalog).mockResolvedValueOnce({
      services: [svc(1, 'UPS', '03', 'UPS Ground'), svc(2, 'FEDEX', 'FEDEX_GROUND', 'FedEx Ground')],
      carriers: [], links: [], rulePackages: [], ruleWarehouses: [], originCountries: ['US'],
    } as never)
    vi.mocked(clientService.listClients).mockResolvedValueOnce({
      data: { content: [{
        clientCode: 'ACME', name: 'Acme Corp',
        carrierAccounts: [{ carrierCode: 'FEDEX', accountNumber: 'F-1', active: true, clientDefault: true }],
      }] },
    } as never)
    ndsLookup.mockResolvedValue(okPrefill())
    const Page = await loadPage()
    renderWithProviders(<Page />)
    const scanInput = await screen.findByPlaceholderText(/\.X<containerId>/)
    // Let the accounts/catalog/clients load settle before scanning.
    await waitFor(() => expect(clientService.listClients).toHaveBeenCalled())
    await new Promise((r) => setTimeout(r, 50))
    const user = userEvent.setup()
    await user.type(scanInput, '.X77{Enter}')
    await screen.findByRole('status')
    await waitFor(() => {
      const values = screen.getAllByRole('combobox').map((el) => (el as HTMLSelectElement).value)
      expect(values).toContain('UPS')
      expect(values).toContain('1')
    })
  }, 20_000)
})
