import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, cleanup, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes, Outlet } from 'react-router-dom'
import type { ComponentType } from 'react'

/**
 * Sprint 53 page-tests — SystemSettingsPage · shell + global.
 *
 * Scope:
 *   - Mount fires systemSettingsService.list().
 *   - Loading placeholder ('Loading…') while list is in-flight.
 *   - Error path: rejection → notify.apiError('Failed to load system settings.').
 *   - Empty state: 'No settings registered.' when list returns empty.
 *   - Populated state renders one section per setting with:
 *       * key (mono font) + description
 *       * has-value badge (FiCheck + maskedValue) when hasValue=true
 *       * 'not set' hint when hasValue=false
 *   - Deployment prerequisite banner (SECRETS_ENCRYPTION_KEY) always visible.
 *   - Role parity: page does NOT import useAppSession — mounts identically
 *     for ADMIN/USER/TENANT (backend @PreAuthorize enforces admin-only).
 *
 * Sibling slice: SystemSettingsPage.actions.test.tsx covers save flow.
 */

// ---------- Service mocks ----------

const listMock = vi.fn()
const updateMock = vi.fn()

vi.mock('../api/systemSettingsService', () => ({
  systemSettingsService: {
    list: (...args: unknown[]) => listMock(...args),
    update: (...args: unknown[]) => updateMock(...args),
  },
}))

