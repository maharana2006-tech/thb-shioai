import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, cleanup, act } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, Outlet } from 'react-router-dom'
import type { ComponentType } from 'react'

/**
 * Sprint 53 page-tests — CarrierConnections · Global actions + role gating.
 *
 * <p>Scope (this slice only):
 *   - Loading state while accountRefService.listAccounts is pending
 *   - Populated state renders account rows (via AdvancedDataTable)
 *   - Empty state renders when zero accounts come back
 *   - Error state — listAccounts rejects → notify.apiError
 *   - Toolbar "Add Account" button is present + opens the drawer
 *   - Role gating matrix: ADMIN vs USER vs TENANT
 *   - Health-strip cards render page-level chrome (non-embedded)
 *
 * <p>All network-touching services are fully mocked. useAppSession is stubbed
 * so we can flip the effective role per-test without poking localStorage.
 * Un-mocked global fetch is treated as a test bug (fail-loud spy).
 *
 * <p>Documented behavior (2026-08-16): CarrierConnections does NOT read
 * useAppSession itself — every toolbar/table control renders regardless of
 * role. The backend rejects unauthorized mutations. We assert the actual
 * matrix so a future FE-level gate has a clear regression point.
 */

// ---------- Service mocks (hoisted before the component is imported) ----------

const listAccountsMock = vi.fn()
const upsertAccountMock = vi.fn()
const verifyAccountMock = vi.fn()
const toggleActiveMock = vi.fn()
const deleteAccountMock = vi.fn()
const verifyCredentialsMock = vi.fn()
const getPlatformCredentialsMock = vi.fn()
const setClientDefaultMock = vi.fn()

vi.mock('../api/accountRefService', () => ({
  accountRefService: {
    listAccounts: (...args: unknown[]) => listAccountsMock(...args),
    upsertAccount: (...args: unknown[]) => upsertAccountMock(...args),
    verifyAccount: (...args: unknown[]) => verifyAccountMock(...args),
    toggleActive: (...args: unknown[]) => toggleActiveMock(...args),
    deleteAccount: (...args: unknown[]) => deleteAccountMock(...args),
    verifyCredentials: (...args: unknown[]) => verifyCredentialsMock(...args),
    getPlatformCredentials: (...args: unknown[]) => getPlatformCredentialsMock(...args),
    setClientDefault: (...args: unknown[]) => setClientDefaultMock(...args),
  },
}))

const listClientsMock = vi.fn()
vi.mock('../api/clientService', () => ({
  clientService: {
    listClients: (...args: unknown[]) => listClientsMock(...args),
    getClient: vi.fn(),
    createClient: vi.fn(),
    updateClient: vi.fn(),
  },
}))

// Notify — stub the whole surface so we can assert apiError fires.
const notifyErrorMock = vi.fn()
const notifySuccessMock = vi.fn()
const notifyApiErrorMock = vi.fn()
const notifyInfoMock = vi.fn()
const notifyConfirmMock = vi.fn().mockResolvedValue(true)
vi.mock('../utils/notify', () => ({
  notify: {
    success: (...args: unknown[]) => notifySuccessMock(...args),
    error: (...args: unknown[]) => notifyErrorMock(...args),
    apiError: (...args: unknown[]) => notifyApiErrorMock(...args),
    info: (...args: unknown[]) => notifyInfoMock(...args),
    confirm: (...args: unknown[]) => notifyConfirmMock(...args),
  },
}))

// useAppSession — even though CarrierConnections doesn't consume it today,
// mock it so any indirect import (utils, shared components) resolves cleanly
// and future FE gating can be flipped via mockRole without a re-mock.
let mockRole: 'ADMIN' | 'USER' | 'TENANT' = 'ADMIN'
vi.mock('../hooks/useAppSession', () => ({
  useAppSession: () => ({
    username: 'ops',
    role: mockRole,
    connectedCarriers: [],
    hasConnectedCarrier: false,
  }),
  clearAuthSession: vi.fn(),
  storeAuthSession: vi.fn(),
  bootstrapSessionFromCookie: vi.fn(),
  syncCarrierSession: vi.fn(),
}))

// isAbortError — CarrierConnections gates a secondary-load debug log on it.
// Return false so any real error still short-circuits the noop debug path.
vi.mock('../api/apiClient', () => ({
  isAbortError: () => false,
  apiClient: {
    get: vi.fn(),
    post: vi.fn(),
    put: vi.fn(),
    delete: vi.fn(),
    patch: vi.fn(),
  },
}))

// ---------- Fail-loud fetch spy (anti-fallback) ----------
// Any test that reaches the real network is a bug in mocking.
const originalFetch = global.fetch
beforeEach(() => {
  vi.spyOn(global, 'fetch').mockImplementation(() => {
    throw new Error('un-mocked fetch forbidden in unit tests')
  })
})

// ---------- Helpers ----------

