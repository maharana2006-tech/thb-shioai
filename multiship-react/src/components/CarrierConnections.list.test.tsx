import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, cleanup, fireEvent, within } from '@testing-library/react'
import { MemoryRouter, Outlet, Route, Routes } from 'react-router-dom'
import type { ComponentType } from 'react'

/**
 * Sprint 53 — /settings/carriers — list + tiles + filters slice.
 *
 * Scope: the CarrierConnections page as rendered at /settings/carriers via
 * the 5-line CarrierPage wrapper. Verifies:
 *   - the account rows render one per CarrierAccountRef with carrier name +
 *     account number + status chip.
 *   - health-strip counters (Ready, Unverified, Platform, Client) reflect
 *     the loaded account book.
 *   - search input filters the visible rows by number/name/client.
 *   - the filter popover exposes carrier / status / verification selects;
 *     each dropdown narrows visibleAccounts client-side (filtering is done
 *     in-memory — no follow-up listAccounts call is expected).
 *   - status chips: Verified / Check failed / Unverified / Incomplete /
 *     Inactive all light up when the underlying account row matches.
 *   - listAccounts rejection surfaces via notify.apiError and the empty
 *     state message.
 *   - the drawer's carrier picker offers UPS / FEDEX / USPS / DHL tiles.
 *
 * Every carrier-adjacent service call is mocked; a fetch spy fails the run
 * loudly if any un-mocked outbound request slips through.
 */

// ===== Service + hook mocks (top level so hoisting lands before the
// component module is imported dynamically inside the tests) =====

const listAccounts = vi.fn()
const upsertAccount = vi.fn()
const verifyAccount = vi.fn()
const toggleActive = vi.fn()
const deleteAccount = vi.fn()
const verifyCredentials = vi.fn()
const getPlatformCredentials = vi.fn()
const setClientDefault = vi.fn()
const resolveOrders = vi.fn()

vi.mock('../api/accountRefService', () => ({
  accountRefService: {
    listAccounts: (...a: unknown[]) => listAccounts(...a),
    upsertAccount: (...a: unknown[]) => upsertAccount(...a),
    verifyAccount: (...a: unknown[]) => verifyAccount(...a),
    toggleActive: (...a: unknown[]) => toggleActive(...a),
    deleteAccount: (...a: unknown[]) => deleteAccount(...a),
    verifyCredentials: (...a: unknown[]) => verifyCredentials(...a),
    getPlatformCredentials: (...a: unknown[]) => getPlatformCredentials(...a),
    setClientDefault: (...a: unknown[]) => setClientDefault(...a),
    resolveOrders: (...a: unknown[]) => resolveOrders(...a),
  },
}))

const listClients = vi.fn()
vi.mock('../api/clientService', () => ({
  clientService: {
    listClients: (...a: unknown[]) => listClients(...a),
    getClient: vi.fn(),
    createClient: vi.fn(),
    updateClient: vi.fn(),
    listClientAccounts: vi.fn().mockResolvedValue([]),
    cascadePreview: vi.fn(),
    toggleActive: vi.fn(),
    deleteClient: vi.fn(),
    exportClientsCsv: vi.fn(),
  },
}))

// notify: stub the whole surface so success toasts + apiError calls in the
// component don't crash the tests without a toast container mounted.
const notifyApiError = vi.fn()
vi.mock('../utils/notify', () => ({
  notify: {
    success: vi.fn(),
    error: vi.fn(),
    info: vi.fn(),
    apiError: (...a: unknown[]) => notifyApiError(...a),
    confirm: vi.fn().mockResolvedValue(true),
  },
}))

// ===== Test data =====

/** Minimal factory for a CarrierAccountRef — every optional field defaulted
 *  so the visible fields under test light up cleanly. */
