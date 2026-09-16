import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, cleanup, act } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { BrowserRouter } from 'react-router-dom'
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
vi.mock('../api/orderImportService', () => ({
  orderImportService: {
    listHistory: (...a: unknown[]) => listHistory(...a),
    getHistory: (...a: unknown[]) => getHistory(...a),
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
vi.mock('./AllOrdersHistory', () => ({
  default: () => <div data-testid="all-orders-stub" />,
}))
vi.mock('./OrderDocumentsTable', () => ({
  default: () => <div data-testid="documents-stub" />,
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
}) {
  const rows = props.data ?? []
  // Fire onRowExpand once per row on mount so ensureRows populates
  // rowsById in the parent — mirrors clicking every row header.
  useEffect(() => {
    if (!props.onRowExpand) return
    for (const r of rows) props.onRowExpand(r)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [rows.length])
  return (
    <div data-testid="advanced-data-table-stub">
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
vi.mock('./workspace/PageSectionHeader', () => ({
  default: ({ actions }: { actions?: React.ReactNode }) => (
    <div data-testid="page-header-stub">{actions}</div>
  ),
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

const batchSummary = (over: Record<string, unknown> = {}) => ({
  id: 100,
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
})

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

async function switchToImportsView() {
  const importsTab = await screen.findByRole('button', { name: /Import history/i })
  await userEvent.click(importsTab)
}

// ==================================================================
// Test 0: sanity — page renders
// ==================================================================

describe('DataHistoryPage — mounts', () => {
  it('renders without crashing', async () => {
    await loadAndRender()
    await waitFor(() => {
      expect(screen.getByTestId('page-header-stub')).toBeInTheDocument()
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

  it('renders the slot BEFORE the Import history tab (top-of-page placement)', async () => {
    await loadAndRender()
    const slot = await waitFor(() => screen.getByTestId('usps-queue-badge-slot'))
    const importsTab = screen.getByRole('button', { name: /Import history/i })
    const pos = slot.compareDocumentPosition(importsTab)
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

    await loadAndRender()
    await switchToImportsView()

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

    await loadAndRender()
    await switchToImportsView()

    // Wait for the batch to auto-expand (advanced-data-table-stub mounts).
    await waitFor(() => {
      expect(screen.getByTestId('advanced-data-table-stub')).toBeInTheDocument()
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

    await loadAndRender()
    await switchToImportsView()

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

    await loadAndRender()
    await switchToImportsView()

    await waitFor(() => {
      expect(screen.getByTestId('advanced-data-table-stub')).toBeInTheDocument()
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
