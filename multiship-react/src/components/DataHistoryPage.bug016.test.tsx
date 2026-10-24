import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, cleanup } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { useEffect } from 'react'

/**
 * BUG-016 - the import-detail `Generate labels` button used to kick off
 * dispatch immediately; a 1,200-row import became 1,200 real paid labels
 * on a single click. The fix inserts a `notify.confirm(...)` step before
 * calling `generate()`. These tests assert:
 *
 *  1. `notify.confirm` is called before `generateLabels` is dispatched.
 *  2. Dismissing the confirm aborts the dispatch (no API call).
 *  3. Accepting the confirm flows through to `generateLabels`.
 */

// ---------- Service mocks ----------

const listHistory = vi.fn()
const getHistory = vi.fn()
const generationProgress = vi.fn()
const generateLabels = vi.fn()
const waitForGeneration = vi.fn()

type Row = { rowNumber: number; clientCode?: string; errors?: string[]; generatedStatus?: string | null }
const bulkBatch = vi.fn((id: number) => getHistory(id).then((res: { data?: Record<string, unknown> }) =>
  ({ data: res.data ? { ...res.data, rows: undefined } : res.data })))
const historyRows = vi.fn<(id: number, q?: unknown) => Promise<unknown>>((id: number) => getHistory(id).then((res: { data?: { rows?: Row[] } }) => {
  const rows = res.data?.rows ?? []
  return { data: { rows, total: rows.length, all: rows.length, attention: 0, pending: 0, clientCodes: [] } }
}))
vi.mock('../api/orderImportService', () => ({
  orderImportService: {
    listHistory: (...a: unknown[]) => listHistory(...a),
    getHistory: (...a: unknown[]) => getHistory(...a),
    historyRows: (id: number, q?: unknown) => historyRows(id, q),
    generationProgress: (...a: unknown[]) => generationProgress(...a),
    listStaging: vi.fn().mockResolvedValue({ data: [] }),
    generateLabels: (...a: unknown[]) => generateLabels(...a),
    waitForGeneration: (...a: unknown[]) => waitForGeneration(...a),
    cancelGeneration: vi.fn(),
    deleteBatch: vi.fn(),
    restoreBatch: vi.fn(),
    emptyTrash: vi.fn(),
    setBillingMode: vi.fn(),
    generateRowLabel: vi.fn(),
    updateRow: vi.fn(),
  },
}))

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
const notifyConfirm = vi.fn()
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
  useAppSession: () => ({ role: 'ADMIN', displayName: 'test', tenantId: 't', email: 't@t' }),
}))

vi.mock('../hooks/useEventStream', () => ({
  useEventStream: () => ({ status: 'closed' }),
}))

const getMetricsMock = vi.fn().mockResolvedValue({
  data: { depth: 0, processing: 0, estimatedWaitSeconds: 0, totalPerHourCap: 55, tenantCode: null },
})
vi.mock('../api/uspsLabelQueueService', () => ({
  uspsLabelQueueService: {
    getMetrics: (...a: unknown[]) => getMetricsMock(...a),
    getMpsProgress: vi.fn(),
  },
}))

vi.mock('../hooks/useMpsProgress', () => ({
  useMpsProgress: () => ({ progress: null, loading: false, error: null, refresh: vi.fn() }),
}))

vi.mock('../api/apiClient', () => {
  class ApiError extends Error {
    status: number
    errorCode: string | null
    payload: unknown
    constructor(message: string, status = 500, errorCode: string | null = null, payload: unknown = null) {
      super(message); this.name = 'ApiError'; this.status = status; this.errorCode = errorCode; this.payload = payload
    }
  }
  return {
    ApiError,
    isAbortError: (err: unknown) => err != null && typeof err === 'object' && (err as { name?: string }).name === 'AbortError',
    apiClient: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
  }
})

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
vi.mock('../api/wmsService', () => ({
  wmsService: { batches: (...a: unknown[]) => wmsBatches(...a), pull: vi.fn() },
}))
vi.mock('./modals/ShipViaCodes', () => ({
  ShipViaCodesPanel: () => <div data-testid="ship-via-panel" />,
  AddShipViaMappingDialog: () => null,
}))
vi.mock('./modals/OrderImportModal', () => ({ default: () => <div data-testid="import-modal-stub" /> }))

type ColumnDefLike<T> = { id?: string; cell?: (ctx: { row: { original: T; getValue: () => unknown } }) => React.ReactNode; accessorKey?: string }

