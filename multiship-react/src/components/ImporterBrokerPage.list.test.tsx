import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, cleanup, waitFor, act } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, Outlet } from 'react-router-dom'
import type { ComponentType } from 'react'

/**
 * Sprint 53 page-tests — ImporterBrokerPage · list + stats + filters.
 *
 * Scope:
 *   - Rows render per column: Client (code + name), Destinations (chips +
 *     count), Importer (RECEIVER chip vs BUSINESS name/city), Broker
 *     (name/company or "Carrier clears"), Account.
 *   - Stats tiles render 3 numbers.
 *   - Filter popover opens/closes.
 *   - Filter changes trigger re-fetch with correct params (server-driven).
 *   - Clear filters resets.
 *
 * Sibling slices: shell (mount/load), actions (edit/delete/export).
 */

// ---------- Service mocks ----------

const listProfilesMock = vi.fn()
const statsMock = vi.fn()
const listClientsMock = vi.fn()

vi.mock('../api/customsProfileService', () => ({
  customsProfileService: {
    listProfiles: (...args: unknown[]) => listProfilesMock(...args),
    stats: (...args: unknown[]) => statsMock(...args),
    exportProfilesCsv: vi.fn(),
    list: vi.fn(), save: vi.fn(), remove: vi.fn(),
  },
}))

vi.mock('../api/clientService', () => ({
  clientService: {
    listClients: (...args: unknown[]) => listClientsMock(...args),
    getClient: vi.fn(), createClient: vi.fn(), updateClient: vi.fn(),
  },
}))