/**
 * Dynamic import so vi.mock calls above land before CarrierConnections
 * evaluates its top-level imports (accountRefService, notify, clientService).
 */
async function loadPage(): Promise<ComponentType> {
  const mod = await import('./CarrierConnections')
  return mod.default
}

function renderPage(Page: ComponentType) {
  // CarrierConnections calls useOutletContext<SettingsOutletContext> for
  // registerRefresh. Wrap it in an Outlet-providing parent route that
  // supplies the context.
  const registerRefresh = vi.fn()

  return {
    registerRefresh,
    ...render(
      <MemoryRouter initialEntries={['/settings/carriers']}>
        <Routes>
          <Route element={<Outlet context={{ registerRefresh }} />}>
            <Route path="/settings/carriers" element={<Page />} />
          </Route>
        </Routes>
      </MemoryRouter>,
    ),
  }
}

/** Canonical account row for the account book. */
function sampleAccount(over: Partial<Record<string, unknown>> = {}) {
  return {
    id: 1,
    accountNumber: 'ACCT-100',
    carrierCode: 'UPS',
    accountName: 'Platform UPS',
    customerNo: null, // null = platform account
    environment: 'PRODUCTION',
    isDefault: false,
    clientDefault: false,
    active: true,
    complete: true,
    clientIdPreview: 'abc***',
    verified: true,
    lastVerifiedAt: '2026-08-15T00:00:00Z',
    labelsGenerated: 3,
    lastUsedAt: '2026-08-15T00:00:00Z',
    shippingPurpose: null,
    clearanceOption: null,
    thirdPartyAccount: null,
    thirdPartyName: null,
    thirdPartyAddress1: null,
    thirdPartyCity: null,
    thirdPartyState: null,
    thirdPartyPostcode: null,
    thirdPartyCountry: null,
    createdAt: '2026-08-01T00:00:00Z',
    updatedAt: '2026-08-01T00:00:00Z',
    ...over,
  }
}

/** listClients ApiResponse shape used only for the secondary client-picker load. */
function clientPageResponse(content: Array<{ clientCode: string; name: string }> = []) {
  return {
    data: {
      content,
      pageNumber: 0,
      pageSize: 500,
      totalElements: content.length,
      totalPages: 1,
    },
  }
}

beforeEach(() => {
  listAccountsMock.mockReset().mockResolvedValue([])
  upsertAccountMock.mockReset()
  verifyAccountMock.mockReset()
  toggleActiveMock.mockReset()
  deleteAccountMock.mockReset()
  verifyCredentialsMock.mockReset()
  getPlatformCredentialsMock.mockReset().mockResolvedValue({ data: { found: false } })
  setClientDefaultMock.mockReset()
  listClientsMock.mockReset().mockResolvedValue(clientPageResponse([]))
  notifyErrorMock.mockReset()
  notifySuccessMock.mockReset()
  notifyApiErrorMock.mockReset()
  notifyInfoMock.mockReset()
  notifyConfirmMock.mockReset().mockResolvedValue(true)
  mockRole = 'ADMIN'
})

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
  global.fetch = originalFetch
})

// ============ Positive cases ============

describe('CarrierConnections · Global · positive', () => {
  it('renders the loading placeholder while the initial listAccounts is pending', async () => {
    // Never resolve — we assert the loading text on first paint.
    listAccountsMock.mockReturnValueOnce(new Promise(() => {}))
    const Page = await loadPage()
    renderPage(Page)

    // "Loading the account book…" (uses a horizontal-ellipsis; regex is robust).
    expect(screen.getByText(/Loading the account book/i)).toBeTruthy()
  }, 15000)

  it('renders the empty-state message when the account book is empty (non-embedded)', async () => {
    listAccountsMock.mockResolvedValue([])
    const Page = await loadPage()
    renderPage(Page)

    // Non-embedded empty state comes from AdvancedDataTable's emptyState prop.
    await waitFor(() => {
      expect(screen.getByText(/No accounts saved yet — add your first carrier account/i)).toBeTruthy()
    })
  })

  it('renders account rows after listAccounts resolves with content', async () => {
    listAccountsMock.mockResolvedValue([
      sampleAccount(),
      sampleAccount({ id: 2, accountNumber: 'ACCT-200', accountName: 'FedEx Ops', carrierCode: 'FEDEX' }),
    ])
    const Page = await loadPage()
    renderPage(Page)

    await waitFor(() => {
      expect(screen.getByText('ACCT-100')).toBeTruthy()
      expect(screen.getByText('ACCT-200')).toBeTruthy()
    })
    // Health-strip card confirms non-embedded chrome renders: "2 platform accounts".
    expect(screen.getByText(/Platform accounts/i)).toBeTruthy()
    // "Showing X of Y accounts" caption from AdvancedDataTable.
    expect(screen.getAllByText(/Showing 2 of 2/i).length).toBeGreaterThan(0)
  })

  it('Add Account button is rendered in the toolbar and opens the drawer', async () => {
    listAccountsMock.mockResolvedValue([sampleAccount()])
    const Page = await loadPage()
    renderPage(Page)

    await waitFor(() => expect(screen.getByText('ACCT-100')).toBeTruthy())

    const user = userEvent.setup()
    const addBtn = screen.getByRole('button', { name: /Add Account/i })
    await user.click(addBtn)

    // Drawer opens as a dialog with the "Add carrier account" aria-label.
    await waitFor(() => {
      expect(screen.getByRole('dialog', { name: /Add carrier account/i })).toBeTruthy()
    })
  })

  it('health-strip totals reflect populated account state (readyCount + platform/client split)', async () => {
    listAccountsMock.mockResolvedValue([
      sampleAccount({ id: 1, active: true, complete: true, customerNo: null }),
      sampleAccount({ id: 2, active: true, complete: true, customerNo: 'ACME' }),
      sampleAccount({ id: 3, active: false, complete: true, customerNo: 'BETA' }),
    ])
    const Page = await loadPage()
    renderPage(Page)

    await waitFor(() => expect(screen.getByText(/Ready to ship/i)).toBeTruthy())
    // Ready to ship = 2 (only active + complete count). Total = 3.
    expect(screen.getByText('2/3')).toBeTruthy()
    // Platform accounts = 1 (null customerNo). Client accounts = 2.
    const platformCard = screen.getByText(/Platform accounts/i).closest('div')
    expect(platformCard?.textContent).toContain('1')
  })
})

