import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, cleanup, waitFor, act } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, Outlet } from 'react-router-dom'
import type { ComponentType } from 'react'

/**
 * Sprint 53 page-tests — LabelTemplatesListPage · list-actions slice.
 *
 * Scope:
 *   - Row menu: Edit + Preview always visible; Delete visible ADMIN-only.
 *   - Edit → navigate to /settings/templates/:id.
 *   - Delete non-admin → notify.error early return (no service call).
 *   - Delete ADMIN: confirm accept → remove + notify.success + reload;
 *     cancel → no call; reject → notify.error(err.message).
 *   - Preview open → modal renders with order-no input.
 *   - Preview run empty → notify.error('Enter an order number to preview.').
 *   - Preview run happy → fetchPreviewObjectUrl called with orderNo.
 *
 * Toolbar Add-template navigation covered here too.
 */

// ---------- Service mocks ----------

const listTemplatesMock = vi.fn()
const removeMock = vi.fn()
const fetchPreviewMock = vi.fn()

vi.mock('../api/labelTemplateService', () => ({
  labelTemplateService: {
    listTemplates: (...args: unknown[]) => listTemplatesMock(...args),
    forTenant: vi.fn(),
    save: vi.fn(),
    remove: (...args: unknown[]) => removeMock(...args),
    fetchPreviewObjectUrl: (...args: unknown[]) => fetchPreviewMock(...args),
  },
  previewTemplateHtml: vi.fn(),
  previewTemplatePdfObjectUrl: vi.fn(),
  previewTemplateZpl: vi.fn(),
}))

const notifyErrorMock = vi.fn()
const notifySuccessMock = vi.fn()
const notifyConfirmMock = vi.fn()
vi.mock('../utils/notify', () => ({
  notify: {
    error: (...args: unknown[]) => notifyErrorMock(...args),
    success: (...args: unknown[]) => notifySuccessMock(...args),
    confirm: (...args: unknown[]) => notifyConfirmMock(...args),
    apiError: vi.fn(),
    info: vi.fn(),
  },
}))

