import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, cleanup, act } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, Outlet } from 'react-router-dom'
import type { ComponentType } from 'react'

/**
 * Sprint 54 — ClientsPage row-actions slice.
 *
 * Scope: only the per-row actions in the table on `/settings/clients` —
 *   • kebab menu → Edit / Importer-Broker / Delete
 *   • the inline ACTIVE ↔ INACTIVE toggle switch (with the disable-cascade
 *     preview blocker + confirm dialog)
 *   • the busyId concurrent-click guard
 *   • the ADMIN-only Delete row-item visibility
 *
 * Every network-touching service is mocked. `notify.confirm` is mocked with a
 * per-test toggleable resolver so we can approve or reject dialogs without
 * touching the real modal implementation.
 */

// ===== Mock router navigate — hoisted so ClientsPage's import sees it =====

const mockNavigate = vi.fn()
vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual<typeof import('react-router-dom')>('react-router-dom')
  return { ...actual, useNavigate: () => mockNavigate }
})

// ===== Service mocks =====

const listClients = vi.fn()
const cascadePreview = vi.fn()
const toggleActive = vi.fn()
const deleteClient = vi.fn()
const exportClientsCsv = vi.fn()

vi.mock('../api/clientService', () => ({
  clientService: {
    listClients: (...args: unknown[]) => listClients(...args),
    cascadePreview: (...args: unknown[]) => cascadePreview(...args),
    toggleActive: (...args: unknown[]) => toggleActive(...args),
    deleteClient: (...args: unknown[]) => deleteClient(...args),
    exportClientsCsv: (...args: unknown[]) => exportClientsCsv(...args),
  },
}))

// ===== Notify mock — confirm() is toggleable per-test =====

const notifySuccess = vi.fn()
const notifyError = vi.fn()
const notifyApiError = vi.fn()
const notifyConfirm = vi.fn<(msg: string, opts?: unknown) => Promise<boolean>>()

vi.mock('../utils/notify', () => ({
  notify: {
    success: (...args: unknown[]) => notifySuccess(...args),
    error: (...args: unknown[]) => notifyError(...args),
    apiError: (...args: unknown[]) => notifyApiError(...args),
    info: vi.fn(),
    confirm: (msg: string, opts?: unknown) => notifyConfirm(msg, opts),
  },
}))

// ===== Session mock — default to ADMIN; role tests override =====

let mockRole: string = 'ADMIN'
vi.mock('../hooks/useAppSession', () => ({
  useAppSession: () => ({ username: 'admin', role: mockRole }),
  clearAuthSession: vi.fn(),
}))

// ===== CustomsProfileModal stub — we only care that it renders for the
// selected client, not its internal editor markup. Real modal pulls a bunch
// of extra services; the stub keeps the DOM small + stable.

vi.mock('./modals/CustomsProfileModal', () => ({
  default: ({ lockedClientCode, onClose }: { lockedClientCode?: string; onClose: () => void }) => (
    <div role="dialog" aria-label="Importer profile modal">
      <p>MODAL_FOR: {lockedClientCode}</p>
      <button type="button" onClick={onClose}>Close modal</button>
    </div>
  ),
}))

// ===== Test-only outlet wrapper — ClientsPage calls useOutletContext for a
// registerRefresh handler. Rendering it under a stub outlet gives it a valid
// context without pulling in the real SettingsLayout.

function Outletish() {
  return <Outlet context={{ registerRefresh: () => {} }} />
}

async function loadPage(): Promise<ComponentType> {
  const mod = await import('./ClientsPage')
  return mod.default
}

function renderList(Page: ComponentType) {
  return render(
    <MemoryRouter initialEntries={['/settings/clients']}>
      <Routes>
        <Route element={<Outletish />}>
          <Route path="/settings/clients" element={<Page />} />
        </Route>
      </Routes>
    </MemoryRouter>,
  )
}

// ===== Fixture: two clients — ACME (ACTIVE) + BETA (INACTIVE). =====

