import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import { render, cleanup, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes, Outlet } from 'react-router-dom'
import { Provider } from 'react-redux'
import { combineReducers, configureStore } from '@reduxjs/toolkit'
import type { ComponentType } from 'react'
import carrierReducer from '../store/carrierSlice'
import orderReducer from '../store/orderSlice'

/** Back from Stamps.com: the Carriers screen says whether the authorization worked. */

// ===== Hoisted mocks =====

const listAccounts = vi.fn()
const upsertAccount = vi.fn()
const deleteAccount = vi.fn()
const setClientDefault = vi.fn()
const toggleActive = vi.fn()
const verifyAccount = vi.fn()
const verifyCredentials = vi.fn()
const getPlatformCredentials = vi.fn()
const resolveOrders = vi.fn()

vi.mock('../api/accountRefService', () => ({
  accountRefService: {
    listAccounts: (...a: unknown[]) => listAccounts(...a),
    upsertAccount: (...a: unknown[]) => upsertAccount(...a),
    deleteAccount: (...a: unknown[]) => deleteAccount(...a),
    setClientDefault: (...a: unknown[]) => setClientDefault(...a),
    toggleActive: (...a: unknown[]) => toggleActive(...a),
    verifyAccount: (...a: unknown[]) => verifyAccount(...a),
    verifyCredentials: (...a: unknown[]) => verifyCredentials(...a),
    getPlatformCredentials: (...a: unknown[]) => getPlatformCredentials(...a),
    resolveOrders: (...a: unknown[]) => resolveOrders(...a),
    authorizeStampsSera: (id: number) => `http://stub/authorize/${id}`,
  },
}))

const listClients = vi.fn()
vi.mock('../api/clientService', async () => {
  const actual = await vi.importActual<typeof import('../api/clientService')>(
    '../api/clientService',
  )
  return {
    ...actual,
    clientService: {
      listClients: (...a: unknown[]) => listClients(...a),
      getClient: vi.fn(),
      createClient: vi.fn(),
      updateClient: vi.fn(),
      listClientAccounts: vi.fn(),
      cascadePreview: vi.fn(),
      toggleActive: vi.fn(),
      deleteClient: vi.fn(),
      exportClientsCsv: vi.fn(),
    },
  }
})

const notifySuccess = vi.fn()
const notifyError = vi.fn()
const notifyApiError = vi.fn()
vi.mock('../utils/notify', () => ({
  notify: {
    success: (...a: unknown[]) => notifySuccess(...a),
    error: (...a: unknown[]) => notifyError(...a),
    apiError: (...a: unknown[]) => notifyApiError(...a),
    info: vi.fn(),
    confirm: vi.fn().mockResolvedValue(true),
  },
}))

// Site-wide USPS_PROVIDER lookup — mocked per test.
const systemSettingsList = vi.fn()
vi.mock('../api/systemSettingsService', () => ({
  systemSettingsService: {
    list: (...a: unknown[]) => systemSettingsList(...a),
    update: vi.fn(),
    getUspsProviderReadiness: vi.fn(),
  },
}))

// ===== Test render helper =====

function Outletish() {
  return <Outlet context={{ registerRefresh: () => {} }} />
}

function renderPage(Page: ComponentType, path: string) {
  const store = configureStore({
    reducer: combineReducers({ carriers: carrierReducer, orders: orderReducer }),
    middleware: (getDefault) => getDefault({ serializableCheck: false }),
  })
  return render(
    <Provider store={store}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route element={<Outletish />}>
            <Route path="/settings/carriers" element={<Page />} />
          </Route>
        </Routes>
      </MemoryRouter>
    </Provider>,
  )
}

async function loadPage(): Promise<ComponentType> {
  const mod = await import('./CarrierConnections')
  return mod.default
}

beforeEach(() => {
  listAccounts.mockReset().mockResolvedValue([])
  getPlatformCredentials.mockReset().mockResolvedValue({ data: { found: false } })
  listClients.mockReset().mockResolvedValue({ data: { content: [] } })
  systemSettingsList.mockReset().mockResolvedValue({ data: [] })
  notifySuccess.mockReset()
  notifyError.mockReset()
  notifyApiError.mockReset()
})

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
})

describe('CarrierConnections · back from Stamps.com', () => {
  it('confirms a successful authorization', async () => {
    renderPage(await loadPage(), '/settings/carriers?seraCallback=ok')
    await waitFor(() => expect(notifySuccess).toHaveBeenCalledWith('Stamps.com authorized — USPS is connected.'))
    expect(notifyError).not.toHaveBeenCalled()
  })

  it("shows why it failed, in Stamps.com's words", async () => {
    renderPage(await loadPage(), '/settings/carriers?seraCallback=error&detail=carrier+returned+access_denied')
    await waitFor(() => expect(notifyError).toHaveBeenCalledWith({
      title: 'Stamps.com authorization failed',
      body: 'carrier returned access_denied',
    }))
  })

  it('says nothing on a normal visit', async () => {
    renderPage(await loadPage(), '/settings/carriers')
    await waitFor(() => expect(listAccounts).toHaveBeenCalled())
    expect(notifySuccess).not.toHaveBeenCalled()
    expect(notifyError).not.toHaveBeenCalled()
  })
})