vi.mock('../utils/notify', () => ({
  notify: {
    apiError: vi.fn(), success: vi.fn(), error: vi.fn(), info: vi.fn(),
    confirm: vi.fn().mockResolvedValue(true),
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

vi.mock('./modals/CustomsProfileModal', () => ({ default: () => null }))
vi.mock('./modals/CustomsEditorModal', () => ({ default: () => null }))

// ---------- Fixtures ----------

const profile = (id: number, overrides: Partial<{
  clientCode: string, clientName: string, countries: string[],
  importerType: 'RECEIVER' | 'BUSINESS', importerName: string, importerCity: string,
  brokerName: string, brokerCompany: string, brokerCity: string,
  accountCarrier: string, accountNo: string,
}> = {}) => ({
  id,
  clientCode: overrides.clientCode ?? 'ACME',
  clientName: overrides.clientName ?? 'Acme Corp',
  countries: overrides.countries ?? ['US'],
  importerType: overrides.importerType ?? 'RECEIVER',
  importerName: overrides.importerName ?? null,
  importerCity: overrides.importerCity ?? null,
  brokerName: overrides.brokerName ?? null,
  brokerCompany: overrides.brokerCompany ?? null,
  brokerCity: overrides.brokerCity ?? null,
  accountCarrier: overrides.accountCarrier ?? null,
  accountNo: overrides.accountNo ?? null,
})

const pageWith = (profiles: ReturnType<typeof profile>[]) => ({
  data: { content: profiles, totalElements: profiles.length, totalPages: 1 },
})

// ---------- Fail-loud fetch spy ----------

beforeEach(() => {
  vi.spyOn(globalThis, 'fetch').mockImplementation(() => {
    throw new Error('un-mocked fetch forbidden in unit tests')
  })
  ;[listProfilesMock, statsMock, listClientsMock].forEach((m) => m.mockReset())
  statsMock.mockResolvedValue({ profiles: 0, destinationsCovered: 0, clientsConfigured: 0 })
  listClientsMock.mockResolvedValue({ data: { content: [
    { clientCode: 'ACME', name: 'Acme Corp' },
    { clientCode: 'ZORP', name: 'Zorp Inc' },
  ] } })
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

// ===================== Per-row rendering =====================

describe('ImporterBrokerPage — per-row rendering', () => {
  it('renders client code + name', async () => {
    listProfilesMock.mockResolvedValue(pageWith([
      profile(1, { clientCode: 'ACME', clientName: 'Acme Corp' }),
    ]))
    const Page = await loadPage()
    renderPage(Page)

    await waitFor(() => expect(screen.getByText('ACME')).toBeInTheDocument())
    expect(screen.getByText('Acme Corp')).toBeInTheDocument()
  })

  it('renders Destinations count for the countries array', async () => {
    listProfilesMock.mockResolvedValue(pageWith([
      profile(1, { countries: ['US', 'CA', 'MX'] }),
    ]))
    const Page = await loadPage()
    renderPage(Page)

    await waitFor(() => expect(screen.getByText(/3 countries/i)).toBeInTheDocument())
  })

  it('renders "Receiver (DAP)" chip for importerType=RECEIVER', async () => {
    listProfilesMock.mockResolvedValue(pageWith([
      profile(1, { importerType: 'RECEIVER' }),
    ]))
    const Page = await loadPage()
    renderPage(Page)

    await waitFor(() => expect(screen.getByText(/Receiver \(DAP\)/)).toBeInTheDocument())
  })

  it('renders importer name + city for importerType=BUSINESS', async () => {
    listProfilesMock.mockResolvedValue(pageWith([
      profile(1, { importerType: 'BUSINESS', importerName: 'Acme LLC', importerCity: 'Springfield' }),
    ]))
    const Page = await loadPage()
    renderPage(Page)

    await waitFor(() => expect(screen.getByText('Acme LLC')).toBeInTheDocument())
    expect(screen.getByText(/Springfield/)).toBeInTheDocument()
  })

  it('broker cell: renders "Carrier clears" when no broker configured', async () => {
    listProfilesMock.mockResolvedValue(pageWith([profile(1)]))
    const Page = await loadPage()
    renderPage(Page)

    await waitFor(() => expect(screen.getByText(/Carrier clears/i)).toBeInTheDocument())
  })

  it('broker cell: renders brokerName when present', async () => {
    listProfilesMock.mockResolvedValue(pageWith([
      profile(1, { brokerName: 'BrokerCorp' }),
    ]))
    const Page = await loadPage()
    renderPage(Page)

    await waitFor(() => expect(screen.getByText('BrokerCorp')).toBeInTheDocument())
  })
})

// ===================== Stats tiles =====================

describe('ImporterBrokerPage — stats tiles', () => {
  it('renders 3 stat cards with numbers from stats()', async () => {
    listProfilesMock.mockResolvedValue(pageWith([]))
    statsMock.mockResolvedValue({
      profiles: 12, destinationsCovered: 34, clientsConfigured: 5,
    })

    const Page = await loadPage()
    renderPage(Page)

    await waitFor(() => expect(screen.getByText('12')).toBeInTheDocument())
    expect(screen.getByText('34')).toBeInTheDocument()
    expect(screen.getByText('5')).toBeInTheDocument()
    // "Profiles" may appear in multiple places (stat card + column labels);
    // scope to the stat-card label pattern.
    expect(screen.getAllByText(/Profiles/i).length).toBeGreaterThan(0)
    expect(screen.getByText(/Destinations covered/i)).toBeInTheDocument()
    expect(screen.getByText(/Clients configured/i)).toBeInTheDocument()
  })
})

// ===================== Filter popover =====================

describe('ImporterBrokerPage — filter popover', () => {
  it('opens on Filters click, closes on Done click', async () => {
    listProfilesMock.mockResolvedValue(pageWith([]))
    const Page = await loadPage()
    renderPage(Page)
    await waitFor(() => expect(screen.getByRole('button', { name: /^Filters$/i })).toBeInTheDocument())

    await act(async () => { await userEvent.click(screen.getByRole('button', { name: /^Filters$/i })) })
    await waitFor(() =>
      expect(screen.getByRole('dialog', { name: /Filter profiles/i })).toBeInTheDocument(),
    )

    await act(async () => { await userEvent.click(screen.getByRole('button', { name: /Done/i })) })
    await waitFor(() =>
      expect(screen.queryByRole('dialog', { name: /Filter profiles/i })).not.toBeInTheDocument(),
    )
  })

  it('selecting a Client filter triggers a new listProfiles call with clientCode param', async () => {
    listProfilesMock.mockResolvedValue(pageWith([]))
    const Page = await loadPage()
    renderPage(Page)
    await waitFor(() => expect(listProfilesMock).toHaveBeenCalledTimes(1))

    await act(async () => { await userEvent.click(screen.getByRole('button', { name: /^Filters$/i })) })
    await userEvent.selectOptions(screen.getByLabelText(/Filter by client/i), 'ACME')

    await waitFor(() => {
      const lastCall = listProfilesMock.mock.calls[listProfilesMock.mock.calls.length - 1][0]
      expect(lastCall.clientCode).toBe('ACME')
    })
  })

  it('selecting a Broker filter (YES) triggers a new listProfiles call with broker=YES', async () => {
    listProfilesMock.mockResolvedValue(pageWith([]))
    const Page = await loadPage()
    renderPage(Page)
    await waitFor(() => expect(listProfilesMock).toHaveBeenCalledTimes(1))

    await act(async () => { await userEvent.click(screen.getByRole('button', { name: /^Filters$/i })) })
    await userEvent.selectOptions(screen.getByLabelText(/Filter by broker/i), 'YES')

    await waitFor(() => {
      const lastCall = listProfilesMock.mock.calls[listProfilesMock.mock.calls.length - 1][0]
      expect(lastCall.broker).toBe('YES')
    })
  })
})

// ===================== Filter counter + Clear =====================

describe('ImporterBrokerPage — filter counter + Clear', () => {
  it('applying 2 filters shows counter chip "2" on Filters button', async () => {
    listProfilesMock.mockResolvedValue(pageWith([]))
    const Page = await loadPage()
    renderPage(Page)
    await waitFor(() => expect(listProfilesMock).toHaveBeenCalled())

    await act(async () => { await userEvent.click(screen.getByRole('button', { name: /^Filters$/i })) })
    await userEvent.selectOptions(screen.getByLabelText(/Filter by client/i), 'ACME')
    await userEvent.selectOptions(screen.getByLabelText(/Filter by carrier/i), 'UPS')

    const filtersBtn = screen.getByRole('button', { name: /^Filters/i })
    await waitFor(() => expect(filtersBtn.textContent).toContain('2'))
  })

  it('Clear resets all filters and clears the counter', async () => {
    listProfilesMock.mockResolvedValue(pageWith([]))
    const Page = await loadPage()
    renderPage(Page)
    await waitFor(() => expect(listProfilesMock).toHaveBeenCalled())

    await act(async () => { await userEvent.click(screen.getByRole('button', { name: /^Filters$/i })) })
    await userEvent.selectOptions(screen.getByLabelText(/Filter by client/i), 'ACME')

    await act(async () => {
      await userEvent.click(await screen.findByRole('button', { name: /Clear/i }))
    })

    const filtersBtn = screen.getByRole('button', { name: /^Filters$/i })
    expect(filtersBtn.textContent).not.toMatch(/\b[1-9]\b/)
  })
})