function AdvancedDataTableStub<T extends { id?: number | string }>(props: {
  data?: T[]
  columns?: ColumnDefLike<T>[]
  renderExpanded?: (row: T) => React.ReactNode
  onRowExpand?: (row: T) => void
}) {
  const rows = props.data ?? []
  const columns = props.columns ?? []
  useEffect(() => {
    if (!props.onRowExpand) return
    for (const r of rows) props.onRowExpand(r)
    // eslint-disable-next-line react-hooks/exhaustive-deps -- stub mirroring expand-once per row
  }, [rows.length])
  return (
    <div data-testid="advanced-data-table-stub">
      {rows.map((row, i) => (
        <div key={String(row.id ?? i)} data-testid={`batch-row-${String(row.id ?? i)}`}>
          {columns.map((col, ci) => (
            <span key={col.id ?? `col-${ci}`} data-testid={`cell-${col.id ?? ci}`}>
              {col.cell ? col.cell({ row: { original: row, getValue: () => undefined } }) : null}
            </span>
          ))}
          {props.renderExpanded ? props.renderExpanded(row) : null}
        </div>
      ))}
    </div>
  )
}
vi.mock('./workspace/AdvancedDataTable', () => ({ default: AdvancedDataTableStub }))
vi.mock('./VirtualTable', () => ({ default: ({ empty }: { empty?: React.ReactNode }) => (<div>{empty}</div>) }))
vi.mock('./batchGrid', () => ({
  DH_COLUMNS: [], GridCell: () => null, RowIssuesIcon: () => null,
  RowChannelChip: () => null, bucketRowErrors: () => ({ byField: {}, rowLevel: [] }),
  fieldLabel: (s: string) => s, rowStatus: () => ({ bucket: 'READY' }), shipViaHint: () => null,
}))

// ---------- Harness ----------

async function loadAndRender() {
  const { default: DataHistoryPage } = await import('./DataHistoryPage')
  return render(
    <MemoryRouter initialEntries={['/bulk/batches/100']}>
      <Routes>
        <Route path="/bulk/batches/:batchId" element={<DataHistoryPage />} />
        <Route path="/bulk/:tab" element={<DataHistoryPage />} />
      </Routes>
    </MemoryRouter>,
  )
}

const batchSummary = (over: Record<string, unknown> = {}) => {
  const id = (over.id as number | undefined) ?? 100
  return {
    id, slug: String(id), createdBy: 'ops', fileName: 'test.csv',
    status: 'INITIATE', labelBatchId: null,
    createdAt: '2026-10-18T00:00:00Z', completedAt: null,
    totalRows: 1200, savedRows: 1200, invalidRows: 0,
    billingMode: 'AUTO', source: 'BULK',
    ...over,
  }
}

beforeEach(() => {
  vi.clearAllMocks()
  wmsBatches.mockResolvedValue({ data: [] })
  listBatches.mockResolvedValue(pageOf([batchSummary()]))
  bulkSummary.mockResolvedValue(summaryOf({ total: 1, readyToGenerate: 1 }))
  listHistory.mockResolvedValue({ data: [batchSummary()] })
  getHistory.mockResolvedValue({ data: { ...batchSummary(), rows: [] } })
  generationProgress.mockResolvedValue({ data: { running: false, done: 0, total: 0 } })
  waitForGeneration.mockResolvedValue({ resultStatus: 'COMPLETED', resultMessage: 'ok' })
})

afterEach(() => { cleanup() })

describe('DataHistoryPage - Generate labels confirm (BUG-016)', () => {
  it('asks for confirmation before dispatching labels', async () => {
    notifyConfirm.mockResolvedValueOnce(false)
    await loadAndRender()
    const button = await screen.findByRole('button', { name: /Generate labels/i })
    const user = userEvent.setup()
    await user.click(button)
    await waitFor(() => {
      expect(notifyConfirm).toHaveBeenCalledTimes(1)
    })
    const message = notifyConfirm.mock.calls[0][0] as string
    expect(message).toMatch(/1200 rows/)
    // BUG-056 — pre-fix the wording said "buys real labels … can't be
    // undone" which misled sandbox operators and was wrong about
    // reversibility. New copy names the void path explicitly.
    expect(message).toMatch(/void.*Labels tab/i)
    expect(message).not.toMatch(/real labels/)
    // Operator dismissed the confirm; the dispatch must not fire.
    expect(generateLabels).not.toHaveBeenCalled()
  })

  it('dispatches after the operator accepts', async () => {
    notifyConfirm.mockResolvedValueOnce(true)
    generateLabels.mockResolvedValueOnce({ data: { ...batchSummary(), status: 'COMPLETED' } })
    await loadAndRender()
    const button = await screen.findByRole('button', { name: /Generate labels/i })
    const user = userEvent.setup()
    await user.click(button)
    await waitFor(() => {
      expect(generateLabels).toHaveBeenCalledTimes(1)
    })
    // Confirm fires BEFORE dispatch.
    expect(notifyConfirm).toHaveBeenCalledTimes(1)
  })
})
