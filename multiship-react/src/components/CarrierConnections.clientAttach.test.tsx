import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, cleanup, fireEvent, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Outlet, Route, Routes } from 'react-router-dom'
import type { ComponentType } from 'react'

/**
 * Sprint 53 carriers-page-tests — Per-client attach + client-default slice.
 *
 * Coverage focuses on the *linkage* between the Client filter dropdown, the
 * per-row "make this the client default" star button, and the
 * `accountRefService.setClientDefault(id)` call that flips the star.
 *
 * Ownership boundary (do NOT expand this file into other slices):
 *   - drawer create / edit / verify           → sibling slice
 *   - filters (env, verification, search)     → sibling slice
 *   - row actions (delete, toggle-active)     → sibling slice
 *
 * Ground truth on the UX shape (verified against CarrierConnections.tsx L421
 * + L659 as of 2026-08-16):
 *   - `setClientDefault` takes ONE argument (accountId). There is no explicit
 *     `clientCode` — the backend uses the account's linked `customerNo`.
 *   - There is NO modal picker for multi-client attach. The star icon in the
 *     Carrier cell is the entire UX surface for the operation.
 *   - Platform rows (customerNo blank) never render a star (spacer).
 *   - Inactive/incomplete rows never render the interactive star button.
 *   - Already-default rows render a filled (or faded, if inactive) star
 *     *span* — not a button.
 */

// ===== module mocks (top-level, hoisted) =====

// The Un-mocked fetch guard: anything that escapes our service mocks and hits
// real `fetch` explodes with a loud error instead of silently succeeding on a
// jsdom stub. Rebound in beforeEach so each test starts clean.
const fetchGuard = vi.fn(() => {
  throw new Error('un-mocked fetch forbidden')
})

const listAccounts = vi.fn()
const setClientDefault = vi.fn()
const upsertAccount = vi.fn()
const verifyAccount = vi.fn()
const verifyCredentials = vi.fn()
const toggleActive = vi.fn()
const deleteAccount = vi.fn()
const getPlatformCredentials = vi.fn()
const resolveOrders = vi.fn()

vi.mock('../api/accountRefService', () => ({
  accountRefService: {
    listAccounts: (...a: unknown[]) => listAccounts(...a),
    upsertAccount: (...a: unknown[]) => upsertAccount(...a),
    setClientDefault: (...a: unknown[]) => setClientDefault(...a),
    verifyAccount: (...a: unknown[]) => verifyAccount(...a),
    verifyCredentials: (...a: unknown[]) => verifyCredentials(...a),
    toggleActive: (...a: unknown[]) => toggleActive(...a),
    deleteAccount: (...a: unknown[]) => deleteAccount(...a),
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
      listClientAccounts: vi.fn().mockResolvedValue([]),
      cascadePreview: vi.fn(),
      toggleActive: vi.fn(),
      deleteClient: vi.fn(),
      exportClientsCsv: vi.fn(),
    },
  }
})

// isAbortError is imported and called on the listClients catch path — the
// real implementation just checks `error?.name === 'AbortError'` and works
// fine on a plain Error, so we pull the real module.

const notifyMock = {
  success: vi.fn(),
  error: vi.fn(),
  info: vi.fn(),
  apiError: vi.fn(),
  confirm: vi.fn().mockResolvedValue(true),
}
vi.mock('../utils/notify', () => ({ notify: notifyMock }))

// ===== fixtures =====

/** Build a carrier account row with test-friendly defaults. */
function acct(overrides: Partial<import('../api/accountRefService').CarrierAccountRef> = {}): import('../api/accountRefService').CarrierAccountRef {
  return {
    id: 100,
    accountNumber: 'ACCT-100',
    carrierCode: 'UPS',
    accountName: null,
    customerNo: null,
    environment: 'SANDBOX',
    isDefault: false,
    clientDefault: false,
    active: true,
    complete: true,
    clientIdPreview: 'ups...',
    verified: true,
    lastVerifiedAt: null,
    labelsGenerated: null,
    lastUsedAt: null,
    createdAt: null,
    updatedAt: null,
    ...overrides,
  }
}