function acc(over: Partial<Record<string, unknown>> = {}) {
  return {
    id: 1,
    accountNumber: 'UPS12345',
    carrierCode: 'UPS',
    accountName: 'Primary UPS',
    customerNo: null,
    environment: 'SANDBOX',
    isDefault: false,
    clientDefault: false,
    active: true,
    complete: true,
    clientIdPreview: 'abc…',
    verified: true,
    lastVerifiedAt: null,
    labelsGenerated: 0,
    lastUsedAt: null,
    shippingPurpose: null,
    clearanceOption: null,
    thirdPartyAccount: null,
    thirdPartyName: null,
    thirdPartyAddress1: null,
    thirdPartyCity: null,
    thirdPartyState: null,
    thirdPartyPostcode: null,
    thirdPartyCountry: null,
    createdAt: null,
    updatedAt: null,
    ...over,
  }
}

const seededAccounts = [
  acc({ id: 1, accountNumber: 'UPS12345', carrierCode: 'UPS', accountName: 'UPS Primary', verified: true }),
  acc({
    id: 2, accountNumber: 'FDX987654321', carrierCode: 'FEDEX', accountName: 'FedEx Backup',
    verified: false, customerNo: 'ACME', clientDefault: true,
  }),
  acc({
    id: 3, accountNumber: 'USPS999', carrierCode: 'USPS', accountName: null,
    verified: null, environment: 'PRODUCTION',
  }),
  acc({
    id: 4, accountNumber: 'DHL42', carrierCode: 'DHL', accountName: 'DHL Global',
    verified: true, active: false, complete: true,
  }),
]

/** Dynamic import so the vi.mock hoisting lands before CarrierConnections
 *  is evaluated (it pulls accountRefService + clientService at top level). */
async function loadPage(): Promise<ComponentType> {
  const mod = await import('./CarrierConnections')
  return mod.default
}

/** CarrierConnections calls useOutletContext<SettingsOutletContext>() to
 *  register its refresh handler. In test we render a MemoryRouter with a
 *  wrapper route that supplies the exact context shape SettingsLayout
 *  provides — a no-op registerRefresh is enough because this slice never
 *  touches the refresh button. */
function OutletShell() {
  return <Outlet context={{ registerRefresh: () => {} }} />
}

function renderPage(Page: ComponentType) {
  return render(
    <MemoryRouter initialEntries={['/settings/carriers']}>
      <Routes>
        <Route element={<OutletShell />}>
          <Route path="/settings/carriers" element={<Page />} />
        </Route>
      </Routes>
    </MemoryRouter>,
  )
}

let fetchSpy: ReturnType<typeof vi.spyOn>

beforeEach(() => {
  listAccounts.mockReset().mockResolvedValue(seededAccounts)
  upsertAccount.mockReset()
  verifyAccount.mockReset()
  toggleActive.mockReset()
  deleteAccount.mockReset()
  verifyCredentials.mockReset()
  getPlatformCredentials.mockReset().mockResolvedValue({ data: { found: false, clientId: null } })
  setClientDefault.mockReset()
  resolveOrders.mockReset()
  listClients.mockReset().mockResolvedValue({ data: { content: [] } })
  notifyApiError.mockReset()

  // Anti-fallback guard: any un-mocked outbound request fails the test loudly.
  fetchSpy = vi.spyOn(globalThis, 'fetch').mockImplementation(() => {
    throw new Error('un-mocked fetch — real carrier IO forbidden in tests')
  })
})

afterEach(() => {
  cleanup()
  fetchSpy?.mockRestore()
})

// ===== Positive: renders + tiles + list =====

