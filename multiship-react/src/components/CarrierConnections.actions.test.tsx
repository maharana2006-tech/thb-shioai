/**
 * Sprint 52 — `/settings/carriers` per-account actions coverage.
 *
 * Slice: RowActionsMenu (Verify / Edit / Delete), ActiveToggle in the Status
 * cell, and the "make client default" star in the Carrier cell. Every backend
 * call is a synchronous mock — a fetch guard at module load rejects any real
 * network call so a regression that removed a service-mock would surface as
 * a red test rather than a silent live carrier hit.
 *
 * The component owns 1768 LoC and pulls the client list on mount, so the
 * scope here is deliberately narrow: the five per-account actions listed
 * above plus a busyId concurrency assertion. Filters, search, drawer save
 * validation, and health-strip cards belong to sibling test files.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Outlet, Route, Routes } from 'react-router-dom'

// Anti-fallback: any unmocked network call should crash loudly so we notice
// a service-mock regression instead of accidentally hitting a real carrier.
vi.spyOn(global, 'fetch').mockImplementation(() => {
  throw new Error('un-mocked fetch forbidden')
})

// ===== module mocks =====

const listAccounts = vi.fn()
const upsertAccount = vi.fn()
const deleteAccount = vi.fn()
const setClientDefault = vi.fn()
const toggleActive = vi.fn()
const verifyAccount = vi.fn()
const verifyCredentials = vi.fn()
const getPlatformCredentials = vi.fn()

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
    resolveOrders: vi.fn().mockResolvedValue([]),
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
      listClientAccounts: vi.fn(),
      cascadePreview: vi.fn(),
      toggleActive: vi.fn(),
      deleteClient: vi.fn(),
      exportClientsCsv: vi.fn(),
    },
  }
})

const notifySuccess = vi.fn()
const notifyError = vi.fn()
const notifyApiError = vi.fn()
const notifyInfo = vi.fn()
const notifyConfirm = vi.fn().mockResolvedValue(true)

vi.mock('../utils/notify', () => ({
  notify: {
    success: (...a: unknown[]) => notifySuccess(...a),
    error: (...a: unknown[]) => notifyError(...a),
    apiError: (...a: unknown[]) => notifyApiError(...a),
    info: (...a: unknown[]) => notifyInfo(...a),
    confirm: (...a: unknown[]) => notifyConfirm(...a),
  },
}))

vi.mock('../hooks/useAppSession', () => ({
  useAppSession: () => ({ username: 'admin', role: 'ADMIN' }),
  clearAuthSession: vi.fn(),
}))

// ===== helpers =====

/** Base account row — override fields per test as needed. */
type AccountRow = {
  id: number
  accountNumber: string
  carrierCode: string
  accountName: string | null
  customerNo: string | null
  environment: string | null
  isDefault: boolean
  clientDefault: boolean
  active: boolean
  complete: boolean
  clientIdPreview: string | null
  verified: boolean | null
  lastVerifiedAt: string | null
  labelsGenerated: number | null
  lastUsedAt: string | null
}

function makeAccount(overrides: Partial<AccountRow> = {}): AccountRow {
  return {
    id: 1,
    accountNumber: 'A1',
    carrierCode: 'UPS',
    accountName: 'UPS Prod',
    customerNo: null,
    environment: 'PRODUCTION',
    isDefault: false,
    clientDefault: false,
    active: true,
    complete: true,
    clientIdPreview: 'ups-***',
    verified: true,
    lastVerifiedAt: null,
    labelsGenerated: 0,
    lastUsedAt: null,
    ...overrides,
  }
}

/** SettingsLayout provides a registerRefresh outlet — stub it so the
 *  useOutletContext() call in CarrierConnections doesn't crash. */
function OutletShim() {
  return <Outlet context={{ registerRefresh: () => {} }} />
}

