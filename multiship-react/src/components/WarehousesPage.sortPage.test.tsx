import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, cleanup, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Outlet, Route, Routes } from 'react-router-dom'
import type { ComponentType } from 'react'

/**
 * Sprint 54 — Warehouses page: sorting + pagination slice.
 *
 * Owns the "Sort + Pagination" test slice for `/settings/warehouses`.
 * All network-touching services are mocked; we assert against
 * `warehouseService.listWarehouses` call params (sortBy / sortDirection /
 * page / size) and the toolbar's page-indicator UI.
 *
 * The page consumes an outlet context (`registerRefresh`) from
 * SettingsLayout — the wrapper below fabricates a minimal Outlet
 * so `useOutletContext` resolves without pulling the real layout.
 */

// ===== Service mocks =====

const listWarehouses = vi.fn()
const toggleActive = vi.fn()
const createWarehouse = vi.fn()
const updateWarehouse = vi.fn()
const deleteWarehouse = vi.fn()
const listForClient = vi.fn()
const attach = vi.fn()

vi.mock('../api/warehouseService', () => ({
  warehouseService: {
    listWarehouses: (...args: unknown[]) => listWarehouses(...args),
    toggleActive: (...args: unknown[]) => toggleActive(...args),
    createWarehouse: (...args: unknown[]) => createWarehouse(...args),
    updateWarehouse: (...args: unknown[]) => updateWarehouse(...args),
    deleteWarehouse: (...args: unknown[]) => deleteWarehouse(...args),
  },
  clientWarehouseService: {
    listForClient: (...args: unknown[]) => listForClient(...args),
    attach: (...args: unknown[]) => attach(...args),
  },
}))

// useAppSession is a store-backed hook — swap for a plain stub so
// admin-role is stable across renders and no subscription bookkeeping
// leaks into the test.
const sessionMock = vi.fn(() => ({
  username: 'admin',
  role: 'ADMIN',
  connectedCarriers: [],
  hasConnectedCarrier: false,
}))
vi.mock('../hooks/useAppSession', () => ({
  useAppSession: () => sessionMock(),
  clearAuthSession: vi.fn(),
}))

// Silence notify: toast side-effects are irrelevant here.
vi.mock('../utils/notify', () => ({
  notify: {
    success: vi.fn(),
    error: vi.fn(),
    info: vi.fn(),
    apiError: vi.fn(),
    confirm: vi.fn().mockResolvedValue(true),
  },
}))

// Portal + modals — untouched by sort/pagination flows. Stub out to
// keep the DOM minimal and avoid pulling in their own service deps.
vi.mock('./workspace/PortalMenu', () => ({
  default: () => null,
}))
vi.mock('./modals/AttachClientsModal', () => ({
  default: () => null,
}))
vi.mock('./modals/WarehouseEditorModal', () => ({
  default: () => null,
}))

async function loadPage(): Promise<ComponentType> {
  const mod = await import('./WarehousesPage')
  return mod.default
}

/**
 * Build a page-shaped response with N sequential rows on the requested
 * page. Simulates a paged backend so pagination clicks translate into
 * real page-number changes.
 */
function pageResponse({
  pageNumber = 0,
  pageSize = 25,
  totalElements = 1,
  totalPages = 1,
  rows,
}: {
  pageNumber?: number
  pageSize?: number
  totalElements?: number
  totalPages?: number
  rows?: Array<Record<string, unknown>>
}) {
  const content =
    rows ??
    [
      {
        id: 1,
        code: 'WH1',
        name: 'Warehouse 1',
        address: { city: 'Chicago', state: 'IL', country: 'US' },
        ownerType: 'PLATFORM',
        ownerClientCode: null,
        active: true,
        attachedClientCount: 0,
        createdAt: '2026-08-01T00:00:00',
        updatedAt: '2026-08-01T00:00:00',
      },
    ]
  return {
    data: {
      content,
      pageNumber,
      pageSize,
      totalElements,
      totalPages,
    },
  }
}

function renderPage(Page: ComponentType) {
  return render(
    <MemoryRouter initialEntries={['/settings/warehouses']}>
      <Routes>
        <Route
          path="/settings"
          element={<Outlet context={{ registerRefresh: () => {} }} />}
        >
          <Route path="warehouses" element={<Page />} />
        </Route>
      </Routes>
    </MemoryRouter>,
  )
}

/** Wait for the first fetch call the page fires on mount to settle. */
async function waitForFirstFetch() {
  // Call was fired…
  await waitFor(() => {
    expect(listWarehouses).toHaveBeenCalled()
  })
  // …and the promise settled — the "Loading warehouses…" placeholder is gone
  // and the real toolbar is on-screen. Without this second wait the test
  // can proceed to click prev/next while the initial async fetch is still
  // pending, when the DOM is just the loading paragraph.
  await waitFor(() => {
    expect(screen.queryByText(/loading warehouses/i)).toBeNull()
  })
}

