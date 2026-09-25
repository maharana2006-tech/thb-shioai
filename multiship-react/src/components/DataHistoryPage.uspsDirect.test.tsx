import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, cleanup, act } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { BrowserRouter, MemoryRouter, Route, Routes } from 'react-router-dom'
import { Provider } from 'react-redux'
import { combineReducers, configureStore } from '@reduxjs/toolkit'
import { useEffect } from 'react'
import carrierReducer from '../store/carrierSlice'
import orderReducer from '../store/orderSlice'

/**
 * PR-G4 — DataHistoryPage USPS_DIRECT UX coverage (audit U2 + U3).
 *
 * <ol>
 *   <li>{@code BulkLabelQueueBadge} is mounted at the top of the page
 *       so ops see queue depth from anywhere.</li>
 *   <li>MpsProgressCard renders inside expanded batch rows for USPS
 *       parent orders, deduped by orderNo.</li>
 *   <li>MpsProgressCard is NOT rendered when the batch contains only
 *       non-USPS rows.</li>
 * </ol>
 *
 * <p>All external services are mocked at module boundary; useMpsProgress
 * is stubbed so mounting the card doesn't kick off polling — it renders
 * the "No MPS progress found" placeholder which we assert against.
 */

// ---------- Service mocks ----------

const listHistory = vi.fn()
const getHistory = vi.fn()
const generationProgress = vi.fn()
type Row = { rowNumber: number; clientCode?: string; errors?: string[]; generatedStatus?: string | null }
// The batch page reads its header and a page of rows; both come from the same
// getHistory fixture here, so each test still sets one batch.
const bulkBatch = vi.fn((id: number) => getHistory(id).then((res: { data?: Record<string, unknown> }) =>
  ({ data: res.data ? { ...res.data, rows: undefined } : res.data })))
const historyRows = vi.fn<(id: number, q?: unknown) => Promise<unknown>>((id: number) => getHistory(id).then((res: { data?: { rows?: Row[] } }) => {
  const rows = res.data?.rows ?? []
  const attention = rows.filter((r) => (r.errors?.length ?? 0) > 0 || r.generatedStatus === 'FAILED').length
  return { data: { rows, total: rows.length, all: rows.length, attention,
    pending: rows.filter((r) => r.generatedStatus !== 'GENERATED').length,
    clientCodes: [...new Set(rows.map((r) => r.clientCode).filter(Boolean))] } }
}))
vi.mock('../api/orderImportService', () => ({
  orderImportService: {
    listHistory: (...a: unknown[]) => listHistory(...a),
    getHistory: (...a: unknown[]) => getHistory(...a),
    historyRows: (id: number, q?: unknown) => historyRows(id, q),
    generationProgress: (...a: unknown[]) => generationProgress(...a),
    listStaging: vi.fn().mockResolvedValue({ data: [] }),
    generateLabels: vi.fn(),
    waitForGeneration: vi.fn(),
    cancelGeneration: vi.fn(),
    deleteBatch: vi.fn(),
    restoreBatch: vi.fn(),
    emptyTrash: vi.fn(),
    setBillingMode: vi.fn(),
    generateRowLabel: vi.fn(),
    updateRow: vi.fn(),
  },
}))

// The batch grid joins the batch's orders (note, created date, tracking link) and
// borrows the Orders page's per-order actions; none of them fire on render.
vi.mock('../api/orderService', () => ({
  orderService: {
    listOrders: vi.fn().mockResolvedValue({ data: { content: [], totalElements: 0, totalPages: 1 } }),
    printDocuments: vi.fn(),
    getLabelPdf: vi.fn(),
    getCommercialInvoicePdf: vi.fn(),
    voidLabel: vi.fn(),
    updateNote: vi.fn(),
  },
}))

const notifyInfo = vi.fn()
const notifySuccess = vi.fn()
const notifyError = vi.fn()
const notifyApiError = vi.fn()
const notifyConfirm = vi.fn().mockResolvedValue(false)
vi.mock('../utils/notify', () => ({
  notify: {
    info: (...a: unknown[]) => notifyInfo(...a),
    success: (...a: unknown[]) => notifySuccess(...a),
    error: (...a: unknown[]) => notifyError(...a),
    apiError: (...a: unknown[]) => notifyApiError(...a),
    confirm: (...a: unknown[]) => notifyConfirm(...a),
  },
  notifyStore: {
    subscribe: vi.fn(() => () => {}),
    snapshot: vi.fn(() => []),
    dismiss: vi.fn(),
  },
}))