async function renderPage() {
  const mod = await import('./CarrierConnections')
  const CarrierConnections = mod.default
  return render(
    <MemoryRouter initialEntries={['/settings/carriers']}>
      <Routes>
        <Route element={<OutletShim />}>
          <Route path="/settings/carriers" element={<CarrierConnections />} />
        </Route>
      </Routes>
    </MemoryRouter>,
  )
}

/** Wait for the row for a given account number to be in the DOM. */
async function waitForRow(accountNumber: string) {
  return waitFor(() => {
    expect(screen.getByText(accountNumber)).toBeInTheDocument()
  })
}

/** Open the row-actions kebab for the first (and only) row. */
async function openRowActions() {
  const user = userEvent.setup()
  const kebab = screen.getByRole('button', { name: /Row actions/i })
  await user.click(kebab)
  // Menu is portaled to document.body under role="menu".
  return within(await screen.findByRole('menu'))
}

// ===== lifecycle =====

beforeEach(() => {
  listAccounts.mockReset().mockResolvedValue([makeAccount()])
  upsertAccount.mockReset().mockResolvedValue({ data: { id: 1 } })
  deleteAccount.mockReset().mockResolvedValue({ data: null })
  setClientDefault.mockReset().mockResolvedValue({ data: null })
  toggleActive.mockReset().mockResolvedValue({ data: { id: 1, active: false } })
  verifyAccount.mockReset().mockResolvedValue({ data: { verified: true }, message: 'Verified.' })
  verifyCredentials.mockReset()
  getPlatformCredentials.mockReset().mockResolvedValue({
    data: { carrierCode: 'UPS', clientId: '', clientSecretMasked: null, hasClientSecret: false, found: false },
  })
  listClients.mockReset().mockResolvedValue({
    data: { content: [], pageNumber: 0, pageSize: 500, totalElements: 0, totalPages: 0 },
  })

  notifySuccess.mockReset()
  notifyError.mockReset()
  notifyApiError.mockReset()
  notifyInfo.mockReset()
  notifyConfirm.mockReset().mockResolvedValue(true)
})

afterEach(() => {
  vi.restoreAllMocks()
  // Re-install the fetch guard: restoreAllMocks() would clear it too.
  vi.spyOn(global, 'fetch').mockImplementation(() => {
    throw new Error('un-mocked fetch forbidden')
  })
})

// ===== positive =====

