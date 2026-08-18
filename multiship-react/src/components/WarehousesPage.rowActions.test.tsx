import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, cleanup, act, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, Outlet } from 'react-router-dom'
import type { ComponentType } from 'react'

/**
 * Sprint 54 — WarehousesPage row-actions + WarehouseEditorModal internals
 * slice.
 *
 * Scope:
 *   Row actions (WarehousesPage)
 *     • Edit → opens WarehouseEditorModal
 *     • Toggle active → warehouseService.toggleActive + refetch + busyId guard
 *     • Delete → notify.confirm → warehouseService.deleteWarehouse + refetch
 *     • Attach clients → PLATFORM only
 *     • New warehouse button → opens editor in create mode
 *
 *   Modal internals (WarehouseEditorModal)
 *     • Create-mode / edit-mode hydration + code read-only in edit
 *     • Validation (required fields, country, zip)
 *     • Submit — createWarehouse / updateWarehouse with normalized payload
 *     • Cancel + Verify Address (addressValidationService.validate)
 *
 * Every network-touching service is mocked. `notify.confirm` is toggleable so
 * we can approve or reject dialogs without pulling the real modal in.
 */

// ===== Service mocks =====

const listWarehouses = vi.fn()
const toggleActive = vi.fn()
const createWarehouse = vi.fn()
const updateWarehouse = vi.fn()
const deleteWarehouse = vi.fn()

vi.mock('../api/warehouseService', () => ({
  warehouseService: {
    listWarehouses: (...args: unknown[]) => listWarehouses(...args),
    toggleActive: (...args: unknown[]) => toggleActive(...args),
    createWarehouse: (...args: unknown[]) => createWarehouse(...args),
    updateWarehouse: (...args: unknown[]) => updateWarehouse(...args),
    deleteWarehouse: (...args: unknown[]) => deleteWarehouse(...args),
    getWarehouse: vi.fn(),
  },
  clientWarehouseService: {
    listForClient: vi.fn().mockResolvedValue({ data: [] }),
    attach: vi.fn(),
    detach: vi.fn(),
    setDefault: vi.fn(),
  },
}))

const validateAddress = vi.fn()
vi.mock('../api/addressValidationService', () => ({
  addressValidationService: {
    validate: (...args: unknown[]) => validateAddress(...args),
  },
}))