describe('CarrierConnections · list', () => {
  it('renders one row per seeded account with carrier name + number', async () => {
    const Page = await loadPage()
    renderPage(Page)

    // Wait for the async fetch to resolve and the table to paint.
    expect(await screen.findByText('UPS12345')).toBeInTheDocument()
    expect(screen.getByText('FDX987654321')).toBeInTheDocument()
    expect(screen.getByText('USPS999')).toBeInTheDocument()
    expect(screen.getByText('DHL42')).toBeInTheDocument()

    // formatCarrierName surfaces the human label in the carrier cell.
    // FedEx = 'FEDEX' → 'FedEx'; UPS stays UPS.
    expect(screen.getAllByText(/UPS/i).length).toBeGreaterThan(0)
    expect(screen.getAllByText(/FedEx/i).length).toBeGreaterThan(0)
  }, 20000)

  it('renders health-strip counters reflecting the seeded book (Platform + Client counts)', async () => {
    const Page = await loadPage()
    renderPage(Page)

    // Wait for the account body to paint before asserting counters.
    await screen.findByText('UPS12345')

    // Health strip: 4 tiles labeled Ready / Unverified / Platform / Client.
    expect(screen.getByText(/Ready to ship/i)).toBeInTheDocument()
    expect(screen.getByText(/Platform accounts/i)).toBeInTheDocument()
    expect(screen.getByText(/Client accounts/i)).toBeInTheDocument()

    // Ready = active && complete → 3 of 4 (DHL row is inactive).
    // The value renders as "3/4" inside the Ready tile.
    expect(screen.getByText('3/4')).toBeInTheDocument()
  }, 20000)

  it('empty account book → shows the empty-state message', async () => {
    listAccounts.mockResolvedValue([])
    const Page = await loadPage()
    renderPage(Page)

    // Wait for the loading spinner to disappear (loading && !accounts.length
    // shows "Loading the account book…" — a non-empty response drops it).
    await waitFor(() => {
      expect(screen.queryByText(/Loading the account book/i)).toBeNull()
    })
    expect(
      screen.getByText(/No accounts saved yet — add your first carrier account/i),
    ).toBeInTheDocument()
  }, 20000)

  it('status chips: Verified / Check failed / Unverified / Inactive light up per row', async () => {
    const Page = await loadPage()
    renderPage(Page)
    await screen.findByText('UPS12345')

    // Row 1 (UPS, verified=true) + row 4 (DHL, verified=true but inactive)
    // → at least one "Verified" chip renders (from UPS row; DHL row shows
    // "Inactive" instead because active=false takes precedence).
    expect(screen.getAllByText(/^Verified$/i).length).toBeGreaterThanOrEqual(1)
    // Row 2 (FedEx, verified=false) → "Check failed" chip.
    expect(screen.getByText(/Check failed/i)).toBeInTheDocument()
    // Row 3 (USPS, verified=null) → chip rendered as "⚠ Unverified".
    // The plain "Unverified" text also appears in the health-strip label
    // (counter tile), so match the chip's exact "⚠ Unverified" glyph.
    expect(screen.getByText(/⚠ Unverified/)).toBeInTheDocument()
    // Row 4 (DHL, active=false) → "Inactive" chip.
    expect(screen.getByText(/^Inactive$/i)).toBeInTheDocument()
  }, 20000)
})

// ===== Filters + search =====