/** Return the last params object handed to listWarehouses. */
function lastListParams(): Record<string, unknown> {
  const calls = listWarehouses.mock.calls
  return calls[calls.length - 1][0] as Record<string, unknown>
}

beforeEach(() => {
  listWarehouses.mockReset().mockResolvedValue(pageResponse({}))
  toggleActive.mockReset()
  createWarehouse.mockReset()
  updateWarehouse.mockReset()
  deleteWarehouse.mockReset()
  listForClient.mockReset().mockResolvedValue({ data: [] })
  attach.mockReset()
  sessionMock.mockReset().mockReturnValue({
    username: 'admin',
    role: 'ADMIN',
    connectedCarriers: [],
    hasConnectedCarrier: false,
  })
  try { localStorage.clear() } catch { /* ignore */ }
})

afterEach(() => {
  cleanup()
})

// ===== Positive =====

describe('WarehousesPage · sort + pagination · positive', () => {
  it('mounts with the default sort: sortBy=code, sortDirection=ASC, page=0, size=25', async () => {
    const Page = await loadPage()
    renderPage(Page)
    await waitForFirstFetch()

    const params = lastListParams()
    expect(params.sortBy).toBe('code')
    expect(params.sortDirection).toBe('ASC')
    expect(params.page).toBe(0)
    expect(params.size).toBe(25)
  }, 15000)

  it('renders the pagination indicator (X / Y) in the toolbar', async () => {
    listWarehouses.mockResolvedValue(pageResponse({ totalElements: 60, totalPages: 3 }))
    const Page = await loadPage()
    renderPage(Page)

    await waitFor(() => {
      // "1 / 3" appears in the header pagination cluster (see AdvancedDataTable).
      expect(screen.getByText(/^\s*1\s*\/\s*3\s*$/)).toBeTruthy()
    })
  })

  it('renders the total elements in the caption ("Showing N of M …")', async () => {
    listWarehouses.mockResolvedValue(pageResponse({ totalElements: 60, totalPages: 3 }))
    const Page = await loadPage()
    renderPage(Page)

    await waitFor(() => {
      expect(screen.getByText(/Showing\s+1\s+of\s+60\s+warehouses?/i)).toBeTruthy()
    })
  })

  it('toggles Code sort ASC → DESC when the Code header is clicked', async () => {
    const Page = await loadPage()
    renderPage(Page)
    await waitForFirstFetch()

    const user = userEvent.setup()
    // The "Code" header renders as a clickable span with the header label.
    const codeHeader = screen.getByText('Code')
    await user.click(codeHeader)

    await waitFor(() => {
      const params = lastListParams()
      expect(params.sortBy).toBe('code')
      expect(params.sortDirection).toBe('DESC')
    })
  })

  it('switches sort column when Name header is clicked (sortBy=name, direction=ASC)', async () => {
    const Page = await loadPage()
    renderPage(Page)
    await waitForFirstFetch()

    const user = userEvent.setup()
    await user.click(screen.getByText('Name'))

    await waitFor(() => {
      const params = lastListParams()
      expect(params.sortBy).toBe('name')
      // A fresh column starts ASC — a re-click would take it to DESC.
      expect(params.sortDirection).toBe('ASC')
    })
  })

  it('Next button click advances to page=1 (server-side manualPagination)', async () => {
    listWarehouses.mockResolvedValue(pageResponse({ totalElements: 60, totalPages: 3 }))
    const Page = await loadPage()
    renderPage(Page)
    await waitForFirstFetch()

    const user = userEvent.setup()
    // Next button label is a right-chevron (›) rendered as text content of a button.
    const buttons = screen.getAllByRole('button')
    const next = buttons.find((b) => b.textContent?.trim() === '›')
    expect(next).toBeTruthy()
    await user.click(next!)

    await waitFor(() => {
      const params = lastListParams()
      expect(params.page).toBe(1)
    })
  })

  it('Prev button on page 2 decrements to page=0', async () => {
    listWarehouses.mockResolvedValue(pageResponse({ totalElements: 60, totalPages: 3 }))
    const Page = await loadPage()
    renderPage(Page)
    await waitForFirstFetch()

    const user = userEvent.setup()
    const buttons = screen.getAllByRole('button')
    const next = buttons.find((b) => b.textContent?.trim() === '›')
    await user.click(next!)

    await waitFor(() => {
      expect(lastListParams().page).toBe(1)
    })

    // Prev is the left-chevron button.
    const prev = screen.getAllByRole('button').find((b) => b.textContent?.trim() === '‹')
    expect(prev).toBeTruthy()
    await user.click(prev!)

    await waitFor(() => {
      expect(lastListParams().page).toBe(0)
    })
  })

  it('changing rows-per-page updates size + resets page to 0', async () => {
    listWarehouses.mockResolvedValue(pageResponse({ totalElements: 60, totalPages: 3 }))
    const Page = await loadPage()
    renderPage(Page)
    await waitForFirstFetch()

    const user = userEvent.setup()
    const sizeSelect = screen.getByLabelText('Rows per page') as HTMLSelectElement
    await user.selectOptions(sizeSelect, '50')

    await waitFor(() => {
      const params = lastListParams()
      expect(params.size).toBe(50)
      expect(params.page).toBe(0)
    })
  })

  it('search input change resets page to 0 (filter → page snap-back)', async () => {
    listWarehouses.mockResolvedValue(pageResponse({ totalElements: 60, totalPages: 3 }))
    const Page = await loadPage()
    renderPage(Page)
    await waitForFirstFetch()

    const user = userEvent.setup()
    // Advance to page 2 first so we can prove the reset.
    const next = screen.getAllByRole('button').find((b) => b.textContent?.trim() === '›')
    await user.click(next!)
    await waitFor(() => {
      expect(lastListParams().page).toBe(1)
    })

    // Type into the toolbar search — 350ms debounce lives on debouncedSearch.
    const searchBox = screen.getByPlaceholderText(/search code, name, city/i)
    await user.type(searchBox, 'wh1')

    await waitFor(
      () => {
        const params = lastListParams()
        expect(params.search).toBe('wh1')
        expect(params.page).toBe(0)
      },
      { timeout: 3000 },
    )
  })
})