const listClients = vi.fn().mockResolvedValue({
  data: { content: [], pageNumber: 0, pageSize: 200, totalElements: 0, totalPages: 1 },
})
vi.mock('../api/clientService', () => ({
  clientService: {
    listClients: (...args: unknown[]) => listClients(...args),
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

// The modal imports notify via '../../utils/notify' (deeper path). Vitest
// treats those as distinct specifiers, so mock both.
vi.mock('../../utils/notify', () => ({
  notify: {
    success: (...args: unknown[]) => notifySuccess(...args),
    error: (...args: unknown[]) => notifyError(...args),
    apiError: (...args: unknown[]) => notifyApiError(...args),
    info: vi.fn(),
    confirm: (msg: string, opts?: unknown) => notifyConfirm(msg, opts),
  },
}))

// ===== Session mock — default to ADMIN =====

let mockRole: string = 'ADMIN'
vi.mock('../hooks/useAppSession', () => ({
  useAppSession: () => ({ username: 'admin', role: mockRole }),
  clearAuthSession: vi.fn(),
}))

// ===== Stub the follow-up attach step so PLATFORM-create tests don't drag in
// the real (network-touching) AttachClientsStep body. We only care that the
// editor flipped into the follow-up step for PLATFORM creates.

vi.mock('./modals/AttachClientsStep', () => ({
  default: ({ warehouse, onDone }: { warehouse: { code: string }; onDone: () => void }) => (
    <div data-testid="attach-clients-step">
      <p>ATTACH_STEP_FOR: {warehouse.code}</p>
      <button type="button" onClick={onDone}>Finish attach step</button>
    </div>
  ),
}))

// Same module imported via the modals-relative path from WarehouseEditorModal.
vi.mock('./AttachClientsStep', () => ({
  default: ({ warehouse, onDone }: { warehouse: { code: string }; onDone: () => void }) => (
    <div data-testid="attach-clients-step">
      <p>ATTACH_STEP_FOR: {warehouse.code}</p>
      <button type="button" onClick={onDone}>Finish attach step</button>
    </div>
  ),
}))

// Also mock the standalone attach modal used by the "Attach clients" row
// action so we don't need to render its internal query UI.
vi.mock('./modals/AttachClientsModal', () => ({
  default: ({ warehouse, onClose }: { warehouse: { code: string }; onClose: () => void }) => (
    <div role="dialog" aria-label="Attach clients modal">
      <p>ATTACH_MODAL_FOR: {warehouse.code}</p>
      <button type="button" onClick={onClose}>Close attach modal</button>
    </div>
  ),
}))

// ===== Test-only outlet wrapper — WarehousesPage calls useOutletContext for a
// registerRefresh handler; rendering under a stub outlet keeps the real
// SettingsLayout out of the tree.

function Outletish() {
  return <Outlet context={{ registerRefresh: () => {} }} />
}

async function loadPage(): Promise<ComponentType> {
  const mod = await import('./WarehousesPage')
  return mod.default
}

function renderList(Page: ComponentType) {
  return render(
    <MemoryRouter initialEntries={['/settings/warehouses']}>
      <Routes>
        <Route element={<Outletish />}>
          <Route path="/settings/warehouses" element={<Page />} />
        </Route>
      </Routes>
    </MemoryRouter>,
  )
}

// ===== Fixtures =====

const PLAT_WH = {
  id: 1,
  code: 'PLAT1',
  name: 'Platform WH',
  ownerType: 'PLATFORM' as const,
  ownerClientCode: null,
  active: true,
  attachedClientCount: 0,
  createdAt: '2026-08-01T00:00:00',
  updatedAt: '2026-08-01T00:00:00',
  address: {
    line1: '1 Main St',
    line2: '',
    city: 'New York',
    state: 'NY',
    zip: '10001',
    country: 'US',
    phone: '5551234567',
  },
}

const CLI_WH = {
  id: 2,
  code: 'CLI1',
  name: 'Client WH',
  ownerType: 'CLIENT' as const,
  ownerClientCode: 'ACME',
  active: true,
  attachedClientCount: 0,
  createdAt: '2026-08-01T00:00:00',
  updatedAt: '2026-08-01T00:00:00',
  address: null,
}

beforeEach(() => {
  listWarehouses.mockReset().mockResolvedValue({
    data: {
      content: [PLAT_WH, CLI_WH],
      pageNumber: 0,
      pageSize: 25,
      totalElements: 2,
      totalPages: 1,
    },
  })
  toggleActive.mockReset().mockResolvedValue({ data: { id: 1, code: 'PLAT1', active: false } })
  createWarehouse.mockReset().mockResolvedValue({ data: { ...PLAT_WH, id: 3, code: 'NEWWH' } })
  updateWarehouse.mockReset().mockResolvedValue({ data: PLAT_WH })
  deleteWarehouse.mockReset().mockResolvedValue({ data: null })
  validateAddress.mockReset().mockResolvedValue({
    data: {
      carrierCode: 'UPS',
      valid: true,
      matchLevel: 'EXACT',
      classification: 'COMMERCIAL',
      suggested: null,
      warnings: [],
      message: 'ok',
    },
  })
  notifySuccess.mockReset()
  notifyError.mockReset()
  notifyApiError.mockReset()
  notifyConfirm.mockReset().mockResolvedValue(true)
  mockRole = 'ADMIN'
})

afterEach(() => {
  cleanup()
})

/** Row kebab click — disambiguates by looking up the row containing `code`. */
async function openRowMenu(user: ReturnType<typeof userEvent.setup>, code: string) {
  const codeCell = await screen.findByText(code)
  const row = codeCell.closest('tr')!
  const kebab = row.querySelector('button[aria-label="Row actions"]') as HTMLButtonElement
  expect(kebab).toBeTruthy()
  await user.click(kebab)
}

async function findRowToggle(code: string): Promise<HTMLButtonElement> {
  const codeCell = await screen.findByText(code)
  const row = codeCell.closest('tr')!
  const toggle = row.querySelector('button[role="switch"]') as HTMLButtonElement
  expect(toggle).toBeTruthy()
  return toggle
}

// ============================================================================
// Row actions — positive
// ============================================================================

describe('WarehousesPage · row actions · positive', () => {
  it('Edit action opens the WarehouseEditorModal hydrated from the row', async () => {
    const Page = await loadPage()
    const user = userEvent.setup()
    renderList(Page)

    await openRowMenu(user, 'PLAT1')
    await user.click(await screen.findByRole('menuitem', { name: /edit/i }))

    // Editor drawer opens as an aria dialog with the row's code in the title.
    const dialog = await screen.findByRole('dialog', { name: /edit warehouse plat1/i })
    expect(dialog).toBeTruthy()
  })

  it('New warehouse button opens the editor in create mode', async () => {
    const Page = await loadPage()
    const user = userEvent.setup()
    renderList(Page)

    // Wait for the initial load to render toolbar.
    await screen.findByText('PLAT1')
    await user.click(screen.getByRole('button', { name: /add warehouse/i }))

    const dialog = await screen.findByRole('dialog', { name: /add warehouse/i })
    expect(dialog).toBeTruthy()
    // Code field is empty and editable in create mode.
    const codeInput = within(dialog).getByPlaceholderText('3PL-EAST') as HTMLInputElement
    expect(codeInput.value).toBe('')
    expect(codeInput).not.toBeDisabled()
  })

  it('Attach-clients action on PLATFORM row opens the attach modal', async () => {
    const Page = await loadPage()
    const user = userEvent.setup()
    renderList(Page)

    await openRowMenu(user, 'PLAT1')
    await user.click(await screen.findByRole('menuitem', { name: /attach clients/i }))

    expect(await screen.findByText('ATTACH_MODAL_FOR: PLAT1')).toBeTruthy()
  })

  it('Attach-clients menu item is HIDDEN on CLIENT-owned rows', async () => {
    const Page = await loadPage()
    const user = userEvent.setup()
    renderList(Page)

    await openRowMenu(user, 'CLI1')
    // Menu is open; assert the attach item is not present while other items are.
    expect(await screen.findByRole('menuitem', { name: /edit/i })).toBeTruthy()
    expect(screen.queryByRole('menuitem', { name: /attach clients/i })).toBeNull()
  })

  it('Toggle-active fires warehouseService.toggleActive and refetches the list', async () => {
    const Page = await loadPage()
    const user = userEvent.setup()
    renderList(Page)

    await waitFor(() => expect(listWarehouses).toHaveBeenCalledTimes(1))

    const toggle = await findRowToggle('PLAT1')
    await user.click(toggle)

    await waitFor(() => expect(toggleActive).toHaveBeenCalledWith('PLAT1'))
    await waitFor(() => expect(notifySuccess).toHaveBeenCalled())
    // Refresh() bumps reloadToken → the listWarehouses effect refires.
    await waitFor(() => expect(listWarehouses.mock.calls.length).toBeGreaterThanOrEqual(2))
  })

  it('Delete action confirms then calls deleteWarehouse + refetches', async () => {
    const Page = await loadPage()
    const user = userEvent.setup()
    renderList(Page)

    await waitFor(() => expect(listWarehouses).toHaveBeenCalledTimes(1))

    await openRowMenu(user, 'PLAT1')
    await user.click(await screen.findByRole('menuitem', { name: /delete/i }))

    await waitFor(() => expect(notifyConfirm).toHaveBeenCalled())
    const opts = notifyConfirm.mock.calls[0]?.[1] as { title?: string; danger?: boolean } | undefined
    expect(opts?.title).toMatch(/delete warehouse/i)
    expect(opts?.danger).toBe(true)
    await waitFor(() => expect(deleteWarehouse).toHaveBeenCalledWith('PLAT1'))
    await waitFor(() => expect(notifySuccess).toHaveBeenCalled())
    await waitFor(() => expect(listWarehouses.mock.calls.length).toBeGreaterThanOrEqual(2))
  })
})

// ============================================================================
// Row actions — negative
// ============================================================================

describe('WarehousesPage · row actions · negative', () => {
  it('Delete cancel → deleteWarehouse is NOT called', async () => {
    const Page = await loadPage()
    const user = userEvent.setup()
    notifyConfirm.mockResolvedValue(false)
    renderList(Page)

    await openRowMenu(user, 'PLAT1')
    await user.click(await screen.findByRole('menuitem', { name: /delete/i }))

    await waitFor(() => expect(notifyConfirm).toHaveBeenCalled())
    await act(async () => { await Promise.resolve() })
    expect(deleteWarehouse).not.toHaveBeenCalled()
    expect(notifySuccess).not.toHaveBeenCalled()
  })

  it('toggleActive rejects → notify.apiError fires', async () => {
    const Page = await loadPage()
    const user = userEvent.setup()
    toggleActive.mockRejectedValue(new Error('network kaboom'))
    renderList(Page)

    const toggle = await findRowToggle('PLAT1')
    await user.click(toggle)

    await waitFor(() => expect(notifyApiError).toHaveBeenCalled())
    // Row still present — component didn't crash.
    expect(screen.getByText('PLAT1')).toBeTruthy()
  })

  it('busyId guard: while a toggle is in flight, the toggle is disabled and second click is no-op', async () => {
    const Page = await loadPage()
    const user = userEvent.setup()
    let releaseToggle: (v: { data: unknown }) => void = () => {}
    toggleActive.mockImplementation(
      () => new Promise((resolve) => { releaseToggle = resolve }),
    )
    renderList(Page)

    const toggle = await findRowToggle('PLAT1')
    await user.click(toggle)
    await waitFor(() => expect(toggleActive).toHaveBeenCalledTimes(1))

    // busyId flips → toggle disabled in every row (component uses busy !== null).
    await waitFor(async () => {
      const t = await findRowToggle('PLAT1')
      expect(t).toBeDisabled()
    })

    // Second click while busy — still exactly one call.
    const busyToggle = await findRowToggle('PLAT1')
    await user.click(busyToggle)
    expect(toggleActive).toHaveBeenCalledTimes(1)

    // Release for clean teardown.
    await act(async () => {
      releaseToggle({ data: { id: 1, active: false } })
      await Promise.resolve()
    })
  })
})

// ============================================================================
// Row actions — role gating (ActiveToggle)
// ============================================================================
//
// Sprint 51 FE role-gating — POST /warehouses/{code}/toggle-active is
// currently `hasAnyRole('ADMIN','USER')` on the backend, but the FE has
// been aligned with ClientsPage for consistency (both settings pages
// present the same ActiveToggle UX). This is defense-in-depth — the
// backend still owns authz.

describe('WarehousesPage · row actions · role gating', () => {
  it('USER role: row ActiveToggle is rendered but disabled with "Admin only" tooltip', async () => {
    mockRole = 'USER'
    const Page = await loadPage()
    renderList(Page)

    const toggle = await findRowToggle('PLAT1')
    expect(toggle).toBeDisabled()
    expect(toggle.getAttribute('title')).toBe('Admin only')
  })

  it('ADMIN role: row ActiveToggle is enabled', async () => {
    mockRole = 'ADMIN'
    const Page = await loadPage()
    renderList(Page)

    const toggle = await findRowToggle('PLAT1')
    expect(toggle).not.toBeDisabled()
    expect(toggle.getAttribute('title')).not.toBe('Admin only')
  })
})

// ============================================================================
// WarehouseEditorModal internals — positive
// ============================================================================

describe('WarehouseEditorModal · internals · positive', () => {
  it('Edit mode: fields hydrated from warehouse prop; code is read-only', async () => {
    const Page = await loadPage()
    const user = userEvent.setup()
    renderList(Page)

    await openRowMenu(user, 'PLAT1')
    await user.click(await screen.findByRole('menuitem', { name: /edit/i }))

    const dialog = await screen.findByRole('dialog', { name: /edit warehouse plat1/i })
    const codeInput = within(dialog).getByPlaceholderText('3PL-EAST') as HTMLInputElement
    expect(codeInput.value).toBe('PLAT1')
    expect(codeInput).toBeDisabled()

    const nameInput = within(dialog).getByPlaceholderText('East Coast fulfilment') as HTMLInputElement
    expect(nameInput.value).toBe('Platform WH')
  })

  it('Submit in edit → updateWarehouse called with the row code + normalized payload', async () => {
    const Page = await loadPage()
    const user = userEvent.setup()
    renderList(Page)

    await openRowMenu(user, 'PLAT1')
    await user.click(await screen.findByRole('menuitem', { name: /edit/i }))
    const dialog = await screen.findByRole('dialog', { name: /edit warehouse plat1/i })

    // Row fixture is already valid — clicking Save should submit immediately.
    await user.click(within(dialog).getByRole('button', { name: /save changes/i }))

    await waitFor(() => expect(updateWarehouse).toHaveBeenCalled())
    const [codeArg, payload] = updateWarehouse.mock.calls[0]
    expect(codeArg).toBe('PLAT1')
    // Normalization: country uppercased, ownerType echoed, address preserved.
    expect(payload).toMatchObject({
      code: 'PLAT1',
      name: 'Platform WH',
      ownerType: 'PLATFORM',
      active: true,
      address: expect.objectContaining({
        line1: '1 Main St',
        city: 'New York',
        state: 'NY',
        zip: '10001',
        country: 'US',
        phone: '5551234567',
      }),
    })
    await waitFor(() => expect(notifySuccess).toHaveBeenCalled())
  })

  it('Submit in create (PLATFORM) → createWarehouse called; AttachClientsStep is rendered next', async () => {
    const Page = await loadPage()
    const user = userEvent.setup()
    renderList(Page)

    await screen.findByText('PLAT1')
    await user.click(screen.getByRole('button', { name: /add warehouse/i }))
    const dialog = await screen.findByRole('dialog', { name: /add warehouse/i })

    // Fill the required fields. Use placeholders for the identity + line1
    // inputs (unique); use direct querySelectors for city / state / zip /
    // phone since they're only distinguishable by their surrounding label.
    await user.type(within(dialog).getByPlaceholderText('3PL-EAST'), 'NEWWH')
    await user.type(within(dialog).getByPlaceholderText('East Coast fulfilment'), 'Brand new WH')
    await user.type(within(dialog).getByPlaceholderText('1 Warehouse Way'), '10 Loading Dock')
    const cityEl = dialog.querySelector('input[maxlength="100"]') as HTMLInputElement
    await user.type(cityEl, 'Chicago')
    // Two inputs share maxlength=50: code (already filled) and state.
    const maxlen50 = Array.from(
      dialog.querySelectorAll('input[maxlength="50"]'),
    ) as HTMLInputElement[]
    const stateEl = maxlen50.find((el) => el.placeholder !== '3PL-EAST' && el.type !== 'tel')!
    await user.type(stateEl, 'IL')
    const zipEl = dialog.querySelector('input[maxlength="20"]') as HTMLInputElement
    await user.type(zipEl, '60601')
    // Country default is 'US'; leave it.
    const phoneEl = dialog.querySelector('input[type="tel"]') as HTMLInputElement
    await user.type(phoneEl, '5551234567')

    await user.click(within(dialog).getByRole('button', { name: /create warehouse/i }))

    await waitFor(() => expect(createWarehouse).toHaveBeenCalled())
    const payload = createWarehouse.mock.calls[0][0]
    expect(payload).toMatchObject({
      code: 'NEWWH',
      name: 'Brand new WH',
      ownerType: 'PLATFORM',
      address: expect.objectContaining({
        line1: '10 Loading Dock',
        city: 'Chicago',
        state: 'IL',
        zip: '60601',
        country: 'US',
        phone: '5551234567',
      }),
    })
    // PLATFORM create → attach step appears.
    expect(await screen.findByTestId('attach-clients-step')).toBeTruthy()
  })

  it('Cancel button in the editor fires onClose (dialog unmounts)', async () => {
    const Page = await loadPage()
    const user = userEvent.setup()
    renderList(Page)

    await openRowMenu(user, 'PLAT1')
    await user.click(await screen.findByRole('menuitem', { name: /edit/i }))
    const dialog = await screen.findByRole('dialog', { name: /edit warehouse plat1/i })

    await user.click(within(dialog).getByRole('button', { name: /cancel/i }))

    await waitFor(() =>
      expect(screen.queryByRole('dialog', { name: /edit warehouse plat1/i })).toBeNull(),
    )
  })

  it('Verify Address button calls addressValidationService.validate with the trimmed payload', async () => {
    const Page = await loadPage()
    const user = userEvent.setup()
    renderList(Page)

    await openRowMenu(user, 'PLAT1')
    await user.click(await screen.findByRole('menuitem', { name: /edit/i }))
    const dialog = await screen.findByRole('dialog', { name: /edit warehouse plat1/i })

    await user.click(within(dialog).getByRole('button', { name: /^verify$/i }))

    await waitFor(() => expect(validateAddress).toHaveBeenCalled())
    const req = validateAddress.mock.calls[0][0]
    expect(req).toMatchObject({
      carrierCode: 'UPS',
      addressLine1: '1 Main St',
      city: 'New York',
      state: 'NY',
      postalCode: '10001',
      countryCode: 'US',
    })
  })

  it('CORRECTED verify result renders a suggestion panel with a Use suggestion button', async () => {
    validateAddress.mockResolvedValue({
      data: {
        carrierCode: 'UPS',
        valid: false,
        matchLevel: 'CORRECTED',
        classification: 'COMMERCIAL',
        suggested: {
          addressLine1: '1 Main Street',
          city: 'New York',
          state: 'NY',
          postalCode: '10001-0001',
          countryCode: 'US',
        },
        warnings: [],
        message: 'Corrected by UPS',
      },
    })

    const Page = await loadPage()
    const user = userEvent.setup()
    renderList(Page)

    await openRowMenu(user, 'PLAT1')
    await user.click(await screen.findByRole('menuitem', { name: /edit/i }))
    const dialog = await screen.findByRole('dialog', { name: /edit warehouse plat1/i })

    await user.click(within(dialog).getByRole('button', { name: /^verify$/i }))
    await waitFor(() => expect(validateAddress).toHaveBeenCalled())

    // Suggested panel is present with a "Use suggestion" button.
    expect(await within(dialog).findByRole('button', { name: /use suggestion/i })).toBeTruthy()
    // CORRECTED badge shows.
    expect(within(dialog).getByText('CORRECTED')).toBeTruthy()
  })
})

// ============================================================================
// WarehouseEditorModal internals — negative
// ============================================================================

describe('WarehouseEditorModal · internals · negative', () => {
  it('Create mode: Save with an empty form surfaces errors and does NOT call createWarehouse', async () => {
    const Page = await loadPage()
    const user = userEvent.setup()
    renderList(Page)

    await screen.findByText('PLAT1')
    await user.click(screen.getByRole('button', { name: /add warehouse/i }))
    const dialog = await screen.findByRole('dialog', { name: /add warehouse/i })

    // Click Save without filling anything.
    await user.click(within(dialog).getByRole('button', { name: /create warehouse/i }))

    // touchAll() + notify.error path.
    await waitFor(() => expect(notifyError).toHaveBeenCalled())
    expect(createWarehouse).not.toHaveBeenCalled()

    // Required-field error rendered for code (touched by touchAll).
    expect(within(dialog).getByText(/warehouse code is required/i)).toBeTruthy()
  })

  it('Verify button is disabled when line1 / city / postal / country are missing', async () => {
    const Page = await loadPage()
    const user = userEvent.setup()
    renderList(Page)

    await screen.findByText('PLAT1')
    await user.click(screen.getByRole('button', { name: /add warehouse/i }))
    const dialog = await screen.findByRole('dialog', { name: /add warehouse/i })

    const verifyBtn = within(dialog).getByRole('button', { name: /^verify$/i })
    expect(verifyBtn).toBeDisabled()

    // Fill line1 only — still disabled (city / zip missing).
    await user.type(within(dialog).getByPlaceholderText('1 Warehouse Way'), '10 Loading Dock')
    expect(verifyBtn).toBeDisabled()
  })

  it('Invalid country (single letter) surfaces a country error after blur', async () => {
    const Page = await loadPage()
    const user = userEvent.setup()
    renderList(Page)

    await screen.findByText('PLAT1')
    await user.click(screen.getByRole('button', { name: /add warehouse/i }))
    const dialog = await screen.findByRole('dialog', { name: /add warehouse/i })

    // Country default is 'US' (valid). Clear it and set to 'X' — 1 letter fails validateCountry.
    const countryInput = dialog.querySelector('input[maxlength="2"]') as HTMLInputElement
    await user.clear(countryInput)
    await user.type(countryInput, 'X')
    await user.tab() // blur → touched

    // validateCountry emits a required-length message on ISO-2 mismatch.
    await waitFor(() => {
      const errs = dialog.querySelectorAll('p.text-rose-600')
      const hasCountryErr = Array.from(errs).some((el) => /country/i.test(el.textContent ?? ''))
      expect(hasCountryErr).toBe(true)
    })
  })

  it('Invalid US zip (letters) surfaces a zip error after blur', async () => {
    const Page = await loadPage()
    const user = userEvent.setup()
    renderList(Page)

    await screen.findByText('PLAT1')
    await user.click(screen.getByRole('button', { name: /add warehouse/i }))
    const dialog = await screen.findByRole('dialog', { name: /add warehouse/i })

    // Zip is the maxlength=20 input.
    const zipInput = dialog.querySelector('input[maxlength="20"]') as HTMLInputElement
    await user.type(zipInput, 'NOTAZIP')
    await user.tab() // blur

    await waitFor(() => {
      const errs = dialog.querySelectorAll('p.text-rose-600')
      const hasZipErr = Array.from(errs).some((el) =>
        /(zip|postal)/i.test(el.textContent ?? ''),
      )
      expect(hasZipErr).toBe(true)
    })
  })
})

// ============================================================================
// Audit fixes (2026-08-17) — 4.4 clear state, 2.1 disable radios, 4.2 unsaved-changes
// ============================================================================

describe('WarehouseEditorModal · audit fixes', () => {
  it('4.4: toggling owner to PLATFORM clears ownerClientCode state', async () => {
    // Seed the client picker with a real option so we can pick + verify reset.
    listClients.mockResolvedValueOnce({
      data: {
        content: [{ id: 1, clientCode: 'ACME', name: 'ACME Co', status: 'ACTIVE' }],
        pageNumber: 0, pageSize: 200, totalElements: 1, totalPages: 1,
      },
    })

    const Page = await loadPage()
    const user = userEvent.setup()
    renderList(Page)

    await screen.findByText('PLAT1')
    await user.click(screen.getByRole('button', { name: /add warehouse/i }))
    const dialog = await screen.findByRole('dialog', { name: /add warehouse/i })

    // Switch to CLIENT — client picker appears.
    const clientRadio = within(dialog).getByLabelText(/Client \(private/i)
    await user.click(clientRadio)
    // Wait for the client dropdown to load its option.
    const clientSel = await waitFor(() => {
      const sel = within(dialog).getByLabelText(/^Owner client/i) as HTMLSelectElement
      if (sel.options.length < 2) throw new Error(`only ${sel.options.length} options`)
      return sel
    })
    await user.selectOptions(clientSel, 'ACME')
    expect(clientSel.value).toBe('ACME')

    // Switch back to PLATFORM — the picker vanishes AND ownerClientCode
    // is cleared. Toggling to CLIENT again shows an empty picker (no leak).
    const platformRadio = within(dialog).getByLabelText(/Platform \(attachable/i)
    await user.click(platformRadio)
    expect(within(dialog).queryByLabelText(/^Owner client/i)).toBeNull()
    await user.click(clientRadio)
    const clientSel2 = await waitFor(() => {
      const sel = within(dialog).getByLabelText(/^Owner client/i) as HTMLSelectElement
      return sel
    })
    expect(clientSel2.value).toBe('')
  })

  it('2.1: ownership radios are DISABLED in edit mode (immutable post-create)', async () => {
    const Page = await loadPage()
    const user = userEvent.setup()
    renderList(Page)

    await openRowMenu(user, 'PLAT1')
    await user.click(await screen.findByRole('menuitem', { name: /edit/i }))
    const dialog = await screen.findByRole('dialog', { name: /edit warehouse plat1/i })

    // Both radios must be disabled.
    const platformRadio = within(dialog).getByLabelText(/Platform \(attachable/i) as HTMLInputElement
    const clientRadio = within(dialog).getByLabelText(/Client \(private/i) as HTMLInputElement
    expect(platformRadio).toBeDisabled()
    expect(clientRadio).toBeDisabled()
  })

  it('4.2: closing with dirty edits prompts for confirmation', async () => {
    const Page = await loadPage()
    const user = userEvent.setup()
    renderList(Page)

    await openRowMenu(user, 'PLAT1')
    await user.click(await screen.findByRole('menuitem', { name: /edit/i }))
    const dialog = await screen.findByRole('dialog', { name: /edit warehouse plat1/i })

    // Edit the name field — sets dirty=true.
    const nameInput = within(dialog).getByPlaceholderText('East Coast fulfilment') as HTMLInputElement
    await user.clear(nameInput)
    await user.type(nameInput, 'Changed')

    // Stub confirm to reject; Cancel should NOT close.
    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(false)
    await user.click(within(dialog).getByRole('button', { name: /^cancel$/i }))
    expect(confirmSpy).toHaveBeenCalledTimes(1)
    // Dialog still visible → onClose wasn't called.
    expect(await screen.findByRole('dialog', { name: /edit warehouse plat1/i })).toBeInTheDocument()

    // Accept the confirm → dialog closes.
    confirmSpy.mockReturnValue(true)
    await user.click(within(dialog).getByRole('button', { name: /^cancel$/i }))
    await waitFor(() => {
      expect(screen.queryByRole('dialog', { name: /edit warehouse plat1/i })).toBeNull()
    })
    confirmSpy.mockRestore()
  })

  it('4.2: closing WITHOUT dirty edits skips the confirm', async () => {
    const Page = await loadPage()
    const user = userEvent.setup()
    renderList(Page)

    await openRowMenu(user, 'PLAT1')
    await user.click(await screen.findByRole('menuitem', { name: /edit/i }))
    const dialog = await screen.findByRole('dialog', { name: /edit warehouse plat1/i })

    // No edits — Cancel closes immediately with no confirm prompt.
    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(true)
    await user.click(within(dialog).getByRole('button', { name: /^cancel$/i }))
    expect(confirmSpy).not.toHaveBeenCalled()
    confirmSpy.mockRestore()
  })
})