const seedAccounts = () => [
  acct({ id: 1, accountNumber: 'PLAT-1', customerNo: null }),
  // ACME client account, complete + active, NOT default → star button offered.
  acct({ id: 2, accountNumber: 'ACME-A', customerNo: 'ACME', clientDefault: false }),
  // WIDGETS client account, already default → filled star (span, no button).
  acct({ id: 3, accountNumber: 'WID-1', customerNo: 'WIDGETS', clientDefault: true }),
  // ACME inactive → no star button, no filled star (empty spacer).
  acct({ id: 4, accountNumber: 'ACME-B', customerNo: 'ACME', clientDefault: false, active: false }),
]

const seedClients = () => ({
  data: {
    content: [
      { clientCode: 'ACME', name: 'Acme Corp' },
      { clientCode: 'WIDGETS', name: 'Widgets LLC' },
    ],
  },
})

// ===== render harness =====

/**
 * CarrierConnections calls `useOutletContext<SettingsOutletContext>()` to
 * register a refresh handler. A no-op outlet gives it a valid context; the
 * client-attach slice never invokes the layout refresh button so we don't
 * need to observe the handler.
 */
function OutletShell() {
  return <Outlet context={{ registerRefresh: () => {} }} />
}

async function loadComponent(): Promise<ComponentType> {
  const mod = await import('./CarrierConnections')
  return mod.default
}

function renderComponent(Cmp: ComponentType) {
  return render(
    <MemoryRouter initialEntries={['/settings/carriers']}>
      <Routes>
        <Route element={<OutletShell />}>
          <Route path="/settings/carriers" element={<Cmp />} />
        </Route>
      </Routes>
    </MemoryRouter>,
  )
}

// ===== lifecycle =====

beforeEach(() => {
  listAccounts.mockReset().mockResolvedValue(seedAccounts())
  setClientDefault.mockReset().mockResolvedValue({ data: null })
  upsertAccount.mockReset()
  verifyAccount.mockReset()
  verifyCredentials.mockReset()
  toggleActive.mockReset()
  deleteAccount.mockReset()
  getPlatformCredentials.mockReset().mockResolvedValue({ data: { found: false, clientId: null, clientSecretMasked: null, hasClientSecret: false, carrierCode: 'UPS' } })
  resolveOrders.mockReset()

  listClients.mockReset().mockResolvedValue(seedClients())

  notifyMock.success.mockReset()
  notifyMock.error.mockReset()
  notifyMock.info.mockReset()
  notifyMock.apiError.mockReset()
  notifyMock.confirm.mockReset().mockResolvedValue(true)

  fetchGuard.mockReset().mockImplementation(() => {
    throw new Error('un-mocked fetch forbidden')
  })
  vi.spyOn(global, 'fetch').mockImplementation(fetchGuard as unknown as typeof fetch)
})

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
})

// ===== positive =====