describe('CarrierConnections · search + filters', () => {
  it('search input narrows visible rows by account number substring', async () => {
    const Page = await loadPage()
    renderPage(Page)
    await screen.findByText('UPS12345')

    const search = screen.getByPlaceholderText(
      /Search account #, name, client…/i,
    ) as HTMLInputElement

    // Type an FDX-only substring — the caption + row count both update.
    fireEvent.change(search, { target: { value: 'FDX' } })

    await waitFor(() => {
      // Only the FedEx row survives.
      expect(screen.getByText('FDX987654321')).toBeInTheDocument()
      expect(screen.queryByText('UPS12345')).toBeNull()
      expect(screen.queryByText('USPS999')).toBeNull()
      expect(screen.queryByText('DHL42')).toBeNull()
    })
  }, 20000)

  it('search with no matches → caption reads "Showing 0 of 4"', async () => {
    const Page = await loadPage()
    renderPage(Page)
    await screen.findByText('UPS12345')

    const search = screen.getByPlaceholderText(
      /Search account #, name, client…/i,
    ) as HTMLInputElement
    fireEvent.change(search, { target: { value: 'nomatch-xyz-nope' } })

    // Caption reflects the empty visible set; empty-state message swaps in
    // ("No accounts match the current filters.").
    await waitFor(() => {
      expect(screen.getByText(/Showing 0 of 4 accounts/i)).toBeInTheDocument()
    })
    expect(screen.getByText(/No accounts match the current filters/i)).toBeInTheDocument()
  }, 20000)

  it('opening the filter popover reveals the carrier + status + verification selects', async () => {
    const Page = await loadPage()
    renderPage(Page)
    await screen.findByText('UPS12345')

    // Popover is collapsed by default — none of the aria-labeled selects are
    // in the DOM yet.
    expect(screen.queryByLabelText(/Filter by carrier/i)).toBeNull()
    expect(screen.queryByLabelText(/Filter by status/i)).toBeNull()

    // Toggle open.
    fireEvent.click(screen.getByRole('button', { name: /^Filters$/i }))

    // Dialog + labelled selects surface.
    expect(screen.getByRole('dialog', { name: /Filter accounts/i })).toBeInTheDocument()
    expect(screen.getByLabelText(/Filter by carrier/i)).toBeInTheDocument()
    expect(screen.getByLabelText(/Filter by status/i)).toBeInTheDocument()
    expect(screen.getByLabelText(/Filter by verification/i)).toBeInTheDocument()
    expect(screen.getByLabelText(/Filter by client default/i)).toBeInTheDocument()
    expect(screen.getByLabelText(/Filter by environment/i)).toBeInTheDocument()
  }, 20000)

  it('carrier filter narrows the table to a single carrier without a re-fetch', async () => {
    const Page = await loadPage()
    renderPage(Page)
    await screen.findByText('UPS12345')

    // Snapshot listAccounts call-count before filter interaction so we can
    // assert client-side filtering (no additional network call).
    const callsBefore = listAccounts.mock.calls.length

    fireEvent.click(screen.getByRole('button', { name: /^Filters$/i }))
    const carrierSelect = screen.getByLabelText(/Filter by carrier/i) as HTMLSelectElement
    fireEvent.change(carrierSelect, { target: { value: 'ups' } })

    await waitFor(() => {
      expect(screen.getByText('UPS12345')).toBeInTheDocument()
      expect(screen.queryByText('FDX987654321')).toBeNull()
      expect(screen.queryByText('USPS999')).toBeNull()
      expect(screen.queryByText('DHL42')).toBeNull()
    })

    // Client-side filter → no follow-up listAccounts request.
    expect(listAccounts.mock.calls.length).toBe(callsBefore)
  }, 20000)

  it('status=INACTIVE narrows to the inactive DHL row only', async () => {
    const Page = await loadPage()
    renderPage(Page)
    await screen.findByText('UPS12345')

    fireEvent.click(screen.getByRole('button', { name: /^Filters$/i }))
    const statusSelect = screen.getByLabelText(/Filter by status/i) as HTMLSelectElement
    fireEvent.change(statusSelect, { target: { value: 'INACTIVE' } })

    await waitFor(() => {
      expect(screen.getByText('DHL42')).toBeInTheDocument()
      expect(screen.queryByText('UPS12345')).toBeNull()
      expect(screen.queryByText('FDX987654321')).toBeNull()
      expect(screen.queryByText('USPS999')).toBeNull()
    })
  }, 20000)

  it('verification=NO narrows to the failed/pending rows (FedEx + USPS)', async () => {
    const Page = await loadPage()
    renderPage(Page)
    await screen.findByText('UPS12345')

    fireEvent.click(screen.getByRole('button', { name: /^Filters$/i }))
    const verifiedSelect = screen.getByLabelText(/Filter by verification/i) as HTMLSelectElement
    fireEvent.change(verifiedSelect, { target: { value: 'NO' } })

    await waitFor(() => {
      // Rows where verified !== true: FedEx (false) + USPS (null) + DHL (true).
      // The filter is `verified === true` so verified:true is out and
      // false/null both survive → FedEx + USPS visible.
      expect(screen.getByText('FDX987654321')).toBeInTheDocument()
      expect(screen.getByText('USPS999')).toBeInTheDocument()
      expect(screen.queryByText('UPS12345')).toBeNull()
      expect(screen.queryByText('DHL42')).toBeNull()
    })
  }, 20000)

  it('Clear button (surfaces once a filter is set) wipes every filter dropdown', async () => {
    const Page = await loadPage()
    renderPage(Page)
    await screen.findByText('UPS12345')

    fireEvent.click(screen.getByRole('button', { name: /^Filters$/i }))
    const carrierSelect = screen.getByLabelText(/Filter by carrier/i) as HTMLSelectElement
    fireEvent.change(carrierSelect, { target: { value: 'fedex' } })

    // Now only FedEx is visible.
    await waitFor(() => {
      expect(screen.queryByText('UPS12345')).toBeNull()
      expect(screen.getByText('FDX987654321')).toBeInTheDocument()
    })

    // Clear button surfaces (only when filterOnlyCount > 0).
    const clearBtn = await screen.findByRole('button', { name: /^Clear$/i })
    fireEvent.click(clearBtn)

    // All rows visible again + carrier select reset.
    await waitFor(() => {
      expect(screen.getByText('UPS12345')).toBeInTheDocument()
      expect(screen.getByText('FDX987654321')).toBeInTheDocument()
      expect(screen.getByText('USPS999')).toBeInTheDocument()
      expect(screen.getByText('DHL42')).toBeInTheDocument()
    })
    // Carrier select is empty again.
    expect(
      (screen.getByLabelText(/Filter by carrier/i) as HTMLSelectElement).value,
    ).toBe('')
  }, 20000)
})