function makeClient(overrides: Record<string, unknown> = {}) {
  return {
    id: 1,
    clientCode: 'ACME',
    name: 'ACME Corp',
    email: 'ops@acme.io',
    phone: null,
    status: 'ACTIVE',
    shipFrom: { city: 'Chicago', state: 'IL', country: 'US' },
    returnAddress: null,
    returnSameAsShipFrom: true,
    defaultCurrency: 'USD',
    defaultWeightUnit: 'LB',
    defaultDimUnit: 'IN',
    timezone: 'America/Chicago',
    defaultOriginCountry: 'US',
    createdAt: '2026-08-01T00:00:00',
    updatedAt: '2026-08-01T00:00:00',
    carrierAccounts: [],
    orderCount: 0,
    ...overrides,
  }
}

const ACME = makeClient()
const BETA = makeClient({ id: 2, clientCode: 'BETA', name: 'Beta Inc', status: 'INACTIVE' })

const previewOk = {
  clientCode: 'ACME',
  pendingOrderCount: 0,
  activeCarrierAccountCount: 3,
  clientOwnedWarehouseCount: 1,
  clientWarehouseLinkCount: 2,
  clientCurrentlyActive: true,
}

beforeEach(() => {
  listClients.mockReset().mockResolvedValue({
    data: {
      content: [ACME, BETA],
      pageNumber: 0,
      pageSize: 25,
      totalElements: 2,
      totalPages: 1,
    },
  })
  cascadePreview.mockReset().mockResolvedValue({ data: previewOk })
  toggleActive.mockReset().mockResolvedValue({ data: { id: 1, clientCode: 'ACME', status: 'INACTIVE' } })
  deleteClient.mockReset().mockResolvedValue({ data: null })
  exportClientsCsv.mockReset().mockResolvedValue(undefined)
  notifySuccess.mockReset()
  notifyError.mockReset()
  notifyApiError.mockReset()
  notifyConfirm.mockReset().mockResolvedValue(true) // default: approve
  mockNavigate.mockReset()
  mockRole = 'ADMIN'
})

afterEach(() => {
  cleanup()
})

/** Row-scoped kebab click — opens the actions menu for the given code. */
async function openRowMenu(user: ReturnType<typeof userEvent.setup>, code: string) {
  // Kebab buttons all share aria-label="Row actions"; disambiguate by finding
  // the row containing the client code, then querying inside it.
  const codeCell = await screen.findByText(code)
  const row = codeCell.closest('tr')!
  // The kebab lives in the last cell of the same row.
  const kebab = row.querySelector('button[aria-label="Row actions"]') as HTMLButtonElement
  expect(kebab).toBeTruthy()
  await user.click(kebab)
}

/** Toggle switch for the row of the given client code. */
async function findRowToggle(code: string): Promise<HTMLButtonElement> {
  const codeCell = await screen.findByText(code)
  const row = codeCell.closest('tr')!
  const toggle = row.querySelector('button[role="switch"]') as HTMLButtonElement
  expect(toggle).toBeTruthy()
  return toggle
}

// ============================================================================
// Positive tests
// ============================================================================

