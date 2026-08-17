import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, cleanup, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes, Outlet } from 'react-router-dom'
import type { ComponentType } from 'react'

/**
 * Sprint 53 page-tests — CustomFieldsPage · shell + global.
 *
 * Scope:
 *   - Mount fires customFieldService.list(null) [Platform-wide default]
 *     + clientService.listClients on mount.
 *   - Loading placeholder ('Loading definitions…') while list is in-flight.
 *   - Error path: rejection → notify.apiError('Failed to load definitions.').
 *   - Empty state: zero defs after load renders the tenant scope + no
 *     definition rows.
 *   - Role parity: page does NOT import useAppSession — mounts identically
 *     for ADMIN / USER / TENANT.
 *
 * Sibling slices: list rendering + definition actions + order-value actions.
 */

// ---------- Service mocks ----------

const listMock = vi.fn()
const applicableMock = vi.fn()
const saveMock = vi.fn()
const removeMock = vi.fn()
const valuesMock = vi.fn()
const upsertValuesMock = vi.fn()
const listClientsMock = vi.fn()

vi.mock('../api/customFieldService', () => ({
  customFieldService: {
    list: (...args: unknown[]) => listMock(...args),
    applicable: (...args: unknown[]) => applicableMock(...args),
    save: (...args: unknown[]) => saveMock(...args),
    remove: (...args: unknown[]) => removeMock(...args),
    values: (...args: unknown[]) => valuesMock(...args),
    upsertValues: (...args: unknown[]) => upsertValuesMock(...args),
  },
}))

vi.mock('../api/clientService', () => ({
  clientService: {
    listClients: (...args: unknown[]) => listClientsMock(...args),
    getClient: vi.fn(), createClient: vi.fn(), updateClient: vi.fn(),
  },
}))

const notifyApiErrorMock = vi.fn()
vi.mock('../utils/notify', () => ({
  notify: {
    apiError: (...args: unknown[]) => notifyApiErrorMock(...args),
    success: vi.fn(),
    error: vi.fn(),
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
  ;[listMock, applicableMock, saveMock, removeMock, valuesMock, upsertValuesMock,
    listClientsMock, notifyApiErrorMock].forEach((m) => m.mockReset())
  listClientsMock.mockResolvedValue({ data: { content: [] } })
})

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
  mockRole = 'ADMIN'
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

// ===================== Mount + service calls =====================

describe('CustomFieldsPage — mount + service calls', () => {
  it('calls customFieldService.list(null) on mount (Platform-wide default)', async () => {
    listMock.mockResolvedValue([])

    const Page = await loadPage()
    renderPage(Page)

    // Platform-wide is the default tenant scope → list is called with null.
    await waitFor(() => expect(listMock).toHaveBeenCalledTimes(1))
    expect(listMock).toHaveBeenCalledWith(null)
  })

  it('calls clientService.listClients on mount for the tenant picker', async () => {
    listMock.mockResolvedValue([])

    const Page = await loadPage()
    renderPage(Page)

    await waitFor(() => expect(listClientsMock).toHaveBeenCalledTimes(1))
    expect(listClientsMock).toHaveBeenCalledWith({ size: 200, status: 'ACTIVE', sortBy: 'code' })
  })
})

// ===================== Loading placeholder =====================

describe('CustomFieldsPage — loading placeholder', () => {
  it('renders "Loading definitions…" while list is in-flight', async () => {
    listMock.mockReturnValue(new Promise(() => {})) // never resolves

    const Page = await loadPage()
    renderPage(Page)

    expect(await screen.findByText(/Loading definitions…/i)).toBeInTheDocument()
  })
})

// ===================== Error path =====================

describe('CustomFieldsPage — error path', () => {
  it('list rejection → notify.apiError("Failed to load definitions.")', async () => {
    listMock.mockRejectedValue(new Error('boom'))

    const Page = await loadPage()
    renderPage(Page)

    await waitFor(() =>
      expect(notifyApiErrorMock).toHaveBeenCalledWith(expect.any(Error), 'Failed to load definitions.'),
    )
    await waitFor(() =>
      expect(screen.queryByText(/Loading definitions…/i)).not.toBeInTheDocument(),
    )
  })

  it('clientService rejection is swallowed silently (secondary load; page still renders)', async () => {
    listMock.mockResolvedValue([])
    listClientsMock.mockRejectedValue(new Error('clients-boom'))

    const Page = await loadPage()
    renderPage(Page)

    // Tenant scope picker still renders (Platform-wide default) even
    // when the client list fetch fails.
    await waitFor(() =>
      expect(screen.getByText(/Tenant scope/i)).toBeInTheDocument(),
    )
    // apiError was NOT called for the client-fetch failure.
    expect(notifyApiErrorMock).not.toHaveBeenCalledWith(
      expect.any(Error),
      expect.stringContaining('client'),
    )
  })
})

// ===================== Empty state =====================

describe('CustomFieldsPage — empty state', () => {
  it('no definitions after load → renders the tenant scope + empty body', async () => {
    listMock.mockResolvedValue([])

    const Page = await loadPage()
    renderPage(Page)

    await waitFor(() =>
      expect(screen.queryByText(/Loading definitions…/i)).not.toBeInTheDocument(),
    )
    // Tenant scope selector is visible (mount survived).
    expect(screen.getByText(/Tenant scope/i)).toBeInTheDocument()
    // The default tenant scope option is 'Platform-wide (all tenants)'.
    expect(screen.getByRole('option', { name: /Platform-wide/i })).toBeInTheDocument()
  })
})

// ===================== Role parity =====================

describe('CustomFieldsPage — role parity (no useAppSession import)', () => {
  it.each(['ADMIN', 'USER', 'TENANT'] as const)(
    '%s mounts identically (page has NO role gate)',
    async (role) => {
      mockRole = role
      listMock.mockResolvedValue([])

      const Page = await loadPage()
      renderPage(Page)

      await waitFor(() => expect(listMock).toHaveBeenCalledTimes(1))
      expect(screen.getByText(/Tenant scope/i)).toBeInTheDocument()
    },
  )
})