vi.mock('../hooks/useAppSession', () => ({
  useAppSession: () => ({
    role: 'ADMIN',
    displayName: 'test',
    tenantId: 't',
    email: 't@t',
  }),
}))

vi.mock('../hooks/useEventStream', () => ({
  useEventStream: () => ({ status: 'closed' }),
}))

// BulkLabelQueueBadge's service — depth=0 default so pill is silent.
const getMetricsMock = vi.fn().mockResolvedValue({
  data: {
    depth: 0,
    processing: 0,
    estimatedWaitSeconds: 0,
    totalPerHourCap: 55,
    tenantCode: null,
  },
})
vi.mock('../api/uspsLabelQueueService', () => ({
  uspsLabelQueueService: {
    getMetrics: (...a: unknown[]) => getMetricsMock(...a),
    getMpsProgress: vi.fn(),
  },
}))

// useMpsProgress — stubbed so MpsProgressCard renders its "No MPS
// progress found" placeholder without polling.
vi.mock('../hooks/useMpsProgress', () => ({
  useMpsProgress: () => ({
    progress: null,
    loading: false,
    error: null,
    refresh: vi.fn(),
  }),
}))

vi.mock('../api/apiClient', () => {
  class ApiError extends Error {
    status: number
    errorCode: string | null
    payload: unknown
    constructor(
      message: string,
      status = 500,
      errorCode: string | null = null,
      payload: unknown = null,
    ) {
      super(message)
      this.name = 'ApiError'
      this.status = status
      this.errorCode = errorCode
      this.payload = payload
    }
  }
  return {
    ApiError,
    isAbortError: (err: unknown) =>
      err != null && typeof err === 'object' && (err as { name?: string }).name === 'AbortError',
    apiClient: {
      get: vi.fn(),
      post: vi.fn(),
      put: vi.fn(),
      delete: vi.fn(),
    },
  }
})

// Sub-components used by the page — stubbed so we don't have to
// wire the whole editable grid.
const listBatches = vi.fn()
const bulkSummary = vi.fn()
vi.mock('../api/bulkService', () => ({
  bulkService: {
    listBatches: (...a: unknown[]) => listBatches(...a),
    summary: (...a: unknown[]) => bulkSummary(...a),
    batch: (id: number) => bulkBatch(id),
  },
}))
const pageOf = (content: unknown[]) => ({ data: { content, totalElements: content.length, totalPages: 1, number: 0, size: 25 } })
const summaryOf = (over: Record<string, unknown> = {}) => ({ data: {
  total: 0, readyToGenerate: 0, generating: 0, needsFixes: 0, completedThisWeek: 0,
  statusCounts: { ALL: 0 }, creators: [], ...over,
} })
const wmsBatches = vi.fn()
const wmsPull = vi.fn()
vi.mock('../api/wmsService', () => ({
  wmsService: { batches: (...a: unknown[]) => wmsBatches(...a), pull: (...a: unknown[]) => wmsPull(...a) },
}))
vi.mock('./modals/ShipViaCodes', () => ({
  ShipViaCodesPanel: () => <div data-testid="ship-via-panel" />,
  AddShipViaMappingDialog: () => null,
}))
vi.mock('./modals/OrderImportModal', () => ({
  default: () => <div data-testid="import-modal-stub" />,
}))

/**
 * Custom AdvancedDataTable stub — real page calls
 * {@code onRowExpand(b)} then renders {@code renderExpanded(b)} inside
 * an accordion. Our stub auto-expands EVERY row on mount so the
 * MpsProgressCard section renders without needing to drive an
 * expand click; that would require full react-table wiring we don't
 * need for a UX-branch presence test.
 */
