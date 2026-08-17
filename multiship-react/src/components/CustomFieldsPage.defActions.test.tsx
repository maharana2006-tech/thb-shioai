import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, cleanup, waitFor, act } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, Outlet } from 'react-router-dom'
import type { ComponentType } from 'react'

/**
 * Sprint 53 page-tests — CustomFieldsPage · definition-actions slice.
 *
 * Scope:
 *   - "New field" button opens editor modal in "New" mode.
 *   - Edit row action opens editor modal in "Edit" mode.
 *   - Delete row: window.confirm accept → remove + notify.success + refresh;
 *     cancel → no remove call.
 *   - Move-up / move-down: calls save with adjusted position.
 *   - Editor modal validations:
 *     * blank key / blank label → notify.error, no save call.
 *     * SELECT type without selectOptions → notify.error.
 *   - Editor save happy → notify.success + close + refresh (list re-called).
 *   - Editor save reject → notify.apiError.
 *
 * Sibling slices: shell, list, order-values (CustomFieldsSection).
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

const notifySuccessMock = vi.fn()
const notifyErrorMock = vi.fn()
const notifyApiErrorMock = vi.fn()
vi.mock('../utils/notify', () => ({
  notify: {
    success: (...args: unknown[]) => notifySuccessMock(...args),
    error: (...args: unknown[]) => notifyErrorMock(...args),
    apiError: (...args: unknown[]) => notifyApiErrorMock(...args),
    info: vi.fn(),
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

// ---------- Fail-loud fetch spy + window.confirm stub ----------

let confirmResult = true
const originalConfirm = globalThis.confirm

beforeEach(() => {
  vi.spyOn(globalThis, 'fetch').mockImplementation(() => {
    throw new Error('un-mocked fetch forbidden in unit tests')
  })
  ;[listMock, saveMock, removeMock, listClientsMock,
    notifySuccessMock, notifyErrorMock, notifyApiErrorMock].forEach((m) => m.mockReset())
  listClientsMock.mockResolvedValue({ data: { content: [] } })
  confirmResult = true
  // The Delete path uses window.confirm — stub for happy + cancel paths.
  globalThis.confirm = vi.fn(() => confirmResult)
})

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
  globalThis.confirm = originalConfirm
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

// ===================== New field / Edit row =====================

describe('CustomFieldsPage — open editor', () => {
  it('clicking "New field" opens editor modal in New mode', async () => {
    listMock.mockResolvedValue([])
    const Page = await loadPage()
    renderPage(Page)
    await waitFor(() => expect(screen.getByRole('button', { name: /New field/i })).toBeInTheDocument())

    await act(async () => {
      await userEvent.click(screen.getByRole('button', { name: /New field/i }))
    })

    expect(await screen.findByRole('heading', { name: /New custom field/i })).toBeInTheDocument()
  })

  it('clicking row Edit opens editor modal in Edit mode with the row values', async () => {
    listMock.mockResolvedValue([def(42, { fieldKey: 'notes', label: 'My Notes' })])
    const Page = await loadPage()
    renderPage(Page)
    await waitFor(() => expect(screen.getByText('notes')).toBeInTheDocument())

    await act(async () => {
      await userEvent.click(screen.getByTitle(/^Edit$/i))
    })

    expect(await screen.findByRole('heading', { name: /Edit custom field/i })).toBeInTheDocument()
    // fieldKey input pre-filled.
    expect(screen.getByDisplayValue('notes')).toBeInTheDocument()
    expect(screen.getByDisplayValue('My Notes')).toBeInTheDocument()
  })
})

// ===================== Row Delete =====================

describe('CustomFieldsPage — row Delete', () => {
  it('confirm accept → remove(id) + notify.success + refresh (list re-called)', async () => {
    listMock.mockResolvedValue([def(42, { fieldKey: 'del' })])
    confirmResult = true
    removeMock.mockResolvedValue(undefined)

    const Page = await loadPage()
    renderPage(Page)
    await waitFor(() => expect(screen.getByText('del')).toBeInTheDocument())
    const baseCalls = listMock.mock.calls.length

    await act(async () => {
      await userEvent.click(screen.getByTitle(/^Delete$/i))
    })

    await waitFor(() => expect(removeMock).toHaveBeenCalledWith(42))
    expect(notifySuccessMock).toHaveBeenCalledWith('Definition deleted.')
    // Refresh: list called again after delete.
    await waitFor(() => expect(listMock.mock.calls.length).toBeGreaterThan(baseCalls))
  })

  it('confirm cancel → NO remove call', async () => {
    listMock.mockResolvedValue([def(42, { fieldKey: 'stay' })])
    confirmResult = false

    const Page = await loadPage()
    renderPage(Page)
    await waitFor(() => expect(screen.getByText('stay')).toBeInTheDocument())

    await act(async () => {
      await userEvent.click(screen.getByTitle(/^Delete$/i))
    })

    expect(removeMock).not.toHaveBeenCalled()
  })
})

// ===================== Move-up / Move-down =====================

describe('CustomFieldsPage — Move up/down', () => {
  it('Move-down on first row calls save with position+10', async () => {
    listMock.mockResolvedValue([
      def(1, { fieldKey: 'a', position: 100 }),
      def(2, { fieldKey: 'b', position: 110 }),
    ])
    saveMock.mockResolvedValue(undefined)

    const Page = await loadPage()
    renderPage(Page)
    await waitFor(() => expect(screen.getByText('a')).toBeInTheDocument())

    const downButtons = screen.getAllByTitle(/Move down/i)
    // First row's Move-down is enabled (only last row is disabled).
    await act(async () => {
      await userEvent.click(downButtons[0])
    })

    // Save called with position bumped by +10.
    expect(saveMock).toHaveBeenCalledWith(expect.objectContaining({
      id: 1, fieldKey: 'a', position: 110,
    }))
  })

  it('Move-up on second row calls save with position-10', async () => {
    listMock.mockResolvedValue([
      def(1, { fieldKey: 'a', position: 100 }),
      def(2, { fieldKey: 'b', position: 110 }),
    ])
    saveMock.mockResolvedValue(undefined)

    const Page = await loadPage()
    renderPage(Page)
    await waitFor(() => expect(screen.getByText('b')).toBeInTheDocument())

    const upButtons = screen.getAllByTitle(/Move up/i)
    // Second row's Move-up is enabled.
    await act(async () => {
      await userEvent.click(upButtons[1])
    })

    expect(saveMock).toHaveBeenCalledWith(expect.objectContaining({
      id: 2, fieldKey: 'b', position: 100,
    }))
  })
})

// ===================== Editor modal validation =====================

describe('CustomFieldsPage — editor modal validation', () => {
  it('blank fieldKey → notify.error, save NOT called', async () => {
    listMock.mockResolvedValue([])
    const Page = await loadPage()
    renderPage(Page)
    await waitFor(() => expect(screen.getByRole('button', { name: /New field/i })).toBeInTheDocument())
    await act(async () => { await userEvent.click(screen.getByRole('button', { name: /New field/i })) })

    // New-mode modal opens with blank fields. Click Save.
    const saveBtn = await screen.findByRole('button', { name: /Save/i })
    await act(async () => { await userEvent.click(saveBtn) })

    expect(notifyErrorMock).toHaveBeenCalledWith('Key and label are both required.')
    expect(saveMock).not.toHaveBeenCalled()
  })

  it('SELECT type without selectOptions → notify.error', async () => {
    listMock.mockResolvedValue([])
    const Page = await loadPage()
    renderPage(Page)
    await waitFor(() => expect(screen.getByRole('button', { name: /New field/i })).toBeInTheDocument())
    await act(async () => { await userEvent.click(screen.getByRole('button', { name: /New field/i })) })

    // Fill fieldKey + label + set type to SELECT (no options).
    const keyInput = await screen.findByPlaceholderText(/po_number/i)
    await userEvent.type(keyInput, 'my_select')
    const labelInput = screen.getByPlaceholderText(/PO Number/i)
    await userEvent.type(labelInput, 'My Select')
    // Change type dropdown to SELECT — find by label 'Type' or by combobox order.
    const combos = screen.getAllByRole('combobox')
    // In the modal, first combobox is Type; find it by option contents.
    const typeCombo = combos.find((c) => Array.from(c.querySelectorAll('option'))
        .some((o) => (o as HTMLOptionElement).value === 'SELECT'))
    expect(typeCombo).toBeDefined()
    await userEvent.selectOptions(typeCombo!, 'SELECT')

    await act(async () => {
      await userEvent.click(screen.getByRole('button', { name: /Save/i }))
    })

    expect(notifyErrorMock).toHaveBeenCalledWith('SELECT fields need comma-separated options.')
    expect(saveMock).not.toHaveBeenCalled()
  })
})

// ===================== Editor save happy + reject =====================

describe('CustomFieldsPage — editor save flow', () => {
  it('valid save → customFieldService.save + notify.success + close + refresh', async () => {
    listMock.mockResolvedValue([])
    saveMock.mockResolvedValue({ id: 101 })
    const Page = await loadPage()
    renderPage(Page)
    await waitFor(() => expect(screen.getByRole('button', { name: /New field/i })).toBeInTheDocument())
    const baseCalls = listMock.mock.calls.length
    await act(async () => { await userEvent.click(screen.getByRole('button', { name: /New field/i })) })

    await userEvent.type(await screen.findByPlaceholderText(/po_number/i), 'notes')
    await userEvent.type(screen.getByPlaceholderText(/PO Number/i), 'Notes')

    await act(async () => {
      await userEvent.click(screen.getByRole('button', { name: /Save/i }))
    })

    await waitFor(() => expect(saveMock).toHaveBeenCalledTimes(1))
    expect(notifySuccessMock).toHaveBeenCalledWith('Definition saved.')
    // Modal closes → New custom field heading gone.
    await waitFor(() =>
      expect(screen.queryByRole('heading', { name: /New custom field/i })).not.toBeInTheDocument(),
    )
    // Refresh: list called again.
    await waitFor(() => expect(listMock.mock.calls.length).toBeGreaterThan(baseCalls))
  })

  it('save reject → notify.apiError("Failed to save.") + modal stays open', async () => {
    listMock.mockResolvedValue([])
    saveMock.mockRejectedValue(new Error('boom-save'))
    const Page = await loadPage()
    renderPage(Page)
    await waitFor(() => expect(screen.getByRole('button', { name: /New field/i })).toBeInTheDocument())
    await act(async () => { await userEvent.click(screen.getByRole('button', { name: /New field/i })) })

    await userEvent.type(await screen.findByPlaceholderText(/po_number/i), 'notes')
    await userEvent.type(screen.getByPlaceholderText(/PO Number/i), 'Notes')

    await act(async () => {
      await userEvent.click(screen.getByRole('button', { name: /Save/i }))
    })

    await waitFor(() =>
      expect(notifyApiErrorMock).toHaveBeenCalledWith(expect.any(Error), 'Failed to save.'),
    )
    // Modal stays open.
    expect(screen.getByRole('heading', { name: /New custom field/i })).toBeInTheDocument()
  })
})