// ===== Negative / edge =====

describe('WarehousesPage · sort + pagination · negative / edge', () => {
  it('Prev button is disabled on page 0', async () => {
    const Page = await loadPage()
    renderPage(Page)
    await waitForFirstFetch()

    await waitFor(() => {
      // "1 / 1" indicator confirms pagination cluster rendered.
      expect(screen.getByText(/^\s*1\s*\/\s*1\s*$/)).toBeTruthy()
    })
    const prev = screen.getAllByRole('button').find((b) => b.textContent?.trim() === '‹')
    expect(prev).toBeTruthy()
    expect((prev as HTMLButtonElement).disabled).toBe(true)
  })

  it('Next button is disabled on the last page', async () => {
    // Single-page result — Next has nowhere to go.
    listWarehouses.mockResolvedValue(pageResponse({ totalElements: 1, totalPages: 1 }))
    const Page = await loadPage()
    renderPage(Page)
    await waitForFirstFetch()

    await waitFor(() => {
      expect(screen.getByText(/^\s*1\s*\/\s*1\s*$/)).toBeTruthy()
    })
    const next = screen.getAllByRole('button').find((b) => b.textContent?.trim() === '›')
    expect(next).toBeTruthy()
    expect((next as HTMLButtonElement).disabled).toBe(true)
  })

  it('zero-results still shows a page indicator (1 / 1) — component clamps pageCount ≥ 1', async () => {
    listWarehouses.mockResolvedValue(
      pageResponse({ rows: [], totalElements: 0, totalPages: 0 }),
    )
    const Page = await loadPage()
    renderPage(Page)
    await waitForFirstFetch()

    // WarehousesPage clamps totalPages via Math.max(…, 1); the AdvancedDataTable
    // then renders "1 / 1" instead of "0 / 0".
    await waitFor(() => {
      expect(screen.getByText(/^\s*1\s*\/\s*1\s*$/)).toBeTruthy()
    })
    // Empty-state text lands in the body.
    expect(screen.getByText(/no warehouses registered yet/i)).toBeTruthy()
  })
})

// ===== Cross-cutting =====

describe('WarehousesPage · sort + pagination · cross-cutting', () => {
  it('USER role still sees sort headers + pagination controls', async () => {
    sessionMock.mockReturnValue({
      username: 'user',
      role: 'USER',
      connectedCarriers: [],
      hasConnectedCarrier: false,
    })
    listWarehouses.mockResolvedValue(pageResponse({ totalElements: 30, totalPages: 2 }))
    const Page = await loadPage()
    renderPage(Page)
    await waitForFirstFetch()

    await waitFor(() => {
      expect(screen.getByText(/^\s*1\s*\/\s*2\s*$/)).toBeTruthy()
    })
    // Sortable Code header is present regardless of role.
    expect(screen.getAllByText('Code').length).toBeGreaterThan(0)
    // Rows-per-page picker present too.
    expect(screen.getByLabelText('Rows per page')).toBeTruthy()
  })

  it('TENANT role also sees sort headers + pagination controls', async () => {
    sessionMock.mockReturnValue({
      username: 'tenant',
      role: 'TENANT',
      connectedCarriers: [],
      hasConnectedCarrier: false,
    })
    listWarehouses.mockResolvedValue(pageResponse({ totalElements: 30, totalPages: 2 }))
    const Page = await loadPage()
    const { container } = renderPage(Page)
    await waitForFirstFetch()

    await waitFor(() => {
      expect(screen.getByText(/^\s*1\s*\/\s*2\s*$/)).toBeTruthy()
    })
    // Prev + Next chevron buttons exist inside the toolbar.
    const buttons = within(container).getAllByRole('button')
    const chevrons = buttons.filter((b) => {
      const t = b.textContent?.trim()
      return t === '‹' || t === '›'
    })
    expect(chevrons.length).toBe(2)
  })
})
