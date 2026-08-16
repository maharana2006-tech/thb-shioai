import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, cleanup, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes, Outlet } from 'react-router-dom'
import type { ComponentType } from 'react'

/**
 * Sprint 53 page-tests — LabelTemplatesListPage · shell + global.
 *
 * Scope:
 *   - Mount fires listTemplates with default params (page=0, size, sort).
 *   - Loading placeholder ('Loading templates…') while listTemplates is
 *     in-flight AND rows are empty.
 *   - Error path: rejection → notify.error(msg) + loading placeholder clears +
 *     rows reset to [].
 *   - Empty state: 'No templates yet — add the platform default or a tenant
 *     override.' when no rows AND no filters/search.
 *   - Role parity: ADMIN / USER / TENANT all mount the shell identically
 *     (loading + list behavior); Add-template + Delete gates covered in
 *     sibling list-actions slice.
 *
 * Sibling slices cover row actions, editor shell/canvas/actions.
 */

// ---------- Service mocks ----------

const listTemplatesMock = vi.fn()

vi.mock('../api/labelTemplateService', () => ({
  labelTemplateService: {
    listTemplates: (...args: unknown[]) => listTemplatesMock(...args),
    forTenant: vi.fn(),
    save: vi.fn(),
    remove: vi.fn(),
    fetchPreviewObjectUrl: vi.fn(),
  },
  previewTemplateHtml: vi.fn(),
  previewTemplatePdfObjectUrl: vi.fn(),
  previewTemplateZpl: vi.fn(),
}))

const notifyErrorMock = vi.fn()
const notifyApiErrorMock = vi.fn()
vi.mock('../utils/notify', () => ({
  notify: {
    error: (...args: unknown[]) => notifyErrorMock(...args),
    apiError: (...args: unknown[]) => notifyApiErrorMock(...args),
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

// ---------- Fail-loud fetch spy ----------

beforeEach(() => {
  vi.spyOn(globalThis, 'fetch').mockImplementation(() => {
    throw new Error('un-mocked fetch forbidden in unit tests')
  })
  ;[listTemplatesMock, notifyErrorMock, notifyApiErrorMock].forEach((m) => m.mockReset())
})

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
  mockRole = 'ADMIN'
})

// ---------- Helpers ----------

const emptyPage = () => ({
  data: { content: [], totalElements: 0, totalPages: 0 },
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

// ===================== Mount + service call =====================

describe('LabelTemplatesListPage — mount + service call', () => {
  it('calls listTemplates on mount with default paging/sorting params', async () => {
    listTemplatesMock.mockResolvedValue(emptyPage())

    const Page = await loadPage()
    renderPage(Page)

    await waitFor(() => expect(listTemplatesMock).toHaveBeenCalledTimes(1))
    const args = listTemplatesMock.mock.calls[0][0]
    expect(args).toBeDefined()
    // page + size + sort default; the search/type/logo filters start unset (undefined).
    expect(args).toHaveProperty('page')
    expect(args).toHaveProperty('size')
    expect(args).toHaveProperty('sortBy')
    expect(args).toHaveProperty('sortDirection')
    expect(args.page).toBe(0)
  })
})

// ===================== Loading placeholder =====================

describe('LabelTemplatesListPage — loading placeholder', () => {
  it('renders "Loading templates…" while listTemplates is in-flight AND rows empty', async () => {
    listTemplatesMock.mockReturnValue(new Promise(() => {})) // never resolves

    const Page = await loadPage()
    renderPage(Page)

    expect(await screen.findByText(/Loading templates…/i)).toBeInTheDocument()
  })
})

// ===================== Error path =====================

describe('LabelTemplatesListPage — error path', () => {
  it('listTemplates rejection → notify.error(msg) + loading placeholder clears', async () => {
    listTemplatesMock.mockRejectedValue(new Error('boom-load'))

    const Page = await loadPage()
    renderPage(Page)

    // notify.error is called with err.message (not notify.apiError).
    await waitFor(() =>
      expect(notifyErrorMock).toHaveBeenCalledWith('boom-load'),
    )
    await waitFor(() =>
      expect(screen.queryByText(/Loading templates…/i)).not.toBeInTheDocument(),
    )
    // notify.apiError should NOT be used — the page uses .error(str) instead.
    expect(notifyApiErrorMock).not.toHaveBeenCalled()
  })

  it('non-Error rejection → generic "Failed to load templates." message', async () => {
    listTemplatesMock.mockRejectedValue('not-an-Error')

    const Page = await loadPage()
    renderPage(Page)

    await waitFor(() =>
      expect(notifyErrorMock).toHaveBeenCalledWith('Failed to load templates.'),
    )
  })
})

// ===================== Empty state =====================

describe('LabelTemplatesListPage — empty state', () => {
  it('no rows + no filters/search → shows the platform-default hint', async () => {
    listTemplatesMock.mockResolvedValue(emptyPage())

    const Page = await loadPage()
    renderPage(Page)

    await waitFor(() =>
      expect(screen.queryByText(/Loading templates…/i)).not.toBeInTheDocument(),
    )
    expect(
      screen.getByText(/No templates yet — add the platform default or a tenant override/i),
    ).toBeInTheDocument()
  })
})

// ===================== Role parity =====================

describe('LabelTemplatesListPage — role parity for shell + list load', () => {
  it.each(['ADMIN', 'USER', 'TENANT'] as const)(
    '%s mounts the shell + fires listTemplates (Add + Delete gates covered by sibling slice)',
    async (role) => {
      mockRole = role
      listTemplatesMock.mockResolvedValue(emptyPage())

      const Page = await loadPage()
      renderPage(Page)

      await waitFor(() => expect(listTemplatesMock).toHaveBeenCalledTimes(1))
      // Empty-state hint always visible for every role (no gate on the hint).
      expect(
        screen.getByText(/No templates yet — add the platform default or a tenant override/i),
      ).toBeInTheDocument()
    },
  )
})
