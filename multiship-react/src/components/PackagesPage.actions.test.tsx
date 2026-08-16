import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, cleanup, waitFor, act } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, Outlet } from 'react-router-dom'
import type { ComponentType } from 'react'

/**
 * Sprint 53 page-tests — PackagesPage · actions slice.
 *
 * Scope:
 *   - savePreset: create (new modal → validation → success + close +
 *     refetch); reject → apiError + modal stays.
 *   - setDefaultPreset: click Make default on non-default row calls
 *     setDefaultPreset(id) + refetch + notify.success; reject → apiError.
 *   - Guard: default row has NO "Make default" button.
 *   - deletePreset: notify.confirm resolves true → delete + refetch +
 *     notify.success; resolves false → no delete call; reject → apiError.
 *   - syncCarrierPackaging: single-carrier filter targets one carrier;
 *     ALL (default) sweeps UPS + FEDEX + USPS.
 *
 * Anti-fallback: fail-loud globalThis.fetch spy; every service mocked;
 * per-carrier verify(times/never) proves scope.
 */

// ---------- Service mocks ----------

const listPresetsMock = vi.fn()
const packagesUsageMock = vi.fn()
const savePresetMock = vi.fn()
const setDefaultPresetMock = vi.fn()
const deletePresetMock = vi.fn()
const syncPackagesMock = vi.fn()

vi.mock('../api/shippingConfigService', () => ({
  shippingConfigService: {
    listPresets: (...args: unknown[]) => listPresetsMock(...args),
    savePreset: (...args: unknown[]) => savePresetMock(...args),
    setDefaultPreset: (...args: unknown[]) => setDefaultPresetMock(...args),
    deletePreset: (...args: unknown[]) => deletePresetMock(...args),
    syncPackages: (...args: unknown[]) => syncPackagesMock(...args),
    catalog: vi.fn(),
    syncServices: vi.fn(),
    setServiceEnabled: vi.fn(),
    saveRule: vi.fn(),
    deleteRule: vi.fn(),
    setServicePackages: vi.fn(),
  },
  dimWeightOf: () => null,
  oversizeOf: () => null,
}))

vi.mock('../api/clientCatalogService', () => ({
  allowlistUsageService: {
    packages: (...args: unknown[]) => packagesUsageMock(...args),
    services: vi.fn(),
  },
}))

const notifyConfirmMock = vi.fn()
const notifySuccessMock = vi.fn()
const notifyErrorMock = vi.fn()
const notifyApiErrorMock = vi.fn()
vi.mock('../utils/notify', () => ({
  notify: {
    confirm: (...args: unknown[]) => notifyConfirmMock(...args),
    success: (...args: unknown[]) => notifySuccessMock(...args),
    error: (...args: unknown[]) => notifyErrorMock(...args),
    apiError: (...args: unknown[]) => notifyApiErrorMock(...args),
    info: vi.fn(),
  },
}))

let mockRole: 'ADMIN' | 'USER' | 'TENANT' = 'ADMIN'
vi.mock('../hooks/useAppSession', () => ({
  useAppSession: () => ({
    username: 'ops', role: mockRole, connectedCarriers: [], hasConnectedCarrier: false,
  }),
  clearAuthSession: vi.fn(),
  storeAuthSession: vi.fn(),
  bootstrapSessionFromCookie: vi.fn(),
  syncCarrierSession: vi.fn(),
}))

vi.mock('../api/apiClient', () => ({
  isAbortError: () => false,
  apiClient: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn(), patch: vi.fn() },
}))

// ---------- Fail-loud fetch spy ----------

beforeEach(() => {
  vi.spyOn(globalThis, 'fetch').mockImplementation(() => {
    throw new Error('un-mocked fetch forbidden in unit tests')
  })
  ;[listPresetsMock, packagesUsageMock, savePresetMock, setDefaultPresetMock,
    deletePresetMock, syncPackagesMock, notifyConfirmMock, notifySuccessMock,
    notifyErrorMock, notifyApiErrorMock].forEach((m) => m.mockReset())
  packagesUsageMock.mockResolvedValue({ data: [] })
  notifyConfirmMock.mockResolvedValue(true)
})

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
  mockRole = 'ADMIN'
})