// Named FC so eslint's react-hooks/rules-of-hooks recognises useEffect
// as legally hosted inside a component.
function AdvancedDataTableStub<T extends { id?: number | string }>(props: {
  data?: T[]
  renderExpanded?: (row: T) => React.ReactNode
  onRowExpand?: (row: T) => void
  search?: { value: string; onChange: (v: string) => void; placeholder?: string }
  filterToggle?: React.ReactNode
  toolbarActions?: React.ReactNode
  emptyState?: React.ReactNode
  columns?: { id?: string; header?: unknown }[]
  initialHiddenColumns?: string[]
}) {
  const hidden = new Set(props.initialHiddenColumns ?? [])
  const headers = (props.columns ?? []).filter((c) => c.id && !hidden.has(c.id)).map((c) => (typeof c.header === 'string' ? c.header : ''))
  const rows = props.data ?? []
  // Fire onRowExpand once per row on mount so ensureRows populates
  // rowsById in the parent — mirrors clicking every row header.
  useEffect(() => {
    if (!props.onRowExpand) return
    for (const r of rows) props.onRowExpand(r)
    // eslint-disable-next-line react-hooks/exhaustive-deps -- test stub; deps intentionally minimal so onRowExpand fires only on row-count change, mirroring clicking each row header once
  }, [rows.length])
  return (
    <div data-testid="advanced-data-table-stub">
      {headers.length ? <div data-testid="visible-headers">{headers.join('|')}</div> : null}
      {props.search ? (
        <input value={props.search.value} onChange={(e) => props.search?.onChange(e.target.value)} placeholder={props.search.placeholder} />
      ) : null}
      {props.filterToggle}
      {props.toolbarActions}
      {rows.length === 0 ? props.emptyState : null}
      {rows.map((row, i) => (
        <div
          key={String(row.id ?? i)}
          data-testid={`batch-row-${row.id ?? i}`}
        >
          {props.renderExpanded ? props.renderExpanded(row) : null}
        </div>
      ))}
    </div>
  )
}
vi.mock('./workspace/AdvancedDataTable', () => ({
  default: AdvancedDataTableStub,
}))
vi.mock('./VirtualTable', () => ({
  default: ({ empty }: { empty?: React.ReactNode }) => (
    <div data-testid="virtual-table-stub">{empty}</div>
  ),
}))
vi.mock('./batchGrid', () => ({
  DH_COLUMNS: [],
  GridCell: () => null,
  RowIssuesIcon: () => null,
  RowChannelChip: () => null,
  bucketRowErrors: () => ({ byField: {}, rowLevel: [] }),
}))

// ---------- Test harness ----------

/** Renders the page under its real route, starting at {@code path}. */
async function renderAt(path: string) {
  const { default: DataHistoryPage } = await import('./DataHistoryPage')
  const store = configureStore({
    reducer: combineReducers({ carriers: carrierReducer, orders: orderReducer }),
    middleware: (getDefaultMiddleware) => getDefaultMiddleware({ serializableCheck: false }),
  })
  return render(
    <Provider store={store}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path="/bulk/batches/:batchId" element={<DataHistoryPage />} />
          <Route path="/bulk/:tab" element={<DataHistoryPage />} />
          <Route path="/orders/api-batches" element={<DataHistoryPage apiBatches />} />
          <Route path="/orders/api-batches/:batchId" element={<DataHistoryPage apiBatches />} />
        </Routes>
      </MemoryRouter>
    </Provider>,
  )
}

async function loadAndRender() {
  const { default: DataHistoryPage } = await import('./DataHistoryPage')
  const store = configureStore({
    reducer: combineReducers({ carriers: carrierReducer, orders: orderReducer }),
    middleware: (getDefaultMiddleware) =>
      getDefaultMiddleware({ serializableCheck: false }),
  })
  return render(
    <Provider store={store}>
      <BrowserRouter>
        <DataHistoryPage />
      </BrowserRouter>
    </Provider>,
  )
}

// ---------- Fixtures ----------

const batchSummary = (over: Record<string, unknown> = {}) => {
  const id = (over.id as number | undefined) ?? 100
  return {
    id,
    // In tests the "slug" mirrors the id string so tests can navigate to
    // /bulk/batches/<id> and still match against b.slug on the client.
    slug: String(id),
    createdBy: 'ops',
    fileName: 'test.csv',
    status: 'COMPLETE',
    labelBatchId: 55,
    createdAt: '2026-09-16T00:00:00Z',
    completedAt: '2026-09-16T00:05:00Z',
    totalRows: 3,
    savedRows: 3,
    invalidRows: 0,
    billingMode: 'AUTO',
    source: 'BULK',
    ...over,
  }
}

