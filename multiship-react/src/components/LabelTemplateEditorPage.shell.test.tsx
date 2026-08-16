import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, cleanup, waitFor, act } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, Outlet } from 'react-router-dom'
import type { ComponentType } from 'react'

/**
 * Sprint 53 page-tests — LabelTemplateEditorPage · shell + global.
 *
 * Scope:
 *   - NEW mode (no id): mounts blank, title 'New template', no getById call.
 *   - EDIT mode (with id): calls getById, title 'Edit template #<id>'.
 *   - Loading spinner visible during getById.
 *   - Load error → notify.apiError + navigate back to /settings/templates.
 *   - clientService.listClients called on mount (for tenant picker).
 *   - Back button navigates to /settings/templates.
 *   - Role gates: Delete button visible ONLY for isEdit && admin
 *     (USER + TENANT never see it, even in edit mode).
 *
 * Sibling slices cover canvas/panels + editor-actions (save/delete/preview).
 */

// ---------- Service mocks ----------

const getByIdMock = vi.fn()
const listClientsMock = vi.fn()

vi.mock('../api/labelTemplateService', () => ({
  labelTemplateService: {
    listTemplates: vi.fn(),
    forTenant: vi.fn(),
    save: vi.fn(),
    remove: vi.fn(),
    getById: (...args: unknown[]) => getByIdMock(...args),
    fetchPreviewObjectUrl: vi.fn(),
  },
  previewTemplateHtml: vi.fn().mockResolvedValue('<html/>'),
  previewTemplatePdfObjectUrl: vi.fn(),
  previewTemplateZpl: vi.fn(),
}))

vi.mock('../api/clientService', () => ({
  clientService: {
    listClients: (...args: unknown[]) => listClientsMock(...args),
    getClient: vi.fn(), createClient: vi.fn(), updateClient: vi.fn(),
  },
}))

const notifyApiErrorMock = vi.fn()
const notifyErrorMock = vi.fn()
vi.mock('../utils/notify', () => ({
  notify: {
    apiError: (...args: unknown[]) => notifyApiErrorMock(...args),
    error: (...args: unknown[]) => notifyErrorMock(...args),
    success: vi.fn(),
    info: vi.fn(),
    confirm: vi.fn().mockResolvedValue(true),
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
  tenantId: string | null, templateType: string, headerText: string,
}> = {}) => ({
  data: {
    id,
    tenantId: overrides.tenantId ?? 'ACME',
    templateType: overrides.templateType ?? 'PACKING_SLIP',
    headerText: overrides.headerText ?? 'Hdr',
    footerText: 'Ftr',
    showItems: true,
  },
})

// ---------- Fail-loud fetch spy ----------

beforeEach(() => {
  vi.spyOn(globalThis, 'fetch').mockImplementation(() => {
    throw new Error('un-mocked fetch forbidden in unit tests')
  })
  ;[getByIdMock, listClientsMock, notifyApiErrorMock, notifyErrorMock, navigateMock]
    .forEach((m) => m.mockReset())
  listClientsMock.mockResolvedValue({ data: { content: [] } })
})

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
  mockRole = 'ADMIN'
})

async function loadPage(): Promise<ComponentType> {
  const mod = await import('./LabelTemplateEditorPage')
  return mod.default
}

/**
 * Editor page reads `useParams().id` — MemoryRouter with a route pattern
 * lets us drive the id param via the URL.
 */
function renderAt(Page: ComponentType, path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route element={<Outlet context={{ registerRefresh: vi.fn() }} />}>
          <Route path="/settings/templates/new" element={<Page />} />
          <Route path="/settings/templates/:id" element={<Page />} />
        </Route>
      </Routes>
    </MemoryRouter>,
  )
}

// ===================== NEW mode =====================

describe('LabelTemplateEditorPage — NEW mode', () => {
  it('mounts without an id: title "New template" + no getById call', async () => {
    const Page = await loadPage()
    renderAt(Page, '/settings/templates/new')

    await waitFor(() =>
      expect(screen.getByRole('heading', { name: /New template/i })).toBeInTheDocument(),
    )
    expect(getByIdMock).not.toHaveBeenCalled()
    // Client picker still fetches for the tenant dropdown.
    expect(listClientsMock).toHaveBeenCalledTimes(1)
  })
})