describe('ClientsPage · row actions · positive', () => {
  it('Edit action navigates to /settings/clients/{code}', async () => {
    const Page = await loadPage()
    const user = userEvent.setup()
    renderList(Page)

    await openRowMenu(user, 'ACME')
    await user.click(await screen.findByRole('menuitem', { name: /edit/i }))

    expect(mockNavigate).toHaveBeenCalledWith('/settings/clients/ACME')
  })

  it('Importer / Broker action opens the CustomsProfile modal for the row', async () => {
    const Page = await loadPage()
    const user = userEvent.setup()
    renderList(Page)

    await openRowMenu(user, 'BETA')
    await user.click(await screen.findByRole('menuitem', { name: /importer.*broker/i }))

    // Our stub renders the client code inside so we can assert scope.
    expect(await screen.findByText('MODAL_FOR: BETA')).toBeTruthy()
  })

  it('Toggle-active on ACTIVE client (pendingOrderCount=0): preview → confirm → toggleActive', async () => {
    const Page = await loadPage()
    const user = userEvent.setup()
    renderList(Page)

    const toggle = await findRowToggle('ACME')
    await user.click(toggle)

    await waitFor(() => expect(cascadePreview).toHaveBeenCalledWith('ACME'))
    await waitFor(() => expect(notifyConfirm).toHaveBeenCalled())
    // Confirm dialog was invoked with a Deactivate title.
    const opts = notifyConfirm.mock.calls[0]?.[1] as { title?: string } | undefined
    expect(opts?.title).toMatch(/Deactivate ACME/)
    await waitFor(() => expect(toggleActive).toHaveBeenCalledWith('ACME'))
    await waitFor(() => expect(notifySuccess).toHaveBeenCalled())
  })

  it('Toggle-active on INACTIVE client: no cascade preview, direct toggleActive', async () => {
    const Page = await loadPage()
    const user = userEvent.setup()
    toggleActive.mockResolvedValue({ data: { id: 2, clientCode: 'BETA', status: 'ACTIVE' } })
    renderList(Page)

    const toggle = await findRowToggle('BETA')
    await user.click(toggle)

    await waitFor(() => expect(toggleActive).toHaveBeenCalledWith('BETA'))
    expect(cascadePreview).not.toHaveBeenCalled()
    // Re-enable path should not invoke confirm at all (per the component).
    expect(notifyConfirm).not.toHaveBeenCalled()
  })

  it('Delete action confirms then calls clientService.deleteClient', async () => {
    const Page = await loadPage()
    const user = userEvent.setup()
    renderList(Page)

    await openRowMenu(user, 'ACME')
    await user.click(await screen.findByRole('menuitem', { name: /delete/i }))

    await waitFor(() => expect(notifyConfirm).toHaveBeenCalled())
    await waitFor(() => expect(deleteClient).toHaveBeenCalledWith('ACME'))
    await waitFor(() => expect(notifySuccess).toHaveBeenCalled())
  })

  it('Successful toggleActive triggers a list refetch (reload token bump)', async () => {
    const Page = await loadPage()
    const user = userEvent.setup()
    renderList(Page)

    // Initial load call.
    await waitFor(() => expect(listClients).toHaveBeenCalledTimes(1))

    const toggle = await findRowToggle('BETA')
    await user.click(toggle)
    await waitFor(() => expect(toggleActive).toHaveBeenCalledWith('BETA'))

    // Refresh() bumps reloadToken, triggering the fetch effect again.
    await waitFor(() => expect(listClients.mock.calls.length).toBeGreaterThanOrEqual(2))
  })
})

// ============================================================================
// Negative tests
// ============================================================================

