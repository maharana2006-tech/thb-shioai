import { createPortal } from 'react-dom'
import { lazy, Suspense, useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react'
import { useNavigate, useParams, useSearchParams } from 'react-router-dom'
import {
  FiAlertCircle,
  FiEye,
  FiInfo,
  FiPrinter,
  FiTruck,
  FiXCircle,
  FiCheckCircle,
  FiDownloadCloud,
  FiArrowLeft,
  FiFileText,
  FiHome,
  FiRefreshCw,
  FiTrash2,
  FiUpload,
  FiRotateCcw,
  FiSlash,
  FiX,
  FiZap,
  FiEdit3,
} from 'react-icons/fi'
import type { ColumnDef } from '@tanstack/react-table'
import AdvancedDataTable from './workspace/AdvancedDataTable'
import FixRowPanel from './bulk/FixRowPanel'
import BatchPrintMenu from './bulk/BatchPrintMenu'
import { bulkBatchPath, bulkPaths, settingsPaths } from '../routes/workspaceRoutes'
import { wmsService } from '../api/wmsService'
import { bulkService, type BulkSummary, type BulkView } from '../api/bulkService'
import { AddShipViaMappingDialog, ShipViaCodesPanel } from './modals/ShipViaCodes'
// Lazy — only rendered on the /bulk/documents tab. Keeps ~20-30 kB of
// documents-table code out of the default /bulk/imports first-nav chunk.
const OrderDocumentsTable = lazy(() => import('./OrderDocumentsTable'))
import DataHistoryFilterToolbar, { BulkFilterChips, statusMeta } from './DataHistoryFilterToolbar'
import { GridCell, DH_COLUMNS, fieldLabel, RowIssuesIcon, RowChannelChip, bucketRowErrors, rowStatus, type DhColumn } from './batchGrid'
import NoteCell from './workspace/NoteCell'
import AnimatedHeight from './ui/AnimatedHeight'
import BatchLabelBar from './bulk/BatchLabelBar'
import LabelPreviewModal from './bulk/LabelPreviewModal'
// The Orders page's per-order modals, reused on the batch page.
const OrderDetailsModal = lazy(() => import('./modals/OrderDetailsModal'))
const TrackingTimelineModal = lazy(() => import('./tracking/TrackingTimelineModal'))
import { hasCommercialInvoice, labelCountsOf, liveOrdersOf, type LabelCounts } from '../utils/batchLabels'
import { printPdfBlob } from '../utils/printPdf'
import { formatDuration, relativeTime } from '../utils/relativeTime'
import { orderService, type Order } from '../api/orderService'
import SendToPrinterDialog from './workspace/SendToPrinterDialog'
import { BTN_GHOST_SM } from './ui/buttons'
import { notify } from '../utils/notify'
import { ApiError } from '../api/apiClient'
import {
  orderImportService,
  type ImportBatchSummary,
  type OrderImportRow,
} from '../api/orderImportService'
import { useAppSession } from '../hooks/useAppSession'
import { useEventStream } from '../hooks/useEventStream'
import { useHistoryFilters } from '../hooks/useHistoryFilters'
import { useTrashActions } from '../hooks/useTrashActions'
import { useLatestRequest } from '../hooks/useLatestRequest'
import { useDismissable } from '../hooks/useDismissable'
import { normalizeRole } from '../utils/roles'
// PR-G4 — USPS_DIRECT UX (audit U2 + U3). Badge surfaces queue depth
// at the top of Data History so ops see rate-limit pressure without
// hopping to the admin dashboard; MpsProgressCard renders inside
// each expanded USPS row so MPS-parent orders show per-piece
// progress instead of a stalled "Generating…" spinner.
import BulkLabelQueueBadge from './orders/BulkLabelQueueBadge'
import MpsProgressCard from './orders/MpsProgressCard'
import { normalizeCarrierCode } from '../utils/carrierUtils'

/**
 * Renders the running-elapsed caption ("12s", "1m 04s", …) and self-ticks
 * every second. Owns its own timer so the parent's `dhColumns` memo — 13
 * deps, one per interactive control — doesn't invalidate every second and
 * cascade a full-table re-render. Only THIS span re-renders on each tick.
 *
 * <p>Renders the raw duration text only; caller applies wrapper styling
 * (label, colour, "running "/"took " prefix).
 */
const RunningElapsed = ({ startedMs, prefix, className }: {
  startedMs: number
  prefix?: string
  className?: string
}) => {
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    const t = setInterval(() => setNow(Date.now()), 1000)
    return () => clearInterval(t)
  }, [])
  const el = formatDuration(now - startedMs)
  if (!el) return null
  return <span className={className}>{prefix ? `${prefix}${el}` : el}</span>
}

/**
 * Data History — every saved CSV/XLSX import. "Commit" in the import modal
 * saves the parsed rows here (no labels generated); this page lists those
 * saved imports and lets you expand one to see its rows.
 */