let mockRole: 'ADMIN' | 'USER' | 'TENANT' = 'ADMIN'
vi.mock('../hooks/useAppSession', () => ({
  useAppSession: () => ({
    username: 'ops', role: mockRole,
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

// Router navigate mock so we can assert Edit + Add-template navigation.
const navigateMock = vi.fn()
vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual<typeof import('react-router-dom')>('react-router-dom')
  return {
    ...actual,
    useNavigate: () => navigateMock,
  }
})

// ---------- Fixtures ----------

const template = (id: number, overrides: Partial<{
  tenantId: string | null, templateType: string, headerText: string, footerText: string,
}> = {}) => ({
  id,
  tenantId: overrides.tenantId ?? 'ACME',
  templateType: overrides.templateType ?? 'PACKING_SLIP',
  headerText: overrides.headerText ?? 'Hdr',
  footerText: overrides.footerText ?? 'Ftr',
})

const pageWith = (templates: ReturnType<typeof template>[]) => ({
  data: { content: templates, totalElements: templates.length, totalPages: 1 },
})

// ---------- Fail-loud fetch spy ----------

beforeEach(() => {
  vi.spyOn(globalThis, 'fetch').mockImplementation(() => {
    throw new Error('un-mocked fetch forbidden in unit tests')
  })
  ;[listTemplatesMock, removeMock, fetchPreviewMock,
    notifyErrorMock, notifySuccessMock, notifyConfirmMock, navigateMock]
    .forEach((m) => m.mockReset())
  notifyConfirmMock.mockResolvedValue(true)
})

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
  mockRole = 'ADMIN'
})

async function loadPage(): Promise<ComponentType> {
  const mod = await import('./LabelTemplatesListPage')
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

async function openRowMenu() {
  await act(async () => {
    await userEvent.click(screen.getByRole('button', { name: /Row actions/i }))
  })
}

// ===================== Add template navigation =====================

describe('LabelTemplatesListPage — Add template', () => {
  it('clicking Add template navigates to /settings/templates/new', async () => {
    listTemplatesMock.mockResolvedValue(pageWith([]))
    const Page = await loadPage()
    renderPage(Page)
    await waitFor(() => expect(screen.getByRole('button', { name: /Add template/i })).toBeInTheDocument())

    await act(async () => {
      await userEvent.click(screen.getByRole('button', { name: /Add template/i }))
    })

    expect(navigateMock).toHaveBeenCalledWith('/settings/templates/new')
  })
})

// ===================== Row menu visibility =====================

describe('LabelTemplatesListPage — row menu visibility', () => {
  it('ADMIN sees Edit + Preview + Delete', async () => {
    mockRole = 'ADMIN'
    listTemplatesMock.mockResolvedValue(pageWith([template(42)]))
    const Page = await loadPage()
    renderPage(Page)
    await waitFor(() => expect(screen.getByText('ACME')).toBeInTheDocument())

    await openRowMenu()
    expect(await screen.findByRole('menuitem', { name: /Edit/i })).toBeInTheDocument()
    expect(screen.getByRole('menuitem', { name: /Preview/i })).toBeInTheDocument()
    expect(screen.getByRole('menuitem', { name: /Delete/i })).toBeInTheDocument()
  })

  it('USER sees Edit + Preview but NO Delete', async () => {
    mockRole = 'USER'
    listTemplatesMock.mockResolvedValue(pageWith([template(42)]))
    const Page = await loadPage()
    renderPage(Page)
    await waitFor(() => expect(screen.getByText('ACME')).toBeInTheDocument())

    await openRowMenu()
    expect(await screen.findByRole('menuitem', { name: /Edit/i })).toBeInTheDocument()
    expect(screen.getByRole('menuitem', { name: /Preview/i })).toBeInTheDocument()
    expect(screen.queryByRole('menuitem', { name: /Delete/i })).not.toBeInTheDocument()
  })

  it('TENANT sees Edit + Preview but NO Delete', async () => {
    mockRole = 'TENANT'
    listTemplatesMock.mockResolvedValue(pageWith([template(42)]))
    const Page = await loadPage()
    renderPage(Page)
    await waitFor(() => expect(screen.getByText('ACME')).toBeInTheDocument())

    await openRowMenu()
    expect(screen.queryByRole('menuitem', { name: /Delete/i })).not.toBeInTheDocument()
  })
})

// ===================== Edit navigation =====================

describe('LabelTemplatesListPage — row Edit', () => {
  it('clicking Edit navigates to /settings/templates/:id', async () => {
    listTemplatesMock.mockResolvedValue(pageWith([template(42)]))
    const Page = await loadPage()
    renderPage(Page)
    await waitFor(() => expect(screen.getByText('ACME')).toBeInTheDocument())

    await openRowMenu()
    await act(async () => {
      await userEvent.click(await screen.findByRole('menuitem', { name: /Edit/i }))
    })

    expect(navigateMock).toHaveBeenCalledWith('/settings/templates/42')
  })
})

// ===================== Delete =====================

describe('LabelTemplatesListPage — row Delete', () => {
  it('ADMIN confirm accept → remove(id) + notify.success + reload (listTemplates re-called)', async () => {
    mockRole = 'ADMIN'
    listTemplatesMock.mockResolvedValue(pageWith([template(42)]))
    notifyConfirmMock.mockResolvedValue(true)
    removeMock.mockResolvedValue({})

    const Page = await loadPage()
    renderPage(Page)
    await waitFor(() => expect(screen.getByText('ACME')).toBeInTheDocument())
    const baseCalls = listTemplatesMock.mock.calls.length

    await openRowMenu()
    await act(async () => {
      await userEvent.click(await screen.findByRole('menuitem', { name: /Delete/i }))
    })

    await waitFor(() => expect(removeMock).toHaveBeenCalledWith(42))
    expect(notifySuccessMock).toHaveBeenCalledWith('Template deleted.')
    await waitFor(() => expect(listTemplatesMock.mock.calls.length).toBeGreaterThan(baseCalls))
  })

  it('ADMIN confirm cancel → NO remove call', async () => {
    mockRole = 'ADMIN'
    listTemplatesMock.mockResolvedValue(pageWith([template(42)]))
    notifyConfirmMock.mockResolvedValue(false)

    const Page = await loadPage()
    renderPage(Page)
    await waitFor(() => expect(screen.getByText('ACME')).toBeInTheDocument())

    await openRowMenu()
    await act(async () => {
      await userEvent.click(await screen.findByRole('menuitem', { name: /Delete/i }))
    })

    await waitFor(() => expect(notifyConfirmMock).toHaveBeenCalled())
    expect(removeMock).not.toHaveBeenCalled()
    expect(notifySuccessMock).not.toHaveBeenCalled()
  })

  it('ADMIN remove rejection → notify.error(err.message)', async () => {
    mockRole = 'ADMIN'
    listTemplatesMock.mockResolvedValue(pageWith([template(42)]))
    notifyConfirmMock.mockResolvedValue(true)
    removeMock.mockRejectedValue(new Error('boom-delete'))

    const Page = await loadPage()
    renderPage(Page)
    await waitFor(() => expect(screen.getByText('ACME')).toBeInTheDocument())

    await openRowMenu()
    await act(async () => {
      await userEvent.click(await screen.findByRole('menuitem', { name: /Delete/i }))
    })

    await waitFor(() => expect(notifyErrorMock).toHaveBeenCalledWith('boom-delete'))
  })
})

// ===================== Preview =====================

describe('LabelTemplatesListPage — Preview', () => {
  it('clicking Preview opens the modal with an order-no input', async () => {
    listTemplatesMock.mockResolvedValue(pageWith([template(42, { tenantId: 'ACME' })]))
    const Page = await loadPage()
    renderPage(Page)
    await waitFor(() => expect(screen.getByText('ACME')).toBeInTheDocument())

    await openRowMenu()
    await act(async () => {
      await userEvent.click(await screen.findByRole('menuitem', { name: /Preview/i }))
    })

    // Preview modal opens — order-no input visible.
    expect(await screen.findByPlaceholderText(/order/i)).toBeInTheDocument()
  })

  it('Preview with empty order-no → notify.error("Enter an order number to preview.")', async () => {
    listTemplatesMock.mockResolvedValue(pageWith([template(42)]))
    const Page = await loadPage()
    renderPage(Page)
    await waitFor(() => expect(screen.getByText('ACME')).toBeInTheDocument())

    await openRowMenu()
    await act(async () => {
      await userEvent.click(await screen.findByRole('menuitem', { name: /Preview/i }))
    })

    // Find the Preview submit button inside the modal (not the row menu item).
    const runBtn = await screen.findByRole('button', { name: /^Preview$/i })
    await act(async () => { await userEvent.click(runBtn) })

    expect(notifyErrorMock).toHaveBeenCalledWith('Enter an order number to preview.')
    expect(fetchPreviewMock).not.toHaveBeenCalled()
  })

  it('Preview with valid order-no → fetchPreviewObjectUrl called with orderNo', async () => {
    listTemplatesMock.mockResolvedValue(pageWith([template(42)]))
    fetchPreviewMock.mockResolvedValue('blob:http://x/y')

    const Page = await loadPage()
    renderPage(Page)
    await waitFor(() => expect(screen.getByText('ACME')).toBeInTheDocument())

    await openRowMenu()
    await act(async () => {
      await userEvent.click(await screen.findByRole('menuitem', { name: /Preview/i }))
    })

    await userEvent.type(await screen.findByPlaceholderText(/order/i), '12345')
    const runBtn = screen.getByRole('button', { name: /^Preview$/i })
    await act(async () => { await userEvent.click(runBtn) })

    await waitFor(() => expect(fetchPreviewMock).toHaveBeenCalledWith('12345'))
  })
})