describe('CarrierConnections · client-attach · positive', () => {
  it('populates the Filters > Client dropdown from clientService.listClients', async () => {
    const Cmp = await loadComponent()
    renderComponent(Cmp)

    // Wait for the table to hydrate — caption reads once loading resolves.
    await screen.findByText(/Showing 4 of 4 accounts/i)

    // Open the filter popover.
    fireEvent.click(screen.getByRole('button', { name: /^Filters$/i }))

    // Exact aria-label match — the popover also has a "Filter by client
    // default" select whose label is a superstring of "Filter by client".
    const clientSelect = await screen.findByLabelText('Filter by client') as HTMLSelectElement
    const values = Array.from(clientSelect.options).map((o) => o.value)
    expect(values).toEqual(['', 'ACME', 'WIDGETS'])
    // The listClients side-load fires exactly once on mount.
    expect(listClients).toHaveBeenCalledTimes(1)
  }, 20000)

  it('selecting a client in the filter narrows visible accounts to that client', async () => {
    const Cmp = await loadComponent()
    renderComponent(Cmp)
    await screen.findByText(/Showing 4 of 4 accounts/i)

    fireEvent.click(screen.getByRole('button', { name: /^Filters$/i }))
    const clientSelect = await screen.findByLabelText('Filter by client') as HTMLSelectElement
    fireEvent.change(clientSelect, { target: { value: 'ACME' } })

    // 2 ACME rows visible (one active + one inactive) out of 4 total.
    await screen.findByText(/Showing 2 of 4 accounts/i)
  })

  it('Client-default filter (YES) narrows to only rows with clientDefault=true', async () => {
    const Cmp = await loadComponent()
    renderComponent(Cmp)
    await screen.findByText(/Showing 4 of 4 accounts/i)

    fireEvent.click(screen.getByRole('button', { name: /^Filters$/i }))
    const defaultSelect = await screen.findByLabelText(/Filter by client default/i) as HTMLSelectElement
    fireEvent.change(defaultSelect, { target: { value: 'YES' } })

    // Only WIDGETS row (id 3) has clientDefault=true.
    await screen.findByText(/Showing 1 of 4 accounts/i)
  })

  it('star button on an active+complete client row calls setClientDefault(accountId)', async () => {
    const user = userEvent.setup()
    const Cmp = await loadComponent()
    renderComponent(Cmp)
    await screen.findByText(/Showing 4 of 4 accounts/i)

    // The interactive star renders only on ACME-A (id 2) in the seed data —
    // ACME-B is inactive (spacer), WIDGETS is already default (filled span),
    // PLAT-1 is platform (spacer).
    const starBtn = await screen.findByRole('button', {
      name: /Make this ACME's default account/i,
    })
    await user.click(starBtn)

    await waitFor(() => {
      expect(setClientDefault).toHaveBeenCalledTimes(1)
    })
    expect(setClientDefault).toHaveBeenCalledWith(2)
  })

  it('after setClientDefault succeeds → notify.success + accounts refetch', async () => {
    const user = userEvent.setup()
    const Cmp = await loadComponent()
    renderComponent(Cmp)
    await screen.findByText(/Showing 4 of 4 accounts/i)

    // Baseline: 1 initial load call.
    expect(listAccounts).toHaveBeenCalledTimes(1)

    const starBtn = await screen.findByRole('button', {
      name: /Make this ACME's default account/i,
    })
    await user.click(starBtn)

    await waitFor(() => {
      expect(notifyMock.success).toHaveBeenCalled()
    })
    // Message name includes the accountNumber + customerNo.
    const successMsg = notifyMock.success.mock.calls[0][0] as string
    expect(successMsg).toMatch(/ACME-A/)
    expect(successMsg).toMatch(/ACME/)

    // Refetch happened after the mutation.
    await waitFor(() => {
      expect(listAccounts).toHaveBeenCalledTimes(2)
    })
  })

  it('rows with clientDefault=true render a filled non-button star (Default account for …)', async () => {
    const Cmp = await loadComponent()
    renderComponent(Cmp)
    await screen.findByText(/Showing 4 of 4 accounts/i)

    // The filled star lives in a <span title="Default account for WIDGETS">.
    // Querying by title is the most stable hook — the icon is a react-icons svg.
    const filled = document.querySelector('span[title="Default account for WIDGETS"]')
    expect(filled).not.toBeNull()

    // And crucially it is NOT a button (so it does not re-fire setClientDefault).
    expect(filled?.tagName.toLowerCase()).toBe('span')
    expect(screen.queryByRole('button', { name: /Make this WIDGETS's default account/i })).toBeNull()
  })
})

// ===== negative =====

describe('CarrierConnections · client-attach · negative', () => {
  it('listClients reject → Client dropdown falls back to just "All clients"', async () => {
    listClients.mockRejectedValueOnce(new Error('boom 500'))
    const Cmp = await loadComponent()
    renderComponent(Cmp)

    // Accounts still load — the client-list failure is non-blocking.
    await screen.findByText(/Showing 4 of 4 accounts/i)

    fireEvent.click(screen.getByRole('button', { name: /^Filters$/i }))
    const clientSelect = await screen.findByLabelText('Filter by client') as HTMLSelectElement
    const values = Array.from(clientSelect.options).map((o) => o.value)
    expect(values).toEqual([''])
    expect(within(clientSelect).getByText(/All clients/i)).toBeTruthy()
  })

  it('listAccounts reject → notify.apiError surfaces the failure', async () => {
    listAccounts.mockRejectedValueOnce(new Error('boom'))
    const Cmp = await loadComponent()
    renderComponent(Cmp)

    await waitFor(() => {
      expect(notifyMock.apiError).toHaveBeenCalled()
    })
    const call = notifyMock.apiError.mock.calls[0]
    expect((call[0] as Error).message).toBe('boom')
    expect(call[1]).toMatch(/Failed to load the account book/i)
  })

  it('setClientDefault reject → notify.apiError; no success toast', async () => {
    setClientDefault.mockRejectedValueOnce(new Error('nope'))
    const user = userEvent.setup()
    const Cmp = await loadComponent()
    renderComponent(Cmp)
    await screen.findByText(/Showing 4 of 4 accounts/i)

    await user.click(await screen.findByRole('button', {
      name: /Make this ACME's default account/i,
    }))

    await waitFor(() => {
      expect(notifyMock.apiError).toHaveBeenCalled()
    })
    const call = notifyMock.apiError.mock.calls[0]
    expect((call[0] as Error).message).toBe('nope')
    expect(call[1]).toMatch(/Failed to set the client default/i)
    expect(notifyMock.success).not.toHaveBeenCalled()
  })
})

// ===== cross-cutting =====

describe('CarrierConnections · client-attach · cross-cutting', () => {
  it('platform rows never render a star (button OR filled) — spacer only', async () => {
    const Cmp = await loadComponent()
    renderComponent(Cmp)
    await screen.findByText(/Showing 4 of 4 accounts/i)

    // The PLAT-1 row is present in the table.
    expect(screen.getByText('PLAT-1')).toBeTruthy()
    // But there is no star span titled with a platform customerNo, and no
    // star button that would target the platform account id (1).
    // A negative existence check on all "Make this …'s default account"
    // buttons — only ACME-A should qualify from the seed set.
    const starBtns = screen.queryAllByRole('button', { name: /Make this .+ default account/i })
    expect(starBtns).toHaveLength(1)
    // And it targets ACME (customerNo).
    expect(starBtns[0].getAttribute('aria-label')).toMatch(/ACME/)
  })

  it('inactive client rows do not offer the interactive star button', async () => {
    const Cmp = await loadComponent()
    renderComponent(Cmp)
    await screen.findByText(/Showing 4 of 4 accounts/i)

    // ACME-B is inactive → no button targeting it (customerNo is "ACME" but
    // the row is not eligible). There is exactly one star button in the
    // whole table (ACME-A), and it fires on account id 2 not 4.
    const starBtns = screen.getAllByRole('button', { name: /Make this ACME's default account/i })
    expect(starBtns).toHaveLength(1)
  })

  it('un-mocked fetch is forbidden — the guard fires if anything escapes the service mocks', () => {
    // Sanity: the guard we installed in beforeEach is armed. If any of the
    // mocked services above were bypassed the transitive apiClient call
    // would hit `global.fetch` and throw.
    expect(() => (global.fetch as unknown as () => unknown)()).toThrow(
      /un-mocked fetch forbidden/,
    )
  })
})