// ---------- Helpers ----------

const customPreset = (id: number, name: string, overrides: Partial<{
  isDefault: boolean, enabled: boolean,
}> = {}) => ({
  id, name, kind: 'CUSTOM' as const,
  length: 10, width: 10, height: 10, dimUnit: 'IN', weightUnit: 'LB',
  maxWeight: 5,
  enabled: overrides.enabled ?? true,
  default: overrides.isDefault ?? false,
})

async function loadPage(): Promise<ComponentType> {
  const mod = await import('./PackagesPage')
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

// ===================== savePreset (create modal) =====================

describe('PackagesPage — save (create)', () => {
  it('blank name → notify.error, savePreset NOT called', async () => {
    listPresetsMock.mockResolvedValue([])
    const Page = await loadPage()
    renderPage(Page)

    await waitFor(() => expect(screen.getByRole('button', { name: /Add Package/i })).toBeInTheDocument())
    await act(async () => { await userEvent.click(screen.getByRole('button', { name: /Add Package/i })) })

    // The Register package button is in the modal footer. Click without a name.
    const saveBtn = await screen.findByRole('button', { name: /Register package/i })
    await act(async () => { await userEvent.click(saveBtn) })

    expect(notifyErrorMock).toHaveBeenCalledWith('Give the package a name.')
    expect(savePresetMock).not.toHaveBeenCalled()
  })

  it('custom kind without dims → notify.error, savePreset NOT called', async () => {
    listPresetsMock.mockResolvedValue([])
    const Page = await loadPage()
    renderPage(Page)

    await waitFor(() => expect(screen.getByRole('button', { name: /Add Package/i })).toBeInTheDocument())
    await act(async () => { await userEvent.click(screen.getByRole('button', { name: /Add Package/i })) })

    // Fill a name but leave dims blank (blankPreset() sets kind=CUSTOM).
    const nameInput = await screen.findByPlaceholderText(/e.g. Small Box/i)
    await userEvent.type(nameInput, 'MyBox')

    await act(async () => {
      await userEvent.click(screen.getByRole('button', { name: /Register package/i }))
    })

    expect(notifyErrorMock).toHaveBeenCalledWith('A custom box needs length, width and height.')
    expect(savePresetMock).not.toHaveBeenCalled()
  })

  it('valid create → savePreset + notify.success + close modal + refetch', async () => {
    listPresetsMock.mockResolvedValue([])
    savePresetMock.mockResolvedValue({ data: { id: 999, name: 'Widget Box' } })
    const Page = await loadPage()
    renderPage(Page)

    await waitFor(() => expect(screen.getByRole('button', { name: /Add Package/i })).toBeInTheDocument())
    await act(async () => { await userEvent.click(screen.getByRole('button', { name: /Add Package/i })) })

    // Fill name + dims.
    await userEvent.type(await screen.findByPlaceholderText(/e.g. Small Box/i), 'Widget Box')
    // Length / Width / Height inputs — number type + specific labels. Use aria-label.
    const numInputs = screen.getAllByRole('spinbutton')
    // Order in DOM: [Length, Width, Height, Internal L, Internal W, Internal H, Max weight, Tare weight, Box cost, Pick priority]
    await userEvent.type(numInputs[0], '10')
    await userEvent.type(numInputs[1], '8')
    await userEvent.type(numInputs[2], '6')

    await act(async () => {
      await userEvent.click(screen.getByRole('button', { name: /Register package/i }))
    })

    expect(savePresetMock).toHaveBeenCalledTimes(1)
    expect(notifySuccessMock).toHaveBeenCalledWith("Package 'Widget Box' saved.")
    // Modal closes → Register package button gone.
    await waitFor(() =>
      expect(screen.queryByRole('button', { name: /Register package/i })).not.toBeInTheDocument(),
    )
    // Refetch: listPresets called again (mount + post-save).
    await waitFor(() => expect(listPresetsMock).toHaveBeenCalledTimes(2))
  })

  it('save rejection → notify.apiError + modal stays open', async () => {
    listPresetsMock.mockResolvedValue([])
    savePresetMock.mockRejectedValue(new Error('boom'))
    const Page = await loadPage()
    renderPage(Page)

    await waitFor(() => expect(screen.getByRole('button', { name: /Add Package/i })).toBeInTheDocument())
    await act(async () => { await userEvent.click(screen.getByRole('button', { name: /Add Package/i })) })

    await userEvent.type(await screen.findByPlaceholderText(/e.g. Small Box/i), 'MyBox')
    const numInputs = screen.getAllByRole('spinbutton')
    await userEvent.type(numInputs[0], '10')
    await userEvent.type(numInputs[1], '8')
    await userEvent.type(numInputs[2], '6')

    await act(async () => {
      await userEvent.click(screen.getByRole('button', { name: /Register package/i }))
    })

    await waitFor(() =>
      expect(notifyApiErrorMock).toHaveBeenCalledWith(expect.any(Error), 'Failed to save the package.'),
    )
    // Modal still open.
    expect(screen.getByRole('button', { name: /Register package/i })).toBeInTheDocument()
  })
})

// ===================== setDefaultPreset =====================

describe('PackagesPage — set default', () => {
  it('clicking Make default on non-default row calls setDefaultPreset(id) + refetch + success', async () => {
    listPresetsMock.mockResolvedValue([customPreset(3, 'New Default', { isDefault: false })])
    setDefaultPresetMock.mockResolvedValue({ data: { id: 3, default: true } })
    const Page = await loadPage()
    renderPage(Page)

    await waitFor(() => expect(screen.getByText('New Default')).toBeInTheDocument())
    await act(async () => {
      await userEvent.click(screen.getByRole('button', { name: /Make default/i }))
    })

    expect(setDefaultPresetMock).toHaveBeenCalledWith(3)
    expect(notifySuccessMock).toHaveBeenCalledWith("'New Default' is now the default package.")
    await waitFor(() => expect(listPresetsMock).toHaveBeenCalledTimes(2))
  })

  it('default row has NO Make default button (guard)', async () => {
    listPresetsMock.mockResolvedValue([customPreset(1, 'Already Default', { isDefault: true })])
    const Page = await loadPage()
    renderPage(Page)

    await waitFor(() => expect(screen.getByText('Already Default')).toBeInTheDocument())
    expect(screen.queryByRole('button', { name: /Make default/i })).not.toBeInTheDocument()
  })

  it('setDefaultPreset rejection → notify.apiError', async () => {
    listPresetsMock.mockResolvedValue([customPreset(3, 'Target', { isDefault: false })])
    setDefaultPresetMock.mockRejectedValue(new Error('nope'))
    const Page = await loadPage()
    renderPage(Page)

    await waitFor(() => expect(screen.getByText('Target')).toBeInTheDocument())
    await act(async () => {
      await userEvent.click(screen.getByRole('button', { name: /Make default/i }))
    })

    await waitFor(() =>
      expect(notifyApiErrorMock).toHaveBeenCalledWith(expect.any(Error), 'Failed to set the default.'),
    )
  })
})

// ===================== deletePreset =====================

describe('PackagesPage — delete', () => {
  it('confirm accept → deletePreset + refetch + notify.success', async () => {
    listPresetsMock.mockResolvedValue([customPreset(7, 'To Remove')])
    notifyConfirmMock.mockResolvedValue(true)
    deletePresetMock.mockResolvedValue({ data: null })
    const Page = await loadPage()
    renderPage(Page)

    await waitFor(() => expect(screen.getByText('To Remove')).toBeInTheDocument())
    await act(async () => {
      await userEvent.click(screen.getByLabelText('Delete To Remove'))
    })

    await waitFor(() => expect(deletePresetMock).toHaveBeenCalledWith(7))
    expect(notifySuccessMock).toHaveBeenCalledWith("Package 'To Remove' removed.")
    await waitFor(() => expect(listPresetsMock).toHaveBeenCalledTimes(2))
  })

  it('confirm cancel → NO deletePreset call', async () => {
    listPresetsMock.mockResolvedValue([customPreset(7, 'To Remove')])
    notifyConfirmMock.mockResolvedValue(false)
    const Page = await loadPage()
    renderPage(Page)

    await waitFor(() => expect(screen.getByText('To Remove')).toBeInTheDocument())
    await act(async () => {
      await userEvent.click(screen.getByLabelText('Delete To Remove'))
    })

    expect(deletePresetMock).not.toHaveBeenCalled()
    expect(notifySuccessMock).not.toHaveBeenCalled()
  })

  it('deletePreset rejection → notify.apiError', async () => {
    listPresetsMock.mockResolvedValue([customPreset(7, 'To Remove')])
    notifyConfirmMock.mockResolvedValue(true)
    deletePresetMock.mockRejectedValue(new Error('boom'))
    const Page = await loadPage()
    renderPage(Page)

    await waitFor(() => expect(screen.getByText('To Remove')).toBeInTheDocument())
    await act(async () => {
      await userEvent.click(screen.getByLabelText('Delete To Remove'))
    })

    await waitFor(() =>
      expect(notifyApiErrorMock).toHaveBeenCalledWith(expect.any(Error), 'Failed to delete the package.'),
    )
  })
})

// ===================== syncCarrierPackaging =====================

describe('PackagesPage — sync carrier packaging', () => {
  it('ALL filter (default) sweeps every carrier (UPS + FEDEX + USPS)', async () => {
    listPresetsMock.mockResolvedValue([])
    syncPackagesMock.mockResolvedValue({ data: { added: 0, updated: 0 } })
    const Page = await loadPage()
    renderPage(Page)

    await waitFor(() =>
      expect(screen.getByRole('button', { name: /Sync carrier packaging/i })).toBeInTheDocument(),
    )
    await act(async () => {
      await userEvent.click(screen.getByRole('button', { name: /Sync carrier packaging/i }))
    })

    // Under ALL filter, targets = ['UPS', 'FEDEX', 'USPS'] — 3 sync calls.
    await waitFor(() => expect(syncPackagesMock).toHaveBeenCalledTimes(3))
    const calls = syncPackagesMock.mock.calls.map((c) => c[0])
    expect(calls).toEqual(expect.arrayContaining(['UPS', 'FEDEX', 'USPS']))
  })

  it('specific carrier filter targets only that carrier', async () => {
    // Seed at least one CARRIER preset for UPS so it appears in the dropdown.
    listPresetsMock.mockResolvedValue([{
      id: 1, name: 'UPS Small Box', kind: 'CARRIER', carrier: 'UPS',
      carrierPackageCode: 'SMALL_BOX', scope: 'DOMESTIC',
      originCountry: 'US', length: 10, width: 8, height: 6,
      dimUnit: 'IN', weightUnit: 'LB', maxWeight: 5, enabled: true, default: false,
    }])
    syncPackagesMock.mockResolvedValue({ data: { added: 0, updated: 0 } })
    const Page = await loadPage()
    renderPage(Page)

    await waitFor(() => expect(screen.getByText('UPS Small Box')).toBeInTheDocument())
    // Pick UPS in the carrier filter dropdown (second combobox).
    const selects = screen.getAllByRole('combobox')
    await userEvent.selectOptions(selects[1], 'UPS')

    await act(async () => {
      await userEvent.click(screen.getByRole('button', { name: /Sync carrier packaging/i }))
    })

    // Only ONE call — for UPS.
    await waitFor(() => expect(syncPackagesMock).toHaveBeenCalledTimes(1))
    expect(syncPackagesMock).toHaveBeenCalledWith('UPS', 'US')
    // FedEx / USPS never touched.
    expect(syncPackagesMock).not.toHaveBeenCalledWith('FEDEX', 'US')
    expect(syncPackagesMock).not.toHaveBeenCalledWith('USPS', 'US')
  })
})