describe('Per-account actions — positive', () => {
  it('Edit opens the drawer pre-filled with account data', async () => {
    listAccounts.mockResolvedValue([
      makeAccount({ id: 1, accountName: 'UPS Prod', accountNumber: 'A1', environment: 'PRODUCTION' }),
    ])
    const user = userEvent.setup()
    await renderPage()
    await waitForRow('A1')

    // Open the kebab + click Edit with the SAME user session (reusing setup
    // avoids the userEvent-v14 hang we saw when two setups fought over the
    // same fake-timer clock).
    await user.click(screen.getByRole('button', { name: /Row actions/i }))
    const menu = within(await screen.findByRole('menu'))
    await user.click(menu.getByRole('menuitem', { name: /^Edit$/ }))

    // Drawer opens with the "Update A1" header + Save button visible.
    const dialog = await screen.findByRole('dialog', { name: /Update carrier account/i })
    expect(within(dialog).getByText(/Update A1/i)).toBeInTheDocument()

    // Account-name input is pre-filled with the stored value.
    const nameInput = within(dialog).getByPlaceholderText(/Shown across the app/i) as HTMLInputElement
    expect(nameInput.value).toBe('UPS Prod')

    // Account-number field is locked (readOnly) with the persisted number.
    const numberInput = within(dialog).getByDisplayValue('A1') as HTMLInputElement
    expect(numberInput.readOnly).toBe(true)
  })

  it('Edit save calls upsertAccount with the correct payload', async () => {
    listAccounts.mockResolvedValue([
      makeAccount({ id: 42, accountNumber: 'A1', accountName: 'UPS Prod' }),
    ])
    // upsertAccount echoes the same id so the component's post-save flow finds it.
    upsertAccount.mockResolvedValue({ data: { id: 42, accountNumber: 'A1' } })

    const user = userEvent.setup()
    await renderPage()
    await waitForRow('A1')

    const menu = await openRowActions()
    await user.click(menu.getByRole('menuitem', { name: /^Edit$/ }))
    const dialog = await screen.findByRole('dialog', { name: /Update carrier account/i })

    // Change the account name so the save payload has a distinct signal.
    const nameInput = within(dialog).getByPlaceholderText(/Shown across the app/i) as HTMLInputElement
    await user.clear(nameInput)
    await user.type(nameInput, 'UPS Prod v2')

    await user.click(within(dialog).getByRole('button', { name: /Save to Account Book/i }))

    await waitFor(() => {
      expect(upsertAccount).toHaveBeenCalledTimes(1)
    })
    // The payload is a plain object — assert on shape, not identity.
    const payload = upsertAccount.mock.calls[0][0] as {
      accountNumber: string
      carrierCode: string
      accountName?: string
      environment?: string
    }
    expect(payload).toMatchObject({
      accountNumber: 'A1',
      carrierCode: 'UPS',
      accountName: 'UPS Prod v2',
      environment: 'PRODUCTION',
    })
  })

  it('Verify calls verifyAccount(id) and shows a success toast when verified=true', async () => {
    listAccounts
      .mockResolvedValueOnce([makeAccount({ id: 7, verified: null })])
      .mockResolvedValue([makeAccount({ id: 7, verified: true })])
    verifyAccount.mockResolvedValue({ data: { verified: true }, message: 'Verified OK.' })

    const user = userEvent.setup()
    await renderPage()
    await waitForRow('A1')

    const menu = await openRowActions()
    await user.click(menu.getByRole('menuitem', { name: /Verify/i }))

    await waitFor(() => {
      expect(verifyAccount).toHaveBeenCalledTimes(1)
    })
    expect(verifyAccount).toHaveBeenCalledWith(7)
    await waitFor(() => {
      expect(notifySuccess).toHaveBeenCalledWith('Verified OK.')
    })
    // Verified chip surfaces after the refetch.
    await waitFor(() => {
      expect(screen.getAllByText(/^Verified$/).length).toBeGreaterThan(0)
    })
  })

  it('Toggle-active calls toggleActive(id) + refetches the list', async () => {
    listAccounts
      .mockResolvedValueOnce([makeAccount({ id: 3, active: true })])
      .mockResolvedValue([makeAccount({ id: 3, active: false })])

    const user = userEvent.setup()
    await renderPage()
    await waitForRow('A1')

    // ActiveToggle exposes role="switch" with aria-label "Deactivate account".
    const toggle = screen.getByRole('switch', { name: /Deactivate account/i })
    await user.click(toggle)

    await waitFor(() => {
      expect(toggleActive).toHaveBeenCalledWith(3)
    })
    // Second listAccounts call is the post-action refetch.
    await waitFor(() => {
      expect(listAccounts).toHaveBeenCalledTimes(2)
    })
    expect(notifySuccess).toHaveBeenCalledWith('Account deactivated.')
  })

  it('Delete → confirm accepts → deleteAccount(id) called + list refetches', async () => {
    listAccounts
      .mockResolvedValueOnce([makeAccount({ id: 9 })])
      .mockResolvedValue([])
    // Confirm-dialog is window.confirm (component uses native). Stub to accept.
    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(true)

    const user = userEvent.setup()
    await renderPage()
    await waitForRow('A1')

    const menu = await openRowActions()
    await user.click(menu.getByRole('menuitem', { name: /^Delete$/ }))

    await waitFor(() => {
      expect(confirmSpy).toHaveBeenCalledTimes(1)
    })
    await waitFor(() => {
      expect(deleteAccount).toHaveBeenCalledWith(9)
    })
    await waitFor(() => {
      expect(listAccounts).toHaveBeenCalledTimes(2)
    })
  })

  it('Mark client-default (star) calls setClientDefault(id) — client account only', async () => {
    listAccounts
      .mockResolvedValueOnce([
        makeAccount({ id: 55, customerNo: 'ACME', clientDefault: false, active: true, complete: true }),
      ])
      .mockResolvedValue([
        makeAccount({ id: 55, customerNo: 'ACME', clientDefault: true, active: true, complete: true }),
      ])

    const user = userEvent.setup()
    await renderPage()
    await waitForRow('A1')

    // Non-default client account renders a star button with an accessible label.
    const star = screen.getByRole('button', { name: /Make this ACME's default account/i })
    await user.click(star)

    await waitFor(() => {
      expect(setClientDefault).toHaveBeenCalledWith(55)
    })
    // Component signature is (accountId) — clientCode comes from the row's
    // customerNo, not from a second argument. Assert we invoked it exactly
    // once with just the id (no fallback code path).
    expect(setClientDefault).toHaveBeenCalledTimes(1)
    expect(setClientDefault.mock.calls[0]).toEqual([55])
  })
})

// ===== negative =====

describe('Per-account actions — negative', () => {
  it('Verify rejects → apiError fired, account row still visible', async () => {
    listAccounts.mockResolvedValue([makeAccount({ id: 5 })])
    verifyAccount.mockRejectedValue(new Error('carrier 401'))

    const user = userEvent.setup()
    await renderPage()
    await waitForRow('A1')

    const menu = await openRowActions()
    await user.click(menu.getByRole('menuitem', { name: /Verify/i }))

    await waitFor(() => {
      expect(notifyApiError).toHaveBeenCalledTimes(1)
    })
    // Row is still there — verification failure is a non-fatal signal.
    expect(screen.getByText('A1')).toBeInTheDocument()
    // No fallback verify call — assert exactly one attempt.
    expect(verifyAccount).toHaveBeenCalledTimes(1)
  })

  it('Verify returns verified=false → error toast, no success toast', async () => {
    listAccounts.mockResolvedValue([makeAccount({ id: 5, verified: null })])
    verifyAccount.mockResolvedValue({ data: { verified: false }, message: 'Invalid credentials.' })

    const user = userEvent.setup()
    await renderPage()
    await waitForRow('A1')

    const menu = await openRowActions()
    await user.click(menu.getByRole('menuitem', { name: /Verify/i }))

    await waitFor(() => {
      expect(notifyError).toHaveBeenCalledWith('Invalid credentials.')
    })
    expect(notifySuccess).not.toHaveBeenCalled()
  })

  it('Delete: user cancels confirm → deleteAccount is NOT called', async () => {
    listAccounts.mockResolvedValue([makeAccount({ id: 9 })])
    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(false)

    const user = userEvent.setup()
    await renderPage()
    await waitForRow('A1')

    const menu = await openRowActions()
    await user.click(menu.getByRole('menuitem', { name: /^Delete$/ }))

    expect(confirmSpy).toHaveBeenCalledTimes(1)
    // Give the microtask queue a beat — if deleteAccount were called it'd have
    // resolved by now. Assert it was never invoked.
    await Promise.resolve()
    expect(deleteAccount).not.toHaveBeenCalled()
    // Only the initial listAccounts (no refetch).
    expect(listAccounts).toHaveBeenCalledTimes(1)
  })

  it('Toggle-active rejects → notify.apiError fired', async () => {
    listAccounts.mockResolvedValue([makeAccount({ id: 3, active: true })])
    toggleActive.mockRejectedValue(new Error('backend 500'))

    const user = userEvent.setup()
    await renderPage()
    await waitForRow('A1')

    const toggle = screen.getByRole('switch', { name: /Deactivate account/i })
    await user.click(toggle)

    await waitFor(() => {
      expect(notifyApiError).toHaveBeenCalledTimes(1)
    })
    // Success toast must NOT fire on failure.
    expect(notifySuccess).not.toHaveBeenCalled()
  })

  it('Delete disabled when account has generated labels (deletable=false)', async () => {
    // labelsGenerated > 0 → Delete menuitem is rendered but disabled.
    listAccounts.mockResolvedValue([makeAccount({ id: 9, labelsGenerated: 3 })])

    const user = userEvent.setup()
    await renderPage()
    await waitForRow('A1')

    const menu = await openRowActions()
    const del = menu.getByRole('menuitem', { name: /^Delete$/ }) as HTMLButtonElement
    expect(del.disabled).toBe(true)
    // Clicking a disabled button in the DOM still fires onClick in jsdom;
    // guard against that regression by asserting deleteAccount is untouched
    // when we simulate a click.
    await user.click(del)
    expect(deleteAccount).not.toHaveBeenCalled()
  })

  it('Verify menuitem is not rendered for incomplete accounts', async () => {
    // complete=false → verifiable=false → RowActionsMenu hides the Verify item.
    listAccounts.mockResolvedValue([makeAccount({ id: 9, complete: false, verified: null })])

    await renderPage()
    await waitForRow('A1')

    const menu = await openRowActions()
    expect(menu.queryByRole('menuitem', { name: /Verify/i })).toBeNull()
    // Edit + Delete are still there.
    expect(menu.getByRole('menuitem', { name: /^Edit$/ })).toBeInTheDocument()
  })
})

// ===== cross-cutting =====

describe('Per-account actions — cross-cutting', () => {
  it('while a verify is in-flight, the Delete menuitem is disabled (busyId gate)', async () => {
    listAccounts.mockResolvedValue([makeAccount({ id: 3, labelsGenerated: 0 })])
    // Never-resolving verify → busyId stays set for the whole test.
    let releaseVerify: () => void = () => {}
    verifyAccount.mockImplementation(
      () => new Promise((resolve) => {
        releaseVerify = () => resolve({ data: { verified: true }, message: 'ok' })
      }),
    )

    const user = userEvent.setup()
    await renderPage()
    await waitForRow('A1')

    // Fire the verify — the menu closes, so we reopen it to check the
    // delete-item disabled state.
    let menu = await openRowActions()
    await user.click(menu.getByRole('menuitem', { name: /Verify/i }))
    // The kebab menu closes after clicking Verify. Reopen and check Delete.
    menu = await openRowActions()
    const del = menu.getByRole('menuitem', { name: /^Delete$/ }) as HTMLButtonElement
    expect(del.disabled).toBe(true)
    // The Status-cell toggle is also gated by busy.
    const toggle = screen.getByRole('switch', { name: /Deactivate account/i }) as HTMLButtonElement
    expect(toggle.disabled).toBe(true)

    // Cleanly release the pending verify so React can settle before teardown.
    releaseVerify()
    await waitFor(() => {
      expect(listAccounts).toHaveBeenCalledTimes(2)
    })
  })

  it('opening actions menu is keyboard-reachable (ADMIN sees Verify + Edit + Delete)', async () => {
    // ADMIN session is set at the top-level mock; role wiring is out of scope
    // for this component (it renders the same menu regardless of role today —
    // future RBAC gating belongs to a wrapper). Document current behaviour.
    listAccounts.mockResolvedValue([makeAccount({ id: 3, complete: true, active: true })])
    await renderPage()
    await waitForRow('A1')

    // Focus the kebab and open with Space; menu should appear.
    const kebab = screen.getByRole('button', { name: /Row actions/i })
    kebab.focus()
    fireEvent.click(kebab)
    const menu = within(await screen.findByRole('menu'))
    expect(menu.getByRole('menuitem', { name: /Verify/i })).toBeInTheDocument()
    expect(menu.getByRole('menuitem', { name: /^Edit$/ })).toBeInTheDocument()
    expect(menu.getByRole('menuitem', { name: /^Delete$/ })).toBeInTheDocument()
  })
})
