import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, cleanup, waitFor, act } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, Outlet } from 'react-router-dom'
import type { ComponentType } from 'react'

/**
 * Sprint 53 page-tests — ImporterBrokerPage · actions slice.
 *
 * Scope:
 *   - Add profile button → CustomsProfileModal mounts in 'new' mode.
 *   - Row Edit (via RowActionsMenu) → CustomsProfileModal mounts in 'edit'
 *     mode with the row.
 *   - Row Delete: notify.confirm accept → customsProfileService.remove
 *     called + notify.success + refresh (listProfiles re-called);
 *     cancel → NO remove call; reject → notify.apiError.
 *   - Export CSV (via AdvancedDataTable's onExport hook):
 *     customsProfileService.exportProfilesCsv called; reject → apiError.
 */

// ---------- Service mocks ----------

const listProfilesMock = vi.fn()
const statsMock = vi.fn()
const listClientsMock = vi.fn()
const removeMock = vi.fn()
const exportCsvMock = vi.fn()

vi.mock('../api/customsProfileService', () => ({
  customsProfileService: {
    listProfiles: (...args: unknown[]) => listProfilesMock(...args),
    stats: (...args: unknown[]) => statsMock(...args),
    exportProfilesCsv: (...args: unknown[]) => exportCsvMock(...args),
    list: vi.fn(), save: vi.fn(),
    remove: (...args: unknown[]) => removeMock(...args),
  },
}))

vi.mock('../api/clientService', () => ({
  clientService: {
    listClients: (...args: unknown[]) => listClientsMock(...args),
    getClient: vi.fn(), createClient: vi.fn(), updateClient: vi.fn(),
  },
}))

const notifyConfirmMock = vi.fn()
const notifySuccessMock = vi.fn()
const notifyApiErrorMock = vi.fn()
vi.mock('../utils/notify', () => ({
  notify: {
    confirm: (...args: unknown[]) => notifyConfirmMock(...args),
    success: (...args: unknown[]) => notifySuccessMock(...args),
    apiError: (...args: unknown[]) => notifyApiErrorMock(...args),
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
  isAbortError: () => false,
  apiClient: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn(), patch: vi.fn() },
}))

// Prop-spy shim for CustomsProfileModal. The SUT passes profile=null for
// 'new' mode, profile={...} for 'edit' mode; only mounts when the SUT's
// modal state is truthy.
type ModalProps = { profile: { id?: number } | null; onClose: () => void; onSaved: () => void }
let lastModalProps: ModalProps | null = null
vi.mock('./modals/CustomsProfileModal', () => ({
  default: (p: ModalProps) => {
    lastModalProps = p
    return (
      <div data-testid="profile-modal-shim">
        modal-{p.profile ? 'edit' : 'new'}
      </div>
    )
  },
}))
vi.mock('./modals/CustomsEditorModal', () => ({ default: () => null }))

// ---------- Fixtures ----------

const profile = (id: number, overrides: Partial<{
  clientCode: string, clientName: string, countries: string[],
  importerType: 'RECEIVER' | 'BUSINESS',
}> = {}) => ({
  id,
  clientCode: overrides.clientCode ?? 'ACME',
  clientName: overrides.clientName ?? 'Acme Corp',
  countries: overrides.countries ?? ['US'],
  importerType: overrides.importerType ?? 'RECEIVER',
  importerName: null, importerCity: null,
  brokerName: null, brokerCompany: null, brokerCity: null,
  accountCarrier: null, accountNo: null,
})

const pageWith = (profiles: ReturnType<typeof profile>[]) => ({
  data: { content: profiles, totalElements: profiles.length, totalPages: 1 },
})

// ---------- Fail-loud fetch spy ----------

beforeEach(() => {
  vi.spyOn(globalThis, 'fetch').mockImplementation(() => {
    throw new Error('un-mocked fetch forbidden in unit tests')
  })
  ;[listProfilesMock, statsMock, listClientsMock, removeMock, exportCsvMock,
    notifyConfirmMock, notifySuccessMock, notifyApiErrorMock]
    .forEach((m) => m.mockReset())
  statsMock.mockResolvedValue({ profiles: 0, destinationsCovered: 0, clientsConfigured: 0 })
  listClientsMock.mockResolvedValue({ data: { content: [] } })
  notifyConfirmMock.mockResolvedValue(true)
  lastModalProps = null
})

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
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

// ===================== Add profile =====================

describe('ImporterBrokerPage — Add profile', () => {
  it('clicking Add profile mounts the CustomsProfileModal in "new" mode', async () => {
    listProfilesMock.mockResolvedValue(pageWith([]))
    const Page = await loadPage()
    renderPage(Page)
    await waitFor(() => expect(screen.getByRole('button', { name: /Add profile/i })).toBeInTheDocument())

    await act(async () => {
      await userEvent.click(screen.getByRole('button', { name: /Add profile/i }))
    })

    await waitFor(() => expect(screen.getByTestId('profile-modal-shim')).toBeInTheDocument())
    expect(screen.getByTestId('profile-modal-shim').textContent).toBe('modal-new')
    expect(lastModalProps?.profile).toBeNull()
  })
})

// ===================== Row Edit (via RowActionsMenu) =====================