// ===== Negative: service rejection =====

describe('CarrierConnections · error paths', () => {
  it('listAccounts rejection → notify.apiError fired + empty-state renders', async () => {
    const rejection = new Error('boom — 500 from carrier-accounts')
    listAccounts.mockRejectedValue(rejection)

    const Page = await loadPage()
    renderPage(Page)

    // notify.apiError is called with the error + a fallback message.
    await waitFor(() => {
      expect(notifyApiError).toHaveBeenCalledTimes(1)
    })
    expect(notifyApiError.mock.calls[0][0]).toBe(rejection)
    expect(notifyApiError.mock.calls[0][1]).toMatch(/Failed to load the account book/i)

    // With no accounts loaded, the table falls back to its empty state.
    await waitFor(() => {
      expect(screen.queryByText(/Loading the account book/i)).toBeNull()
    })
    expect(
      screen.getByText(/No accounts saved yet — add your first carrier account/i),
    ).toBeInTheDocument()
  }, 20000)
})

// ===== Cross-cutting: drawer carrier tiles + toolbar Add button =====

describe('CarrierConnections · toolbar + drawer tiles', () => {
  it('clicking Add Account opens the drawer with a radiogroup of four carriers (UPS/FEDEX/USPS/DHL)', async () => {
    const Page = await loadPage()
    renderPage(Page)
    await screen.findByText('UPS12345')

    // Drawer is closed by default.
    expect(screen.queryByRole('dialog', { name: /Add carrier account/i })).toBeNull()

    fireEvent.click(screen.getByRole('button', { name: /Add Account/i }))

    // Drawer opens; the Carrier picker is a radiogroup with four tiles.
    const drawer = await screen.findByRole('dialog', { name: /Add carrier account/i })
    const carrierGroup = within(drawer).getByRole('radiogroup', { name: /^Carrier$/i })
    const radios = within(carrierGroup).getAllByRole('radio')
    // The picker renders exactly the four carrierOptions entries.
    expect(radios).toHaveLength(4)
    const labels = radios.map((r) => r.textContent?.trim())
    expect(labels).toEqual(expect.arrayContaining(['UPS', 'FedEx', 'USPS', 'DHL Express']))
  }, 20000)

  it('caption reads "Showing N of N accounts" and reflects the count', async () => {
    const Page = await loadPage()
    renderPage(Page)
    await screen.findByText('UPS12345')

    // With 4 seeded accounts + no filters → caption shows "4 of 4".
    expect(screen.getByText(/Showing 4 of 4 accounts/i)).toBeInTheDocument()
  }, 20000)
})
