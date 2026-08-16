import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, cleanup, waitFor, act } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, Outlet } from 'react-router-dom'
import type { ComponentType } from 'react'

/**
 * Sprint 53 page-tests — CustomFieldsPage · list slice.
 *
 * Scope:
 *   - Rows render: fieldKey / label / type chip / required / selectOptions
 *     / active-badge (green vs 'Inactive' rose).
 *   - Multiple rows render.
 *   - Move-up disabled on first row; move-down disabled on last row.
 *   - Empty-scope state renders 'No custom fields defined for this scope yet.'
 *   - Tenant scope switch triggers a new list call with the new tenantId.
 *
 * Sibling slices: shell, def-actions (add/save/delete), order-values.
 */

// ---------- Service mocks ----------

const listMock = vi.fn()
const saveMock = vi.fn()
const removeMock = vi.fn()
const listClientsMock = vi.fn()

vi.mock('../api/customFieldService', () => ({
  customFieldService: {
    list: (...args: unknown[]) => listMock(...args),
    applicable: vi.fn(),
    save: (...args: unknown[]) => saveMock(...args),
    remove: (...args: unknown[]) => removeMock(...args),
    values: vi.fn(),
    upsertValues: vi.fn(),
  },
}))

vi.mock('../api/clientService', () => ({
  clientService: {
    listClients: (...args: unknown[]) => listClientsMock(...args),
    getClient: vi.fn(), createClient: vi.fn(), updateClient: vi.fn(),
  },
}))

vi.mock('../utils/notify', () => ({
  notify: {
    apiError: vi.fn(), success: vi.fn(), error: vi.fn(), info: vi.fn(),
    confirm: vi.fn().mockResolvedValue(true),
  },
}))

vi.mock('../hooks/useAppSession', () => ({
  useAppSession: () => ({
    username: 'ops', role: 'ADMIN' as const,
    connectedCarriers: [], hasConnectedCarrier: false,
  }),
  clearAuthSession: vi.fn(), storeAuthSession: vi.fn(),
  bootstrapSessionFromCookie: vi.fn(), syncCarrierSession: vi.fn(),
}))

vi.mock('../api/apiClient', () => ({
  ApiError: class ApiError extends Error {},
  isAbortError: () => false,
  apiClient: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn(), patch: vi.fn() },
}))

// ---------- Fixtures ----------

const def = (id: number | undefined, overrides: Partial<{
  fieldKey: string, label: string, fieldType: 'TEXT' | 'NUMBER' | 'DATE' | 'SELECT',
  required: boolean, selectOptions: string | null, active: boolean, position: number,
  tenantId: string | null,
}> = {}) => ({
  id,
  tenantId: overrides.tenantId ?? null,
  fieldKey: overrides.fieldKey ?? 'notes',
  label: overrides.label ?? 'Notes',
  fieldType: overrides.fieldType ?? 'TEXT' as const,
  required: overrides.required ?? false,
  selectOptions: overrides.selectOptions ?? null,
  active: overrides.active ?? true,
  position: overrides.position ?? 100,
})

// ---------- Fail-loud fetch spy ----------

beforeEach(() => {
  vi.spyOn(globalThis, 'fetch').mockImplementation(() => {
    throw new Error('un-mocked fetch forbidden in unit tests')
  })
  ;[listMock, saveMock, removeMock, listClientsMock].forEach((m) => m.mockReset())
  listClientsMock.mockResolvedValue({ data: { content: [
    { clientCode: 'ACME', name: 'Acme Corp' },
    { clientCode: 'ZORP', name: 'Zorp Inc' },
  ] } })
})

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
})

async function loadPage(): Promise<ComponentType> {
  const mod = await import('./CustomFieldsPage')
  return mod.default
}

function renderPage(Page: ComponentType) {
  return render(
    <MemoryRouter>
      <Routes>
        <Route element={<Outlet context={{ registerRefresh: vi.fn() }} />}>
          <Route path="*" element={<Page />} />
        </Route>
      </Routes>
    </MemoryRouter>,
  )
}

// ===================== Per-row rendering =====================