export default function DataHistoryPage() {
  const navigate = useNavigate()
  /** Audit R2 #329 — TENANT role should see the page read-only.
   *  Backend already 403s on cross-tenant access (OrderImportServiceImpl
   *  history() / historyDetail() / generateLabelsForBatch enforce
   *  clientCode filtering + tenantScope), but the FE was showing the
   *  Generate/Retry buttons anyway → click → silent 403 → confusion.
   *  Now hidden for TENANT so the read-only intent is visible upfront. */
  const { role } = useAppSession()
  const canWrite = normalizeRole(role) !== 'TENANT'
  /** Pulling from the WMS is admin-only on the server. */
  const canPullWms = normalizeRole(role) === 'ADMIN'
  const [fetchingWms, setFetchingWms] = useState(false)
  /** The unmapped ship via code being mapped, and the batch to re-check afterwards. */
  const [mapping, setMapping] = useState<{ code: string; clientCode: string | null; batchId: number } | null>(null)
  /** Bumped after a mapping is added, so the ship-via panel re-reads its codes. */
  const [codesTick, setCodesTick] = useState(0)
  const [batches, setBatches] = useState<ImportBatchSummary[]>([])
  const [loading, setLoading] = useState(true)
  // Running-elapsed ticker moved inline into <RunningElapsed/> so the
  // 1s tick only re-renders that span; the parent's dhColumns memo stays
  // stable across ticks (was rebuilding the whole column def every second).
  const [rowsById, setRowsById] = useState<Record<number, OrderImportRow[] | 'loading'>>({})
  const [generatingId, setGeneratingId] = useState<number | null>(null)
  const [validatingId, setValidatingId] = useState<number | null>(null)
  // Live "X of N" label-generation progress per batch, polled while a batch
  // generate/retry runs so the button shows a real progress bar, not a spinner.
  const [genProgressById, setGenProgressById] = useState<Record<number, { done: number; total: number; note?: string | null; cancelling?: boolean; jobStatus?: string | null }>>({})
  // Generate is a background job; stop following it if the page is left.
  const mountedRef = useRef(true)
  useEffect(() => {
    // Set on every mount: React's development double-mount runs the cleanup once,
    // and a flag left false made the page think it was already gone — it stopped
    // following the job the moment Generate returned (no toast, and the card fell
    // back to the slower list poll, which is what flickered).
    mountedRef.current = true
    return () => { mountedRef.current = false }
  }, [])
  // Imports this tab asked to cancel. Held until the run actually stops so the
  // card and button keep saying "Cancelling…" — it used to snap straight back
  // to "Cancel" while workers drained, which read as the click doing nothing.
  const [cancelRequested, setCancelRequested] = useState<Set<number>>(() => new Set())
  // Bill-to account: the batch whose "Bills to" selector is mid-save.
  const [billingSavingId, setBillingSavingId] = useState<number | null>(null)
  // Confirm-before-generate when a batch bills to the platform account.
  const [confirmGenId, setConfirmGenId] = useState<number | null>(null)
  // Per-batch row filter for the expanded grid — a 1,000-order batch is 2,484 rows.
  const [gridFilter, setGridFilter] = useState<Record<number, 'all' | 'failed' | 'pending'>>({})
  /** The grid's search box (client-side over the loaded rows). */
  const [gridSearch, setGridSearch] = useState('')
  /** The Orders page's Details / Track modals, for one order of the batch. */
  const [detailsOrderNo, setDetailsOrderNo] = useState<number | null>(null)
  /** The import row open in the Fix panel, and whether its save is in flight. */
  const [fixing, setFixing] = useState<{ batchId: number; rowNumber: number } | null>(null)
  const [fixSaving, setFixSaving] = useState(false)
  const [trackingOrderNo, setTrackingOrderNo] = useState<number | null>(null)
  const [voidingOrderNo, setVoidingOrderNo] = useState<number | null>(null)
  /** The batch's orders by number — what the rows don't carry (note, created date, tracking link). */
  const [batchOrders, setBatchOrders] = useState<Record<number, Order>>({})
  /** Ticked rows (row numbers) per batch — for print / send / void. */
  const [pickedRows, setPickedRows] = useState<Record<number, number[]>>({})
  const [genRowKey, setGenRowKey] = useState<string | null>(null)
  // Inline correction: the cell being saved (rowKey), for a per-cell spinner.
  const [savingCell, setSavingCell] = useState<string | null>(null)
  // The order whose label is open in the preview modal.
  const [labelModalOrderNo, setLabelModalOrderNo] = useState<number | null>(null)

  // Order Intake has three views: "orders" (unified per-order list across
  // Bulk / Manual / API / WMS), "import" (inline CSV/Excel upload + validation),
  // and "imports" (history of bulk import batches).
  // Bulk Mailer tab from the URL (/bulk/:tab): imports (default) · api · documents · trash.
  const { tab, batchId: batchIdParam } = useParams<{ tab?: string; batchId?: string }>()
  /** /bulk/batches/:id — one batch on its own page. */
  const batchPageId = batchIdParam ? Number(batchIdParam) || null : null
  const bulkTab: BulkTab = BULK_TABS.some((t) => t.key === tab) ? (tab as BulkTab) : 'imports'
  const dhView: 'imports' | 'docs' = bulkTab === 'documents' ? 'docs' : 'imports'
  /** Import history and API batches are the same list — only where the batches come from differs. */
  const isApiTab = bulkTab === 'api'
  // Which way the content slides in: from the right for a tab further along, from the left for one before.
  const [tabMotion, setTabMotion] = useState<{ tab: BulkTab; dir: 1 | -1 }>({ tab: bulkTab, dir: 1 })
  /** The Documents table has loaded (for this visit to the tab). */
  const [docsLoadedFor, setDocsLoadedFor] = useState<BulkTab | null>(null)
  if (tabMotion.tab !== bulkTab) {
    const from = BULK_TABS.findIndex((t) => t.key === tabMotion.tab)
    const to = BULK_TABS.findIndex((t) => t.key === bulkTab)
    setTabMotion({ tab: bulkTab, dir: to >= from ? 1 : -1 })
    setDocsLoadedFor(null)
  }
  const [searchParams] = useSearchParams()

  // What the toolbar set; the server applies it (listQuery below).
  const filters = useHistoryFilters()
  const { clearFilters } = filters

  // ── Server-side list (phase 4): the page shows one page of batches, and the
  // toolbar's filters, the sort and paging are sent to the server.
  const [pageIndex, setPageIndex] = useState(0)
  const [pageSize, setPageSize] = useState(25)
  const [pageInfo, setPageInfo] = useState({ total: 0, pages: 1 })
  const [summary, setSummary] = useState<BulkSummary | null>(null)
  const [debouncedSearch, setDebouncedSearch] = useState('')
  useEffect(() => {
    const t = window.setTimeout(() => setDebouncedSearch(filters.search.trim()), 300)
    return () => window.clearTimeout(t)
  }, [filters.search])
  const listQuery = {
    status: filters.statusFilter === 'ALL' ? undefined : filters.statusFilter,
    q: debouncedSearch || undefined,
    from: filters.dateFrom || undefined,
    to: filters.dateTo || undefined,
    createdBy: filters.createdBy || undefined,
    labelBatch: filters.batchPresence === 'ANY' ? undefined : filters.batchPresence,
    minSaved: filters.minSaved ? Number(filters.minSaved) : undefined,
    sort: filters.sortKey,
    dir: filters.sortDir,
  } as const
  const listQueryKey = JSON.stringify(listQuery)
  // A new filter starts again at page 1.
  const [pagedQueryKey, setPagedQueryKey] = useState(listQueryKey)
  if (pagedQueryKey !== listQueryKey) {
    setPagedQueryKey(listQueryKey)
    setPageIndex(0)
  }

  // F5-A — soft-delete / restore / empty-Trash extracted to
  // useTrashActions. The Trash-view toggle lives here now so we can
  // reload independently when the operator flips between live and Trash.
  // The batch page re-reads its batch after a delete / restore (load is declared below).
  const reloadRef = useRef<() => void>(() => {})
  /** Trash is a tab of its own; the batch page is "in Trash" when its batch is. */
  const viewTrash = batchPageId != null
    ? !!batches.find((b) => b.id === batchPageId)?.deletedAt
    : bulkTab === 'trash'
  const trash = useTrashActions({
    setBatches,
    onMoved: batchPageId != null
      ? () => reloadRef.current()
      : (id) => { setBatches((list) => list.filter((b) => b.id !== id)); void reloadQuiet() },
    onEmptied: () => reloadRef.current(),
  })
  const {
    trashBusyId,
    confirmEmpty,
    setConfirmEmpty,
    emptying,
    handleEmptyTrash,
    handleDelete,
    handleRestore,
  } = trash
  // An armed "Delete N forever" stays armed until clicked — Escape and a click
  // elsewhere disarm it, like every other popover.
  const emptyConfirmRef = useDismissable(confirmEmpty, useCallback(() => setConfirmEmpty(false), [setConfirmEmpty]))


  useEffect(() => {
    if (!batchPageId && tab && !BULK_TABS.some((t) => t.key === tab)) navigate(bulkPaths.imports, { replace: true })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tab])

  /** Restore, then hold Generate for a moment: it lands exactly where Restore was, under the cursor. */
  const [justRestoredId, setJustRestoredId] = useState<number | null>(null)
  const restoreBatch = async (id: number, fileName?: string | null) => {
    await handleRestore(id, fileName)
    setJustRestoredId(id)
    window.setTimeout(() => setJustRestoredId((cur) => (cur === id ? null : cur)), 1500)
  }

  /** Print / send a whole batch from the list: its live labels, looked up on demand. */
  const [batchPrintBusy, setBatchPrintBusy] = useState<number | null>(null)
  const [sendBatch, setSendBatch] = useState<number[] | null>(null)
  /** A batch's rows for the list's Print menu — the page's copy when it has one. */
  const rowsOfBatch = async (b: ImportBatchSummary): Promise<OrderImportRow[]> => {
    const cached = rowsById[b.id]
    return Array.isArray(cached) ? cached : (await orderImportService.getHistory(b.id)).data?.rows ?? []
  }
  /** busyId: the batch whose row spins; -1 for the selection bar. */
  const printBatchDocs = async (busyId: number, scope: string, orders: number[], docType: 'LABEL' | 'COMMERCIAL_INVOICE') => {
    if (orders.length === 0) return
    setBatchPrintBusy(busyId)
    try {
      const res = await orderService.printDocuments(orders.slice(0, 500), docType)
      printPdfBlob(res.blob)
      const what = docType === 'LABEL' ? 'label' : 'commercial invoice'
      notify.success(`Opening ${res.included} ${what}${res.included === 1 ? '' : 's'} of ${scope} in the print dialog`
        + (orders.length > 500 ? ' — the first 500; print the rest in smaller groups.' : '.'))
      void reloadQuiet()
    } catch (e) {
      notify.apiError(e, 'Could not print this batch.')
    } finally {
      setBatchPrintBusy(null)
    }
  }
  /** Void every live label of a batch from the list — the batch page's Void all, one click away. */
  const voidBatchLabels = async (b: ImportBatchSummary) => {
    const n = liveCountOf(b)
    if (n === 0 || batchPrintBusy === b.id) return
    const ok = await notify.confirm(
      `The carriers will cancel every live label of batch #${b.id} (${b.fileName || 'this import'}) — the orders can't ship on them any more. This can't be undone.`,
      { title: `Void all labels of batch #${b.id}?`, confirmLabel: 'Void them', cancelLabel: 'Keep them', danger: true },
    )
    if (!ok) return
    setBatchPrintBusy(b.id)
    try {
      const res = (await orderImportService.voidBatchLabels(b.id, [])).data
      if (!res || res.orders.length === 0) notify.info('There were no live labels left to void.')
      else if (res.refused === 0) notify.success(`${res.voided} label${res.voided === 1 ? '' : 's'} of batch #${b.id} voided.`)
      else {
        const refusals = res.orders.filter((o) => !o.voided).slice(0, 3).map((o) => `#${o.orderNo}: ${o.message}`).join(' · ')
        notify.info({ title: `${res.voided} voided, ${res.refused} refused by the carrier`,
          body: refusals + (res.refused > 3 ? ` · and ${res.refused - 3} more` : '') })
      }
      setRowsById((m) => { const next = { ...m }; delete next[b.id]; return next })   // stale rows
      void reloadQuiet()
    } catch (e) {
      notify.apiError(e, 'Could not void the labels.')
    } finally {
      setBatchPrintBusy(null)
    }
  }

  /** The orders behind the batch page's rows, joined by order number. */
  const batchPageLabelBatch = batchPageId != null ? batches.find((x) => x.id === batchPageId)?.labelBatchId ?? null : null
  const batchPageRows = batchPageId != null ? rowsById[batchPageId] : undefined
  useEffect(() => {
    if (batchPageLabelBatch == null) return
    let gone = false
    orderService.listOrders({ batch: String(batchPageLabelBatch), page: 0, size: 500 })
      .then((res) => {
        if (gone) return
        const byNo: Record<number, Order> = {}
        for (const o of res.data?.content ?? []) byNo[o.orderDetails.orderNo] = o
        setBatchOrders(byNo)
      })
      .catch(() => { /* the rows still render from their own fields */ })
    return () => { gone = true }
    // eslint-disable-next-line react-hooks/exhaustive-deps -- re-read when the batch's rows change (a void, a retry)
  }, [batchPageLabelBatch, batchPageRows])

  /** Re-read a batch's rows (after a void) and its header counts. */
  const reloadRows = (id: number) => {
    orderImportService.getHistory(id)
      .then((res) => setRowsById((m) => ({ ...m, [id]: res.data?.rows ?? [] })))
      .catch((e) => notify.apiError(e, 'Could not reload the rows.'))
  }

  /**
   * Lazy-load a batch's rows when its row is expanded.
   *
   * <p>Also does a one-shot {@code generationProgress} fetch if the
   * batch is currently IN_PROGRESS. Replaces the observer-mode poll
   * (removed 2026-09-23) with an on-demand snapshot: operators see
   * "X of N" the moment they expand a row; the number won't tick
   * live but they get an accurate reading each expand + on Refresh.
   */
  const ensureRows = (id: number) => {
    const batch = batches.find((b) => b.id === id)
    const isRunning = (batch?.status || '').toUpperCase() === 'IN_PROGRESS'
    if (isRunning) {
      orderImportService.generationProgress(id).then((pr) => {
        const d = pr.data
        if (d && d.running && d.total > 0) {
          setGenProgressById((m) => ({
            ...m,
            [id]: { done: d.done, total: d.total, note: d.note ?? null,
                    cancelling: !!d.cancelling, jobStatus: d.jobStatus ?? null },
          }))
        }
      }).catch(() => { /* progress is a nice-to-have; a failed snapshot doesn't matter */ })
    }
    if (rowsById[id]) return
    setRowsById((m) => ({ ...m, [id]: 'loading' }))
    orderImportService
      .getHistory(id)
      .then((res) => setRowsById((m) => ({ ...m, [id]: res.data?.rows ?? [] })))
      .catch((e) => {
        notify.apiError(e, 'Could not load import rows.')
        setRowsById((m) => ({ ...m, [id]: [] }))
      })
  }

  /** File imports, API/WMS fetches, or Trash (deleted batches of either kind). */
  const listView: BulkView = viewTrash ? 'TRASH' : isApiTab ? 'API' : 'FILE'
  const fetchBatches = async (): Promise<{ data: ImportBatchSummary[]; page?: { total: number; pages: number }; summary?: BulkSummary | null }> => {
    // The batch page's "list" is that one batch (live or in Trash, any source).
    if (batchPageId != null) {
      const res = await orderImportService.getHistory(batchPageId)
      return { data: res.data ? [res.data as ImportBatchSummary] : [] }
    }
    const [res, sum] = await Promise.all([
      bulkService.listBatches({ view: listView, ...listQuery, page: pageIndex, size: pageSize }),
      bulkService.summary(listView).catch(() => null),
    ])
    return {
      data: res.data?.content ?? [],
      page: { total: res.data?.totalElements ?? 0, pages: Math.max(res.data?.totalPages ?? 1, 1) },
      summary: sum?.data ?? null,
    }
  }
  /** Each fetch is numbered; a slower answer for a list the operator has already left is dropped. */
  const latest = useLatestRequest()
  /** Which list the shown batches belong to — until the new tab's answer lands, its skeleton shows. */
  const [loadedView, setLoadedView] = useState<string | null>(null)
  const [refreshing, setRefreshing] = useState(false)
  const viewKey = batchPageId != null ? `batch:${batchPageId}` : listView
  /** The tab shows its real content — the panel's height may settle. */
  const tabReady = dhView === 'docs' ? docsLoadedFor === 'documents' : loadedView === viewKey
  const applyFetch = (seq: number, view: string, r: Awaited<ReturnType<typeof fetchBatches>>) => {
    if (!latest.isLatest(seq)) return false
    setBatches(r.data)
    if (r.page) setPageInfo(r.page)
    if (r.summary) setSummary(r.summary)
    setLoadedView(view)
    return true
  }


  /** Pull the WMS's pending shipments in as one new batch (or reopen the same one). */
  const fetchFromWms = async () => {
    setFetchingWms(true)
    try {
      const r = (await wmsService.pull()).data
      if (r && !r.configured) {
        notify.info('The WMS link is not set up on the server.')
      } else if (r) {
        if (r.imported > 0) {
          notify.success(`WMS: ${r.imported} shipment(s) imported as a new batch${r.failed ? ` · ${r.failed} skipped` : ''}.`)
        } else if (r.importBatchId != null) {
          notify.info('These shipments were already fetched — opening the existing batch.')
        } else {
          notify.info('The WMS has no pending shipments to fetch.')
        }
        if (r.importBatchId != null) navigate(bulkBatchPath(r.importBatchId))
      }
    } catch (e) {
      notify.apiError(e, 'Could not reach the WMS.')
    } finally {
      setFetchingWms(false)
    }
  }

  const load = async () => {
    const seq = latest.begin()
    const view = viewKey
    if (batches.length === 0) setLoading(true)
    setRefreshing(true)
    try {
      if (!applyFetch(seq, view, await fetchBatches())) return
      if (batchPageId != null) ensureRows(batchPageId)
      // Old ?highlight=<id> links: open that batch's page.
      const highlightId = Number(searchParams.get('highlight')) || null
      if (highlightId) navigate(bulkBatchPath(highlightId), { replace: true })
    } catch (e) {
      // Keep whatever is already listed: wiping it rendered the "no imports
      // yet" empty state on a transient 502, which reads as data loss.
      // A missing batch gets the page's own "isn't here" message, not an error notice.
      if (batchPageId != null && e instanceof ApiError && (e.status === 404 || e.status === 403)) return
      notify.apiError(e, batchPageId != null ? 'Could not load this batch.'
        : viewTrash ? 'Could not load Trash.' : isApiTab ? 'Could not load the API batches.' : 'Could not load import history.')
    } finally {
      if (latest.isLatest(seq)) {
        setLoading(false)
        setRefreshing(false)
      }
    }
  }

  /**
   * Silent reload — same DB fetch as load(), but doesn't flip the
   * loading spinner (auto-poll shouldn't flash the page every 5s).
   * Errors are swallowed because a transient network hiccup shouldn't
   * blow up the operator's view; the next poll will retry.
   */
  const reloadQuiet = async () => {
    const seq = latest.begin()
    const view = viewKey
    try {
      applyFetch(seq, view, await fetchBatches())
    } catch {
      // ignore transient failures during background polling
    }
  }

  useEffect(() => { reloadRef.current = () => { void load() } })

  useEffect(() => {
    /* eslint-disable react-hooks/set-state-in-effect -- data fetch on mount + when switching list (file / API / Trash / one batch) */
    void load()
    setConfirmEmpty(false)
    /* eslint-enable react-hooks/set-state-in-effect */
    // eslint-disable-next-line react-hooks/exhaustive-deps -- load/setOpenId/setConfirmEmpty are stable; switching list re-fetches
  }, [viewTrash, isApiTab, batchPageId, listQueryKey, pageIndex, pageSize])

  /**
   * Auto-poll the list while any batch is IN_PROGRESS so status
   * changes made by backend workers (or by another operator) reflect
   * without a manual refresh. Fixes the "status not updating till
   * refreshed" complaint on the /orders/history page.
   *
   * <p>Runs ONLY while there's at least one IN_PROGRESS row — an idle
   * list doesn't poll (saves DB round-trips and network chatter). Also
   * skips the Trash view (its rows are terminal by construction).
   * When any batch flips to a terminal state on the server, the next
   * poll picks it up and the loop naturally stops on the tick after
   * that.
   *
   * <p>Interval is 4 s — fast enough that operators see progress
   * without hitting refresh, slow enough that a 20-operator office
   * doesn't hammer /history.
   */
  /**
   * Phase 3 — SSE push subscription for real-time status updates.
   * Opens ONE long-lived connection while the page is mounted. On
   * every backend batch state change (INITIATE→IN_PROGRESS, terminal
   * status, cancel), the backend publishes an event; the handler
   * below reloads the list. Removes the poll's per-request cost when
   * SSE is available.
   *
   * <p>When SSE is 'open', the auto-poll below is suppressed (push
   * is authoritative). When SSE drops (network hiccup, Redis off,
   * proxy strips text/event-stream), the poll picks up transparently
   * so the operator never sees stale state.
   */
  const sseHandlers = useMemo(() => ({
    'batch-updated': () => { void reloadQuiet() },
    'batch-created': () => { void reloadQuiet() },
    'batch-cancel-requested': () => { void reloadQuiet() },
  // eslint-disable-next-line react-hooks/exhaustive-deps -- reloadQuiet reads only stable refs; empty deps keeps the handler map identity stable across renders so useEventStream doesn't churn subscriptions
  }), [])

  // Side-effect only — the hook holds the SSE connection open and
  // dispatches sseHandlers as events arrive. No `status` capture: the
  // polling fallbacks that used to gate on it are gone (zero-poll
  // strategy, see block below), and there's no UI indicator today.
  useEventStream({
    enabled: !viewTrash,
    topics: ['import-batches'],
    handlers: sseHandlers,
  })

  // ─── Zero-poll update strategy (2026-09-23) ───────────────────────
  // All three polls that used to live here (4 s batch-list, 20 s idle
  // refresh, 2 s per-IN_PROGRESS observer for generationProgress) were
  // removed. The page now updates via exactly two paths:
  //
  //   1. SSE push (see sseHandlers above) — cross-operator batch state
  //      changes arrive through the always-open /events/stream. Requires
  //      REDIS_HOST + Redis autoconfig on (PR #744 wired the dev profile;
  //      prod already sets it). When SSE drops, the browser tab shows
  //      stale state until the operator hits Refresh.
  //
  //   2. Explicit operator action — the Refresh button in the tab
  //      toolbar (listActions) calls load(), and clicking to expand a
  //      batch triggers ensureRows(id) which now ALSO one-shot-fetches
  //      generationProgress if the batch is IN_PROGRESS.
  //
  // Trade-off: the "X of N" counter no longer ticks live during a run;
  // it snapshots on expand + on Refresh. Operators asked for this
  // explicitly ("remove the observer and update the status after page
  // load") because a 50-batch list × 2 s polls was still visibly slow.

  /** Show a success / info / error toast that matches the generation outcome,
   *  so a FAILED batch never appears under a green "Success" header. */
  const notifyForStatus = (status: string | null | undefined, message: string) => {
    switch ((status || '').toUpperCase()) {
      case 'COMPLETE':
        notify.success(message)
        break
      case 'PARTIAL_COMPLETE':
        notify.info({ title: 'Partially generated', body: message })
        break
      case 'FAILED':
      case 'INITIATE':
        notify.error({ title: 'Label generation failed', body: message })
        break
      case 'CANCELLED':
        // Import I-3 — operator cancelled during the run. Labels that
        // finished before the cancel toggle keep their generated status
        // and stay downloadable; the rest carry a "Cancelled by operator"
        // error and can be retried from Data History.
        notify.info({ title: 'Cancelled', body: message })
        break
      default:
        notify.info(message)
    }
  }

  /** Kick off label generation for a batch. Optimistically flips the row to
   *  "In progress" while the carrier calls run, then reflects the result. */

  /** Persist a batch's bill-to account mode (survives reload + auditable). */
  const setBilling = async (id: number, mode: 'AUTO' | 'PLATFORM') => {
    setBillingSavingId(id)
    // Optimistic: reflect the choice immediately.
    setBatches((list) => list.map((b) => (b.id === id ? { ...b, billingMode: mode } : b)))
    if (mode !== 'PLATFORM') setConfirmGenId((c) => (c === id ? null : c))
    try {
      await orderImportService.setBillingMode(id, mode)
    } catch (e) {
      notify.apiError(e, 'Could not update the bill-to account.')
      await load() // revert to server truth on failure
    } finally {
      setBillingSavingId(null)
    }
  }

  /** Fix #302 F3.2 — RETRY (isRetry) uses onlyFailed=true so already-generated
   *  rows aren't re-sent to the carrier + re-billed. Platform billing mode
   *  (billingMode) forces the house account for every row. Double-click is
   *  guarded by the generatingId===id busy check at the call site. */
  /** 409 from generate = the server found rows whose orders already have a live
   *  label. Ask before shipping them a second time; true = the user confirmed. */
  const confirmDuplicates = async (e: unknown): Promise<boolean> => {
    // IMPORT_BATCH_STATE 409s (in Trash, generating…) are not "already labelled" questions.
    if (!(e instanceof ApiError) || e.status !== 409 || e.errorCode === 'IMPORT_BATCH_STATE') return false
    return notify.confirm(`${e.message}\n\nGenerate anyway?`, {
      title: 'These orders are already labelled',
      confirmLabel: 'Generate anyway',
      cancelLabel: 'Cancel',
      danger: true,
    })
  }

  const generate = async (id: number, isRetry: boolean, allowDuplicate = false) => {
    const platform = batches.find((b) => b.id === id)?.billingMode === 'PLATFORM'
    setConfirmGenId(null)
    setGeneratingId(id)
    setGenProgressById((m) => ({ ...m, [id]: { done: 0, total: 0 } }))
    // Carry the new run's own start (and drop the previous run's finish) or the
    // caption would tick from the LAST run's start until the first poll lands.
    setBatches((list) => list.map((b) => (b.id === id
      ? { ...b, status: 'IN_PROGRESS', generationStartedAt: new Date().toISOString(), completedAt: null, note: null }
      : b)))
    // Poll the server's live counter ALONGSIDE the generate request (a separate
    // GET) so the button shows a real "X of N" bar while the POST runs. The flag
    // stops the loop the moment the POST settles.
    let polling = true
    const pollProgress = async () => {
      while (polling) {
        try {
          const pr = await orderImportService.generationProgress(id)
          const d = pr.data
          if (polling && d && d.running && d.total > 0) {
            setGenProgressById((m) => ({ ...m, [id]: { done: d.done, total: d.total, note: d.note ?? null, cancelling: !!d.cancelling, jobStatus: d.jobStatus ?? null } }))
          }
        } catch {
          /* transient poll error — keep going, the POST result is authoritative */
        }
        await new Promise((r) => setTimeout(r, 400))
      }
    }
    void pollProgress()
    try {
      const res = await orderImportService.generateLabels(id, { onlyFailed: isRetry, usePlatformAccount: platform, allowDuplicate })
      const updated = res.data
      if (updated && (updated.status || '').toUpperCase() === 'IN_PROGRESS') {
        // Queued as a background job (the default). The card is driven by the
        // progress poll; follow the job to the end, then show what happened.
        const final = await orderImportService.waitForGeneration(id, { isAlive: () => mountedRef.current })
        if (!final) return
        let detail: Awaited<ReturnType<typeof orderImportService.getHistory>>['data'] | undefined
        try {
          detail = (await orderImportService.getHistory(id)).data
        } catch {
          /* the list reload below still settles the row */
        }
        if (detail) {
          const d = detail
          setBatches((list) =>
            list.map((b) =>
              b.id === id
                ? {
                    ...b,
                    status: d.status,
                    savedRows: d.savedRows,
                    invalidRows: d.invalidRows,
                    labelBatchId: d.labelBatchId ?? b.labelBatchId,
                    generationStartedAt: d.generationStartedAt ?? b.generationStartedAt,
                    completedAt: d.completedAt ?? b.completedAt,
                    note: d.note ?? null,
                  }
                : b,
            ),
          )
          if (d.rows) setRowsById((m) => ({ ...m, [id]: d.rows }))
        } else {
          await load()
        }
        notifyForStatus(
          final.resultStatus ?? detail?.status ?? 'FAILED',
          final.resultMessage ?? detail?.note ?? 'Label generation finished.',
        )
        return
      }
      if (updated) {
        setBatches((list) =>
          list.map((b) =>
            b.id === id
              ? {
                  ...b,
                  status: updated.status,
                  savedRows: updated.savedRows,
                  invalidRows: updated.invalidRows,
                  labelBatchId: updated.labelBatchId ?? b.labelBatchId,
                  generationStartedAt: updated.generationStartedAt ?? b.generationStartedAt,
                  completedAt: updated.completedAt ?? b.completedAt,
                  note: updated.note ?? null,
                }
              : b,
          ),
        )
        // Refresh the expanded rows so tracking numbers show.
        if (updated.rows) setRowsById((m) => ({ ...m, [id]: updated.rows }))
        notifyForStatus(updated.status, res.message ?? 'Label generation finished.')
      } else {
        await load()
      }
    } catch (e) {
      if (!allowDuplicate && (await confirmDuplicates(e))) {
        polling = false
        setGeneratingId(null)
        await generate(id, isRetry, true)
        return
      }
      // Import I-11 — a second Generate click landed while another
      // browser tab / operator was already running the batch. Backend
      // refuses with 409 IMPORT_BATCH_ALREADY_GENERATING rather than
      // silently minting duplicate paid shipments. Surface a friendly
      // message and reload to reflect the current status.
      if (e instanceof ApiError && e.status === 409
          && (e.errorCode === 'IMPORT_BATCH_ALREADY_GENERATING'
              || (e.message ?? '').includes('IN_PROGRESS'))) {
        notify.info({
          title: 'Batch is already generating',
          body: 'Another operator (or another tab) is already generating labels for this batch. '
            + 'Refreshing to show the live status.',
        })
        await load()
      } else {
        notify.apiError(e, 'Label generation failed.')
        await load()
      }
    } finally {
      polling = false
      setGeneratingId(null)
      setGenProgressById((m) => {
        const next = { ...m }
        delete next[id]
        return next
      })
    }
  }

  /** Import I-3 — cancel an in-flight label generation. Backend flips
   *  a cooperative flag; workers stop picking up new orders. Already-
   *  in-flight carrier calls run to completion (we can't interrupt a
   *  paid label mid-request without leaking it). */
  const [cancellingId, setCancellingId] = useState<number | null>(null)
  const cancelGeneration = async (id: number) => {
    if (cancellingId != null) return
    const ok = await notify.confirm(
      `Orders already at the carrier finish (that can take a few seconds); orders still queued are not sent. `
        + `Labels already made stay in Import history, and Retry labels sends the rest later.`,
      { title: `Cancel label generation for import #${id}?`, confirmLabel: 'Cancel generation', cancelLabel: 'Keep running', danger: true },
    )
    if (!ok) return
    setCancellingId(id)
    try {
      await orderImportService.cancelGeneration(id)
      setCancelRequested((cur) => new Set(cur).add(id))
      notify.info(`Cancelling import #${id} — finishing the orders already at the carrier.`)
      // Poll status a few times so the button flips when the run
      // actually finishes; the existing generate() polling loop drives
      // most of the UX, this just covers the case where cancel arrives
      // after generate() already returned.
      await load()
    } catch (e) {
      notify.apiError(e, 'Cancel failed.')
    } finally {
      setCancellingId(null)
    }
  }

  /** Validate all rows in a batch */
  const validateAll = async (id: number): Promise<OrderImportRow[] | undefined> => {
    let rowsBack: OrderImportRow[] | undefined
    setValidatingId(id)
    try {
      const res = await orderImportService.validateAllRows(id)
      const updated = res.data
      if (updated) {
        // Update the batch in the list
        setBatches((list) =>
          list.map((b) =>
            b.id === id
              ? { ...b, status: updated.status, totalRows: updated.totalRows, savedRows: updated.savedRows, invalidRows: updated.invalidRows }
              : b,
          ),
        )
        // Update the expanded rows
        if (updated.rows) setRowsById((m) => ({ ...m, [id]: updated.rows }))
        rowsBack = updated.rows ?? undefined
        notify.success('All rows validated successfully. Errors have been updated.')
      }
    } catch (e) {
      notify.apiError(e, 'Validation failed.')
    } finally {
      setValidatingId(null)
    }
    return rowsBack
  }

  /** Generate a label for a single row inside a batch. */
  const generateRow = async (batchId: number, rowNumber: number, allowDuplicate = false) => {
    const key = `${batchId}-${rowNumber}`
    setGenRowKey(key)
    try {
      const res = await orderImportService.generateRowLabel(batchId, rowNumber, allowDuplicate)
      const updated = res.data
      if (updated) {
        if (updated.rows) setRowsById((m) => ({ ...m, [batchId]: updated.rows }))
        setBatches((list) =>
          list.map((b) =>
            b.id === batchId
              ? {
                  ...b,
                  status: updated.status,
                  savedRows: updated.savedRows,
                  invalidRows: updated.invalidRows,
                  labelBatchId: updated.labelBatchId ?? b.labelBatchId,
                  generationStartedAt: updated.generationStartedAt ?? b.generationStartedAt,
                  completedAt: updated.completedAt ?? b.completedAt,
                }
              : b,
          ),
        )
        // Notify on THIS row's outcome, not the whole batch.
        const thisRow = updated.rows?.find((r) => r.rowNumber === rowNumber)
        if ((thisRow?.generatedStatus ?? '').toUpperCase() === 'GENERATED') {
          notify.success(`Label generated for row ${rowNumber}.`)
        } else {
          notify.error({
            title: `Row ${rowNumber} — label failed`,
            body: thisRow?.generatedMessage || res.message || 'The carrier rejected this shipment.',
          })
        }
      }
    } catch (e) {
      if (!allowDuplicate && (await confirmDuplicates(e))) {
        setGenRowKey(null)
        await generateRow(batchId, rowNumber, true)
        return
      }
      notify.apiError(e, 'Label generation failed.')
    } finally {
      setGenRowKey(null)
    }
  }

  /**
   * Persist one edited cell in place. Applies the typed value to the row,
   * PUTs it (updateRow re-validates the whole batch server-side), and drops
   * the fresh rows + counts back into state so the grid repaints — red cells,
   * ready/held status, and the batch counters all update. No-op when the value
   * is unchanged.
   */
  const commitCell = async (batchId: number, row: OrderImportRow, col: DhColumn, raw: string) => {
    let next: unknown = raw
    if (col.numeric) next = raw === '' ? null : Number(raw)
    else if (col.upper) next = raw.toUpperCase()
    const current = col.key === 'serviceType' ? (row.shipViaCode ?? row.serviceType) : (row as unknown as Record<string, unknown>)[col.key]
    if (String(current ?? '') === String(next ?? '')) return // unchanged
    const edited = { ...row, [col.key]: next } as OrderImportRow
    const key = `${batchId}-${row.rowNumber}`
    setSavingCell(key)
    try {
      await saveRow(batchId, edited)
    } finally {
      setSavingCell(null)
    }
  }

  /** Save one import row; the server re-validates the batch and answers with every row. */
  const saveRow = async (batchId: number, edited: OrderImportRow): Promise<OrderImportRow[] | undefined> => {
    try {
      const res = await orderImportService.updateRow(batchId, edited.rowNumber, edited)
      const updated = res.data
      if (updated) {
        if (updated.rows) setRowsById((m) => ({ ...m, [batchId]: updated.rows }))
        setBatches((list) =>
          list.map((b) =>
            b.id === batchId
              ? {
                  ...b,
                  status: updated.status,
                  savedRows: updated.savedRows,
                  invalidRows: updated.invalidRows,
                  generationStartedAt: updated.generationStartedAt ?? b.generationStartedAt,
                  completedAt: updated.completedAt ?? b.completedAt,
                }
              : b,
          ),
        )
        return updated.rows ?? undefined
      } else {
        notify.error(res.message ?? 'Save failed.')
      }
    } catch (e) {
      notify.apiError(e, 'Save failed.')
    }
  }

  useEffect(() => {
    if (cancelRequested.size === 0) return
    const stillRunning = new Set(batches.filter((b) => (b.status || '').toUpperCase() === 'IN_PROGRESS').map((b) => b.id))
    const next = new Set([...cancelRequested].filter((id) => stillRunning.has(id)))
    // eslint-disable-next-line react-hooks/set-state-in-effect -- prune once the run ends
    if (next.size !== cancelRequested.size) setCancelRequested(next)
  }, [batches, cancelRequested])

  /** Where a batch's labels stand — counted from its rows on the batch page, else the server's counts. */
  const labelCountsFor = (b: ImportBatchSummary): LabelCounts | null => {
    const r = rowsById[b.id]
    if (Array.isArray(r)) return labelCountsOf(r)
    if (b.labelsGenerated == null) return null
    return { generated: b.labelsGenerated ?? 0, pending: b.labelsPending ?? 0, voided: b.labelsVoided ?? 0, failed: b.labelsFailed ?? 0 }
  }

  /** When the batch was last printed — from the list, or from its rows on the batch page. */
  const printedAtOf = (b: ImportBatchSummary): string | null => {
    const r = rowsById[b.id]
    const fromRows = Array.isArray(r)
      ? r.map((x) => x.lastPrintedAt).filter((x): x is string => !!x).sort().pop() ?? null
      : null
    return fromRows ?? b.lastPrintedAt ?? null
  }

  /** A batch whose labels can be generated (or retried) now. */
  // useCallback so the memoized scans below can honestly list this in
  // their deps without churning every render (was a plain closure).
  const canGenerateBatch = useCallback((b: ImportBatchSummary) => {
    const st = (b.status || '').toUpperCase()
    return canWrite && (st === 'INITIATE' || st === 'PARTIAL_COMPLETE' || st === 'FAILED'
      || st === 'CANCELLED' || (st === 'DRAFT' && b.savedRows > 0))
  }, [canWrite])
  const isRetryBatch = (b: ImportBatchSummary) => ['PARTIAL_COMPLETE', 'FAILED', 'CANCELLED'].includes((b.status || '').toUpperCase())

  // ── Ticked batches in the list: Bills to and Generate act on all of them at once.
  const [pickedBatches, setPickedBatches] = useState<number[]>([])
  // Memoize the derived scans so the once-a-second re-render (or any
  // parent state churn) doesn't rescan `batches` for every downstream
  // reader. Cheap wins: pickedSet becomes stable identity, pickable /
  // pickedLive / pickedBilling / allPicked only recompute when their
  // real inputs change.
  const pickedSet = useMemo(() => new Set(pickedBatches), [pickedBatches])
  /** Live labels to print — the loaded rows when the page has them, else the list's figure. */
  const hasLiveLabels = useCallback((b: ImportBatchSummary) => {
    const r = rowsById[b.id]
    return Array.isArray(r) ? liveOrdersOf(r).length > 0 : (b.liveOrders ?? b.labelsGenerated ?? 0) > 0
  }, [rowsById])
  const canPickBatch = useCallback((b: ImportBatchSummary) => canGenerateBatch(b) || hasLiveLabels(b), [canGenerateBatch, hasLiveLabels])
  const pickable = useMemo(() => batches.filter(canPickBatch), [batches, canPickBatch])
  const pickedPrintable = useMemo(() => batches.filter((b) => pickedSet.has(b.id) && hasLiveLabels(b)), [batches, pickedSet, hasLiveLabels])
  const allPicked = useMemo(
    () => pickable.length > 0 && pickable.every((b) => pickedSet.has(b.id)),
    [pickable, pickedSet],
  )
  const togglePickBatch = (id: number) => setPickedBatches((cur) => cur.includes(id) ? cur.filter((x) => x !== id) : [...cur, id])
  const togglePickAll = () => setPickedBatches(allPicked ? [] : pickable.map((b) => b.id))
  const [bulkRunning, setBulkRunning] = useState<{ done: number; total: number } | null>(null)
  const pickedLive = useMemo(
    () => batches.filter((b) => pickedSet.has(b.id) && canGenerateBatch(b)),
    [batches, pickedSet, canGenerateBatch],
  )
  const pickedBilling = useMemo<'AUTO' | 'PLATFORM' | 'MIXED'>(
    () => pickedLive.length === 0 ? 'AUTO'
      : pickedLive.every((b) => b.billingMode === 'PLATFORM') ? 'PLATFORM'
        : pickedLive.every((b) => b.billingMode !== 'PLATFORM') ? 'AUTO' : 'MIXED',
    [pickedLive],
  )
  /** Bills to, for every ticked batch. */
  const setBillingForPicked = async (mode: 'AUTO' | 'PLATFORM') => {
    for (const b of pickedLive) await setBilling(b.id, mode)
  }
  /** Generate (or retry) every ticked batch, one after another, after one confirmation. */
  const generatePicked = async () => {
    if (pickedLive.length === 0 || bulkRunning) return
    const rows = pickedLive.reduce((n, b) => n + b.savedRows, 0)
    const platform = pickedLive.filter((b) => b.billingMode === 'PLATFORM').length
    const ok = await notify.confirm(
      `Labels will be bought for ${pickedLive.length} batch${pickedLive.length === 1 ? '' : 'es'} (${rows} rows)`
        + (platform ? ` — ${platform} of them billed to the platform account` : '') + '. Batches with failed rows are retried, not re-bought.',
      { title: `Generate labels for ${pickedLive.length} batch${pickedLive.length === 1 ? '' : 'es'}?`, confirmLabel: 'Generate labels', cancelLabel: 'Not now', danger: platform > 0 },
    )
    if (!ok) return
    setBulkRunning({ done: 0, total: pickedLive.length })
    try {
      for (const b of pickedLive) {
        await generate(b.id, isRetryBatch(b))
        setBulkRunning((r) => (r ? { ...r, done: r.done + 1 } : r))
      }
    } finally {
      setBulkRunning(null)
      setPickedBatches([])
      void reloadQuiet()
    }
  }

  /** Live labels of a batch, when its rows are loaded (the batch page); the server enforces the rule either way. */
  const liveCountOf = (b: ImportBatchSummary) => {
    const r = rowsById[b.id]
    // Orders, not rows — the batch page counts its loaded rows, the list uses the server's figure.
    return Array.isArray(r) ? liveOrdersOf(r).length : (b.liveOrders ?? b.labelsGenerated ?? 0)
  }

  // Status, rows and actions of a batch — shared by the table's cells and the batch page.
  const renderStatusCell = (b: ImportBatchSummary) => {
          const s = statusMeta(b.status)
          // Completion caption (2026-09-12) — only rendered when the
          // batch has landed a terminal state at least once; retries
          // that go back through IN_PROGRESS null completedAt so the
          // caption disappears until the next terminal transition.
          const ago = relativeTime(b.completedAt)
          const done = ago ? `completed ${ago}` : null
          const startedMs = b.generationStartedAt ? new Date(b.generationStartedAt).getTime() : null
          const running = (b.status || '').toUpperCase() === 'IN_PROGRESS'
          // Finished-run elapsed is a fixed diff; running-run elapsed is
          // rendered by <RunningElapsed/> so the 1s tick doesn't invalidate
          // the whole dhColumns memo.
          const finishedElapsed = !running && startedMs != null && b.completedAt
            ? formatDuration(new Date(b.completedAt).getTime() - startedMs)
            : null
          const timingTitle = [
            b.generationStartedAt ? `Started ${new Date(b.generationStartedAt).toLocaleString()}` : null,
            b.completedAt ? `Finished ${new Date(b.completedAt).toLocaleString()}` : null,
          ].filter(Boolean).join(' · ') || undefined
          return (
            <span className="flex max-w-[220px] flex-col items-start gap-0.5">
              <span className={`rounded-full px-2.5 py-0.5 text-[10.5px] font-bold ring-1 ${s.cls}`}>{s.label}</span>
              {running && startedMs != null ? (
                <span
                  className="text-[10px] tabular-nums font-semibold text-[#412d15]"
                  title={timingTitle}
                >
                  <RunningElapsed startedMs={startedMs} prefix="running " />
                </span>
              ) : finishedElapsed ? (
                <span
                  className="text-[10px] tabular-nums text-[#8a7a5a]"
                  title={timingTitle}
                >
                  {`took ${finishedElapsed}`}
                  {done ? <span className="text-[#b6a684]"> · {done}</span> : null}
                </span>
              ) : done ? (
                <span
                  className="text-[10px] text-[#8a7a5a]"
                  title={timingTitle ?? (b.completedAt ? new Date(b.completedAt).toLocaleString() : undefined)}
                >
                  {done}
                </span>
              ) : null}
              {b.note ? (
                // Batch #11 post-mortem (2026-09-12) — batch-level note
                // like "UPS was rate-limiting throughout — N rows still
                // queued". Rendered as a subtle amber caption; full text
                // in the title so operators can scan on hover.
                <span
                  className="whitespace-normal text-[10px] leading-snug text-amber-800"
                  title={b.note}
                >
                  {b.note}
                </span>
              ) : null}
              {printedAtOf(b) ? (
                <span className="inline-flex items-center gap-1 text-[10px] font-semibold text-emerald-700" title={`Last printed ${formatPrinted(printedAtOf(b)!, true)}`}>
                  <FiPrinter className="h-3 w-3" aria-hidden="true" /> Printed {formatPrinted(printedAtOf(b)!)}
                </span>
              ) : null}
              {b.deletedAt ? (
                // Trash showed the import's created date only — when it was
                // trashed, and by whom, is what decides whether to restore it.
                <span className="inline-flex items-center gap-1 text-[10px] font-semibold text-rose-700" title={`Trashed ${new Date(b.deletedAt).toLocaleString()}`}>
                  <FiTrash2 className="h-3 w-3" aria-hidden="true" /> Trashed {relativeTime(b.deletedAt) ?? formatPrinted(b.deletedAt)}{b.deletedBy ? ` by ${b.deletedBy}` : ''}
                </span>
              ) : null}
            </span>
          )
  }

  const renderRowsCell = (b: ImportBatchSummary) => {
          const total = b.totalRows || 0
          const invalid = b.invalidRows || 0
          // Errors are the only thing worth a second line: a clean import is just its size.
          // The bar is the share of rows whose whole order can be labelled.
          const readyPct = total > 0 ? Math.min(100, (b.savedRows / total) * 100) : 0
          return (
            <span className="flex w-full min-w-[88px] flex-col gap-1">
              <span className="text-[12px] font-semibold tabular-nums text-[#1f150c]">
                {total} row{total === 1 ? '' : 's'}
              </span>
              <LabelCountsLine counts={labelCountsFor(b)} />
              {invalid > 0 ? (
                <>
                  <span
                    className="flex h-1 w-full max-w-[110px] overflow-hidden rounded-full bg-rose-200"
                    title={`${b.savedRows} of ${total} rows can be labelled · the rest wait on fixes`}
                  >
                    <span className="h-full bg-[#b6a684]" style={{ width: `${readyPct}%` }} />
                  </span>
                  <span
                    className="inline-flex items-center gap-1 text-[10.5px] font-semibold text-rose-700"
                    title="Open the import to see each error under its row"
                  >
                    <FiAlertCircle className="h-3 w-3 shrink-0" />
                    {invalid} need{invalid === 1 ? 's' : ''} fixes
                  </span>
                </>
              ) : null}
            </span>
          )
  }

  const renderActionsCell = (b: ImportBatchSummary) => {
          const st = (b.status || '').toUpperCase()
          // WMS/API batches are labelled here exactly like file imports (their
          // orders are stamped source = API); there is no other place to label them.
          // A Draft (saved with errors via "Proceed with errors") can label its valid
          // rows now; the rows with errors are skipped until they are fixed.
          // CANCELLED leaves orders not yet labelled — Retry sends them (it had no button).
          const canGenerate = canGenerateBatch(b)
          const isRetry = st === 'PARTIAL_COMPLETE' || st === 'FAILED' || st === 'CANCELLED'
          // busy renders the progress bar. Include server-side IN_PROGRESS
          // (2026-09-12 fix) so operators watching a batch started in
          // another session / tab / browser see the same bar. The
          // observer-poll effect populates genProgressById for those.
          const busy = generatingId === b.id || st === 'IN_PROGRESS'
          const progress = genProgressById[b.id]
          const platform = b.billingMode === 'PLATFORM'
          const confirming = confirmGenId === b.id
          return (
            <div className={`flex items-center gap-1.5 [&_button]:whitespace-nowrap ${batchPageId != null ? 'justify-end' : 'justify-start'}`}>
              {viewTrash ? (
                canWrite ? (
                  <button
                    type="button"
                    onClick={() => void restoreBatch(b.id, b.fileName)}
                    disabled={trashBusyId === b.id}
                    title="Restore this import from Trash"
                    className="inline-flex items-center gap-1.5 rounded-xl border border-[#412d15] bg-white px-3 py-2 text-[12px] font-semibold text-[#412d15] transition hover:bg-[#faf7f0] disabled:cursor-not-allowed disabled:opacity-50"
                  >
                    {trashBusyId === b.id ? (
                      <span className="inline-block h-3.5 w-3.5 animate-spin rounded-full border-2 border-[#412d15]/30 border-t-[#412d15]" />
                    ) : (
                      <FiRotateCcw className="h-3.5 w-3.5" />
                    )}
                    Restore
                  </button>
                ) : null
              ) : (
                <>
                  {/* Validate all — re-checks every row; a quiet secondary button beside Generate. */}
                  <button
                    type="button"
                    onClick={() => void validateAll(b.id)}
                    disabled={validatingId === b.id || (b.status || '').toUpperCase() === 'IN_PROGRESS'}
                    title="Validate all rows in this batch and update their errors/warnings"
                    className={batchPageId == null
                      ? 'inline-flex items-center justify-center rounded-xl border border-emerald-100 bg-emerald-50 p-2 text-emerald-700 transition hover:border-emerald-200 hover:bg-emerald-100 disabled:cursor-not-allowed disabled:opacity-40'
                      : BTN_GHOST_SM}
                  >
                    {validatingId === b.id ? (
                      <span className="inline-block h-3 w-3 animate-spin rounded-full border-2 border-emerald-100 border-t-emerald-600" />
                    ) : (
                      // A green check: "check every row and confirm it's ready".
                      <FiCheckCircle className="h-3.5 w-3.5 text-emerald-600" aria-hidden="true" />
                    )}
                    {/* In the list the check icon alone (tooltip); the batch page spells it out. */}
                    <span className={batchPageId == null ? 'sr-only' : ''}>{validatingId === b.id ? 'Validating…' : 'Validate all'}</span>
                  </button>
                  {/* Keep the button mounted while THIS batch is generating — the
                      click optimistically flips status to IN_PROGRESS, which isn't
                      in canGenerate's set, so without `|| busy` the whole control
                      (and its spinner) would unmount the instant you click and the
                      loader would never show. */}
                  {(batchPageId != null || busy) && (canGenerate || busy) ? (
                    <>
                      <span
                        title="Which carrier account this batch bills to. Platform bills the house account and rebills the client with markup."
                        className={`${confirming || busy || !SHOW_BILLS_TO ? 'hidden' : 'inline-flex'} items-center gap-1.5 rounded-xl border px-2.5 py-1.5 text-[11px] font-semibold ${
                          platform ? 'border-[#412d15] bg-[#412d15]/5 text-[#412d15]' : 'border-[#e3d9c4] bg-white text-[#5a4526]'
                        }`}
                      >
                        <FiHome className="h-3.5 w-3.5 shrink-0" />
                        <span className="hidden sm:inline text-[9.5px] uppercase tracking-[0.08em] text-[#b6a684]">Bills to</span>
                        <select
                          value={platform ? 'PLATFORM' : 'AUTO'}
                          disabled={busy || st === 'IN_PROGRESS' || billingSavingId === b.id}
                          onChange={(e) => void setBilling(b.id, e.target.value as 'AUTO' | 'PLATFORM')}
                          className="cursor-pointer border-0 bg-transparent pr-1 text-[11px] font-semibold text-inherit focus:outline-none disabled:cursor-not-allowed"
                        >
                          <option value="AUTO">Client account</option>
                          <option value="PLATFORM">Platform account</option>
                        </select>
                      </span>
                      {platform && confirming ? (
                        <>
                          <button
                            type="button"
                            onClick={() => void generate(b.id, isRetry)}
                            disabled={busy}
                            className="inline-flex items-center gap-1.5 rounded-xl bg-[#412d15] px-3 py-2 text-[12px] font-semibold text-[#f4eede] shadow-sm transition hover:bg-[#5a4526] disabled:cursor-not-allowed disabled:bg-[#dcd4c4]"
                          >
                            <FiHome className="h-3.5 w-3.5" />
                            Confirm — bill to platform
                          </button>
                          <button
                            type="button"
                            onClick={() => setConfirmGenId(null)}
                            disabled={busy}
                            className="inline-flex items-center rounded-xl border border-[#e3d9c4] bg-white px-3 py-2 text-[12px] font-semibold text-[#5a4526] transition hover:bg-[#faf7f0]"
                          >
                            Cancel
                          </button>
                        </>
                      ) : busy && batchPageId == null ? (
                        <button
                          type="button"
                          onClick={() => void cancelGeneration(b.id)}
                          disabled={cancellingId === b.id || cancelRequested.has(b.id) || !!progress?.cancelling}
                          title="Stop this run — labels already bought stay; queued rows are skipped"
                          className="inline-flex items-center gap-1.5 rounded-xl border border-[#e3d9c4] bg-white px-2.5 py-1.5 text-[11.5px] font-semibold text-[#5a4526] transition hover:border-rose-300 hover:bg-rose-50 hover:text-rose-700 disabled:cursor-not-allowed disabled:opacity-50"
                        >
                          <span className="inline-block h-3 w-3 animate-spin rounded-full border-2 border-amber-200 border-t-amber-600" />
                          {cancellingId === b.id || cancelRequested.has(b.id) || progress?.cancelling ? 'Cancelling…'
                            : progress && progress.total > 0 ? `${progress.done} of ${progress.total} · Cancel` : 'Cancel'}
                        </button>
                      ) : busy ? (
                        // Live progress while generating: a real X-of-N bar once
                        // the first poll lands, an indeterminate shimmer until then.
                        // Import I-3 — Cancel button appears alongside so the operator
                        // can stop mid-run. Workers finish already-in-flight carrier
                        // calls; queued groups are skipped.
                        (() => {
                          const total = progress?.total ?? 0
                          const done = Math.min(progress?.done ?? 0, total)
                          const pct = total > 0 ? Math.round((done / total) * 100) : 0
                          // Another tab's cancel shows up through the server flag.
                          const stopping = cancelRequested.has(b.id) || !!progress?.cancelling
                          return (
                            <div className="flex items-center gap-2">
                              <div
                                className={`flex min-w-[208px] flex-col gap-1.5 rounded-xl px-3 py-2 ${stopping ? 'bg-amber-900 text-amber-50' : 'bg-[#1f150c] text-[#f4eede]'}`}
                                role="progressbar"
                                aria-valuemin={0}
                                aria-valuemax={total || undefined}
                                aria-valuenow={total > 0 ? done : undefined}
                                title={total > 0 ? `Generating labels — ${done} of ${total} done` : 'Generating labels…'}
                              >
                                {/* Three zones so nothing collides: label + elapsed,
                                    the bar, then the count. */}
                                <div className="flex items-center justify-between gap-3 text-[11.5px] font-semibold leading-none">
                                  <span className="inline-flex items-center gap-1.5">
                                    <span className="inline-block h-3 w-3 shrink-0 animate-spin rounded-full border-2 border-[#f4eede]/40 border-t-[#f4eede]" />
                                    {stopping ? 'Cancelling…' : progress?.jobStatus === 'QUEUED' ? 'Queued…' : 'Generating…'}
                                  </span>
                                  {(() => {
                                    const st = b.generationStartedAt ? new Date(b.generationStartedAt).getTime() : null
                                    return st != null ? (
                                      <RunningElapsed
                                        startedMs={st}
                                        className="shrink-0 font-mono tabular-nums text-[#f4eede]/85"
                                      />
                                    ) : null
                                  })()}
                                </div>
                                <div className="h-1.5 w-full overflow-hidden rounded-full bg-[#f4eede]/20">
                                  {total > 0 ? (
                                    <div
                                      className="h-full rounded-full bg-[#f4eede] transition-[width] duration-300 ease-out"
                                      style={{ width: `${pct}%` }}
                                    />
                                  ) : (
                                    <div className="h-full w-1/3 animate-pulse rounded-full bg-[#f4eede]/70" />
                                  )}
                                </div>
                                <div className="flex items-center justify-between text-[10px] leading-none tabular-nums text-[#f4eede]/70">
                                  <span>
                                    {stopping
                                      ? 'Finishing orders at the carrier'
                                      : total > 0 ? `${done} of ${total} orders` : 'starting…'}
                                  </span>
                                  {total > 0 ? <span>{stopping ? `${done} done` : `${pct}%`}</span> : null}
                                </div>
                                {progress?.note ? (
                                  <div className="max-w-[260px] text-[10px] leading-snug text-[#f4eede]/85" aria-live="polite">
                                    {progress.note}
                                  </div>
                                ) : null}
                              </div>
                              <button
                                type="button"
                                onClick={() => void cancelGeneration(b.id)}
                                disabled={cancellingId === b.id || cancelRequested.has(b.id) || !!progress?.cancelling}
                                title="Stop workers from picking up more orders. Already-in-flight carrier calls run to completion."
                                className="inline-flex items-center gap-1.5 rounded-xl border border-rose-300 bg-rose-50 px-2.5 py-1.5 text-[11px] font-semibold text-rose-800 transition hover:bg-rose-100 disabled:opacity-40"
                              >
                                <FiSlash className="h-3 w-3" />
                                {cancellingId === b.id || cancelRequested.has(b.id) || progress?.cancelling ? 'Cancelling…' : 'Cancel'}
                              </button>
                            </div>
                          )
                        })()
                      ) : (
                        <button
                          type="button"
                          onClick={() => (platform ? setConfirmGenId(b.id) : void generate(b.id, isRetry))}
                          disabled={justRestoredId === b.id}
                          title={st === 'DRAFT'
                            ? 'Generate labels for the valid rows — rows with errors are skipped until you fix them'
                            : isRetry ? 'Retry generating labels — only rows that FAILED or are un-generated will be re-sent' : 'Generate carrier labels for this saved import'}
                          className="inline-flex items-center gap-1.5 rounded-xl bg-[#1f150c] px-3 py-2 text-[12px] font-semibold text-[#f4eede] shadow-sm transition hover:bg-[#412d15] disabled:cursor-not-allowed disabled:bg-[#dcd4c4]"
                        >
                          <FiZap className="h-3.5 w-3.5" />
                          {isRetry ? 'Retry labels' : 'Generate labels'}
                        </button>
                      )}
                    </>
                  ) : null}
                  {batchPageId == null && !viewTrash && b.labelBatchId != null && b.liveOrders !== 0 ? (
                    <>
                      {canWrite ? (
                        <button
                          type="button"
                          onClick={() => void voidBatchLabels(b)}
                          disabled={batchPrintBusy === b.id || liveCountOf(b) === 0 || st === 'IN_PROGRESS'}
                          title={st === 'IN_PROGRESS' ? 'Not while the batch is generating'
                            : liveCountOf(b) === 0 ? 'No live labels to void'
                              : `Void every live label of this batch (${liveCountOf(b)}) with the carriers`}
                          aria-label="Void batch labels"
                          className="inline-flex items-center justify-center rounded-xl border border-rose-100 bg-rose-50 p-2 text-rose-700 transition enabled:hover:border-rose-300 enabled:hover:bg-rose-100 disabled:cursor-not-allowed disabled:opacity-40"
                        >
                          <FiSlash className="h-3.5 w-3.5" />
                        </button>
                      ) : null}
                    </>
                  ) : null}
                  {canWrite ? (
                    <button
                      type="button"
                      onClick={() => void handleDelete(b.id, b.fileName)}
                      disabled={trashBusyId === b.id || st === 'IN_PROGRESS' || liveCountOf(b) > 0}
                      title={st === 'IN_PROGRESS' ? 'Wait for the label run to finish (or cancel it) before moving this import to Trash'
                        : liveCountOf(b) > 0 ? `${liveCountOf(b)} label${liveCountOf(b) === 1 ? ' is' : 's are'} still live — void ${liveCountOf(b) === 1 ? 'it' : 'them'} first, then this import can be deleted`
                          : 'Move this import to Trash (recoverable)'}
                      aria-label="Delete import"
                      className="inline-flex items-center justify-center rounded-xl border border-[#e3d9c4] bg-white p-2 text-[#6b5c42] transition enabled:hover:border-rose-300 enabled:hover:bg-rose-50 enabled:hover:text-rose-600 disabled:cursor-not-allowed disabled:opacity-50"
                    >
                      {trashBusyId === b.id ? (
                        <span className="inline-block h-3.5 w-3.5 animate-spin rounded-full border-2 border-rose-300/40 border-t-rose-500" />
                      ) : (
                        <FiTrash2 className="h-3.5 w-3.5" />
                      )}
                    </button>
                  ) : null}
                </>
              )}
            </div>
          )
  }

  // Columns for the Import-history table (reorder/resize via AdvancedDataTable).
  const dhColumns = useMemo<ColumnDef<ImportBatchSummary, unknown>[]>(
    () => [
      {
        id: 'pick',
        header: () => (
          <input
            type="checkbox"
            aria-label="Tick every batch you can print or generate"
            checked={allPicked}
            disabled={pickable.length === 0}
            onChange={togglePickAll}
            onPointerDown={(e) => e.stopPropagation()}
            className="h-3.5 w-3.5 accent-[#1f150c] disabled:opacity-30"
          />
        ),
        enableSorting: false,
        enableResizing: false,
        size: 36,
        cell: ({ row }) => {
          const b = row.original
          const can = canPickBatch(b) && !viewTrash
          return (
            <input
              type="checkbox"
              aria-label={`Tick batch #${b.id}`}
              checked={pickedSet.has(b.id)}
              disabled={!can}
              onChange={() => togglePickBatch(b.id)}
              title={can ? 'Tick to print or generate this batch with the others' : 'Nothing to print or generate in this batch'}
              className="h-3.5 w-3.5 accent-[#1f150c] disabled:opacity-25"
            />
          )
        },
        meta: { headerLabel: 'Tick', exportValue: () => '' },
      },
      {
        id: 'labelBatch',
        header: 'Batch',
        enableSorting: false,
        size: 110,
        accessorFn: (b) => b.labelBatchId ?? '',
        cell: ({ row }) => {
          const b = row.original
          return b.labelBatchId != null ? (
            <span
              title="Batch number — allotted when the import was saved; every label of this import is generated under it, so its orders show together on the Orders page"
              className="inline-flex items-center gap-1 rounded-full bg-[#412d15] px-2 py-0.5 font-mono text-[10px] font-bold text-[#f4eede]"
            >
              <FiZap className="h-2.5 w-2.5" /> {b.labelBatchId}
            </span>
          ) : (
            <span className="text-[10.5px] text-[#b6a684]" title="A batch number is allotted when the import is saved">No batch yet</span>
          )
        },
        meta: { headerLabel: 'Batch', exportValue: (b: ImportBatchSummary) => b.labelBatchId == null ? '' : String(b.labelBatchId) },
      },
      {
        id: 'file',
        header: 'File',
        enableSorting: false,
        size: 230,
        accessorFn: (b) => b.fileName ?? '',
        cell: ({ row }) => {
          const b = row.original
          const apiSource = ['WMS', 'API'].includes((b.source || '').toUpperCase()) ? (b.source || '').toUpperCase() : null
          return (
            <span className="block min-w-0">
              <span className="flex items-center gap-1.5">
                <span className="inline-flex h-6 w-6 shrink-0 items-center justify-center rounded-md bg-sky-50 text-sky-600 ring-1 ring-sky-100" aria-hidden="true">
                  <FiFileText className="h-3.5 w-3.5" />
                </span>
                <span className="truncate text-[13.5px] font-semibold text-[#1f150c]" title={b.fileName || undefined}>
                  {b.fileName || 'Untitled import'}
                </span>
                {apiSource ? (
                  <span
                    title={apiSource === 'WMS' ? 'Pulled in by Fetch from WMS' : 'Sent in through the external API'}
                    className="inline-flex shrink-0 items-center rounded-full bg-emerald-50 px-1.5 py-0.5 text-[9px] font-bold uppercase tracking-[0.08em] text-emerald-700 ring-1 ring-emerald-200"
                  >
                    {apiSource}
                  </span>
                ) : null}
              </span>
              <span className="mt-0.5 flex flex-wrap items-center gap-x-1.5 gap-y-1 text-[11px] text-[#6b5c42]">
                <span className="font-mono">Import #{b.id}</span>
                <span aria-hidden="true">·</span>
                {b.createdBy ? (
                  <span className="inline-flex items-center gap-1" title={`Imported by ${b.createdBy}`}>
                    <span className="inline-flex h-4 w-4 items-center justify-center rounded-full bg-[#412d15] text-[8px] font-bold uppercase text-[#f4eede]" aria-hidden="true">
                      {b.createdBy.slice(0, 1)}
                    </span>
                    {b.createdBy}
                  </span>
                ) : <span>—</span>}
              </span>
            </span>
          )
        },
        meta: { headerLabel: 'File' },
      },
      {
        id: 'created',
        header: 'Date',
        enableSorting: false,
        size: 150,
        accessorFn: (b) => b.createdAt ?? '',
        cell: ({ row }) => {
          const iso = row.original.createdAt
          const d = iso ? new Date(iso) : null
          const valid = d && !Number.isNaN(d.getTime())
          return (
            <span className="flex flex-col gap-0.5" title={valid ? d!.toLocaleString() : undefined}>
              <span className="text-[12px] font-semibold text-[#3f3527]">
                {valid ? d!.toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' }) : '—'}
              </span>
              {valid ? (
                <span className="text-[11px] tabular-nums text-[#6b5c42]">
                  {d!.toLocaleTimeString('en-US', { hour: '2-digit', minute: '2-digit' })}
                </span>
              ) : null}
            </span>
          )
        },
        meta: { headerLabel: 'Date', exportValue: (b: ImportBatchSummary) => b.createdAt ?? '' },
      },
      {
        id: 'status',
        header: 'Status',
        enableSorting: false,
        size: 160,
        accessorFn: (b) => b.status ?? '',
        cell: ({ row }) => renderStatusCell(row.original),
        meta: { headerLabel: 'Status' },
      },
      {
        id: 'rows',
        header: 'Rows',
        enableSorting: false,
        size: 190,
        accessorFn: (b) => b.totalRows,
        cell: ({ row }) => renderRowsCell(row.original),
        meta: { headerLabel: 'Rows' },
      },
      {
        id: 'actions',
        header: 'Actions',
        enableSorting: false,
        size: 210,
        cell: ({ row }) => renderActionsCell(row.original),
        // Buttons have no CSV value — keep the column out of the export.
        meta: { headerLabel: 'Actions', exportable: false },
      },
    ],
    // Deps reference the memoized DERIVATIONS of batches / pickedBatches
    // (pickedSet / pickable / allPicked) instead of the raw arrays. Those
    // memos keep stable identity until their real inputs change, so
    // dhColumns no longer invalidates on every batch-list refresh — that
    // was rebuilding the entire column def + all 25×7 inline cell
    // renderers on every reloadQuiet() tick, blocking first paint.
    // Running-elapsed captions live in <RunningElapsed/>; handlers
    // (cancelGeneration/generate/handleDelete/handleRestore/setBilling)
    // are re-created every render but close over their own state
    // correctly, so we intentionally leave them out.
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [canWrite, viewTrash, trashBusyId, confirmGenId, billingSavingId, generatingId, genProgressById, cancellingId, cancelRequested, validatingId,
      // Tick-column state: memoized derivations, not the raw arrays.
      pickedSet, pickable, allPicked],
  )

  /** Expanded content for a batch row — the all-columns editable grid. */
  /** One order of the batch: print its label / invoice, void it — the Orders page's actions. */
  const printOrderLabel = async (orderNo: number) => {
    try { printPdfBlob(await orderService.getLabelPdf(orderNo, undefined, { main: true })) }
    catch (e) { notify.apiError(e, `Could not print label for #${orderNo}.`) }
  }
  const printOrderInvoice = async (orderNo: number) => {
    try { printPdfBlob(await orderService.getCommercialInvoicePdf(orderNo)) }
    catch (e) { notify.apiError(e, `Could not print commercial invoice for #${orderNo}.`) }
  }
  const voidOrder = async (batchId: number, orderNo: number, trackingNumber: string) => {
    const ok = await notify.confirm(
      `This cancels ${trackingNumber} at the carrier — it cannot be undone. Postage is refunded only if the label hasn't been scanned yet.`,
      { title: `Void order #${orderNo}?`, confirmLabel: 'Void the label', cancelLabel: 'Keep the label', danger: true },
    )
    if (!ok) return
    setVoidingOrderNo(orderNo)
    try {
      const data = (await orderService.voidLabel(orderNo)).data
      if (data?.voided || data?.status === 'ALREADY_VOIDED') notify.success(`Order ${orderNo}: ${data.message}`)
      else notify.error(`Void failed: ${data?.message ?? 'Unknown error.'}`)
      reloadRows(batchId)
      void reloadQuiet()
    } catch (e) {
      notify.apiError(e, 'Void call failed.')
    } finally {
      setVoidingOrderNo(null)
    }
  }

  /** The batch page's grid: the Orders page's columns and actions, plus the import's editable fields. */
  const batchList = Array.isArray(batchPageRows) ? batchPageRows : []
  const batchFilter = batchPageId != null ? gridFilter[batchPageId] ?? 'all' : 'all'
  const batchPicked = batchPageId != null ? pickedRows[batchPageId] ?? [] : []
  const rowNeedsAttention = (r: OrderImportRow) => (r.errors?.length ?? 0) > 0 || (r.generatedStatus ?? '').toUpperCase() === 'FAILED'
  const rowNotLabelled = (r: OrderImportRow) => (r.generatedStatus ?? '').toUpperCase() !== 'GENERATED'
  const rowIsLive = (r: OrderImportRow) => r.generatedOrderNo != null && (r.generatedStatus ?? '').toUpperCase() === 'GENERATED'
  const batchVisible = useMemo(() => {
    const filtered = batchFilter === 'failed' ? batchList.filter(rowNeedsAttention)
      : batchFilter === 'pending' ? batchList.filter(rowNotLabelled) : batchList
    const q = gridSearch.trim().toLowerCase()
    if (!q) return filtered
    return filtered.filter((r) => [r.orderRef, r.reference, r.recipientName, r.city, r.clientCode, r.generatedOrderNo, r.generatedTrackingNumber]
      .some((v) => v != null && String(v).toLowerCase().includes(q)))
  }, [batchList, batchFilter, gridSearch])
  /** Under "Needs attention", the imported fields that have errors come into view, to fix in the grid. */
  const batchErrorColumns = useMemo(() => {
    if (batchFilter !== 'failed') return undefined
    const keys = new Set<string>()
    for (const r of batchVisible) for (const k of Object.keys(bucketRowErrors(r.errors ?? []).byField)) keys.add(k)
    return DH_COLUMNS.filter((c) => keys.has(c.key)).map((c) => `f_${c.key}`)
  }, [batchFilter, batchVisible])
  const fixRow = fixing
    ? (Array.isArray(rowsById[fixing.batchId]) ? (rowsById[fixing.batchId] as OrderImportRow[]) : []).find((r) => r.rowNumber === fixing.rowNumber)
    : undefined
  /** A fix that came back clean closes the panel: the row can be labelled now. */
  const closeFixIfClean = (rows: OrderImportRow[] | undefined, rowNumber: number) => {
    const r = rows?.find((x) => x.rowNumber === rowNumber)
    if (!r || (r.errors?.length ?? 0) > 0) return
    setFixing(null)
    notify.success(`Row ${rowNumber} is ready — generate it now or with the batch.`)
  }
  const batchColumns = useMemo<ColumnDef<OrderImportRow, unknown>[]>(() => {
    const b = batchPageId != null ? batches.find((x) => x.id === batchPageId) : undefined
    if (!b) return []
    const list = batchList
    // An order is labelled as one shipment, so a clean line of an order whose
    // other line has errors can't be labelled on its own either.
    const orderKey = (r: OrderImportRow) => (r.orderRef ?? '').trim() || `__row_${r.rowNumber}`
    const brokenOrders = new Set(list.filter((r) => (r.errors?.length ?? 0) > 0).map(orderKey))
    const pickedSet = new Set(batchPicked)
    const visibleLive = batchVisible.filter(rowIsLive)
    const allVisiblePicked = visibleLive.length > 0 && visibleLive.every((r) => pickedSet.has(r.rowNumber))
    const rowIsWms = ['WMS', 'API'].includes((b.source || '').toUpperCase())
    const locked = viewTrash || (b.status || '').toUpperCase() === 'IN_PROGRESS'
    const togglePick = (rowNumber: number) => setPickedRows((m) => {
      const cur = new Set(m[b.id] ?? [])
      if (cur.has(rowNumber)) cur.delete(rowNumber); else cur.add(rowNumber)
      return { ...m, [b.id]: Array.from(cur) }
    })
    const togglePickVisible = () => setPickedRows((m) => {
      const cur = new Set(m[b.id] ?? [])
      for (const r of visibleLive) { if (allVisiblePicked) cur.delete(r.rowNumber); else cur.add(r.rowNumber) }
      return { ...m, [b.id]: Array.from(cur) }
    })
    const ICON = 'flex h-7 w-7 items-center justify-center rounded-lg border transition'
    const NEUTRAL = 'border-[#e6dcc7] bg-[#faf7f0] text-[#5a4526] hover:border-[#dccfb4] hover:bg-[#f2ebda]'
    const slot = (node: React.ReactNode) => <span className="flex h-7 w-7 shrink-0 items-center justify-center">{node}</span>

    const defs: ColumnDef<OrderImportRow, unknown>[] = [
      {
        id: 'pick',
        header: () => (
          <input type="checkbox" aria-label="Tick every live label shown" checked={allVisiblePicked} disabled={visibleLive.length === 0}
            onChange={togglePickVisible} onPointerDown={(e) => e.stopPropagation()} className="h-3.5 w-3.5 accent-[#1f150c] disabled:opacity-30" />
        ),
        enableSorting: false, enableResizing: false, size: 36,
        cell: ({ row }) => {
          const r = row.original
          return (
            <input type="checkbox" aria-label={`Tick row ${r.rowNumber}`} checked={pickedSet.has(r.rowNumber)} disabled={!rowIsLive(r)}
              onChange={() => togglePick(r.rowNumber)} title={rowIsLive(r) ? 'Tick to print, send or void this label' : 'Only rows with a live label can be ticked'}
              className="h-3.5 w-3.5 accent-[#1f150c] disabled:opacity-25" />
          )
        },
        meta: { headerLabel: 'Tick', exportValue: () => '', hideable: false },
      },
      {
        id: 'order', header: 'Order', size: 190, enableSorting: false,
        accessorFn: (r) => r.generatedOrderNo ?? r.rowNumber,
        cell: ({ row }) => {
          const r = row.original
          const gen = (r.generatedStatus ?? '').toUpperCase()
          const hasOrder = r.generatedOrderNo != null && (gen === 'GENERATED' || gen === 'VOIDED' || gen === 'QUEUED_USPS' || gen === 'FAILED')
          return (
            <span className="flex min-w-0 flex-col gap-0.5">
              <span className="inline-flex items-center gap-1.5">
                {hasOrder ? (
                  <a href={`/label/${r.generatedOrderNo}`} className={`font-mono text-[13px] font-bold underline-offset-2 hover:underline ${gen === 'VOIDED' ? 'text-slate-400 line-through' : 'text-[#1f150c]'}`}>
                    #{r.generatedOrderNo}
                  </a>
                ) : <span className="font-mono text-[12px] font-semibold text-[#6b5c42]">Row {r.rowNumber}</span>}
                <span title={rowIsWms ? 'API — imported via external partner / WMS' : 'Bulk — imported via CSV/Excel'}
                  className={`inline-flex h-4 w-4 items-center justify-center rounded-full text-[9px] font-bold ring-1 ${rowIsWms ? 'bg-emerald-50 text-emerald-700 ring-emerald-200' : 'bg-fuchsia-50 text-fuchsia-700 ring-fuchsia-200'}`}>
                  {rowIsWms ? 'A' : 'B'}
                </span>
                {rowIsWms ? <RowChannelChip recipientCompany={r.recipientCompany} /> : null}
                {savingCell === `${b.id}-${r.rowNumber}` ? <span className="inline-block h-2.5 w-2.5 animate-spin rounded-full border-2 border-[#cdbf9f] border-t-[#5a4526]" /> : null}
              </span>
              <span className="truncate font-mono text-[11px] text-[#6b5c42]" title={`Row ${r.rowNumber} of the file`}>
                {(r.clientCode || '—')} · {r.orderRef || '—'}
              </span>
            </span>
          )
        },
        meta: { headerLabel: 'Order', exportValue: (r: OrderImportRow) => r.generatedOrderNo ?? '' },
      },
      {
        id: 'reference', header: 'Ref #', size: 120, enableSorting: false,
        accessorFn: (r) => r.reference ?? '',
        cell: ({ row }) => <span className="block truncate font-mono text-[12px] text-[#5a4526]" title={row.original.reference || undefined}>{row.original.reference || <span className="text-[#b3a583]">—</span>}</span>,
        meta: { headerLabel: 'Ref #', exportValue: (r: OrderImportRow) => r.reference ?? '' },
      },
      {
        id: 'note', header: 'Note', size: 44, enableSorting: false,
        cell: ({ row }) => {
          const o = row.original.generatedOrderNo != null ? batchOrders[row.original.generatedOrderNo] : undefined
          return o ? <NoteCell orderNo={o.orderDetails.orderNo} note={o.orderDetails.note ?? ''} /> : <span className="text-[#b3a583]">—</span>
        },
        meta: { headerLabel: 'Note', exportValue: (r: OrderImportRow) => (r.generatedOrderNo != null ? batchOrders[r.generatedOrderNo]?.orderDetails.note : '') ?? '' },
      },
      {
        id: 'labelBatch', header: 'Batch', size: 80, enableSorting: false,
        accessorFn: (r) => r.batchId ?? '',
        cell: ({ row }) => <span className="block truncate font-mono text-[12px] text-[#5a4526]">{row.original.batchId ?? <span className="text-[#b3a583]">—</span>}</span>,
        meta: { headerLabel: 'Batch', exportValue: (r: OrderImportRow) => r.batchId ?? '' },
      },
      {
        id: 'dest', header: 'Dest', size: 170, enableSorting: false,
        accessorFn: (r) => `${r.city ?? ''} ${r.state ?? ''}`,
        cell: ({ row }) => {
          const r = row.original
          const sub = [r.state, r.postalCode].filter(Boolean).join(' · ')
          return (
            <span className="flex min-w-0 flex-col gap-0.5" title={[r.recipientName, r.addressLine1, r.city, r.state, r.postalCode, r.countryCode].filter(Boolean).join(' ') || 'No destination on file'}>
              <span className="truncate text-[13.5px] text-[#3f3527]">{r.city || r.countryCode || '—'}</span>
              {sub ? <span className="truncate text-[11.5px] tabular-nums text-[#6b5c42]">{sub}</span> : null}
            </span>
          )
        },
        meta: { headerLabel: 'Destination', exportValue: (r: OrderImportRow) => [r.city, r.state, r.postalCode, r.countryCode].filter(Boolean).join(' ') },
      },
      {
        id: 'status', header: 'Status', size: 190, enableSorting: false,
        accessorFn: (r) => r.generatedStatus ?? '',
        cell: ({ row }) => {
          const r = row.original
          const st = rowStatus(r, !brokenOrders.has(orderKey(r)))
          const failed = (r.generatedStatus ?? '').toUpperCase() === 'FAILED'
          const { byField, rowLevel } = bucketRowErrors(r.errors ?? [])
          const warnings = r.warnings ?? []
          const explain = (r.errors?.length ?? 0) > 0 || (failed && !!r.generatedMessage) || warnings.length > 0
          const o = r.generatedOrderNo != null ? batchOrders[r.generatedOrderNo] : undefined
          const when = o?.orderDetails.createdDate ?? null
          return (
            <span className="flex min-w-0 flex-col gap-0.5">
              <span className="inline-flex items-center gap-1.5">
                <span title={st.label} className="inline-flex items-center gap-1 rounded-full bg-slate-50 px-1.5 py-0.5 text-[10px] font-bold uppercase tracking-wide text-slate-700 ring-1 ring-slate-200">
                  <span className={`h-1.5 w-1.5 rounded-full ${st.dot}`} />{st.short}
                </span>
                {explain ? <RowIssuesIcon side="left" rowNumber={r.rowNumber} byField={byField} rowLevel={rowLevel} carrierMessage={failed ? r.generatedMessage : null} warnings={warnings} /> : null}
              </span>
              {when ? (
                <span className="truncate text-[11.5px] text-[#6b5c42]" title={when}>{new Date(when).toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' })}</span>
              ) : failed && r.generatedMessage ? (
                <span className="truncate text-[11.5px] text-rose-700" title={r.generatedMessage}>{r.generatedMessage}</span>
              ) : null}
            </span>
          )
        },
        meta: { headerLabel: 'Status', exportValue: (r: OrderImportRow) => r.generatedStatus ?? '' },
      },
      {
        id: 'track', header: 'Track', size: 150, enableSorting: false,
        accessorFn: (r) => r.generatedTrackingNumber ?? '',
        cell: ({ row }) => {
          const r = row.original
          const tn = r.generatedTrackingNumber
          if (!tn) return <span className="text-[#b3a583]">—</span>
          const o = r.generatedOrderNo != null ? batchOrders[r.generatedOrderNo] : undefined
          const url = o?.labelDetails.trackingUrl ?? r.trackingUrl ?? null
          const chip = (
            <span title={`Tracking ${tn}${url ? '\n(click to open carrier page)' : ''}`} className="inline-flex items-center rounded-full bg-sky-50 px-2 py-0.5 font-mono text-[11px] font-semibold text-sky-800 ring-1 ring-sky-200">
              …{tn.length > 4 ? tn.slice(-4) : tn}
            </span>
          )
          const ago = relativeTime(o?.labelDetails.generatedAt ?? null)
          return (
            <span className="flex min-w-0 flex-col gap-0.5">
              {url ? <a href={url} target="_blank" rel="noreferrer" className="inline-block hover:opacity-80">{chip}</a> : chip}
              <span className="truncate text-[11.5px] text-[#6b5c42]" title={[o?.labelDetails.generatedAt, r.lastPrintedAt ? `printed ${formatPrinted(r.lastPrintedAt, true)}` : null].filter(Boolean).join(' · ') || undefined}>
                {ago || '—'}
              </span>
            </span>
          )
        },
        meta: { headerLabel: 'Tracking', exportValue: (r: OrderImportRow) => r.generatedTrackingNumber ?? '' },
      },
      // Every imported field, editable in place until the row is labelled.
      ...DH_COLUMNS.map((c): ColumnDef<OrderImportRow, unknown> => ({
        id: `f_${c.key}`, header: fieldLabel(c), size: 120, enableSorting: false,
        accessorFn: (r) => (r as unknown as Record<string, unknown>)[c.key] ?? '',
        cell: ({ row }) => {
          const r = row.original
          const raw = (r as unknown as Record<string, unknown>)[c.key]
          const { byField } = bucketRowErrors(r.errors ?? [])
          const generated = (r.generatedStatus ?? '').toUpperCase() === 'GENERATED'
          // Every row shows the client's own ship via code (its resolved carrier
          // service in the tooltip), and an unmapped one can be mapped here — a
          // file import goes through the mapping just as a WMS pull does.
          const showShipVia = c.key === 'serviceType' && !!r.shipViaCode
          const unmapped = c.key === 'serviceType' ? (byField.serviceType ?? []).map((m) => m.match(UNMAPPED_SHIP_VIA)).find(Boolean) : null
          return (
            <div title={showShipVia ? (r.shipViaNote ?? undefined) : undefined}>
              <GridCell
                value={showShipVia ? String(r.shipViaCode) : raw == null ? '' : String(raw)}
                readOnly={generated || locked || (rowIsWms && c.key === 'orderRef')}
                bad={(byField[c.key]?.length ?? 0) > 0}
                errors={byField[c.key]}
                mono={c.mono}
                onCommit={(v) => void commitCell(b.id, r, c, v)}
              />
              {unmapped && canWrite && !generated && !viewTrash ? (
                <button type="button" onClick={() => setMapping({ code: unmapped[1], clientCode: (r.clientCode ?? '').trim().toUpperCase() || null, batchId: b.id })}
                  className="mt-0.5 block w-full truncate rounded border border-[#e3d9c4] bg-white px-1 py-0.5 text-[9px] font-semibold text-[#5a4526] hover:bg-[#faf7f0]">
                  Map {unmapped[1]}…
                </button>
              ) : null}
            </div>
          )
        },
        meta: { headerLabel: c.label ?? c.key, exportValue: (r: OrderImportRow) => String((r as unknown as Record<string, unknown>)[c.key] ?? '') },
      })),
      {
        id: 'actions', header: () => <span className="block text-right">Actions</span>, size: 300, enableSorting: false,
        cell: ({ row }) => {
          const r = row.original
          const gen = (r.generatedStatus ?? '').toUpperCase()
          const generated = gen === 'GENERATED'
          const failed = gen === 'FAILED'
          const ok = (r.errors?.length ?? 0) === 0
          const orderReady = !brokenOrders.has(orderKey(r))
          const orderNo = r.generatedOrderNo ?? null
          const tn = r.generatedTrackingNumber ?? null
          const isIntl = hasCommercialInvoice(r)
          const rowKey = `${b.id}-${r.rowNumber}`
          const rowBusy = genRowKey === rowKey
          return (
            <span className="flex w-full items-center justify-end gap-1">
              {slot(tn && orderNo != null ? (
                <button type="button" onClick={() => setTrackingOrderNo(orderNo)} title={`Live tracking for ${tn}`} aria-label={`Track order ${orderNo}`} className={`${ICON} ${NEUTRAL}`}>
                  <FiTruck className="h-3.5 w-3.5" />
                </button>
              ) : null)}
              {slot(tn && orderNo != null && generated && canWrite ? (
                <button type="button" disabled={voidingOrderNo === orderNo || locked} onClick={() => void voidOrder(b.id, orderNo, tn)} title={`Void ${tn} at the carrier`} aria-label={`Void order ${orderNo}`}
                  className={`${ICON} border-rose-200 bg-rose-50 text-rose-700 hover:border-rose-300 hover:bg-rose-100 disabled:opacity-40`}>
                  {voidingOrderNo === orderNo ? <span className="inline-block h-3.5 w-3.5 animate-spin rounded-full border-2 border-rose-300 border-t-rose-700" /> : <FiXCircle className="h-3.5 w-3.5" />}
                </button>
              ) : null)}
              {slot(orderNo != null ? (
                <button type="button" onClick={() => setDetailsOrderNo(orderNo)} title="Order details" aria-label={`Details for order ${orderNo}`} className={`${ICON} ${NEUTRAL}`}>
                  <FiInfo className="h-3.5 w-3.5" />
                </button>
              ) : null)}
              {slot(generated && orderNo != null ? (
                <button type="button" onClick={() => void printOrderLabel(orderNo)} title="Print the shipping label" aria-label={`Print label for order ${orderNo}`} className={`${ICON} ${NEUTRAL}`}>
                  <FiPrinter className="h-3.5 w-3.5" />
                </button>
              ) : null)}
              {slot(generated && orderNo != null && isIntl ? (
                <button type="button" onClick={() => void printOrderInvoice(orderNo)} title="Print the commercial invoice" aria-label={`Print commercial invoice for order ${orderNo}`} className={`${ICON} ${NEUTRAL}`}>
                  <FiFileText className="h-3.5 w-3.5" />
                </button>
              ) : null)}
              <span className="ml-1 flex min-w-[116px] shrink-0 justify-end">
                {generated && orderNo != null ? (
                  <button type="button" onClick={() => navigate(`/label/${orderNo}`)}
                    className="inline-flex items-center gap-1.5 rounded-lg bg-[#1f150c] px-3 py-1.5 text-[12px] font-semibold text-[#f4eede] transition hover:bg-[#412d15]">
                    <FiEye className="h-3 w-3" /> View Label
                  </button>
                ) : gen === 'VOIDED' ? (
                  <span className="text-[11px] text-slate-500" title="Voided with the carrier">Voided</span>
                ) : !canWrite ? (
                  <span className="text-[11px] text-[#b6a684]">Read-only</span>
                ) : orderReady && (ok || failed) ? (
                  <button type="button" onClick={() => void generateRow(b.id, r.rowNumber)} disabled={rowBusy || locked}
                    title={failed ? 'Retry — re-sends this same order to the carrier (no duplicate order is created)' : 'Generate a carrier label for this row'}
                    className={`inline-flex items-center gap-1 whitespace-nowrap rounded-lg border px-3 py-1.5 text-[12px] font-semibold transition disabled:cursor-not-allowed disabled:opacity-50 ${
                      failed ? 'border-rose-200 bg-white text-rose-700 hover:border-rose-300 hover:bg-rose-50' : 'border-[#1f150c] bg-[#1f150c] text-[#f4eede] hover:bg-[#412d15]'}`}>
                    {rowBusy ? <span className={`inline-block h-3 w-3 animate-spin rounded-full border-2 ${failed ? 'border-rose-100 border-t-rose-600' : 'border-[#f4eede]/40 border-t-[#f4eede]'}`} />
                      : failed ? <FiRotateCcw className="h-3 w-3" /> : <FiZap className="h-3 w-3" />}
                    {rowBusy ? 'Generating…' : failed ? 'Retry' : 'Generate'}
                  </button>
                ) : locked ? (
                  <span className="text-[11px] text-[#b6a684]">Fix errors first</span>
                ) : (
                  // A clean line of a broken order opens the line that needs the fix.
                  <button type="button"
                    onClick={() => setFixing({ batchId: b.id, rowNumber: ok ? (list.find((x) => orderKey(x) === orderKey(r) && (x.errors?.length ?? 0) > 0)?.rowNumber ?? r.rowNumber) : r.rowNumber })}
                    title={ok ? `Another line of order ${r.orderRef ?? ''} needs fixes — the order is labelled as one shipment` : 'Edit this row and re-check it'}
                    aria-label={`Fix row ${r.rowNumber}`}
                    className="inline-flex items-center gap-1 whitespace-nowrap rounded-lg border border-rose-200 bg-white px-3 py-1.5 text-[12px] font-semibold text-rose-700 transition hover:border-rose-300 hover:bg-rose-50">
                    <FiEdit3 className="h-3 w-3" /> Fix
                  </button>
                )}
              </span>
            </span>
          )
        },
        meta: { headerLabel: 'Actions', hideable: false, exportable: false },
      },
    ]
    return defs
    // Cells close over the picked rows, busy states and the batch's orders; the
    // handlers are stable enough (they read state through setters and refs).
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [batchPageId, batches, batchList, batchVisible, batchPicked, batchOrders, savingCell, genRowKey, voidingOrderNo, viewTrash, canWrite])

  const renderBatchExpanded = (b: ImportBatchSummary) => {
    const rows = rowsById[b.id]
    const list = Array.isArray(rows) ? rows : []
    const filter = gridFilter[b.id] ?? 'all'
    const picked = pickedRows[b.id] ?? []
    return (
      <div className="px-3 py-2.5">
        {rows === 'loading' || rows === undefined ? (
          <p className="py-4 text-center text-[12px] text-[#6b5c42]">Loading rows…</p>
        ) : rows.length === 0 ? (
          <p className="px-4 py-4 text-center text-[12px] text-[#6b5c42]">
            {['WMS', 'API'].includes((b.source || '').toUpperCase())
              ? "This fetch's rows are no longer stored — it was pulled before rows were kept. Fetch from WMS again for a fresh batch."
              : 'No rows stored for this import.'}
          </p>
        ) : (
          <>
            {/* PR-G4 — MPS progress cards for USPS rows in this batch
                (audit U3). Row-schema doesn't carry packageCount, so we
                render for every USPS row that generated an order and
                let MpsProgressCard's own 404-fallback hide non-MPS
                orders (per PR-F2's design). Deduped by orderNo so a
                7-line MPS parent doesn't fire 7 identical cards. */}
            {(() => {
              const seen = new Set<number>()
              const parents: number[] = []
              for (const r of list) {
                if (r.generatedOrderNo == null) continue
                if (normalizeCarrierCode(r.carrierCode) !== 'usps') continue
                if (seen.has(r.generatedOrderNo)) continue
                seen.add(r.generatedOrderNo)
                parents.push(r.generatedOrderNo)
              }
              if (parents.length === 0) return null
              return (
                <div
                  className="mb-3 flex flex-col gap-2"
                  data-testid="usps-mps-progress-section"
                >
                  {parents.map((orderNo) => (
                    <MpsProgressCard key={orderNo} orderNo={orderNo} />
                  ))}
                </div>
              )
            })()}
            {['WMS', 'API'].includes((b.source || '').toUpperCase()) ? (
              // The WMS sends the client's own ship via codes; these are the ones
              // that resolve to a carrier service.
              <ShipViaCodesPanel
                clientCode={(() => {
                  const codes = Array.from(new Set(rows.map((r) => (r.clientCode ?? '').trim().toUpperCase()).filter(Boolean)))
                  return codes.length === 1 ? codes[0] : null
                })()}
                canEdit={canWrite}
                onOpenMapping={() => navigate(settingsPaths.shippingServiceMapping)}
                reloadKey={codesTick}
              />
            ) : null}
            <AdvancedDataTable<OrderImportRow>
              tableKey="bulk-batch-rows-v2"
              columns={batchColumns}
              data={batchVisible}
              getRowId={(r) => String(r.rowNumber)}
              search={{ value: gridSearch, onChange: setGridSearch, placeholder: 'Search order #, ref, recipient, city, tracking…' }}
              filterToggle={
                <div className="inline-flex shrink-0 overflow-hidden rounded-lg border border-[#e3d9c4]" role="group" aria-label="Show rows">
                  {([
                    ['all', `All ${list.length}`],
                    ['failed', `Needs attention ${list.filter(rowNeedsAttention).length}`],
                    ['pending', `Not labelled ${list.filter(rowNotLabelled).length}`],
                  ] as const).map(([k, label]) => (
                    <button key={k} type="button" aria-pressed={filter === k} onClick={() => setGridFilter((m) => ({ ...m, [b.id]: k }))}
                      className={`px-2.5 py-1.5 text-[11px] font-semibold transition ${filter === k ? 'bg-[#1f150c] text-[#f4eede]' : 'bg-white text-[#5a4526] hover:bg-[#faf7f0]'}`}>
                      {label}
                    </button>
                  ))}
                </div>
              }
              // Exactly the Orders page's columns; every imported field is one Columns click away (to edit it).
              initialHiddenColumns={DH_COLUMNS.map((c) => `f_${c.key}`)}
              forceVisibleColumns={batchErrorColumns}
              csvFilename={`batch-${b.id}-rows`}
              maxBodyHeight="calc(100vh - 320px)"
              emptyState={<p className="py-6 text-center text-[12px] text-[#6b5c42]">No rows match.</p>}
              caption={
                <p className="text-[10.5px] text-[#b6a684]">
                  {viewTrash
                    ? 'Read-only in Trash — restore this import to edit rows or generate labels.'
                    : (b.status || '').toUpperCase() === 'IN_PROGRESS'
                      ? 'Locked while labels are generating — editing opens again when the run finishes.'
                      : !canWrite
                        ? 'Read-only view.'
                        : 'Click a field cell to edit; it saves and re-validates on blur. Columns shows every imported field.'}
                </p>
              }
            />
            {picked.length > 0 ? (
              // Sticky in the content column, as on the Orders page: it appears once rows are ticked.
              <div className="pointer-events-none sticky bottom-5 z-30 mt-4 flex justify-center [&>*]:pointer-events-auto">
                <BatchLabelBar
                  floating
                  batchId={b.id}
                  rows={list}
                  picked={picked}
                  onPickAllLive={() => setPickedRows((m) => ({ ...m, [b.id]: list.filter(rowIsLive).map((r) => r.rowNumber) }))}
                  onClearPick={() => setPickedRows((m) => ({ ...m, [b.id]: [] }))}
                  onChanged={() => { reloadRows(b.id); void reloadQuiet() }}
                  onPrinted={() => reloadRows(b.id)}
                  canWrite={canWrite}
                  canManagePrinters={canPullWms}
                  locked={viewTrash || (b.status || '').toUpperCase() === 'IN_PROGRESS'}
                  onOpenPrinterSettings={() => navigate(settingsPaths.printers)}
                />
              </div>
            ) : null}
          </>
        )}
      </div>
    )
  }

  const mappingDialog = mapping ? (
    <AddShipViaMappingDialog
      code={mapping.code}
      clientCode={mapping.clientCode}
      onClose={() => setMapping(null)}
      onSaved={() => {
        const batchId = mapping.batchId
        setMapping(null)
        setCodesTick((t) => t + 1)
        // The server re-validates the batch, so rows that failed on this code clear —
        // and a Fix panel open on one of them closes if that was its last problem.
        const at = fixing
        void validateAll(batchId).then((rows) => { if (at?.batchId === batchId) closeFixIfClean(rows, at.rowNumber) })
      }}
    />
  ) : null

  const labelModal = (
    <>
      {fixing && fixRow ? (
        <FixRowPanel
          row={fixRow}
          saving={fixSaving}
          canMap={canWrite}
          onSave={(edited) => {
            setFixSaving(true)
            const at = fixing
            void saveRow(at.batchId, edited)
              .then((rows) => closeFixIfClean(rows, at.rowNumber))
              .finally(() => setFixSaving(false))
          }}
          onMap={(code) => setMapping({ code, clientCode: (fixRow.clientCode ?? '').trim().toUpperCase() || null, batchId: fixing.batchId })}
          onClose={() => setFixing(null)}
        />
      ) : null}
      {labelModalOrderNo ? <LabelPreviewModal orderNo={labelModalOrderNo} onClose={() => setLabelModalOrderNo(null)} /> : null}
      {detailsOrderNo != null ? <Suspense fallback={null}><OrderDetailsModal orderNo={detailsOrderNo} onClose={() => setDetailsOrderNo(null)} /></Suspense> : null}
      {trackingOrderNo != null ? <Suspense fallback={null}><TrackingTimelineModal orderNo={trackingOrderNo} onClose={() => setTrackingOrderNo(null)} /></Suspense> : null}
    </>
  )

  // ── /bulk/batches/:id — one batch on its own page ───────────────────────
  if (batchPageId != null) {
    const b = batches.find((x) => x.id === batchPageId)
    const src = (b?.source || '').toUpperCase()
    const back = b?.deletedAt
      ? { to: bulkPaths.trash, label: 'Trash' }
      : src === 'WMS' || src === 'API'
        ? { to: bulkPaths.api, label: 'API batches' }
        : { to: bulkPaths.imports, label: 'Import history' }
    return (
      <div className="space-y-3 pb-8">
        {mappingDialog}
        {loading && !b ? (
          <p className="rounded-2xl border border-[#e3d9c4] bg-white px-5 py-14 text-center text-sm text-[#6b5c42]">Loading…</p>
        ) : !b ? (
          <div className="rounded-2xl border border-[#e3d9c4] bg-white px-5 py-12 text-center">
            <button type="button" onClick={() => navigate(back.to)} className="mb-3 inline-flex items-center gap-1 text-[12px] font-semibold text-[#5a4526] hover:underline">
              <FiArrowLeft className="h-3.5 w-3.5" /> Bulk Mailer · {back.label}
            </button>
            <p className="text-sm font-semibold text-[#1f150c]">Batch #{batchPageId} isn't here.</p>
            <p className="mt-1 text-[12.5px] text-[#6b5c42]">It may have been deleted, or it belongs to a client you can't see.</p>
            <div className="mt-4 flex justify-center gap-2">
              <button type="button" onClick={() => navigate(bulkPaths.imports)} className="rounded-xl border border-[#e3d9c4] bg-white px-3 py-1.5 text-[12.5px] font-semibold text-[#5a4526] hover:bg-[#faf7f0]">Import history</button>
              <button type="button" onClick={() => navigate(bulkPaths.trash)} className="rounded-xl border border-[#e3d9c4] bg-white px-3 py-1.5 text-[12.5px] font-semibold text-[#5a4526] hover:bg-[#faf7f0]">Trash</button>
            </div>
          </div>
        ) : (
          <>
            {/* One bar: the way back, the batch, its status and counts, and its actions. */}
            <section data-testid="batch-page-header" className="flex flex-wrap items-center gap-x-4 gap-y-2 rounded-2xl border border-[#e3d9c4] bg-white px-3 py-2.5 shadow-sm">
              <button
                type="button"
                onClick={() => navigate(back.to)}
                aria-label={`Bulk Mailer · ${back.label}`}
                title={`Back to ${back.label}`}
                className="inline-flex h-8 w-8 shrink-0 items-center justify-center rounded-lg border border-[#e3d9c4] bg-white text-[#5a4526] transition hover:border-[#cdbf9f] hover:bg-[#faf7f0]"
              >
                <FiArrowLeft className="h-3.5 w-3.5" />
              </button>
              <div className="min-w-0 flex-1">
                <h1 className="flex min-w-0 items-baseline gap-2 text-[15px] font-semibold text-[#1f150c]">
                  <span className="truncate" title={b.fileName || undefined}>{b.fileName || 'Untitled import'}</span>
                  <span className="shrink-0 rounded-md bg-[#f4eede] px-1.5 py-0.5 font-mono text-[10px] font-bold text-[#412d15]">Batch #{b.id}</span>
                </h1>
                <p className="mt-0.5 truncate text-[11px] text-[#6b5c42]">
                  {src === 'WMS' || src === 'API' ? src : 'File import'}{b.deletedAt ? ' · In Trash' : ''}
                  {b.createdAt ? ` · ${new Date(b.createdAt).toLocaleString()}` : ''}
                  {b.createdBy ? ` · by ${b.createdBy}` : ''}
                  {b.labelBatchId ? ` · label batch ${b.labelBatchId}` : ''}
                </p>
              </div>
              <div className="flex flex-wrap items-center gap-4">
                {renderStatusCell(b)}
                <div className="min-w-[120px]">{renderRowsCell(b)}</div>
              </div>
              {renderActionsCell(b)}
            </section>
            <section className="rounded-2xl border border-[#e3d9c4] bg-white shadow-sm">
              {renderBatchExpanded(b)}
            </section>
          </>
        )}
        {labelModal}
      </div>
    )
  }

  const sendBatchDialog = sendBatch ? (
    <SendToPrinterDialog
      orderNumbers={sendBatch}
      canManagePrinters={canPullWms}
      onClose={() => { setSendBatch(null); void reloadQuiet() }}
      onOpenSettings={() => { setSendBatch(null); navigate(settingsPaths.printers) }}
    />
  ) : null

  /** This tab's own buttons, in the table's toolbar: refresh, then the one action the tab is for. */
  const listActions = dhView === 'imports' ? (
    <>
      <button
        type="button"
        onClick={() => void load()}
        aria-label="Refresh"
        title="Refresh the list"
        className="inline-flex h-[30px] w-[30px] items-center justify-center rounded-lg border border-[#e3d9c4] bg-white text-[#5a4526] transition hover:border-[#cdbf9f] hover:bg-[#faf7f0]"
      >
        <FiRefreshCw className={`h-3.5 w-3.5 ${refreshing ? 'animate-spin' : ''}`} />
      </button>
      {viewTrash && (summary?.total ?? batches.length) > 0 ? (
        confirmEmpty ? (
          <div ref={emptyConfirmRef} className="inline-flex items-center gap-1.5">
            <button
              type="button"
              onClick={() => void handleEmptyTrash()}
              disabled={emptying}
              className="inline-flex items-center gap-1.5 rounded-lg bg-rose-600 px-2.5 py-1.5 text-[12px] font-semibold text-white shadow-sm transition hover:bg-rose-700 disabled:cursor-not-allowed disabled:opacity-60"
            >
              {emptying ? (
                <span className="inline-block h-3.5 w-3.5 animate-spin rounded-full border-2 border-white/40 border-t-white" />
              ) : (
                <FiTrash2 className="h-3.5 w-3.5" />
              )}
              Delete {summary?.total ?? batches.length} forever
            </button>
            <button
              type="button"
              onClick={() => setConfirmEmpty(false)}
              disabled={emptying}
              className="inline-flex items-center rounded-lg border border-[#e3d9c4] bg-white px-2.5 py-1.5 text-[12px] font-semibold text-[#5a4526] transition hover:bg-[#faf7f0]"
            >
              Cancel
            </button>
          </div>
        ) : (
          <button
            type="button"
            onClick={() => setConfirmEmpty(true)}
            title="Permanently delete everything in Trash"
            className="inline-flex items-center gap-1.5 rounded-lg border border-rose-200 bg-white px-2.5 py-1.5 text-[12px] font-semibold text-rose-600 transition hover:border-rose-300 hover:bg-rose-50"
          >
            <FiTrash2 className="h-3.5 w-3.5" />
            Empty Trash
          </button>
        )
      ) : null}
      {canPullWms && isApiTab ? (
        <button
          type="button"
          onClick={() => void fetchFromWms()}
          disabled={fetchingWms}
          title="Pull the WMS's current pending shipments in as a new batch"
          className="inline-flex items-center gap-1.5 rounded-lg bg-[#1f150c] px-3 py-1.5 text-[12px] font-semibold text-[#f4eede] shadow-sm transition hover:bg-[#412d15] disabled:opacity-60"
        >
          {fetchingWms
            ? <span className="inline-block h-3.5 w-3.5 animate-spin rounded-full border-2 border-[#f4eede]/40 border-t-[#f4eede]" />
            : <FiDownloadCloud className="h-3.5 w-3.5" />}
          {fetchingWms ? 'Fetching…' : 'Fetch from WMS'}
        </button>
      ) : null}
      {canWrite && bulkTab === 'imports' ? (
        <button
          type="button"
          onClick={() => navigate(bulkPaths.importFile)}
          className="inline-flex items-center gap-1.5 rounded-lg bg-[#1f150c] px-3 py-1.5 text-[12px] font-semibold text-[#f4eede] shadow-sm transition hover:bg-[#412d15]"
        >
          <FiUpload className="h-3.5 w-3.5" />
          Import CSV / Excel
        </button>
      ) : null}
    </>
  ) : null

  /** Every filter behind one button, in the table's toolbar. */
  const filterMenu = (
    <DataHistoryFilterToolbar
      statusFilter={filters.statusFilter}
      setStatusFilter={filters.setStatusFilter}
      statusCounts={summary?.statusCounts ?? {}}
      clearFilters={filters.clearFilters}
      dateFrom={filters.dateFrom}
      setDateFrom={filters.setDateFrom}
      dateTo={filters.dateTo}
      setDateTo={filters.setDateTo}
      dateFilterActive={filters.dateFilterActive}
      sortKey={filters.sortKey}
      setSortKey={filters.setSortKey}
      sortDir={filters.sortDir}
      setSortDir={filters.setSortDir}
      createdBy={filters.createdBy}
      setCreatedBy={filters.setCreatedBy}
      creators={summary?.creators ?? []}
      batchPresence={filters.batchPresence}
      setBatchPresence={filters.setBatchPresence}
      minSaved={filters.minSaved}
      setMinSaved={filters.setMinSaved}
      filteredCount={pageInfo.total}
      totalCount={summary?.total ?? pageInfo.total}
    />
  )

  return (
    <div className="space-y-3 pb-8">
      {mappingDialog}
      {sendBatchDialog}
      {/* One line: the title, the tabs, the USPS queue pill, Trash (an icon), back to Orders. */}
      <div className="flex flex-wrap items-center gap-x-3 gap-y-2 px-1 pt-1">
        <h2
          className="flex items-center gap-2 text-[17px] font-semibold tracking-tight text-[#1f150c]"
          title="Import orders in bulk, fix what needs it, and buy their labels — from a file or from the API."
        >
          <span className="inline-flex h-7 w-7 shrink-0 items-center justify-center rounded-lg bg-[#1f150c] text-[#f4eede] shadow-sm" aria-hidden="true">
            <FiUpload className="h-3.5 w-3.5" />
          </span>
          Bulk Mailer
        </h2>
        {/* Bulk Mailer tabs — each one is its own address (/bulk/:tab). */}
        <BulkTabBar
          active={bulkTab}
          onSelect={(t) => navigate(`/bulk/${t}`)}
          trailing={
            /* PR-G4 — USPS queue depth pill (audit U2). Self-hides when the queue is
               empty; non-admin users see nothing because the metrics endpoint 403s. */
            <div data-testid="usps-queue-badge-slot">
              <BulkLabelQueueBadge />
            </div>
          }
        />
        <button
          type="button"
          onClick={() => navigate('/orders')}
          className="inline-flex items-center gap-1.5 rounded-lg border border-[#e3d9c4] bg-white px-2.5 py-1.5 text-[12px] font-semibold text-[#5a4526] transition hover:border-[#cdbf9f] hover:bg-[#faf7f0]"
        >
          <FiArrowLeft className="h-3.5 w-3.5" />
          Orders
        </button>
      </div>

      {/* The panel's height glides between tabs (and from skeleton to list) so
          nothing below it jumps. */}
      <AnimatedHeight holdKey={bulkTab} ready={tabReady}>
      <div
        key={bulkTab}
        role="tabpanel"
        aria-label={BULK_TABS.find((t) => t.key === bulkTab)?.label}
        className={`space-y-3 ${tabMotion.dir > 0 ? 'bulk-tab-in-right' : 'bulk-tab-in-left'}`}
      >
      {dhView === 'docs' ? (
        <Suspense fallback={<BatchListSkeleton />}>
          <OrderDocumentsTable onLoaded={() => setDocsLoadedFor(tabMotion.tab)} />
        </Suspense>
      ) : loadedView !== viewKey ? (
        <BatchListSkeleton />
      ) : (
      <div className="bulk-fade-in space-y-3">
      <section
        aria-busy={refreshing}
        className={`rounded-2xl border border-slate-200 bg-white p-3 shadow-sm transition-opacity duration-200 ${refreshing && !loading ? 'opacity-60' : ''}`}
      >
        {(
          <AdvancedDataTable<ImportBatchSummary>
            tableKey={viewTrash ? 'order-intake-imports-trash-v8' : isApiTab ? 'bulk-api-batches-v6' : 'order-intake-imports-v8'}
            columns={dhColumns}
            data={batches}
            search={{ value: filters.search, onChange: filters.setSearch, placeholder: 'Search file name, batch #, or user…' }}
            filterToggle={filterMenu}
            filterPanel={
              <BulkFilterChips
                statusFilter={filters.statusFilter}
                setStatusFilter={filters.setStatusFilter}
                          dateFrom={filters.dateFrom}
                setDateFrom={filters.setDateFrom}
                dateTo={filters.dateTo}
                setDateTo={filters.setDateTo}
                createdBy={filters.createdBy}
                setCreatedBy={filters.setCreatedBy}
                batchPresence={filters.batchPresence}
                setBatchPresence={filters.setBatchPresence}
                minSaved={filters.minSaved}
                setMinSaved={filters.setMinSaved}
                clearFilters={filters.clearFilters}
              />
            }
            toolbarActions={listActions}
            manualPagination
            manualSorting
            pageIndex={pageIndex}
            pageSize={pageSize}
            pageCount={pageInfo.pages}
            onPaginationChange={({ pageIndex: i, pageSize: n }) => { setPageIndex(n !== pageSize ? 0 : i); setPageSize(n) }}
            onRowClick={(b) => navigate(bulkBatchPath(b.id))}
            getRowId={(b) => String(b.id)}
            caption={viewTrash ? 'Trash — deleted batches · click a batch to open it'
              : isApiTab ? 'Batches from the WMS and the API · each fetch is one batch · click a batch to open it'
                : 'Saved imports · click a batch to open it'}
            emptyState={
              (summary?.total ?? 0) === 0 && !filters.anyFilterActive ? (
                <p className="px-5 py-10 text-center text-sm text-[#6b5c42]">
                  {viewTrash
                    ? 'Trash is empty — no deleted imports.'
                    : isApiTab
                      ? (canPullWms ? 'No API batches yet. Use Fetch from WMS to pull the pending shipments in.' : 'No API batches yet. An admin can use Fetch from WMS to pull the pending shipments in.')
                      : 'No saved imports yet. Use Import CSV / Excel to add your first file — saved orders show up here.'}
                </p>
              ) : (
                <div className="px-5 py-10 text-center">
                  <p className="text-sm text-[#6b5c42]">No imports match your filters.</p>
                  <button
                    type="button"
                    onClick={clearFilters}
                    className="mt-3 inline-flex items-center gap-1 rounded-xl border border-[#e3d9c4] bg-white px-3 py-1.5 text-[12px] font-semibold text-[#5a4526] transition hover:bg-[#faf7f0]"
                  >
                    <FiX className="h-3.5 w-3.5" /> Clear filters
                  </button>
                </div>
              )
            }
          />
        )}
      </section>
      {pickedBatches.length > 0 && batchPageId == null && !viewTrash ? (
        // Pinned to the bottom of the window (the list is often shorter than the
        // screen) — portalled, since the tab's slide-in transform would pin a fixed
        // bar to the tab instead. The spacer keeps the last batch from sitting under it.
        <>
        <div aria-hidden="true" className="h-20" />
        {createPortal(<div className="pointer-events-none fixed inset-x-0 bottom-5 z-30 flex justify-center px-4 [&>*]:pointer-events-auto">
          <div data-testid="batch-pick-bar" className="flex max-w-full flex-wrap items-center justify-center gap-2 rounded-2xl border border-[#e3d9c4] bg-white px-3 py-2.5 shadow-[0_18px_50px_rgba(31,21,12,0.22)] sm:gap-3 sm:px-4">
            <span className="flex flex-wrap items-center gap-2 text-[11.5px] text-[#6b5c42]">
              <span className="text-[13px] font-semibold tabular-nums text-[#1f150c]">{pickedBatches.length} batch{pickedBatches.length === 1 ? '' : 'es'} selected</span>
              <button type="button" onClick={() => setPickedBatches([])} className="inline-flex items-center gap-0.5 font-semibold text-[#5a4526] hover:underline">
                <FiX className="h-3 w-3" /> Clear
              </button>
            </span>
            {/* Always there, as on the batch page; greyed out until a ticked batch has a label. */}
            <BatchPrintMenu
              scope={pickedPrintable.length === 1 ? `batch #${pickedPrintable[0].id}` : `${pickedPrintable.length} batches`}
              buttonLabel="Print"
              busy={batchPrintBusy === -1}
              disabledReason={pickedPrintable.length === 0 ? 'No labels yet in the ticked batches — generate them first' : undefined}
              loadRows={async () => (await Promise.all(pickedPrintable.map(rowsOfBatch))).flat()}
              onPrint={(orders, docType) => void printBatchDocs(-1,
                pickedPrintable.length === 1 ? `batch #${pickedPrintable[0].id}` : `${pickedPrintable.length} batches`, orders, docType)}
              onSend={(orders) => setSendBatch(orders.slice(0, 500))}
            />
            <label className={`${SHOW_BILLS_TO ? 'inline-flex' : 'hidden'} items-center gap-1.5 rounded-xl border border-[#e3d9c4] bg-white px-2.5 py-1.5 text-[11px] font-semibold text-[#5a4526]`}>
              <FiHome className="h-3.5 w-3.5" />
              <span className="text-[9.5px] uppercase tracking-[0.08em] text-[#b6a684]">Bills to</span>
              <select
                value={pickedBilling === 'MIXED' ? '' : pickedBilling}
                onChange={(e) => { if (e.target.value) void setBillingForPicked(e.target.value as 'AUTO' | 'PLATFORM') }}
                disabled={!!bulkRunning || billingSavingId != null}
                className="bg-transparent text-[12px] font-semibold text-[#1f150c] outline-none"
              >
                {pickedBilling === 'MIXED' ? <option value="">Mixed — pick one</option> : null}
                <option value="AUTO">Client account</option>
                <option value="PLATFORM">Platform account</option>
              </select>
            </label>
            {pickedLive.length > 0 ? (
              <button
                type="button"
                onClick={() => void generatePicked()}
                disabled={!!bulkRunning}
                className="inline-flex items-center gap-1.5 rounded-xl bg-[#1f150c] px-3.5 py-2 text-[12.5px] font-semibold text-[#f4eede] shadow-sm transition hover:bg-[#412d15] disabled:cursor-not-allowed disabled:bg-[#dcd4c4]"
              >
                {bulkRunning
                  ? <span className="inline-block h-3.5 w-3.5 animate-spin rounded-full border-2 border-[#f4eede]/40 border-t-[#f4eede]" />
                  : <FiZap className="h-3.5 w-3.5" />}
                {bulkRunning ? `Generating ${bulkRunning.done + 1} of ${bulkRunning.total}…` : `Generate labels (${pickedLive.length})`}
              </button>
            ) : null}
          </div>
        </div>, document.body)}
        </>
      ) : null}

      {labelModal}
      </div>
      )}
      </div>
      </AnimatedHeight>
    </div>
  )
}

export type BulkTab = 'imports' | 'api' | 'documents' | 'trash'

/** The "Bills to" control (client vs platform account) — hidden for now at the
 *  client's request; billing stays as each batch has it (client account by
 *  default). Flip to true to bring it back on the batch page and the bar. */
const SHOW_BILLS_TO = false

/** Bulk Mailer tabs, in order. Import history is the landing tab. */
const BULK_TABS: { key: BulkTab; label: string; hint: string; dot: string }[] = [
  { key: 'imports', label: 'Import history', hint: 'Saved batches', dot: 'bg-[#412d15]' },
  { key: 'api', label: 'API batches', hint: 'WMS · API', dot: 'bg-violet-500' },
  { key: 'documents', label: 'Documents', hint: 'Label · invoice · statement', dot: 'bg-sky-500' },
  { key: 'trash', label: 'Trash', hint: 'Deleted batches', dot: 'bg-rose-400' },
]

/** A row's serviceType error for a ship via code with no carrier service mapped. */
const UNMAPPED_SHIP_VIA = /serviceType '([^']+)' is (?:not mapped|mapped, but not)/

/**
 * The Bulk Mailer tabs. A single highlight slides to the chosen tab (moved
 * with a transform — no re-layout), and the arrow / Home / End keys move
 * between tabs as a tablist should.
 */
export /** Set by an arrow-key move; the next tab bar to mount focuses its active tab. */
let focusTabOnMount = false

export function BulkTabBar({ active, onSelect, trailing }: { active: BulkTab; onSelect: (tab: BulkTab) => void; trailing?: React.ReactNode }) {
  const listRef = useRef<HTMLDivElement>(null)
  const pillRef = useRef<HTMLSpanElement>(null)

  useLayoutEffect(() => {
    const list = listRef.current
    const pill = pillRef.current
    if (!list || !pill) return
    const place = () => {
      const tab = list.querySelector<HTMLElement>(`[data-tab="${active}"]`)
      // Trash sits outside the pill group — the highlight steps aside.
      pill.style.opacity = tab && tab.dataset.group === 'pill' ? '1' : '0'
      if (!tab || tab.dataset.group !== 'pill') return
      pill.style.width = `${tab.offsetWidth}px`
      pill.style.height = `${tab.offsetHeight}px`
      pill.style.transform = `translate(${tab.offsetLeft}px, ${tab.offsetTop}px)`
    }
    place()
    // Animate only moves, not the first placement.
    const raf = requestAnimationFrame(() => { pill.dataset.ready = 'true' })
    const ro = typeof ResizeObserver !== 'undefined' ? new ResizeObserver(place) : null
    ro?.observe(list)
    return () => { cancelAnimationFrame(raf); ro?.disconnect() }
  }, [active])

  const onKeyDown = (e: React.KeyboardEvent) => {
    const i = BULK_TABS.findIndex((t) => t.key === active)
    const next = e.key === 'ArrowRight' ? (i + 1) % BULK_TABS.length
      : e.key === 'ArrowLeft' ? (i - 1 + BULK_TABS.length) % BULK_TABS.length
        : e.key === 'Home' ? 0 : e.key === 'End' ? BULK_TABS.length - 1 : null
    if (next == null) return
    e.preventDefault()
    // The page is rebuilt on the address change; the new tab bar puts focus back (see below).
    focusTabOnMount = true
    onSelect(BULK_TABS[next].key)
  }

  useLayoutEffect(() => {
    if (!focusTabOnMount) return
    focusTabOnMount = false
    listRef.current?.querySelector<HTMLElement>(`[data-tab="${active}"]`)?.focus()
  }, [active])

  const trashTab = BULK_TABS.find((t) => t.key === 'trash')!
  const trashSelected = active === 'trash'
  return (
    <div
      ref={listRef}
      role="tablist"
      aria-label="Bulk Mailer"
      onKeyDown={onKeyDown}
      // On a phone the tabs take their own line under the title; wider, they share it.
      className="order-last flex w-full flex-wrap items-center gap-2 sm:order-none sm:w-auto sm:flex-1"
    >
      <div className="relative flex flex-nowrap items-center gap-0.5 overflow-x-auto rounded-lg border border-[#e3d9c4] bg-[#f4eede]/60 p-0.5 [scrollbar-width:none] [&::-webkit-scrollbar]:hidden">
        <span
          ref={pillRef}
          aria-hidden="true"
          className="bulk-tab-pill pointer-events-none absolute left-0 top-0 rounded-md bg-white shadow-sm ring-1 ring-[#e3d9c4]"
        />
        {BULK_TABS.filter((t) => t.key !== 'trash').map((t) => {
          const selected = active === t.key
          return (
            <button
              key={t.key}
              type="button"
              role="tab"
              data-tab={t.key}
              data-group="pill"
              aria-selected={selected}
              tabIndex={selected ? 0 : -1}
              onClick={() => onSelect(t.key)}
              title={t.hint}
              className={`relative z-[1] inline-flex shrink-0 items-center gap-1.5 whitespace-nowrap rounded-md px-3 py-1.5 text-[12.5px] font-semibold outline-none transition-colors duration-200 focus-visible:ring-2 focus-visible:ring-[#412d15]/40 ${
                selected ? 'text-[#1f150c]' : 'text-[#6b5c42] hover:text-[#1f150c]'
              }`}
            >
              <span className={`h-2 w-2 shrink-0 rounded-full ${t.dot} ${selected ? '' : 'opacity-60'}`} aria-hidden="true" />
              {t.label}
            </button>
          )
        })}
      </div>
      <span className="ml-auto flex items-center gap-2">
        {trailing}
        <button
          type="button"
          role="tab"
          data-tab="trash"
          aria-selected={trashSelected}
          tabIndex={trashSelected ? 0 : -1}
          onClick={() => onSelect('trash')}
          title={`${trashTab.label} · ${trashTab.hint.toLowerCase()}`}
          className={`inline-flex h-[30px] w-[30px] items-center justify-center rounded-lg border outline-none transition focus-visible:ring-2 focus-visible:ring-[#412d15]/40 ${
            trashSelected
              ? 'border-rose-200 bg-rose-50 text-rose-700'
              : 'border-[#e3d9c4] bg-white text-[#6b5c42] hover:border-rose-200 hover:bg-rose-50 hover:text-rose-700'
          }`}
        >
          <FiTrash2 className="h-3.5 w-3.5" />
          <span className="sr-only">{trashTab.label}</span>
        </button>
      </span>
    </div>
  )
}

/** What a tab shows while its first answer is on the way — the shape of the list, not a bare "Loading…". */
function BatchListSkeleton() {
  return (
    <div data-testid="batch-list-skeleton" aria-busy="true" aria-label="Loading batches" className="space-y-3">
      <div className="space-y-2 rounded-2xl border border-slate-200 bg-white p-3 shadow-sm">
        <div className="flex items-center gap-2">
          <div className="h-8 flex-1 animate-pulse rounded-lg bg-[#f6f1e6]" />
          {[72, 80, 76, 68].map((w, i) => (
            <div key={i} className="h-8 animate-pulse rounded-lg bg-[#f2ecdf]" style={{ width: w }} />
          ))}
        </div>
        {[0, 1, 2, 3, 4].map((i) => (
          <div key={i} className="flex items-center gap-3 px-2 py-2">
            <div className="h-3 w-10 animate-pulse rounded bg-[#efe7d6]" />
            <div className="h-3 flex-1 animate-pulse rounded bg-[#f2ecdf]" />
            <div className="h-5 w-20 animate-pulse rounded-full bg-[#efe7d6]" />
            <div className="h-7 w-28 animate-pulse rounded-lg bg-[#f2ecdf]" />
          </div>
        ))}
      </div>
    </div>
  )
}

/** "22 Sep" (or with the time, for a tooltip). */
function formatPrinted(iso: string, withTime = false) {
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return iso
  return withTime
    ? d.toLocaleString(undefined, { day: 'numeric', month: 'short', year: 'numeric', hour: 'numeric', minute: '2-digit' })
    : d.toLocaleDateString(undefined, { day: 'numeric', month: 'short' })
}

/** "18 generated · 2 pending · 1 voided · 1 failed" — only the parts that aren't zero. */
function LabelCountsLine({ counts }: { counts: LabelCounts | null }) {
  if (!counts) return null
  const parts: { n: number; label: string; dot: string; text: string }[] = [
    { n: counts.generated, label: 'generated', dot: 'bg-emerald-500', text: 'text-emerald-800' },
    { n: counts.pending, label: 'pending', dot: 'bg-amber-400', text: 'text-amber-800' },
    { n: counts.voided, label: 'voided', dot: 'bg-slate-400', text: 'text-slate-500' },
    { n: counts.failed, label: 'failed', dot: 'bg-rose-500', text: 'text-rose-700' },
  ].filter((p) => p.n > 0)
  if (parts.length === 0) return null
  return (
    <span data-testid="label-counts" className="flex flex-wrap items-center gap-x-1.5 gap-y-0.5 whitespace-nowrap text-[10.5px] font-semibold tabular-nums">
      {parts.map((p, i) => (
        <span key={p.label} className={`inline-flex items-center gap-1 ${p.text}`}>
          {i > 0 ? <span className="text-[#b6a684]" aria-hidden="true">·</span> : null}
          <span className={`h-1.5 w-1.5 rounded-full ${p.dot}`} aria-hidden="true" />
          {p.n} {p.label}
        </span>
      ))}
    </span>
  )
}
