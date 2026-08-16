import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, cleanup, waitFor, act } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, Outlet } from 'react-router-dom'
import type { ComponentType } from 'react'

/**
 * Sprint 53 page-tests — LabelTemplateEditorPage · actions slice.
 *
 * Scope:
 *   - Save happy (NEW mode): calls labelTemplateService.save; on success
 *     navigates to /settings/templates/<newId> (replace).
 *   - Save happy (EDIT mode): calls save with the loaded id; no navigation.
 *   - Save reject → notify.apiError; button re-enabled.
 *   - Delete happy (ADMIN + EDIT): notify.confirm accept → remove +
 *     notify.success + navigate('/settings/templates').
 *   - Delete confirm cancel → no remove call.
 *   - Delete reject → notify.apiError.
 *   - Delete non-admin path (USER + EDIT): button not visible (guard from
 *     shell slice); the code path also has an early-return notify.error
 *     — smoke that pattern via direct handler if reachable.
 */

// ---------- Service mocks ----------

const getByIdMock = vi.fn()
const listClientsMock = vi.fn()
const saveMock = vi.fn()
const removeMock = vi.fn()

vi.mock('../api/labelTemplateService', () => ({
  labelTemplateService: {
    listTemplates: vi.fn(),
    forTenant: vi.fn(),
    save: (...args: unknown[]) => saveMock(...args),
    remove: (...args: unknown[]) => removeMock(...args),
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

const notifySuccessMock = vi.fn()
const notifyApiErrorMock = vi.fn()
const notifyConfirmMock = vi.fn()
vi.mock('../utils/notify', () => ({
  notify: {
    success: (...args: unknown[]) => notifySuccessMock(...args),
    apiError: (...args: unknown[]) => notifyApiErrorMock(...args),
    confirm: (...args: unknown[]) => notifyConfirmMock(...args),
    error: vi.fn(),
    info: vi.fn(),
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

vi.mock('./LabelTemplateLayoutBuilder', () => ({ default: () => null }))

const navigateMock = vi.fn()
vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual<typeof import('react-router-dom')>('react-router-dom')
  return { ...actual, useNavigate: () => navigateMock }
})

// ---------- Fixtures ----------

const templateOf = (id: number, overrides: Partial<{
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
  ;[getByIdMock, listClientsMock, saveMock, removeMock, notifySuccessMock,
    notifyApiErrorMock, notifyConfirmMock, navigateMock].forEach((m) => m.mockReset())
  listClientsMock.mockResolvedValue({ data: { content: [] } })
  notifyConfirmMock.mockResolvedValue(true)
})

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
})

async function loadPage(): Promise<ComponentType> {
  const mod = await import('./LabelTemplateEditorPage')
  return mod.default
}

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

// ===================== Save (NEW) =====================

describe('LabelTemplateEditorPage — Save (NEW)', () => {
  it('happy: labelTemplateService.save called; on success navigate to /settings/templates/<newId>', async () => {
    saveMock.mockResolvedValue({ data: { id: 101, tenantId: null, templateType: 'PACKING_SLIP' } })
    const Page = await loadPage()
    renderAt(Page, '/settings/templates/new')
    await waitFor(() => expect(screen.getByRole('button', { name: /Save template/i })).toBeInTheDocument())

    await act(async () => {
      await userEvent.click(screen.getByRole('button', { name: /Save template/i }))
    })

    await waitFor(() => expect(saveMock).toHaveBeenCalledTimes(1))
    expect(notifySuccessMock).toHaveBeenCalledWith('Template saved.')
    // NEW → navigate replace to the new row's edit URL.
    expect(navigateMock).toHaveBeenCalledWith('/settings/templates/101', { replace: true })
  })

  it('reject → notify.apiError + Save button re-enabled', async () => {
    saveMock.mockRejectedValue(new Error('boom-save'))
    const Page = await loadPage()
    renderAt(Page, '/settings/templates/new')
    await waitFor(() => expect(screen.getByRole('button', { name: /Save template/i })).toBeInTheDocument())

    const saveBtn = screen.getByRole('button', { name: /Save template/i })
    await act(async () => { await userEvent.click(saveBtn) })

    await waitFor(() =>
      expect(notifyApiErrorMock).toHaveBeenCalledWith(expect.any(Error), 'Failed to save template.'),
    )
    // Button re-enabled after finally-block.
    await waitFor(() => expect(saveBtn).not.toBeDisabled())
  })
})

// ===================== Save (EDIT) =====================

describe('LabelTemplateEditorPage — Save (EDIT)', () => {
  it('EDIT-mode save: NO navigate on success (stays on same URL)', async () => {
    getByIdMock.mockResolvedValue(templateOf(42))
    saveMock.mockResolvedValue({ data: { id: 42, tenantId: 'ACME', templateType: 'PACKING_SLIP' } })

    const Page = await loadPage()
    renderAt(Page, '/settings/templates/42')
    await waitFor(() => expect(screen.getByRole('button', { name: /Save changes/i })).toBeInTheDocument())

    // Reset navigate (getById triggered no navigation so this is safe).
    navigateMock.mockReset()

    await act(async () => {
      await userEvent.click(screen.getByRole('button', { name: /Save changes/i }))
    })

    await waitFor(() => expect(saveMock).toHaveBeenCalledTimes(1))
    expect(notifySuccessMock).toHaveBeenCalledWith('Template saved.')
    // EDIT-mode success does NOT navigate.
    expect(navigateMock).not.toHaveBeenCalled()
  })
})

// ===================== Delete (ADMIN + EDIT) =====================

describe('LabelTemplateEditorPage — Delete (ADMIN + EDIT)', () => {
  it('confirm accept → remove + notify.success + navigate to /settings/templates', async () => {
    getByIdMock.mockResolvedValue(templateOf(42))
    notifyConfirmMock.mockResolvedValue(true)
    removeMock.mockResolvedValue({})

    const Page = await loadPage()
    renderAt(Page, '/settings/templates/42')
    await waitFor(() => expect(screen.getByRole('button', { name: /^Delete$/i })).toBeInTheDocument())
    navigateMock.mockReset()

    await act(async () => {
      await userEvent.click(screen.getByRole('button', { name: /^Delete$/i }))
    })

    await waitFor(() => expect(removeMock).toHaveBeenCalledWith(42))
    expect(notifySuccessMock).toHaveBeenCalledWith('Template deleted.')
    expect(navigateMock).toHaveBeenCalledWith('/settings/templates')
  })

  it('confirm cancel → NO remove call', async () => {
    getByIdMock.mockResolvedValue(templateOf(42))
    notifyConfirmMock.mockResolvedValue(false)

    const Page = await loadPage()
    renderAt(Page, '/settings/templates/42')
    await waitFor(() => expect(screen.getByRole('button', { name: /^Delete$/i })).toBeInTheDocument())

    await act(async () => {
      await userEvent.click(screen.getByRole('button', { name: /^Delete$/i }))
    })

    await waitFor(() => expect(notifyConfirmMock).toHaveBeenCalled())
    expect(removeMock).not.toHaveBeenCalled()
    expect(notifySuccessMock).not.toHaveBeenCalled()
  })

  it('remove rejection → notify.apiError', async () => {
    getByIdMock.mockResolvedValue(templateOf(42))
    notifyConfirmMock.mockResolvedValue(true)
    removeMock.mockRejectedValue(new Error('boom-delete'))

    const Page = await loadPage()
    renderAt(Page, '/settings/templates/42')
    await waitFor(() => expect(screen.getByRole('button', { name: /^Delete$/i })).toBeInTheDocument())

    await act(async () => {
      await userEvent.click(screen.getByRole('button', { name: /^Delete$/i }))
    })

    await waitFor(() =>
      expect(notifyApiErrorMock).toHaveBeenCalledWith(expect.any(Error), 'Failed to delete template.'),
    )
  })
})