describe('CustomFieldsPage — per-row rendering', () => {
  it('renders fieldKey + label + type chip + required indicator', async () => {
    listMock.mockResolvedValue([
      def(1, { fieldKey: 'po_number', label: 'PO Number', fieldType: 'TEXT', required: true }),
    ])

    const Page = await loadPage()
    renderPage(Page)

    await waitFor(() => expect(screen.getByText('po_number')).toBeInTheDocument())
    expect(screen.getByText('PO Number')).toBeInTheDocument()
    expect(screen.getByText('TEXT')).toBeInTheDocument()
    // 'Required' appears as both the column header (th) AND the row value (td).
    // Check that the row-value <td> is present (>= 2 total, one being the header).
    expect(screen.getAllByText('Required').length).toBeGreaterThanOrEqual(2)
  })

  it('non-required field renders "—" in the Required column', async () => {
    listMock.mockResolvedValue([def(1, { required: false })])

    const Page = await loadPage()
    renderPage(Page)

    await waitFor(() => expect(screen.getByText('notes')).toBeInTheDocument())
    // Multiple "—" may appear (in options cell too) — check at least 1.
    expect(screen.getAllByText('—').length).toBeGreaterThan(0)
  })

  it('SELECT type renders selectOptions in the Options column', async () => {
    listMock.mockResolvedValue([
      def(1, { fieldKey: 'size', fieldType: 'SELECT', selectOptions: 'S, M, L' }),
    ])

    const Page = await loadPage()
    renderPage(Page)

    await waitFor(() => expect(screen.getByText('size')).toBeInTheDocument())
    expect(screen.getByText('S, M, L')).toBeInTheDocument()
  })

  it('active=true renders green "Active" badge; active=false renders rose "Inactive"', async () => {
    listMock.mockResolvedValue([
      def(1, { fieldKey: 'yes', active: true }),
      def(2, { fieldKey: 'no', active: false }),
    ])

    const Page = await loadPage()
    renderPage(Page)

    await waitFor(() => expect(screen.getByText('yes')).toBeInTheDocument())
    // 'Active' appears as a column header AND a row badge — check ≥2.
    expect(screen.getAllByText('Active').length).toBeGreaterThanOrEqual(2)
    // 'Inactive' is only a row badge — exactly 1.
    expect(screen.getByText('Inactive')).toBeInTheDocument()
  })
})

// ===================== Multiple rows + move-order gating =====================

describe('CustomFieldsPage — multiple rows + move-order gating', () => {
  it('renders every row from the service', async () => {
    listMock.mockResolvedValue([
      def(1, { fieldKey: 'k1' }),
      def(2, { fieldKey: 'k2' }),
      def(3, { fieldKey: 'k3' }),
    ])

    const Page = await loadPage()
    renderPage(Page)

    await waitFor(() => expect(screen.getByText('k1')).toBeInTheDocument())
    expect(screen.getByText('k2')).toBeInTheDocument()
    expect(screen.getByText('k3')).toBeInTheDocument()
  })

  it('move-up disabled on first row; move-down disabled on last row', async () => {
    listMock.mockResolvedValue([
      def(1, { fieldKey: 'first' }),
      def(2, { fieldKey: 'middle' }),
      def(3, { fieldKey: 'last' }),
    ])

    const Page = await loadPage()
    renderPage(Page)
    await waitFor(() => expect(screen.getByText('first')).toBeInTheDocument())

    // 3 rows × 2 move buttons = 6 total move buttons.
    const upButtons = screen.getAllByTitle(/Move up/i)
    const downButtons = screen.getAllByTitle(/Move down/i)
    expect(upButtons).toHaveLength(3)
    expect(downButtons).toHaveLength(3)

    // First row's Move-up disabled.
    expect(upButtons[0]).toBeDisabled()
    // Last row's Move-down disabled.
    expect(downButtons[2]).toBeDisabled()
  })
})

// ===================== Empty state =====================

describe('CustomFieldsPage — empty state', () => {
  it('zero defs → renders "No custom fields defined for this scope yet."', async () => {
    listMock.mockResolvedValue([])

    const Page = await loadPage()
    renderPage(Page)

    await waitFor(() =>
      expect(screen.getByText(/No custom fields defined for this scope yet/i)).toBeInTheDocument(),
    )
  })
})

// ===================== Tenant-scope switch refetch =====================

describe('CustomFieldsPage — tenant scope switch', () => {
  it('changing tenant scope triggers a new list call with the new tenantId', async () => {
    listMock.mockResolvedValue([])

    const Page = await loadPage()
    renderPage(Page)

    // Initial call: Platform-wide → list(null).
    await waitFor(() => expect(listMock).toHaveBeenCalledWith(null))
    listMock.mockClear()

    // Switch to ACME — the tenant scope select is the first combobox.
    await act(async () => {
      await userEvent.selectOptions(screen.getAllByRole('combobox')[0], 'ACME')
    })

    // Now list is called with 'ACME'.
    await waitFor(() => expect(listMock).toHaveBeenCalledWith('ACME'))
  })
})