describe('ImporterBrokerPage — Row Edit', () => {
  it('opening Row actions and clicking Edit mounts modal in "edit" mode with the row', async () => {
    listProfilesMock.mockResolvedValue(pageWith([profile(42, { clientCode: 'ACME' })]))
    const Page = await loadPage()
    renderPage(Page)
    await waitFor(() => expect(screen.getByText('ACME')).toBeInTheDocument())

    // Open the row's action menu.
    await act(async () => {
      await userEvent.click(screen.getByRole('button', { name: /Row actions/i }))
    })
    // Click the Edit menu item (rendered inside PortalMenu, but still in DOM).
    await act(async () => {
      await userEvent.click(await screen.findByRole('menuitem', { name: /Edit/i }))
    })

    await waitFor(() => expect(screen.getByTestId('profile-modal-shim')).toBeInTheDocument())
    expect(screen.getByTestId('profile-modal-shim').textContent).toBe('modal-edit')
    expect(lastModalProps?.profile).toMatchObject({ id: 42, clientCode: 'ACME' })
  })
})

// ===================== Row Delete =====================

describe('ImporterBrokerPage — Row Delete', () => {
  it('confirm accept → remove called + notify.success + refresh (listProfiles re-called)', async () => {
    listProfilesMock.mockResolvedValue(pageWith([profile(42, { clientCode: 'ACME' })]))
    notifyConfirmMock.mockResolvedValue(true)
    removeMock.mockResolvedValue({})

    const Page = await loadPage()
    renderPage(Page)
    await waitFor(() => expect(screen.getByText('ACME')).toBeInTheDocument())
    // Baseline: mount call.
    const baseCalls = listProfilesMock.mock.calls.length

    await act(async () => {
      await userEvent.click(screen.getByRole('button', { name: /Row actions/i }))
    })
    await act(async () => {
      await userEvent.click(await screen.findByRole('menuitem', { name: /Delete/i }))
    })

    await waitFor(() => expect(removeMock).toHaveBeenCalledWith('ACME', 42))
    expect(notifySuccessMock).toHaveBeenCalledWith('Profile deleted.')
    // Refresh bumps reloadToken → listProfiles re-fires (call count increases).
    await waitFor(() => expect(listProfilesMock.mock.calls.length).toBeGreaterThan(baseCalls))
  })

  it('confirm cancel → NO remove call', async () => {
    listProfilesMock.mockResolvedValue(pageWith([profile(42, { clientCode: 'ACME' })]))
    notifyConfirmMock.mockResolvedValue(false)

    const Page = await loadPage()
    renderPage(Page)
    await waitFor(() => expect(screen.getByText('ACME')).toBeInTheDocument())

    await act(async () => {
      await userEvent.click(screen.getByRole('button', { name: /Row actions/i }))
    })
    await act(async () => {
      await userEvent.click(await screen.findByRole('menuitem', { name: /Delete/i }))
    })

    // Confirm was invoked but user cancelled → no remove call, no success toast.
    await waitFor(() => expect(notifyConfirmMock).toHaveBeenCalled())
    expect(removeMock).not.toHaveBeenCalled()
    expect(notifySuccessMock).not.toHaveBeenCalled()
  })

  it('remove rejection → notify.apiError', async () => {
    listProfilesMock.mockResolvedValue(pageWith([profile(42, { clientCode: 'ACME' })]))
    notifyConfirmMock.mockResolvedValue(true)
    removeMock.mockRejectedValue(new Error('boom'))

    const Page = await loadPage()
    renderPage(Page)
    await waitFor(() => expect(screen.getByText('ACME')).toBeInTheDocument())

    await act(async () => {
      await userEvent.click(screen.getByRole('button', { name: /Row actions/i }))
    })
    await act(async () => {
      await userEvent.click(await screen.findByRole('menuitem', { name: /Delete/i }))
    })

    await waitFor(() =>
      expect(notifyApiErrorMock).toHaveBeenCalledWith(expect.any(Error), 'Failed to delete.'),
    )
  })
})

// ===================== Export CSV =====================

describe('ImporterBrokerPage — Export CSV', () => {
  it('Export triggers customsProfileService.exportProfilesCsv with listParams', async () => {
    listProfilesMock.mockResolvedValue(pageWith([]))
    exportCsvMock.mockResolvedValue(undefined)

    const Page = await loadPage()
    renderPage(Page)
    // Export CSV button — usually in the AdvancedDataTable toolbar area
    // via the onExport prop.
    await waitFor(() => expect(screen.getByRole('button', { name: /Export/i })).toBeInTheDocument())

    // Click Export button — opens the menu with "CSV — current view" item.
    await act(async () => {
      await userEvent.click(screen.getByRole('button', { name: /Export/i }))
    })
    // Now click the CSV menu item to trigger onExport.
    await act(async () => {
      await userEvent.click(await screen.findByRole('button', { name: /CSV.*current view/i }))
    })

    await waitFor(() => expect(exportCsvMock).toHaveBeenCalledTimes(1))
    // Called with listParams (search/clientCode/carrier/broker/countries/sort).
    const call = exportCsvMock.mock.calls[0][0]
    expect(call).toBeDefined()
    expect(typeof call).toBe('object')
  })

  it('Export rejection → notify.apiError("Failed to export profiles.")', async () => {
    listProfilesMock.mockResolvedValue(pageWith([]))
    exportCsvMock.mockRejectedValue(new Error('csv-boom'))

    const Page = await loadPage()
    renderPage(Page)
    await waitFor(() => expect(screen.getByRole('button', { name: /Export/i })).toBeInTheDocument())

    await act(async () => {
      await userEvent.click(screen.getByRole('button', { name: /Export/i }))
    })
    await act(async () => {
      await userEvent.click(await screen.findByRole('button', { name: /CSV.*current view/i }))
    })

    await waitFor(() =>
      expect(notifyApiErrorMock).toHaveBeenCalledWith(expect.any(Error), 'Failed to export profiles.'),
    )
  })
})