// ===================== EDIT mode =====================

describe('LabelTemplateEditorPage — EDIT mode', () => {
  it('mounts with :id → calls getById, title "Edit template #<id>"', async () => {
    getByIdMock.mockResolvedValue(template(42))
    const Page = await loadPage()
    renderAt(Page, '/settings/templates/42')

    await waitFor(() => expect(getByIdMock).toHaveBeenCalledWith(42))
    await waitFor(() =>
      expect(screen.getByRole('heading', { name: /Edit template #42/i })).toBeInTheDocument(),
    )
  })

  it('load error → notify.apiError + navigate back to /settings/templates', async () => {
    getByIdMock.mockRejectedValue(new Error('boom-load'))
    const Page = await loadPage()
    renderAt(Page, '/settings/templates/42')

    await waitFor(() =>
      expect(notifyApiErrorMock).toHaveBeenCalledWith(expect.any(Error), 'Failed to load template.'),
    )
    expect(navigateMock).toHaveBeenCalledWith('/settings/templates')
  })
})

// ===================== Back button =====================

describe('LabelTemplateEditorPage — Back button', () => {
  it('clicking Back navigates to /settings/templates', async () => {
    const Page = await loadPage()
    renderAt(Page, '/settings/templates/new')

    await waitFor(() =>
      expect(screen.getByRole('heading', { name: /New template/i })).toBeInTheDocument(),
    )

    // Back button — has title "Back to templates".
    await act(async () => {
      await userEvent.click(screen.getByTitle(/Back to templates/i))
    })

    expect(navigateMock).toHaveBeenCalledWith('/settings/templates')
  })
})

// ===================== Save button always visible =====================

describe('LabelTemplateEditorPage — Save button', () => {
  it.each(['ADMIN', 'USER', 'TENANT'] as const)(
    '%s sees the Save button (button-name is "Save template" in NEW mode)',
    async (role) => {
      mockRole = role
      const Page = await loadPage()
      renderAt(Page, '/settings/templates/new')

      await waitFor(() =>
        expect(screen.getByRole('button', { name: /Save template/i })).toBeInTheDocument(),
      )
    },
  )

  it('EDIT mode Save button reads "Save changes" (not "Save template")', async () => {
    getByIdMock.mockResolvedValue(template(42))
    const Page = await loadPage()
    renderAt(Page, '/settings/templates/42')

    await waitFor(() =>
      expect(screen.getByRole('button', { name: /Save changes/i })).toBeInTheDocument(),
    )
  })
})

// ===================== Delete role gate (isEdit && admin) =====================

describe('LabelTemplateEditorPage — Delete role gate', () => {
  it('ADMIN + EDIT mode: Delete button visible in header', async () => {
    mockRole = 'ADMIN'
    getByIdMock.mockResolvedValue(template(42))
    const Page = await loadPage()
    renderAt(Page, '/settings/templates/42')

    await waitFor(() =>
      expect(screen.getByRole('button', { name: /^Delete$/i })).toBeInTheDocument(),
    )
  })

  it('USER + EDIT mode: Delete button NOT visible', async () => {
    mockRole = 'USER'
    getByIdMock.mockResolvedValue(template(42))
    const Page = await loadPage()
    renderAt(Page, '/settings/templates/42')

    await waitFor(() =>
      expect(screen.getByRole('heading', { name: /Edit template/i })).toBeInTheDocument(),
    )
    expect(screen.queryByRole('button', { name: /^Delete$/i })).not.toBeInTheDocument()
  })

  it('ADMIN + NEW mode: Delete button NOT visible (nothing to delete yet)', async () => {
    mockRole = 'ADMIN'
    const Page = await loadPage()
    renderAt(Page, '/settings/templates/new')

    await waitFor(() =>
      expect(screen.getByRole('heading', { name: /New template/i })).toBeInTheDocument(),
    )
    expect(screen.queryByRole('button', { name: /^Delete$/i })).not.toBeInTheDocument()
  })
})