const rowUsps = (over: Record<string, unknown> = {}) => ({
  rowNumber: 1,
  carrierCode: 'USPS',
  errors: [],
  generatedOrderNo: 5001,
  generatedStatus: 'GENERATED' as const,
  generatedTrackingNumber: '9400111899999999999999',
  ...over,
})

const rowFedex = (over: Record<string, unknown> = {}) => ({
  rowNumber: 1,
  carrierCode: 'FEDEX',
  errors: [],
  generatedOrderNo: 6001,
  generatedStatus: 'GENERATED' as const,
  generatedTrackingNumber: '794611234567',
  ...over,
})

beforeEach(() => {
  vi.clearAllMocks()
  wmsBatches.mockResolvedValue({ data: [] })
  listBatches.mockResolvedValue(pageOf([]))
  bulkSummary.mockResolvedValue(summaryOf())
  listHistory.mockResolvedValue({ data: [] })
  getHistory.mockResolvedValue({ data: null })
  getMetricsMock.mockResolvedValue({
    data: {
      depth: 0,
      processing: 0,
      estimatedWaitSeconds: 0,
      totalPerHourCap: 55,
      tenantCode: null,
    },
  })
})

afterEach(() => {
  cleanup()
})


// ==================================================================
// Test 0: sanity — page renders
// ==================================================================

describe('DataHistoryPage — mounts', () => {
  it('renders without crashing', async () => {
    await loadAndRender()
    await waitFor(() => {
      expect(screen.getByRole('heading', { name: /Bulk Mailer/i })).toBeInTheDocument()
    })
  })
})

// ==================================================================
// Test 1: BulkLabelQueueBadge slot at top (audit U2)
// ==================================================================