const notifyApiErrorMock = vi.fn()
vi.mock('../utils/notify', () => ({
  notify: {
    apiError: (...args: unknown[]) => notifyApiErrorMock(...args),
    success: vi.fn(), error: vi.fn(), info: vi.fn(),
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

// ---------- Fixtures ----------

const setting = (overrides: Partial<{
  key: string, hasValue: boolean, maskedValue: string, description: string,
}> = {}) => ({
  key: overrides.key ?? 'openai.api-key',
  hasValue: overrides.hasValue ?? true,
  maskedValue: overrides.maskedValue ?? '****abcd',
  description: overrides.description ?? 'OpenAI API key used by AI-assist features.',
})

// ---------- Fail-loud fetch spy ----------

beforeEach(() => {
  vi.spyOn(globalThis, 'fetch').mockImplementation(() => {
    throw new Error('un-mocked fetch forbidden in unit tests')
  })
  ;[listMock, updateMock, notifyApiErrorMock].forEach((m) => m.mockReset())
})

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
  mockRole = 'ADMIN'
})

async function loadPage(): Promise<ComponentType> {
  const mod = await import('./SystemSettingsPage')
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

describe('SystemSettingsPage — mount + service call', () => {
  it('calls systemSettingsService.list() on mount', async () => {
    listMock.mockResolvedValue([])

    const Page = await loadPage()
    renderPage(Page)

    await waitFor(() => expect(listMock).toHaveBeenCalledTimes(1))
  })
})

// ===================== Loading placeholder =====================

describe('SystemSettingsPage — loading placeholder', () => {
  it('renders "Loading…" while list is in-flight', async () => {
    listMock.mockReturnValue(new Promise(() => {})) // never resolves

    const Page = await loadPage()
    renderPage(Page)

    expect(await screen.findByText(/^Loading…$/)).toBeInTheDocument()
  })
})

// ===================== Error path =====================

describe('SystemSettingsPage — error path', () => {
  it('list rejection → notify.apiError("Failed to load system settings.")', async () => {
    listMock.mockRejectedValue(new Error('boom'))

    const Page = await loadPage()
    renderPage(Page)

    await waitFor(() =>
      expect(notifyApiErrorMock).toHaveBeenCalledWith(expect.any(Error), 'Failed to load system settings.'),
    )
    await waitFor(() =>
      expect(screen.queryByText(/^Loading…$/)).not.toBeInTheDocument(),
    )
  })

  it('non-array response is defensively coerced to empty (Array.isArray guard)', async () => {
    // Documented defense: if the API returns non-array (e.g. an ApiResponse
    // wrapper accidentally), the page shows "No settings registered."
    listMock.mockResolvedValue({ unexpected: 'shape' })

    const Page = await loadPage()
    renderPage(Page)

    await waitFor(() =>
      expect(screen.getByText(/No settings registered/i)).toBeInTheDocument(),
    )
    expect(notifyApiErrorMock).not.toHaveBeenCalled()
  })
})

// ===================== Empty state =====================

describe('SystemSettingsPage — empty state', () => {
  it('empty list → "No settings registered."', async () => {
    listMock.mockResolvedValue([])

    const Page = await loadPage()
    renderPage(Page)

    await waitFor(() =>
      expect(screen.getByText(/No settings registered/i)).toBeInTheDocument(),
    )
  })
})

// ===================== Populated state =====================

describe('SystemSettingsPage — populated state', () => {
  it('renders one section per setting with key + description', async () => {
    listMock.mockResolvedValue([
      setting({
        key: 'openai.api-key',
        description: 'OpenAI API key used by AI-assist features.',
      }),
    ])

    const Page = await loadPage()
    renderPage(Page)

    await waitFor(() => expect(screen.getByText('openai.api-key')).toBeInTheDocument())
    expect(screen.getByText(/OpenAI API key used by AI-assist features/i)).toBeInTheDocument()
  })

  it('hasValue=true → renders the masked value chip', async () => {
    listMock.mockResolvedValue([
      setting({ hasValue: true, maskedValue: '****xyz9' }),
    ])

    const Page = await loadPage()
    renderPage(Page)

    await waitFor(() => expect(screen.getByText('****xyz9')).toBeInTheDocument())
    // The 'not set' hint should NOT appear.
    expect(screen.queryByText(/^not set$/i)).not.toBeInTheDocument()
  })

  it('hasValue=false → renders "not set" hint (no masked chip)', async () => {
    listMock.mockResolvedValue([setting({ hasValue: false, maskedValue: '' })])

    const Page = await loadPage()
    renderPage(Page)

    await waitFor(() => expect(screen.getByText(/^not set$/i)).toBeInTheDocument())
  })

  it('renders input with "Replace stored value" placeholder when hasValue=true', async () => {
    listMock.mockResolvedValue([setting({ hasValue: true })])

    const Page = await loadPage()
    renderPage(Page)

    await waitFor(() =>
      expect(screen.getByPlaceholderText(/Replace stored value/i)).toBeInTheDocument(),
    )
  })

  it('renders input with "Enter new value" placeholder when hasValue=false', async () => {
    listMock.mockResolvedValue([setting({ hasValue: false })])

    const Page = await loadPage()
    renderPage(Page)

    await waitFor(() =>
      expect(screen.getByPlaceholderText(/Enter new value/i)).toBeInTheDocument(),
    )
  })
})

// ===================== Deployment prerequisite banner =====================

describe('SystemSettingsPage — deployment banner', () => {
  it('always renders the SECRETS_ENCRYPTION_KEY banner', async () => {
    listMock.mockResolvedValue([])

    const Page = await loadPage()
    renderPage(Page)

    await waitFor(() =>
      expect(screen.getByText(/SECRETS_ENCRYPTION_KEY/i)).toBeInTheDocument(),
    )
    expect(screen.getByText(/base64-encoded 32-byte/i)).toBeInTheDocument()
  })
})

// ===================== Role parity =====================

describe('SystemSettingsPage — role parity (no FE gate)', () => {
  it.each(['ADMIN', 'USER', 'TENANT'] as const)(
    '%s mounts identically (page has NO useAppSession import; backend @PreAuthorize is admin-only)',
    async (role) => {
      mockRole = role
      listMock.mockResolvedValue([setting()])

      const Page = await loadPage()
      renderPage(Page)

      await waitFor(() => expect(listMock).toHaveBeenCalledTimes(1))
      // Page renders identically — role doesn't gate anything on the FE.
      expect(screen.getByText('openai.api-key')).toBeInTheDocument()
    },
  )
})