// ============ Role-gating matrix ============

describe('CarrierConnections · role gating', () => {
  /**
   * Documented behavior (2026-08-16): CarrierConnections does NOT gate any
   * toolbar/row action on role at the FE layer. The backend rejects
   * unauthorized POST/PUT/DELETE calls. We pin the current matrix so a
   * regression is visible; flip these expectations when a FE gate is added.
   */

  it('ADMIN: Add Account button visible in toolbar', async () => {
    mockRole = 'ADMIN'
    listAccountsMock.mockResolvedValue([sampleAccount()])
    const Page = await loadPage()
    renderPage(Page)

    await waitFor(() => expect(screen.getByText('ACCT-100')).toBeTruthy())
    expect(screen.getByRole('button', { name: /Add Account/i })).toBeTruthy()
  })

  it('USER: Add Account button still renders (frontend does not gate the toolbar)', async () => {
    mockRole = 'USER'
    listAccountsMock.mockResolvedValue([sampleAccount()])
    const Page = await loadPage()
    renderPage(Page)

    await waitFor(() => expect(screen.getByText('ACCT-100')).toBeTruthy())
    // Documented behavior: no role gate. Backend rejects if unauthorized.
    // If FE gating is added, flip to `.queryByRole(...)` + `.toBeNull()`.
    expect(screen.getByRole('button', { name: /Add Account/i })).toBeTruthy()
  })

  it('TENANT: sees only the scoped rows the backend returns (frontend just renders)', async () => {
    mockRole = 'TENANT'
    // Backend-scoped response — a single tenant-owned client account.
    listAccountsMock.mockResolvedValue([
      sampleAccount({
        id: 10,
        accountNumber: 'ACCT-T-10',
        accountName: 'Tenant UPS',
        customerNo: 'ARHDEV',
      }),
    ])
    const Page = await loadPage()
    renderPage(Page)

    await waitFor(() => expect(screen.getByText('ACCT-T-10')).toBeTruthy())
    // No other tenants leak in.
    expect(screen.queryByText('ACCT-100')).toBeNull()
    // "Showing 1 of 1 account" appears in the caption and pagination footer.
    expect(screen.getAllByText(/Showing 1 of 1/i).length).toBeGreaterThan(0)
  })
})

// ============ Negative cases ============

describe('CarrierConnections · Global · negative', () => {
  it('listAccounts rejection fires notify.apiError with the "Failed to load" fallback', async () => {
    listAccountsMock.mockRejectedValue(new Error('backend down'))
    const Page = await loadPage()
    await act(async () => {
      renderPage(Page)
    })

    await waitFor(() => expect(notifyApiErrorMock).toHaveBeenCalledTimes(1))
    // Second arg is the friendly-fallback message.
    expect(notifyApiErrorMock.mock.calls[0][1]).toMatch(/Failed to load the account book/i)
  })

  it('after a listAccounts error the table falls through to the empty state', async () => {
    listAccountsMock.mockRejectedValue(new Error('boom'))
    const Page = await loadPage()
    await act(async () => {
      renderPage(Page)
    })

    // Loading placeholder resolves — accounts stays [] — empty state renders.
    await waitFor(() => {
      expect(screen.getByText(/No accounts saved yet/i)).toBeTruthy()
    })
    // And no ghost rows leak in.
    expect(screen.queryByText('ACCT-100')).toBeNull()
  })
})
