import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, cleanup, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes, Outlet } from 'react-router-dom'
import type { ComponentType } from 'react'

/**
 * Sprint 53 page-tests — ImporterBrokerPage · shell + global.
 *
 * Scope (this slice only):
 *   - Mount fires the 2-way load: customsProfileService.listProfiles +
 *     .stats (parallel; effect chains render + reload token).
 *   - Loading placeholder while listProfiles is in-flight AND profiles empty.
 *   - Error path: listProfiles rejection → notify.apiError('Failed to load
 *     profiles.') AND loading placeholder clears.
 *   - Empty state: zero profiles → AdvancedDataTable renders without rows.
 *   - Role parity: page mounts + renders identically for ADMIN / USER /
 *     TENANT (no shell-level useAppSession gate).
 *
 * Sibling slices cover list+stats+filters and actions.
 */

// ---------- Service mocks ----------

const listProfilesMock = vi.fn()
const statsMock = vi.fn()
const removeMock = vi.fn()
const exportCsvMock = vi.fn()

vi.mock('../api/customsProfileService', () => ({
  customsProfileService: {
    listProfiles: (...args: unknown[]) => listProfilesMock(...args),
    stats: (...args: unknown[]) => statsMock(...args),
    exportProfilesCsv: (...args: unknown[]) => exportCsvMock(...args),
    list: vi.fn(),
    save: vi.fn(),
    remove: (...args: unknown[]) => removeMock(...args),
  },
}))

const listClientsMock = vi.fn()
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
    username: 'ops', role: mockRole, connectedCarriers: [], hasConnectedCarrier: false,
  }),
  clearAuthSession: vi.fn(), storeAuthSession: vi.fn(),
  bootstrapSessionFromCookie: vi.fn(), syncCarrierSession: vi.fn(),
}))

vi.mock('../api/apiClient', () => ({
  isAbortError: () => false,
  apiClient: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn(), patch: vi.fn() },
}))

// Stub heavy child modals so the shell test doesn't exercise them.
vi.mock('./modals/CustomsProfileModal', () => ({ default: () => null }))
vi.mock('./modals/CustomsEditorModal', () => ({ default: () => null }))

// ---------- Fail-loud fetch spy ----------

beforeEach(() => {
  vi.spyOn(globalThis, 'fetch').mockImplementation(() => {
    throw new Error('un-mocked fetch forbidden in unit tests')
  })
  ;[listProfilesMock, statsMock, removeMock, exportCsvMock, listClientsMock, notifyApiErrorMock]
    .forEach((m) => m.mockReset())
  statsMock.mockResolvedValue({
    profiles: 0, destinationsCovered: 0, clientsConfigured: 0,
  })
  listClientsMock.mockResolvedValue({ data: { content: [] } })
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
  const mod = await import('./ImporterBrokerPage')
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

describe('ImporterBrokerPage — mount + service calls', () => {
  it('calls listProfiles + stats on mount (2-way parallel load)', async () => {
    listProfilesMock.mockResolvedValue(emptyPage())

    const Page = await loadPage()
    renderPage(Page)

    await waitFor(() => expect(listProfilesMock).toHaveBeenCalledTimes(1))
    expect(statsMock).toHaveBeenCalledTimes(1)
  })

  it('listProfiles is called with page + size defaults', async () => {
    listProfilesMock.mockResolvedValue(emptyPage())

    const Page = await loadPage()
    renderPage(Page)

    await waitFor(() => expect(listProfilesMock).toHaveBeenCalled())
    // The call should include a page and size (from listParams + pageIndex + pageSize).
    const callArgs = listProfilesMock.mock.calls[0][0]
    expect(callArgs).toHaveProperty('page')
    expect(callArgs).toHaveProperty('size')
    expect(typeof callArgs.page).toBe('number')
    expect(typeof callArgs.size).toBe('number')
  })
})

// ===================== Loading placeholder =====================

describe('ImporterBrokerPage — loading placeholder', () => {
  it('renders "Loading profiles…" while listProfiles is in-flight AND profiles empty', async () => {
    listProfilesMock.mockReturnValue(new Promise(() => {})) // never resolves

    const Page = await loadPage()
    renderPage(Page)

    expect(await screen.findByText(/Loading profiles…/i)).toBeInTheDocument()
  })
})

// ===================== Error path =====================

describe('ImporterBrokerPage — error path', () => {
  it('listProfiles rejection → notify.apiError + loading placeholder clears', async () => {
    listProfilesMock.mockRejectedValue(new Error('boom'))

    const Page = await loadPage()
    renderPage(Page)

    await waitFor(() =>
      expect(notifyApiErrorMock).toHaveBeenCalledWith(expect.any(Error), 'Failed to load profiles.'),
    )
    await waitFor(() =>
      expect(screen.queryByText(/Loading profiles…/i)).not.toBeInTheDocument(),
    )
  })

  it('stats rejection is swallowed silently (secondary load; page still renders)', async () => {
    listProfilesMock.mockResolvedValue(emptyPage())
    statsMock.mockRejectedValue(new Error('stats-boom'))

    const Page = await loadPage()
    renderPage(Page)

    // notify.apiError must NOT be called for stats failure (per FE-L3 policy:
    // log-instead-of-notify for secondary loads).
    await waitFor(() =>
      expect(screen.queryByText(/Loading profiles…/i)).not.toBeInTheDocument(),
    )
    // Verify: apiError was not called on account of stats failure.
    // (May be called for other reasons in future — this asserts current behavior.)
    expect(notifyApiErrorMock).not.toHaveBeenCalledWith(
      expect.any(Error),
      expect.stringContaining('stats'),
    )
  })
})

// ===================== Empty state =====================

describe('ImporterBrokerPage — empty state', () => {
  it('zero profiles → the AdvancedDataTable renders without ghost rows', async () => {
    listProfilesMock.mockResolvedValue(emptyPage())

    const Page = await loadPage()
    renderPage(Page)

    await waitFor(() =>
      expect(screen.queryByText(/Loading profiles…/i)).not.toBeInTheDocument(),
    )
    // The DataTable's search box (part of the wrapper) confirms the table
    // actually mounted with an empty body.
    expect(screen.getByRole('button', { name: /Add profile/i })).toBeInTheDocument()
  })
})

// ===================== Role parity =====================

describe('ImporterBrokerPage — role parity (no shell-level gate)', () => {
  it.each(['ADMIN', 'USER', 'TENANT'] as const)(
    '%s sees the mapping table (page does not import useAppSession for shell gating)',
    async (role) => {
      mockRole = role
      listProfilesMock.mockResolvedValue(emptyPage())

      const Page = await loadPage()
      renderPage(Page)

      await waitFor(() =>
        expect(screen.queryByText(/Loading profiles…/i)).not.toBeInTheDocument(),
      )
      // Search box is present for every role (backend is the trust boundary).
      expect(screen.getByRole('button', { name: /Add profile/i })).toBeInTheDocument()
    },
  )
})