describe('DataHistoryPage — BulkLabelQueueBadge mount (audit U2)', () => {
  it('renders the queue-badge slot at the top of the page', async () => {
    await loadAndRender()
    await waitFor(() => {
      expect(screen.getByTestId('usps-queue-badge-slot')).toBeInTheDocument()
    })
  })

  it('renders the pill itself when metrics report non-zero depth', async () => {
    getMetricsMock.mockResolvedValue({
      data: {
        depth: 7,
        processing: 2,
        estimatedWaitSeconds: 300,
        totalPerHourCap: 55,
        tenantCode: null,
      },
    })
    await loadAndRender()
    await waitFor(() => {
      expect(screen.getByTestId('usps-queue-badge')).toBeInTheDocument()
    })
    expect(screen.getByTestId('usps-queue-badge').textContent).toContain('7')
  })

  it('renders the slot in the header row, before the tab panel (top-of-page placement)', async () => {
    await loadAndRender()
    const slot = await waitFor(() => screen.getByTestId('usps-queue-badge-slot'))
    // Everything the view shows comes after the header's pill.
    const panel = screen.getByRole('region', { name: 'Import history' })
    const pos = slot.compareDocumentPosition(panel)
    expect(pos & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  })
})

// ==================================================================
// Test 2: MpsProgressCard inside expanded USPS batch (audit U3)
// ==================================================================

describe('DataHistoryPage — MpsProgressCard renders inside USPS batches (audit U3)', () => {
  it('renders the MPS progress section for a batch with USPS rows', async () => {
    listHistory.mockResolvedValue({ data: [batchSummary({ id: 100 })] })
    getHistory.mockResolvedValue({
      data: {
        ...batchSummary({ id: 100 }),
        rows: [
          rowUsps({ rowNumber: 1, generatedOrderNo: 5001 }),
          // Duplicate order — dedup should collapse to one card.
          rowUsps({ rowNumber: 2, generatedOrderNo: 5001 }),
          rowUsps({ rowNumber: 3, generatedOrderNo: 5002 }),
        ],
      },
    })

    // The rows (and the MPS cards) live on the batch's own page.
    await renderAt('/bulk/batches/100')

    // Auto-expand fires from the stub, which triggers ensureRows →
    // getHistory. Wait for the MPS section to appear.
    await waitFor(
      () => {
        expect(screen.getByTestId('usps-mps-progress-section')).toBeInTheDocument()
      },
      { timeout: 5_000 },
    )
    // Two unique USPS orders → two MpsProgressCards (each renders its
    // "empty" placeholder because useMpsProgress mock returns null).
    const section = screen.getByTestId('usps-mps-progress-section')
    const cards = section.querySelectorAll('[data-testid="mps-progress-card-empty"]')
    expect(cards.length).toBe(2)
  })

  it('does NOT render MPS section for a batch with only FEDEX rows', async () => {
    listHistory.mockResolvedValue({ data: [batchSummary({ id: 200 })] })
    getHistory.mockResolvedValue({
      data: {
        ...batchSummary({ id: 200 }),
        rows: [
          rowFedex({ rowNumber: 1, generatedOrderNo: 6001 }),
          rowFedex({ rowNumber: 2, generatedOrderNo: 6002 }),
        ],
      },
    })

    // The rows (and the MPS cards) live on the batch's own page.
    await renderAt('/bulk/batches/200')

    // Wait for the batch page to show the batch.
    await waitFor(() => {
      expect(screen.getByTestId('batch-page-header')).toBeInTheDocument()
    })
    // Give the auto-expand a beat to trigger getHistory.
    await act(async () => {
      await Promise.resolve()
      await Promise.resolve()
    })
    // No MPS section for non-USPS batches.
    expect(screen.queryByTestId('usps-mps-progress-section')).toBeNull()
  })

  it('recognises legacy USPS aliases (L01 / STAMPS) and renders one card per unique order', async () => {
    listHistory.mockResolvedValue({ data: [batchSummary({ id: 300 })] })
    getHistory.mockResolvedValue({
      data: {
        ...batchSummary({ id: 300 }),
        rows: [
          rowUsps({ rowNumber: 1, carrierCode: 'L01', generatedOrderNo: 7001 }),
          rowUsps({ rowNumber: 2, carrierCode: 'STAMPS', generatedOrderNo: 7002 }),
        ],
      },
    })

    // The rows (and the MPS cards) live on the batch's own page.
    await renderAt('/bulk/batches/300')

    await waitFor(
      () => {
        expect(screen.getByTestId('usps-mps-progress-section')).toBeInTheDocument()
      },
      { timeout: 5_000 },
    )
    const section = screen.getByTestId('usps-mps-progress-section')
    const cards = section.querySelectorAll('[data-testid="mps-progress-card-empty"]')
    expect(cards.length).toBe(2)
  })

  it('skips rows with null generatedOrderNo (never labelled — no order to poll)', async () => {
    listHistory.mockResolvedValue({ data: [batchSummary({ id: 400 })] })
    getHistory.mockResolvedValue({
      data: {
        ...batchSummary({ id: 400 }),
        rows: [
          rowUsps({ rowNumber: 1, generatedOrderNo: null }),
          rowUsps({ rowNumber: 2, generatedOrderNo: null }),
        ],
      },
    })

    // The rows (and the MPS cards) live on the batch's own page.
    await renderAt('/bulk/batches/400')

    await waitFor(() => {
      expect(screen.getByTestId('batch-page-header')).toBeInTheDocument()
    })
    await act(async () => {
      await Promise.resolve()
      await Promise.resolve()
    })
    // No section — nothing to poll.
    expect(screen.queryByTestId('usps-mps-progress-section')).toBeNull()
  })
})

// ==================================================================
// Test 3: helper contract — normalizeCarrierCode maps USPS aliases
// ==================================================================

describe('DataHistoryPage — normalizeCarrierCode helper contract', () => {
  it('routes USPS, L01, STAMPS, ENDICIA to usps', async () => {
    const mod = await import('../utils/carrierUtils')
    expect(mod.normalizeCarrierCode('USPS')).toBe('usps')
    expect(mod.normalizeCarrierCode('usps')).toBe('usps')
    expect(mod.normalizeCarrierCode('L01')).toBe('usps')
    expect(mod.normalizeCarrierCode('STAMPS')).toBe('usps')
    expect(mod.normalizeCarrierCode('ENDICIA')).toBe('usps')
  })

  it('does NOT route FEDEX / UPS / DHL to usps', async () => {
    const mod = await import('../utils/carrierUtils')
    expect(mod.normalizeCarrierCode('FEDEX')).not.toBe('usps')
    expect(mod.normalizeCarrierCode('UPS')).not.toBe('usps')
    expect(mod.normalizeCarrierCode('DHL')).not.toBe('usps')
  })
})

// ==================================================================
// Bulk Mailer layout
// ==================================================================

describe('Bulk Mailer — layout', () => {
  it('opens on Import history with no tabs, a Trash button, no All orders, and an Import CSV / Excel button', async () => {
    listBatches.mockResolvedValue(pageOf([
      batchSummary({ id: 1, status: 'INITIATE', invalidRows: 0 }),
      batchSummary({ id: 2, status: 'IN_PROGRESS' }),
      batchSummary({ id: 3, status: 'DRAFT', invalidRows: 4 }),
    ]))
    // The cards come from the server's counts over the whole view.
    bulkSummary.mockResolvedValue(summaryOf({ total: 3, readyToGenerate: 1, generating: 1, needsFixes: 1 }))
    await loadAndRender()
    expect(await screen.findByRole('region', { name: 'Import history' })).toBeInTheDocument()
    expect(screen.queryByRole('tab')).toBeNull()
    expect(screen.getByRole('button', { name: 'Trash' })).toHaveAttribute('aria-pressed', 'false')
    // Labels & Invoices is its own page, opened from here.
    expect(screen.getByRole('button', { name: /Labels & Invoices/i })).toBeInTheDocument()
    expect(screen.queryByText(/All orders/i)).toBeNull()
    expect(await screen.findByRole('button', { name: /Import CSV \/ Excel/i })).toBeInTheDocument()
    // No summary cards: the list gets the screen, the filters sit behind one button.
    expect(screen.queryByTestId('bulk-summary')).toBeNull()
    await userEvent.click(screen.getByRole('button', { name: /^Filters/i }))
    const dialog = screen.getByRole('dialog', { name: 'Filters' })
    expect(dialog).toHaveTextContent('Saved · not generated')
    expect(dialog).toHaveTextContent('3 of 3 imports shown')
  })

  it('API batches are their own page, with Fetch from WMS for an admin and no Bulk Mailer tabs', async () => {
    listBatches.mockImplementation(async (q: { view: string }) => q.view === 'API'
      ? pageOf([batchSummary({ id: 7, fileName: 'WMS fetch 22 Sep', source: 'WMS', status: 'DRAFT', invalidRows: 1, savedRows: 3 })])
      : pageOf([]))
    await renderAt('/orders/api-batches')
    expect(await screen.findByRole('heading', { name: 'API Batches' })).toBeInTheDocument()
    expect(screen.queryByRole('tab')).toBeNull()
    await waitFor(() => expect(listBatches).toHaveBeenCalledWith(expect.objectContaining({ view: 'API' })))
    expect(await screen.findByTestId('batch-row-7')).toBeInTheDocument()
    expect(await screen.findByRole('button', { name: /Fetch from WMS/i })).toBeInTheDocument()
    // The Import button belongs to Import history only.
    expect(screen.queryByRole('button', { name: /Import CSV \/ Excel/i })).toBeNull()
  })

  it('opens the Trash tab from its address', async () => {
    await renderAt('/bulk/trash')
    expect(await screen.findByRole('button', { name: 'Trash' })).toHaveAttribute('aria-pressed', 'true')
    await waitFor(() => expect(listBatches).toHaveBeenCalledWith(expect.objectContaining({ view: 'TRASH' })))
  })

  it('Empty Trash re-reads the list and says which imports were kept', async () => {
    const kept = batchSummary({ id: 119, deletedAt: '2026-09-15T15:37:42Z', deletedBy: 'e2etester', labelsGenerated: 5, liveOrders: 5 })
    listBatches.mockImplementation(async (q: { view: string }) => q.view === 'TRASH' ? pageOf([kept]) : pageOf([]))
    bulkSummary.mockResolvedValue(summaryOf({ total: 1 }))
    const { orderImportService } = await import('../api/orderImportService')
    const emptyTrash = orderImportService.emptyTrash as ReturnType<typeof vi.fn>
    emptyTrash.mockResolvedValue({ data: 2, message: '2 imports permanently deleted. Kept 1 import that still has live labels — void them first.' })
    await renderAt('/bulk/trash')
    expect(await screen.findByTestId('batch-row-119')).toBeInTheDocument()
    const before = listBatches.mock.calls.length
    await userEvent.click(screen.getByRole('button', { name: /^Empty Trash$/i }))
    await userEvent.click(screen.getByRole('button', { name: /Delete 1 forever/i }))
    await waitFor(() => expect(emptyTrash).toHaveBeenCalledTimes(1))
    // Re-read, not blanked: the kept import is still listed without a refresh.
    await waitFor(() => expect(listBatches.mock.calls.length).toBeGreaterThan(before))
    expect(screen.getByTestId('batch-row-119')).toBeInTheDocument()
    expect(notifyInfo).toHaveBeenCalledWith(expect.objectContaining({ body: expect.stringMatching(/Kept 1 import/) }))
    expect(notifySuccess).not.toHaveBeenCalled()
  })

  it('an armed Empty Trash disarms on Escape without deleting anything', async () => {
    listBatches.mockImplementation(async (q: { view: string }) => q.view === 'TRASH'
      ? pageOf([batchSummary({ id: 123, deletedAt: '2026-09-23T18:25:03Z', deletedBy: 'e2etester' })])
      : pageOf([]))
    bulkSummary.mockResolvedValue(summaryOf({ total: 1 }))
    const { orderImportService } = await import('../api/orderImportService')
    await renderAt('/bulk/trash')
    await userEvent.click(await screen.findByRole('button', { name: /^Empty Trash$/i }))
    expect(screen.getByRole('button', { name: /Delete 1 forever/i })).toBeInTheDocument()
    await userEvent.keyboard('{Escape}')
    expect(screen.queryByRole('button', { name: /Delete 1 forever/i })).toBeNull()
    expect(screen.getByRole('button', { name: /^Empty Trash$/i })).toBeInTheDocument()
    expect(orderImportService.emptyTrash).not.toHaveBeenCalled()
  })

  it('opens a batch on its own page, with its header and a way back', async () => {
    getHistory.mockResolvedValue({ data: { ...batchSummary({ id: 121, fileName: 'acme_sept.csv', status: 'INITIATE' }), rows: [] } })
    await renderAt('/bulk/batches/121')
    const header = await screen.findByTestId('batch-page-header')
    expect(header).toHaveTextContent('Batch #121')
    expect(header).toHaveTextContent('acme_sept.csv')
    expect(screen.getByRole('button', { name: /Bulk Mailer · Import history/i })).toBeInTheDocument()
    // The header and one page of rows — never the whole batch at once (a 50k-row batch was ~58 MB, twice).
    await waitFor(() => expect(historyRows).toHaveBeenCalledWith('121', expect.objectContaining({ page: 0 })))
    expect(bulkBatch).toHaveBeenCalledWith('121')
    await new Promise((r) => setTimeout(r, 50))
    expect(bulkBatch).toHaveBeenCalledTimes(1)
    expect(historyRows).toHaveBeenCalledTimes(1)
  })

  it('the batch grid searches and filters on the server, from page 1', async () => {
    getHistory.mockResolvedValue({ data: { ...batchSummary({ id: 121, fileName: 'acme_sept.csv', status: 'INITIATE' }),
      rows: [{ rowNumber: 1, recipientName: 'Ann', city: 'Austin', errors: [] }] } })
    await renderAt('/bulk/batches/121')
    await waitFor(() => expect(historyRows).toHaveBeenCalledWith('121', expect.objectContaining({ view: 'all', page: 0, size: 25 })))
    await userEvent.type(screen.getByPlaceholderText(/Search order #/i), 'ZZ50K-12345')
    await waitFor(() => expect(historyRows).toHaveBeenCalledWith('121', expect.objectContaining({ q: 'ZZ50K-12345', page: 0 })), { timeout: 2000 })
    await userEvent.click(screen.getByRole('button', { name: /^Needs attention/ }))
    await waitFor(() => expect(historyRows).toHaveBeenCalledWith('121', expect.objectContaining({ view: 'attention', q: 'ZZ50K-12345', page: 0 })))
  })

  it('shows exactly the Orders screen\'s columns; the imported fields stay in the Columns menu', async () => {
    getHistory.mockResolvedValue({ data: { ...batchSummary({ id: 121, fileName: 'acme_sept.csv', status: 'INITIATE' }),
      rows: [{ rowNumber: 1, recipientName: 'Ann', city: 'Austin', errors: {}, generatedStatus: 'GENERATED', generatedOrderNo: 906976 }] } })
    await renderAt('/bulk/batches/121')
    await screen.findByTestId('batch-page-header')
    // pick and actions have no text header; every imported f_* column is hidden by default
    expect((await screen.findByTestId('visible-headers')).textContent).toBe('|Order|Ref #|Batch|Dest|Status|Track|')
    // Print / void live in a floating bar that appears once rows are ticked, not in the toolbar
    expect(screen.queryByTestId('batch-label-bar')).toBeNull()
  })

  it('a trashed batch says when and by whom it was trashed', async () => {
    getHistory.mockResolvedValue({ data: { ...batchSummary({ id: 123, fileName: 'trashtest.csv', status: 'INITIATE',
      deletedAt: new Date(Date.now() - 5 * 60_000).toISOString(), deletedBy: 'e2etester' }), rows: [] } })
    await renderAt('/bulk/batches/123')
    const header = await screen.findByTestId('batch-page-header')
    expect(header).toHaveTextContent('In Trash')
    expect(header).toHaveTextContent(/Trashed 5m ago by e2etester/)
    expect(screen.getByRole('button', { name: /Bulk Mailer · Trash/i })).toBeInTheDocument()
  })

  it('says so when the batch is not there', async () => {
    getHistory.mockRejectedValue(new (await import('../api/apiClient')).ApiError('Not found', 404, null))
    await renderAt('/bulk/batches/999999')
    expect(await screen.findByText(/This batch isn't here/)).toBeInTheDocument()
  })

  it('sends the filters to the server and starts again at page 1', async () => {
    await renderAt('/bulk/imports')
    await waitFor(() => expect(listBatches).toHaveBeenCalledWith(expect.objectContaining({ view: 'FILE', page: 0, size: 25, sort: 'created', dir: 'DESC' })))
    listBatches.mockClear()
    bulkSummary.mockClear()
    await userEvent.type(screen.getByPlaceholderText(/Search file name/i), 'acme')
    await waitFor(() => expect(listBatches).toHaveBeenCalledWith(expect.objectContaining({ q: 'acme', page: 0 })), { timeout: 2000 })
    // The summary depends on the view only: a search does not re-read it.
    expect(bulkSummary).not.toHaveBeenCalled()
  })

})

describe('Bulk Mailer — Import history and Trash', () => {
  it('the Trash button opens Trash, and pressed again goes back to Import history, sliding each way', async () => {
    await renderAt('/bulk/imports')
    await userEvent.click(await screen.findByRole('button', { name: 'Trash' }))
    expect(screen.getByRole('region', { name: 'Trash' })).toHaveClass('bulk-tab-in-right')
    expect(screen.getByRole('button', { name: 'Trash' })).toHaveAttribute('aria-pressed', 'true')
    await userEvent.click(screen.getByRole('button', { name: 'Trash' }))
    expect(screen.getByRole('region', { name: 'Import history' })).toHaveClass('bulk-tab-in-left')
    expect(screen.getByRole('button', { name: 'Trash' })).toHaveAttribute('aria-pressed', 'false')
  })

  it('shows a skeleton, not the last tab\'s batches, and ignores a late answer for a tab already left', async () => {
    let answerApi: (v: unknown) => void = () => {}
    listBatches.mockImplementation((q: { view: string }) => q.view === 'FILE'
      ? new Promise((resolve) => { answerApi = resolve })
      : Promise.resolve(pageOf(q.view === 'TRASH' ? [batchSummary({ id: 55, fileName: 'deleted.csv' })] : [batchSummary({ id: 1 })])))
    await renderAt('/bulk/trash')
    expect(await screen.findByTestId('batch-row-55')).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 'Trash' }))
    expect(screen.getByTestId('batch-list-skeleton')).toBeInTheDocument()
    expect(screen.queryByTestId('batch-row-1')).toBeNull()

    await userEvent.click(screen.getByRole('button', { name: 'Trash' }))
    expect(await screen.findByTestId('batch-row-55')).toBeInTheDocument()
    // Import history's answer finally arrives — it must not replace Trash.
    await act(async () => { answerApi(pageOf([batchSummary({ id: 7 })])) })
    expect(screen.getByTestId('batch-row-55')).toBeInTheDocument()
    expect(screen.queryByTestId('batch-row-7')).toBeNull()
  })
})