describe('ClientsPage · row actions · negative', () => {
  it('Toggle-active on ACTIVE with pendingOrderCount>0 blocks; toggleActive not called', async () => {
    const Page = await loadPage()
    const user = userEvent.setup()
    cascadePreview.mockResolvedValue({
      data: { ...previewOk, pendingOrderCount: 4 },
    })
    renderList(Page)

    const toggle = await findRowToggle('ACME')
    await user.click(toggle)

    await waitFor(() => expect(cascadePreview).toHaveBeenCalledWith('ACME'))
    await waitFor(() => expect(notifyError).toHaveBeenCalled())
    // Blocker fires an error toast mentioning the pending count.
    const msg = String(notifyError.mock.calls[0]?.[0] ?? '')
    expect(msg).toMatch(/4 pending order/i)
    expect(notifyConfirm).not.toHaveBeenCalled()
    expect(toggleActive).not.toHaveBeenCalled()
  })

  it('Delete: user cancels the confirm → deleteClient is NOT called', async () => {
    const Page = await loadPage()
    const user = userEvent.setup()
    notifyConfirm.mockResolvedValue(false) // reject
    renderList(Page)

    await openRowMenu(user, 'ACME')
    await user.click(await screen.findByRole('menuitem', { name: /delete/i }))

    await waitFor(() => expect(notifyConfirm).toHaveBeenCalled())
    // Give React a beat to settle async continuation.
    await act(async () => { await Promise.resolve() })
    expect(deleteClient).not.toHaveBeenCalled()
    expect(notifySuccess).not.toHaveBeenCalled()
  })

  it('Toggle-active on ACTIVE: user rejects confirm → toggleActive is NOT called', async () => {
    const Page = await loadPage()
    const user = userEvent.setup()
    notifyConfirm.mockResolvedValue(false)
    renderList(Page)

    const toggle = await findRowToggle('ACME')
    await user.click(toggle)

    await waitFor(() => expect(cascadePreview).toHaveBeenCalledWith('ACME'))
    await waitFor(() => expect(notifyConfirm).toHaveBeenCalled())
    await act(async () => { await Promise.resolve() })
    expect(toggleActive).not.toHaveBeenCalled()
  })

  it('busyId guard: while a toggle is in flight, the row toggle is disabled', async () => {
    const Page = await loadPage()
    const user = userEvent.setup()
    // Delay the toggle so we can observe the busy state before it resolves.
    let releaseToggle: (v: { data: unknown }) => void = () => {}
    toggleActive.mockImplementation(
      () =>
        new Promise((resolve) => {
          releaseToggle = resolve
        }),
    )
    renderList(Page)

    // BETA is INACTIVE — the enable path skips cascade + confirm and jumps
    // straight to setBusyId + toggleActive, which is exactly what we need
    // for the busy assertion.
    const toggle = await findRowToggle('BETA')
    await user.click(toggle)
    // First call fired.
    await waitFor(() => expect(toggleActive).toHaveBeenCalledTimes(1))

    // Re-query the toggle (React re-rendered with busyId=id) and assert it
    // is now disabled.
    await waitFor(async () => {
      const t = await findRowToggle('BETA')
      expect(t).toBeDisabled()
    })

    // Second click while busy: should be a no-op (still exactly one call).
    const busyToggle = await findRowToggle('BETA')
    await user.click(busyToggle)
    expect(toggleActive).toHaveBeenCalledTimes(1)

    // Release the pending toggle so React can settle before teardown.
    await act(async () => {
      releaseToggle({ data: { id: 2, clientCode: 'BETA', status: 'ACTIVE' } })
      await Promise.resolve()
    })
  })

  it('toggleActive rejects → notify.apiError is called; row still renders', async () => {
    const Page = await loadPage()
    const user = userEvent.setup()
    toggleActive.mockRejectedValue(new Error('network kaboom'))
    renderList(Page)

    const toggle = await findRowToggle('BETA')
    await user.click(toggle)

    await waitFor(() => expect(notifyApiError).toHaveBeenCalled())
    // Row is still present (component didn't unmount / crash).
    expect(screen.getByText('BETA')).toBeTruthy()
  })
})

// ============================================================================
// Cross-cutting: role gating
// ============================================================================

describe('ClientsPage · row actions · roles', () => {
  it('ADMIN role: kebab menu exposes Delete action', async () => {
    mockRole = 'ADMIN'
    const Page = await loadPage()
    const user = userEvent.setup()
    renderList(Page)

    await openRowMenu(user, 'ACME')
    expect(await screen.findByRole('menuitem', { name: /delete/i })).toBeTruthy()
    expect(screen.getByRole('menuitem', { name: /edit/i })).toBeTruthy()
    expect(screen.getByRole('menuitem', { name: /importer.*broker/i })).toBeTruthy()
  })

  it('USER role: kebab menu HIDES Delete; keeps Edit + Importer/Broker', async () => {
    mockRole = 'USER'
    const Page = await loadPage()
    const user = userEvent.setup()
    renderList(Page)

    await openRowMenu(user, 'ACME')
    expect(await screen.findByRole('menuitem', { name: /edit/i })).toBeTruthy()
    expect(screen.getByRole('menuitem', { name: /importer.*broker/i })).toBeTruthy()
    expect(screen.queryByRole('menuitem', { name: /delete/i })).toBeNull()
  })

  // Sprint 51 FE role-gating — backend @PreAuthorize on
  // POST /clients/{code}/toggle-active is hasRole('ADMIN'). Row ActiveToggle
  // now renders disabled for USER with an "Admin only" tooltip, so a
  // silent 403 → row-snap-back can't happen.
  it('USER role: row ActiveToggle is rendered but disabled with "Admin only" tooltip', async () => {
    mockRole = 'USER'
    const Page = await loadPage()
    renderList(Page)

    const toggle = await findRowToggle('ACME')
    expect(toggle).toBeDisabled()
    expect(toggle.getAttribute('title')).toBe('Admin only')
  })

  it('ADMIN role: row ActiveToggle is enabled', async () => {
    mockRole = 'ADMIN'
    const Page = await loadPage()
    renderList(Page)

    const toggle = await findRowToggle('ACME')
    expect(toggle).not.toBeDisabled()
    expect(toggle.getAttribute('title')).not.toBe('Admin only')
  })
})

// ============================================================================
// Delete cascade-preview (2026-08-17) — mirror deactivate flow
// ============================================================================

describe('ClientsPage · row actions · delete cascade preview', () => {
  it('Delete now calls cascadePreview BEFORE the confirm dialog', async () => {
    const Page = await loadPage()
    const user = userEvent.setup()
    renderList(Page)

    await openRowMenu(user, 'ACME')
    await user.click(await screen.findByRole('menuitem', { name: /delete/i }))

    // cascadePreview MUST fire first — mirror the deactivate flow.
    await waitFor(() => expect(cascadePreview).toHaveBeenCalledWith('ACME'))
    // Then the confirm dialog.
    await waitFor(() => expect(notifyConfirm).toHaveBeenCalled())
    // Then the delete.
    await waitFor(() => expect(deleteClient).toHaveBeenCalledWith('ACME'))
  })

  it('Delete confirm message includes the cascade counts + PERMANENT warning', async () => {
    cascadePreview.mockResolvedValue({
      data: {
        clientCode: 'ACME', pendingOrderCount: 0,
        activeCarrierAccountCount: 4,
        clientOwnedWarehouseCount: 2,
        clientWarehouseLinkCount: 5,
        clientCurrentlyActive: true,
      },
    })
    const Page = await loadPage()
    const user = userEvent.setup()
    renderList(Page)

    await openRowMenu(user, 'ACME')
    await user.click(await screen.findByRole('menuitem', { name: /delete/i }))

    await waitFor(() => expect(notifyConfirm).toHaveBeenCalled())
    const msg = String(notifyConfirm.mock.calls[0]?.[0] ?? '')
    // Cascade counts show up in the confirm.
    expect(msg).toMatch(/4 carrier account/i)
    expect(msg).toMatch(/2 client-owned warehouse/i)
    expect(msg).toMatch(/5 warehouse attachment/i)
    // "PERMANENTLY" wording + "cannot be undone" wording present so
    // the operator can't confuse delete with the reversible deactivate.
    expect(msg).toMatch(/PERMANENTLY/i)
    expect(msg).toMatch(/cannot be undone/i)
  })

  it('Delete BLOCKS when pendingOrderCount > 0 (no confirm shown, no delete call)', async () => {
    cascadePreview.mockResolvedValue({
      data: {
        clientCode: 'ACME', pendingOrderCount: 3,
        activeCarrierAccountCount: 1,
        clientOwnedWarehouseCount: 0,
        clientWarehouseLinkCount: 0,
        clientCurrentlyActive: true,
      },
    })
    const Page = await loadPage()
    const user = userEvent.setup()
    renderList(Page)

    await openRowMenu(user, 'ACME')
    await user.click(await screen.findByRole('menuitem', { name: /delete/i }))

    await waitFor(() => expect(cascadePreview).toHaveBeenCalled())
    await waitFor(() => expect(notifyError).toHaveBeenCalled())
    const errMsg = String(notifyError.mock.calls[0]?.[0] ?? '')
    expect(errMsg).toMatch(/3 pending order/i)
    // NO confirm dialog; NO delete call.
    expect(notifyConfirm).not.toHaveBeenCalled()
    expect(deleteClient).not.toHaveBeenCalled()
  })

  it('cascadePreview call itself failing surfaces via apiError; no delete happens', async () => {
    cascadePreview.mockRejectedValue(new Error('preview blew up'))
    const Page = await loadPage()
    const user = userEvent.setup()
    renderList(Page)

    await openRowMenu(user, 'ACME')
    await user.click(await screen.findByRole('menuitem', { name: /delete/i }))

    await waitFor(() => expect(notifyApiError).toHaveBeenCalled())
    expect(notifyConfirm).not.toHaveBeenCalled()
    expect(deleteClient).not.toHaveBeenCalled()
  })
})
