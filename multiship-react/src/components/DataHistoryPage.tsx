import { Fragment, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react'
import { useNavigate, useParams, useSearchParams } from 'react-router-dom'
import {
  FiAlertCircle,
  FiCheckCircle,
  FiDownloadCloud,
  FiArrowLeft,
  FiFileText,
  FiHome,
  FiRefreshCw,
  FiSliders,
  FiTrash2,
  FiUpload,
  FiRotateCcw,
  FiSlash,
  FiX,
  FiZap,
} from 'react-icons/fi'
import type { ColumnDef } from '@tanstack/react-table'
import PageSectionHeader from './workspace/PageSectionHeader'
import AdvancedDataTable from './workspace/AdvancedDataTable'
import { bulkBatchPath, bulkPaths, settingsPaths } from '../routes/workspaceRoutes'
import { wmsService } from '../api/wmsService'
import { bulkService, type BulkSummary, type BulkView } from '../api/bulkService'
import { AddShipViaMappingDialog, ShipViaCodesPanel } from './modals/ShipViaCodes'
import OrderDocumentsTable from './OrderDocumentsTable'
import DataHistoryFilterToolbar from './DataHistoryFilterToolbar'
import { GridCell, DH_COLUMNS, RowIssuesIcon, RowChannelChip, bucketRowErrors, type DhColumn } from './batchGrid'
import VirtualTable from './VirtualTable'
import AnimatedHeight from './ui/AnimatedHeight'
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
 * Compact "X ago" for a completion timestamp — mirrors the pattern
 * used in ApiKeysPage / CarrierConnections so all "last activity" cells
 * on the site read the same way. Returns null for unset / future
 * timestamps so the caller can render nothing at all.
 */
const completedAgo = (iso?: string | null): string | null => {
  if (!iso) return null
  const secs = Math.round((Date.now() - new Date(iso).getTime()) / 1000)
  if (Number.isNaN(secs) || secs < 0) return null
  if (secs < 60) return 'completed just now'
  const mins = Math.round(secs / 60)
  if (mins < 60) return `completed ${mins}m ago`
  const hrs = Math.round(mins / 60)
  if (hrs < 24) return `completed ${hrs}h ago`
  return `completed ${Math.round(hrs / 24)}d ago`
}

/** "45s" · "2m 03s" · "1h 04m" — how long a generate run took / has been running. */
const formatDuration = (ms: number): string | null => {
  if (!Number.isFinite(ms) || ms < 0) return null
  const secs = Math.round(ms / 1000)
  if (secs < 60) return `${secs}s`
  const mins = Math.floor(secs / 60)
  if (mins < 60) return `${mins}m ${String(secs % 60).padStart(2, '0')}s`
  return `${Math.floor(mins / 60)}h ${String(mins % 60).padStart(2, '0')}m`
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
  const [openId, setOpenId] = useState<number | null>(null)
  // Ticks once a second while a run is in flight so the elapsed timer moves.
  const [nowTick, setNowTick] = useState(() => Date.now())
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
  // Observer-mode poll tracking (2026-09-12 post-mortem) — pre-fix, the
  // progress bar only rendered when THIS browser session called
  // generate(id). Reloading the page or opening Data History in a fresh
  // tab left IN_PROGRESS batches without a visible progress bar even
  // though workers were actively labelling. This map holds a cancel
  // function per batch so the effect below can restart / stop polls
  // without racing the local generate() session.
  const observerPollsRef = useRef<Map<number, { cancel: () => void }>>(new Map())
  // Bill-to account: the batch whose "Bills to" selector is mid-save.
  const [billingSavingId, setBillingSavingId] = useState<number | null>(null)
  // Confirm-before-generate when a batch bills to the platform account.
  const [confirmGenId, setConfirmGenId] = useState<number | null>(null)
  // Per-batch row filter for the expanded grid — a 1,000-order batch is 2,484 rows.
  const [gridFilter, setGridFilter] = useState<Record<number, 'all' | 'failed' | 'pending'>>({})
  const [genRowKey, setGenRowKey] = useState<string | null>(null)
  // Inline correction: the cell being saved (rowKey), for a per-cell spinner.
  const [savingCell, setSavingCell] = useState<string | null>(null)
  // Label preview modal: stores the order number to show its label
  const [showLabelModal, setShowLabelModal] = useState(false)
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

  // F5-A — advanced filter + sort + pagination state extracted to
  // useHistoryFilters (see hooks/useHistoryFilters.ts). Behavior is
  // preserved 1:1 including the DRAFT/IN_PROGRESS-first status tiebreaker
  // and the reset-to-page-1 effect on filter change.
  const filters = useHistoryFilters(batches)
  // These four flow into `dhColumns` deps + the header Advanced button;
  // the rest of the filter API is passed straight to the toolbar below.
  const {
    showAdvanced,
    setShowAdvanced,
    activeAdvancedCount,
    clearFilters,
  } = filters

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
  const trash = useTrashActions({
    batches, setBatches, openId, setOpenId,
    onMoved: batchPageId != null ? () => reloadRef.current() : undefined,
    viewTrash: batchPageId != null ? !!batches.find((b) => b.id === batchPageId)?.deletedAt : bulkTab === 'trash',
  })
  const {
    viewTrash,
    trashBusyId,
    confirmEmpty,
    setConfirmEmpty,
    emptying,
    handleEmptyTrash,
    handleDelete,
    handleRestore,
  } = trash


  useEffect(() => {
    if (!batchPageId && tab && !BULK_TABS.some((t) => t.key === tab)) navigate(bulkPaths.imports, { replace: true })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tab])

  /** Lazy-load a batch's rows when its row is expanded. */
  const ensureRows = (id: number) => {
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
    setOpenId(null)
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

  const { status: sseStatus } = useEventStream({
    enabled: !viewTrash,
    topics: ['import-batches'],
    handlers: sseHandlers,
  })

  /**
   * Auto-poll — the FALLBACK path when SSE isn't available. Runs
   * ONLY while: (a) not in Trash view, (b) at least one batch is
   * IN_PROGRESS, AND (c) the SSE stream is NOT 'open'. When SSE
   * connects, the poll goes quiet; when SSE drops, it resumes.
   */
  useEffect(() => {
    if (viewTrash) return
    if (sseStatus === 'open') return
    const anyInProgress = batches.some(
      (b) => (b.status || '').toUpperCase() === 'IN_PROGRESS',
    )
    if (!anyInProgress) return
    const timer = window.setInterval(() => { void reloadQuiet() }, 4_000)
    return () => window.clearInterval(timer)
    // eslint-disable-next-line react-hooks/exhaustive-deps -- reloadQuiet reads only stable state via closure; deps kept minimal so the interval doesn't churn on unrelated re-renders
  }, [batches, viewTrash, sseStatus])

  /**
   * Idle refresh. Generate is a background job, so a run can start from another
   * tab, another operator or the API while this page sits idle — and the 4 s poll
   * above only runs once the page already knows something is IN_PROGRESS. A slow
   * refresh (visible tab only, no SSE) picks those up.
   */
  useEffect(() => {
    if (viewTrash) return
    if (sseStatus === 'open') return
    const timer = window.setInterval(() => {
      if (document.visibilityState === 'visible') void reloadQuiet()
    }, 20_000)
    return () => window.clearInterval(timer)
    // eslint-disable-next-line react-hooks/exhaustive-deps -- reloadQuiet + document.visibilityState read via closure; only viewTrash + sseStatus toggle the interval on/off
  }, [viewTrash, sseStatus])

  /**
   * Observer-mode progress polling (2026-09-12 fix). For any batch whose
   * SERVER-side status is IN_PROGRESS but that WASN'T started by this
   * session (generatingId !== id), poll {@code generationProgress} so
   * the progress bar renders regardless of who kicked off the generate.
   * Covers: page-reload while a batch is generating, opening Data
   * History in a fresh tab, watching a batch another operator started.
   *
   * <p>The local generate() call runs its own poll (with a tighter
   * closure-scoped stop signal), so this observer poll skips batches
   * where {@code generatingId === b.id}. Otherwise duplicate polls
   * would race on the same setGenProgressById state.
   *
   * <p>Each poll auto-terminates when the server reports
   * {@code running: false} (batch reached a terminal status) or when
   * the batch leaves the IN_PROGRESS state on our next render. Cancel
   * functions live in {@link observerPollsRef} keyed by batch id.
   */
  useEffect(() => {
    if (viewTrash) return
    const polls = observerPollsRef.current
    const inProgressIds = new Set(
      batches
        .filter((b) => (b.status || '').toUpperCase() === 'IN_PROGRESS')
        .map((b) => b.id),
    )
    // Start polls for any newly-IN_PROGRESS batch we're not already
    // polling AND that the local generate() isn't already polling itself.
    for (const id of inProgressIds) {
      if (polls.has(id)) continue
      if (generatingId === id) continue
      let cancelled = false
      polls.set(id, { cancel: () => { cancelled = true } })
      void (async () => {
        while (!cancelled) {
          try {
            const pr = await orderImportService.generationProgress(id)
            const d = pr.data
            if (cancelled) break
            if (d && d.running && d.total > 0) {
              setGenProgressById((m) => ({
                ...m,
                [id]: { done: d.done, total: d.total, note: d.note ?? null, cancelling: !!d.cancelling, jobStatus: d.jobStatus ?? null },
              }))
            } else if (d && !d.running) {
              // Server says the run finished — reload first so the row leaves
              // IN_PROGRESS at once (waiting for the 4 s list poll left a
              // count-less "Generating…" card flickering in between), then stop.
              await reloadQuiet()
              setGenProgressById((m) => {
                if (!(id in m)) return m
                const next = { ...m }
                delete next[id]
                return next
              })
              break
            }
          } catch {
            /* transient poll error — try again; the auto-poll effect
               above will refresh the batches list which drives our
               continue/stop decision on the next tick. */
          }
          await new Promise((r) => setTimeout(r, 400))
        }
        polls.delete(id)
      })()
    }
    // Stop polls for batches that are no longer IN_PROGRESS (reached
    // a terminal state, got soft-deleted, or otherwise fell off the list).
    for (const [id, poll] of polls) {
      if (!inProgressIds.has(id)) {
        poll.cancel()
        polls.delete(id)
      }
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps -- observerPollsRef + reloadQuiet + setGenProgressById read via closure; batches/viewTrash/generatingId are the real driver signals
  }, [batches, viewTrash, generatingId])

  /** Cleanup on unmount — cancel every in-flight observer poll so the
   *  loop doesn't outlive the component. Ref captured OUTSIDE the cleanup
   *  so React lint doesn't warn about a stale ref.current read. */
  useEffect(() => {
    const polls = observerPollsRef.current
    return () => {
      for (const [, poll] of polls) poll.cancel()
      polls.clear()
    }
  }, [])

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
  const validateAll = async (id: number) => {
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
        notify.success('All rows validated successfully. Errors have been updated.')
      }
    } catch (e) {
      notify.apiError(e, 'Validation failed.')
    } finally {
      setValidatingId(null)
    }
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
    const current = (row as unknown as Record<string, unknown>)[col.key]
    if (String(current ?? '') === String(next ?? '')) return // unchanged
    const edited = { ...row, [col.key]: next } as OrderImportRow
    const key = `${batchId}-${row.rowNumber}`
    setSavingCell(key)
    try {
      const res = await orderImportService.updateRow(batchId, row.rowNumber, edited)
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
      } else {
        notify.error(res.message ?? 'Save failed.')
      }
    } catch (e) {
      notify.apiError(e, 'Save failed.')
    } finally {
      setSavingCell(null)
    }
  }

  /** Map an import status to a friendly label + pill classes. */
  const statusMeta = (status?: string | null): { label: string; cls: string } => {
    switch ((status || '').toUpperCase()) {
      case 'COMPLETE':
        return { label: 'Complete', cls: 'bg-emerald-50 text-emerald-700 ring-emerald-200' }
      case 'PARTIAL_COMPLETE':
        return { label: 'Partial complete', cls: 'bg-amber-50 text-amber-700 ring-amber-200' }
      case 'FAILED':
        return { label: 'Failed', cls: 'bg-rose-50 text-rose-700 ring-rose-200' }
      case 'IN_PROGRESS':
        return { label: 'In progress', cls: 'bg-sky-50 text-sky-700 ring-sky-200' }
      case 'CANCELLED':
        // Import I-3 — operator cancelled during the run. Amber ring to
        // match the CANCELLED status style used on the bulk-labels modal.
        return { label: 'Cancelled', cls: 'bg-amber-50 text-amber-700 ring-amber-200' }
      case 'INITIATE':
        return { label: 'Saved · not generated', cls: 'bg-slate-100 text-slate-600 ring-slate-200' }
      case 'DRAFT':
        return { label: 'Draft', cls: 'bg-orange-50 text-orange-700 ring-orange-200' }
      default:
        return { label: status || '—', cls: 'bg-slate-100 text-slate-500 ring-slate-200' }
    }
  }

  useEffect(() => {
    if (cancelRequested.size === 0) return
    const stillRunning = new Set(batches.filter((b) => (b.status || '').toUpperCase() === 'IN_PROGRESS').map((b) => b.id))
    const next = new Set([...cancelRequested].filter((id) => stillRunning.has(id)))
    // eslint-disable-next-line react-hooks/set-state-in-effect -- prune once the run ends
    if (next.size !== cancelRequested.size) setCancelRequested(next)
  }, [batches, cancelRequested])

  // Only runs while something is generating — no idle interval.
  const anyGenerating = batches.some((b) => (b.status || '').toUpperCase() === 'IN_PROGRESS') || generatingId != null
  useEffect(() => {
    if (!anyGenerating) return
    const t = setInterval(() => setNowTick(Date.now()), 1000)
    return () => clearInterval(t)
  }, [anyGenerating])

  // Status, rows and actions of a batch — shared by the table's cells and the batch page.
  const renderStatusCell = (b: ImportBatchSummary) => {
          const s = statusMeta(b.status)
          // Completion caption (2026-09-12) — only rendered when the
          // batch has landed a terminal state at least once; retries
          // that go back through IN_PROGRESS null completedAt so the
          // caption disappears until the next terminal transition.
          const done = completedAgo(b.completedAt)
          const startedMs = b.generationStartedAt ? new Date(b.generationStartedAt).getTime() : null
          const running = (b.status || '').toUpperCase() === 'IN_PROGRESS'
          // Running: tick from the claim. Finished: how long that run took.
          const elapsed = running && startedMs != null
            ? formatDuration(nowTick - startedMs)
            : startedMs != null && b.completedAt
              ? formatDuration(new Date(b.completedAt).getTime() - startedMs)
              : null
          const timingTitle = [
            b.generationStartedAt ? `Started ${new Date(b.generationStartedAt).toLocaleString()}` : null,
            b.completedAt ? `Finished ${new Date(b.completedAt).toLocaleString()}` : null,
          ].filter(Boolean).join(' · ') || undefined
          return (
            <span className="flex max-w-[220px] flex-col items-start gap-0.5">
              <span className={`rounded-full px-2.5 py-0.5 text-[10.5px] font-bold ring-1 ${s.cls}`}>{s.label}</span>
              {elapsed ? (
                <span
                  className={`text-[10px] tabular-nums ${running ? 'font-semibold text-[#412d15]' : 'text-[#8a7a5a]'}`}
                  title={timingTitle}
                >
                  {running ? `running ${elapsed}` : `took ${elapsed}`}
                  {!running && done ? <span className="text-[#b6a684]"> · {done}</span> : null}
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
          const canGenerate = canWrite && (st === 'INITIATE' || st === 'PARTIAL_COMPLETE' || st === 'FAILED'
            || st === 'CANCELLED' || (st === 'DRAFT' && b.savedRows > 0))
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
            <div className="flex items-center justify-end gap-1.5">
              {viewTrash ? (
                canWrite ? (
                  <button
                    type="button"
                    onClick={() => void handleRestore(b.id, b.fileName)}
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
                    className={BTN_GHOST_SM}
                  >
                    {validatingId === b.id ? (
                      <span className="inline-block h-3 w-3 animate-spin rounded-full border-2 border-emerald-100 border-t-emerald-600" />
                    ) : (
                      // A green check: "check every row and confirm it's ready".
                      <FiCheckCircle className="h-3.5 w-3.5 text-emerald-600" aria-hidden="true" />
                    )}
                    {validatingId === b.id ? 'Validating…' : 'Validate all'}
                  </button>
                  {/* Keep the button mounted while THIS batch is generating — the
                      click optimistically flips status to IN_PROGRESS, which isn't
                      in canGenerate's set, so without `|| busy` the whole control
                      (and its spinner) would unmount the instant you click and the
                      loader would never show. */}
                  {(canGenerate || busy) ? (
                    <>
                      <span
                        title="Which carrier account this batch bills to. Platform bills the house account and rebills the client with markup."
                        className={`${confirming || busy ? 'hidden' : 'inline-flex'} items-center gap-1.5 rounded-xl border px-2.5 py-1.5 text-[11px] font-semibold ${
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
                                    const el = st != null ? formatDuration(nowTick - st) : null
                                    return el ? (
                                      <span className="shrink-0 font-mono tabular-nums text-[#f4eede]/85">{el}</span>
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
                  {canWrite ? (
                    <button
                      type="button"
                      onClick={() => void handleDelete(b.id, b.fileName)}
                      disabled={trashBusyId === b.id || st === 'IN_PROGRESS'}
                      title={st === 'IN_PROGRESS' ? 'Wait for the label run to finish (or cancel it) before moving this import to Trash' : 'Move this import to Trash (recoverable)'}
                      aria-label="Delete import"
                      className="inline-flex items-center justify-center rounded-xl border border-[#e3d9c4] bg-white p-2 text-[#6b5c42] transition hover:border-rose-300 hover:bg-rose-50 hover:text-rose-600 disabled:cursor-not-allowed disabled:opacity-50"
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
        id: 'serial',
        header: 'Serial no.',
        enableSorting: false,
        size: 70,
        accessorFn: (b) => b.id,
        cell: ({ row }) => <span className="font-mono text-[13px] font-bold text-[#1f150c]">#{row.original.id}</span>,
        meta: { headerLabel: 'Serial no.' },
      },
      {
        id: 'file',
        header: 'File',
        enableSorting: false,
        size: 240,
        accessorFn: (b) => b.fileName ?? '',
        cell: ({ row }) => {
          const b = row.original
          const apiSource = ['WMS', 'API'].includes((b.source || '').toUpperCase()) ? (b.source || '').toUpperCase() : null
          return (
            <span className="block min-w-0">
              <span className="flex items-center gap-1.5">
                <FiFileText className="h-3.5 w-3.5 shrink-0 text-[#b6a684]" />
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
                <span>{b.createdBy || '—'}</span>
                {b.labelBatchId != null ? (
                  <span
                    title="Label batch — find these orders together in All Orders"
                    className="inline-flex items-center gap-1 rounded-full bg-[#412d15] px-2 py-0.5 font-mono text-[9.5px] font-bold uppercase tracking-[0.08em] text-[#f4eede]"
                  >
                    <FiZap className="h-2.5 w-2.5" /> Batch {b.labelBatchId}
                  </span>
                ) : (
                  <span className="font-mono text-[9.5px] uppercase tracking-[0.08em] text-[#b6a684]">No batch yet</span>
                )}
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
        size: 130,
        accessorFn: (b) => b.status ?? '',
        cell: ({ row }) => renderStatusCell(row.original),
        meta: { headerLabel: 'Status' },
      },
      {
        id: 'rows',
        header: 'Rows',
        enableSorting: false,
        size: 120,
        accessorFn: (b) => b.totalRows,
        cell: ({ row }) => renderRowsCell(row.original),
        meta: { headerLabel: 'Rows' },
      },
      {
        id: 'actions',
        header: 'Actions',
        enableSorting: false,
        size: 400,
        cell: ({ row }) => renderActionsCell(row.original),
        // Buttons have no CSV value — keep the column out of the export.
        meta: { headerLabel: 'Actions', exportable: false },
      },
    ],
    // nowTick re-renders the running-elapsed caption once a second.
    // Handlers (cancelGeneration/generate/handleDelete/handleRestore/setBilling)
    // are re-created every render but close over their own state correctly —
    // adding them here would defeat memoization by giving dhColumns a new
    // identity every render.
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [canWrite, viewTrash, trashBusyId, confirmGenId, billingSavingId, generatingId, genProgressById, nowTick, cancellingId, cancelRequested, validatingId],
  )

  /** Expanded content for a batch row — the all-columns editable grid. */
  const renderBatchExpanded = (b: ImportBatchSummary) => {
    const rows = rowsById[b.id]
    const list = Array.isArray(rows) ? rows : []
    console.log('renderBatchExpanded called for batch', b.id, 'rows:', rows, 'list.length:', list.length)
    const filter = gridFilter[b.id] ?? 'all'
    const needsAttention = (r: (typeof list)[number]) =>
      (r.errors?.length ?? 0) > 0 || (r.generatedStatus ?? '').toUpperCase() === 'FAILED'
    const notLabelled = (r: (typeof list)[number]) => (r.generatedStatus ?? '').toUpperCase() !== 'GENERATED'
    const visible = filter === 'failed' ? list.filter(needsAttention) : filter === 'pending' ? list.filter(notLabelled) : list
    // An order is labelled as one shipment, so a clean line of an order whose
    // other line has errors can't be labelled on its own either.
    const orderKey = (r: (typeof list)[number]) => (r.orderRef ?? '').trim() || `__row_${r.rowNumber}`
    const brokenOrders = new Set(list.filter((r) => (r.errors?.length ?? 0) > 0).map(orderKey))
    return (
      <div className="border-t border-dashed border-[#eee6d6] bg-[#faf7f0]/50 px-5 py-3">
        {rows === 'loading' || rows === undefined ? (
          <p className="py-4 text-center text-[12px] text-[#6b5c42]">Loading rows…</p>
        ) : rows.length === 0 ? (
          <p className="py-4 text-center text-[12px] text-[#6b5c42]">No rows stored for this import.</p>
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
            <div className="mb-1.5 flex flex-wrap items-center gap-2">
              <div className="inline-flex overflow-hidden rounded-lg border border-[#e3d9c4]" role="group" aria-label="Show rows">
                {([
                  ['all', `All ${list.length}`],
                  ['failed', `Needs attention ${list.filter(needsAttention).length}`],
                  ['pending', `Not labelled ${list.filter(notLabelled).length}`],
                ] as const).map(([k, label]) => (
                  <button
                    key={k}
                    type="button"
                    aria-pressed={filter === k}
                    onClick={() => setGridFilter((m) => ({ ...m, [b.id]: k }))}
                    className={`px-2.5 py-1 text-[10.5px] font-semibold transition ${
                      filter === k ? 'bg-[#1f150c] text-[#f4eede]' : 'bg-white text-[#5a4526] hover:bg-[#faf7f0]'
                    }`}
                  >
                    {label}
                  </button>
                ))}
              </div>
              {/* Validate all — re-checks every row; a quiet secondary button beside Generate. */}
              {!viewTrash ? (
                <button
                  type="button"
                  onClick={() => void validateAll(b.id)}
                  disabled={validatingId === b.id || (b.status || '').toUpperCase() === 'IN_PROGRESS'}
                  title="Validate all rows in this batch and update their errors/warnings"
                  className={BTN_GHOST_SM}
                >
                  {validatingId === b.id ? (
                    <span className="inline-block h-3 w-3 animate-spin rounded-full border-2 border-emerald-100 border-t-emerald-600" />
                  ) : (
                    <FiCheckCircle className="h-3.5 w-3.5 text-emerald-600" aria-hidden="true" />
                  )}
                  {validatingId === b.id ? 'Validating…' : 'Validate all'}
                </button>
              ) : null}
              <p className="text-[10.5px] text-[#b6a684]">
                {viewTrash
                  ? 'Read-only in Trash — restore this import to edit rows or generate labels.'
                  : (b.status || '').toUpperCase() === 'IN_PROGRESS'
                    ? 'Locked while labels are generating — editing opens again when the run finishes.'
                    : !canWrite
                      ? 'Read-only view. Scroll right for more columns.'
                      : 'Click any cell to edit; it saves and re-validates on blur. Scroll right for more columns.'}
              </p>
            </div>
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
            <VirtualTable
              rows={visible}
              rowKey={(r) => r.rowNumber}
              colCount={DH_COLUMNS.length + 2}
              maxHeight="70vh"
              className="rounded-xl border border-[#e3d9c4] bg-white"
              tableClassName="w-full border-collapse text-[11px] text-[#3f3527]"
              empty={<p className="py-6 text-center text-[11px] text-[#6b5c42]">No rows match this filter.</p>}
              head={
                <thead className="sticky top-0 z-30">
                  <tr className="bg-[#faf7f0] text-[8.5px] uppercase tracking-[0.1em] text-[#6b5c42]">
                    <th className="sticky left-0 z-20 border-b border-r border-[#e3d9c4] bg-[#faf7f0] px-2 py-1.5 text-left font-bold">Row</th>
                    {DH_COLUMNS.map((c) => (
                      <th key={c.key} className="whitespace-nowrap border-b border-[#e3d9c4] px-2 py-1.5 text-left font-bold">{c.key}</th>
                    ))}
                    <th className="whitespace-nowrap border-b border-[#e3d9c4] px-2 py-1.5 text-left font-bold">Label</th>
                  </tr>
                </thead>
              }
              renderRow={(r, index, measureRef) => {
                    const ok = (r.errors?.length ?? 0) === 0
                    const orderReady = !brokenOrders.has(orderKey(r))
                    const gen = (r.generatedStatus ?? '').toUpperCase()
                    const rowIsWms = ['WMS', 'API'].includes((b.source || '').toUpperCase())
                    const generated = gen === 'GENERATED'
                    // A row that passed validation but was rejected by the carrier
                    // is FAILED, not "Ready" — surface that so it shows Retry (which
                    // reuses the same order) instead of a fresh Generate.
                    const failed = gen === 'FAILED'
                    const rowKey = `${b.id}-${r.rowNumber}`
                    const rowBusy = genRowKey === rowKey
                    const saving = savingCell === rowKey
                    const { byField, rowLevel } = bucketRowErrors(r.errors ?? [])
                    const statusTitle = (r.errors ?? []).map((m) => '✗ ' + m).join('\n') || undefined
                    const warnings = r.warnings ?? []
                    // Anything worth explaining under the row: validation errors,
                    // a carrier rejection, or warnings. Rendered as a visible
                    // strip — hover tooltips alone hid the "why".
                    const hasExplain = !ok || (failed && !!r.generatedMessage) || warnings.length > 0
                    return (
                      <Fragment key={r.rowNumber}>
                      <tr ref={measureRef} data-index={index} className={ok ? 'bg-white' : 'bg-rose-50/40'}>
                        <td className={`sticky left-0 z-10 whitespace-nowrap border-b border-r border-[#e3d9c4] px-2 py-1 ${ok ? 'bg-white' : 'bg-rose-50'}`}>
                          <div className="flex items-center gap-1.5">
                            <span className="font-mono text-[10px] font-bold text-[#6b5c42]">{r.rowNumber}</span>
                            {generated ? (
                              <span className="rounded-full bg-emerald-100 px-1.5 py-0.5 text-[9px] font-semibold text-emerald-800">Generated</span>
                            ) : failed ? (
                              <span title={r.generatedMessage || 'The carrier rejected this shipment'} className="cursor-help rounded-full bg-rose-100 px-1.5 py-0.5 text-[9px] font-semibold text-rose-800">Failed</span>
                            ) : ok && orderReady ? (
                              <span className="rounded-full bg-emerald-100 px-1.5 py-0.5 text-[9px] font-semibold text-emerald-800">Ready</span>
                            ) : ok ? (
                              <span
                                title={`This line is fine, but another line of order ${r.orderRef ?? ''} needs fixes`}
                                className="cursor-help rounded-full bg-amber-100 px-1.5 py-0.5 text-[9px] font-semibold text-amber-800"
                              >
                                Order needs fixes
                              </span>
                            ) : (
                              <span title={statusTitle} className="cursor-help rounded-full bg-rose-100 px-1.5 py-0.5 text-[9px] font-semibold text-rose-800">
                                {r.errors!.length} error{r.errors!.length === 1 ? '' : 's'}
                              </span>
                            )}
                            {/* D2C / B2B belongs to the API side only (client request) —
                                shown for WMS fetches, never for CSV/Excel uploads. */}
                            {rowIsWms ? <RowChannelChip recipientCompany={r.recipientCompany} /> : null}
                            {saving ? <span className="inline-block h-2.5 w-2.5 animate-spin rounded-full border-2 border-[#cdbf9f] border-t-[#5a4526]" /> : null}
                            {/* Left ⓘ — sticky cell, so the issues stay one
                                hover away at any horizontal scroll position. */}
                            {hasExplain ? (
                              <RowIssuesIcon
                                side="left"
                                rowNumber={r.rowNumber}
                                byField={byField}
                                rowLevel={rowLevel}
                                carrierMessage={failed ? r.generatedMessage : null}
                                warnings={warnings}
                              />
                            ) : null}
                          </div>
                        </td>
                        {DH_COLUMNS.map((c) => {
                          const raw = (r as unknown as Record<string, unknown>)[c.key]
                          // An API/WMS order shows the client's own ship via code (its
                          // resolved carrier service in the tooltip), and an unmapped
                          // code can be mapped right here.
                          const showShipVia = c.key === 'serviceType' && rowIsWms && !!r.shipViaCode
                          const unmapped = c.key === 'serviceType' && rowIsWms
                            ? (byField.serviceType ?? []).map((m) => m.match(UNMAPPED_SHIP_VIA)).find(Boolean)
                            : null
                          return (
                            <td key={c.key} className="border-b border-[#f2ecdf] px-1 py-1 align-top">
                              <div className={c.w} title={showShipVia ? (r.shipViaNote ?? undefined) : undefined}>
                                <GridCell
                                  value={showShipVia ? String(r.shipViaCode) : raw == null ? '' : String(raw)}
                                  // Locked when labelled, while the import is generating
                                  // (the run would overwrite the edit) and in Trash. An API
                                  // order's reference is the sender's, not ours to change.
                                  readOnly={generated || viewTrash || (b.status || '').toUpperCase() === 'IN_PROGRESS'
                                    || (rowIsWms && c.key === 'orderRef')}
                                  bad={(byField[c.key]?.length ?? 0) > 0}
                                  errors={byField[c.key]}
                                  mono={c.mono}
                                  onCommit={(v) => void commitCell(b.id, r, c, v)}
                                />
                                {unmapped && canWrite && !generated && !viewTrash ? (
                                  <button
                                    type="button"
                                    onClick={() => setMapping({
                                      code: unmapped[1],
                                      clientCode: (r.clientCode ?? '').trim().toUpperCase() || null,
                                      batchId: b.id,
                                    })}
                                    className="mt-0.5 block w-full truncate rounded border border-[#e3d9c4] bg-white px-1 py-0.5 text-[9px] font-semibold text-[#5a4526] hover:bg-[#faf7f0]"
                                  >
                                    Map {unmapped[1]}…
                                  </button>
                                ) : null}
                              </div>
                            </td>
                          )
                        })}
                        <td className="whitespace-nowrap border-b border-[#f2ecdf] px-2 py-1">
                          {generated ? (
                            <span className="inline-flex flex-col gap-0.5">
                              {/* Order number first — masked sandbox tracking (1ZXXXX…) is
                                  identical on every UPS row, so it alone cannot say which
                                  rows share an order. */}
                              {r.generatedOrderNo ? (
                                <div className="flex items-center gap-1">
                                  <a
                                    href={`/label/${r.generatedOrderNo}`}
                                    className="font-mono text-[10px] font-semibold text-[#1f150c] underline-offset-2 hover:underline"
                                    title="Open this order"
                                  >
                                    #{r.generatedOrderNo}
                                  </a>
                                  {r.labelUrl && r.generatedOrderNo ? (
                                    <button
                                      type="button"
                                      onClick={() => {
                                        setLabelModalOrderNo(r.generatedOrderNo ?? null)
                                        setShowLabelModal(true)
                                      }}
                                      className="inline-flex items-center gap-0.5 rounded px-1.5 py-0.5 text-[9px] font-semibold text-white bg-blue-600 hover:bg-blue-700 transition"
                                      title="View the generated label PDF"
                                    >
                                      📄 View
                                    </button>
                                  ) : null}
                                </div>
                              ) : null}
                              {r.generatedTrackingNumber ? (
                                <span className="font-mono text-[9.5px] text-[#6b5c42]">{r.generatedTrackingNumber}</span>
                              ) : (
                                <span className="text-[9.5px] text-[#6b5c42]">—</span>
                              )}
                            </span>
                          ) : !canWrite ? (
                            <span className="text-[9.5px] text-[#b6a684]">Read-only view</span>
                          ) : orderReady && (ok || failed) ? (
                            <div className="flex flex-col gap-0.5">
                              <button
                                type="button"
                                onClick={() => void generateRow(b.id, r.rowNumber)}
                                disabled={rowBusy || viewTrash || (b.status || '').toUpperCase() === 'IN_PROGRESS'}
                                className={`inline-flex items-center gap-1 rounded-lg px-2 py-1 text-[10px] font-semibold text-[#f4eede] transition disabled:cursor-not-allowed disabled:bg-[#dcd4c4] ${failed ? 'bg-rose-700 hover:bg-rose-800' : 'bg-[#1f150c] hover:bg-[#412d15]'}`}
                                title={failed ? 'Retry — re-sends this same order to the carrier (no duplicate order is created)' : 'Generate a carrier label for this row'}
                              >
                                {rowBusy ? (
                                  <span className="inline-block h-3 w-3 animate-spin rounded-full border-2 border-[#f4eede]/40 border-t-[#f4eede]" />
                                ) : (
                                  <FiZap className="h-3 w-3" />
                                )}
                                {rowBusy ? 'Generating…' : failed ? 'Retry label' : 'Generate label'}
                              </button>
                              {failed && r.generatedMessage ? (
                                <span title={r.generatedMessage} className="max-w-[220px] truncate text-[9px] text-rose-700">{r.generatedMessage}</span>
                              ) : null}
                            </div>
                          ) : (
                            <span
                              className="text-[9.5px] text-[#b6a684]"
                              title={ok ? `Another line of order ${r.orderRef ?? ''} needs fixes — the order is labelled as one shipment` : undefined}
                            >
                              Fix errors first
                            </span>
                          )}
                          {hasExplain ? (
                            <RowIssuesIcon
                              side="right"
                              rowNumber={r.rowNumber}
                              byField={byField}
                              rowLevel={rowLevel}
                              carrierMessage={failed ? r.generatedMessage : null}
                              warnings={warnings}
                            />
                          ) : null}
                        </td>
                      </tr>
                      </Fragment>
                    )
              }}
            />
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
        // The server re-validates the batch, so rows that failed on this code clear.
        void validateAll(batchId)
      }}
    />
  ) : null

  const labelModal = showLabelModal && labelModalOrderNo ? (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4"
          onClick={() => setShowLabelModal(false)}
        >
          <div
            className="relative w-full max-w-4xl rounded-lg bg-white shadow-lg"
            onClick={(e) => e.stopPropagation()}
          >
            {/* Header */}
            <div className="flex items-center justify-between border-b border-[#e3d9c4] px-6 py-4">
              <h2 className="text-lg font-semibold text-[#1f150c]">Order #{labelModalOrderNo} - Label Preview</h2>
              <button
                type="button"
                onClick={() => setShowLabelModal(false)}
                className="rounded-full p-1 text-[#6b5c42] hover:bg-[#f2ecdf]"
                title="Close"
              >
                <FiX className="h-5 w-5" />
              </button>
            </div>

            {/* Content - PDF Viewer */}
            <div className="h-[70vh] overflow-auto bg-[#f9f6f0]">
              <iframe
                src={`/api/v1/orders/${labelModalOrderNo}/label/pdf`}
                className="h-full w-full border-0"
                title={`Label for order ${labelModalOrderNo}`}
              />
            </div>

            {/* Footer - Action Buttons */}
            <div className="flex items-center justify-end gap-2 border-t border-[#e3d9c4] px-6 py-3">
              <button
                type="button"
                onClick={() => window.open(`/api/v1/orders/${labelModalOrderNo}/label/pdf`, '_blank')}
                className="inline-flex items-center gap-1 rounded-lg px-3 py-2 text-[12px] font-semibold text-[#1f150c] hover:bg-[#f2ecdf]"
              >
                📥 Download
              </button>
              <button
                type="button"
                onClick={() => window.print()}
                className="inline-flex items-center gap-1 rounded-lg px-3 py-2 text-[12px] font-semibold text-[#1f150c] hover:bg-[#f2ecdf]"
              >
                🖨️ Print
              </button>
              <button
                type="button"
                onClick={() => setShowLabelModal(false)}
                className="inline-flex items-center gap-1 rounded-lg bg-[#1f150c] px-3 py-2 text-[12px] font-semibold text-white hover:bg-[#412d15]"
              >
                Close
              </button>
            </div>
          </div>
        </div>
  ) : null

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
      <div className="space-y-4 pb-24">
        {mappingDialog}
        <nav aria-label="Breadcrumb" className="flex flex-wrap items-center gap-1.5 text-[12px] text-[#6b5c42]">
          <button type="button" onClick={() => navigate(back.to)} className="inline-flex items-center gap-1 font-semibold text-[#5a4526] hover:underline">
            <FiArrowLeft className="h-3.5 w-3.5" /> Bulk Mailer · {back.label}
          </button>
          <span aria-hidden="true">/</span>
          <span>Batch #{batchPageId}</span>
        </nav>
        {loading && !b ? (
          <p className="rounded-2xl border border-[#e3d9c4] bg-white px-5 py-14 text-center text-sm text-[#6b5c42]">Loading…</p>
        ) : !b ? (
          <div className="rounded-2xl border border-[#e3d9c4] bg-white px-5 py-12 text-center">
            <p className="text-sm font-semibold text-[#1f150c]">Batch #{batchPageId} isn't here.</p>
            <p className="mt-1 text-[12.5px] text-[#6b5c42]">It may have been deleted, or it belongs to a client you can't see.</p>
            <div className="mt-4 flex justify-center gap-2">
              <button type="button" onClick={() => navigate(bulkPaths.imports)} className="rounded-xl border border-[#e3d9c4] bg-white px-3 py-1.5 text-[12.5px] font-semibold text-[#5a4526] hover:bg-[#faf7f0]">Import history</button>
              <button type="button" onClick={() => navigate(bulkPaths.trash)} className="rounded-xl border border-[#e3d9c4] bg-white px-3 py-1.5 text-[12.5px] font-semibold text-[#5a4526] hover:bg-[#faf7f0]">Trash</button>
            </div>
          </div>
        ) : (
          <>
            <section data-testid="batch-page-header" className="rounded-2xl border border-[#e3d9c4] bg-white p-4 shadow-sm sm:p-5">
              <div className="flex flex-wrap items-start justify-between gap-3">
                <div className="min-w-0">
                  <p className="text-[10.5px] font-bold uppercase tracking-[0.14em] text-[#8a7a5a]">
                    Batch #{b.id}{src === 'WMS' || src === 'API' ? ` · ${src}` : ' · File import'}{b.deletedAt ? ' · In Trash' : ''}
                  </p>
                  <h1 className="mt-0.5 truncate text-[18px] font-semibold text-[#1f150c]" title={b.fileName || undefined}>
                    {b.fileName || 'Untitled import'}
                  </h1>
                  <p className="mt-0.5 text-[12px] text-[#6b5c42]">
                    {b.createdAt ? new Date(b.createdAt).toLocaleString() : '—'}
                    {b.createdBy ? ` · by ${b.createdBy}` : ''}
                    {b.labelBatchId ? ` · label batch ${b.labelBatchId}` : ''}
                  </p>
                </div>
                <div className="flex flex-wrap items-start gap-4">
                  {renderStatusCell(b)}
                  <div className="w-[120px]">{renderRowsCell(b)}</div>
                </div>
              </div>
              <div className="mt-3 border-t border-dashed border-[#e3d9c4] pt-3">{renderActionsCell(b)}</div>
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

  return (
    <div className="space-y-4 pb-24">
      {mappingDialog}
      <PageSectionHeader
        eyebrow="Operations"
        title="Bulk Mailer"
        description="Import orders in bulk, fix what needs it, and buy their labels — from a file or from the API."
        actions={
          <button
            type="button"
            onClick={() => navigate('/orders')}
            className="inline-flex items-center gap-1.5 rounded-xl border border-[#e3d9c4] bg-white px-3 py-2 text-[13.5px] font-semibold text-[#5a4526] transition hover:border-[#cdbf9f] hover:bg-[#faf7f0]"
          >
            <FiArrowLeft className="h-3.5 w-3.5" />
            Orders
          </button>
        }
      />

      {/* PR-G4 — always-visible USPS queue depth pill (audit U2). Self-
          hides when the queue is empty (i.e. USPS_PROVIDER != USPS_DIRECT
          on this platform, or USPS_DIRECT with no backlog). Non-admin
          users see nothing because the admin metrics endpoint 403s. */}
      <div data-testid="usps-queue-badge-slot">
        <BulkLabelQueueBadge />
      </div>

      {/* Bulk Mailer tabs — each one is its own address (/bulk/:tab). */}
      <BulkTabBar active={bulkTab} onSelect={(t) => navigate(`/bulk/${t}`)} />

      {/* The panel's height glides between tabs (and from skeleton to list) so
          nothing below it jumps. */}
      <AnimatedHeight holdKey={bulkTab} ready={tabReady}>
      <div
        key={bulkTab}
        role="tabpanel"
        aria-label={BULK_TABS.find((t) => t.key === bulkTab)?.label}
        className={`space-y-4 ${tabMotion.dir > 0 ? 'bulk-tab-in-right' : 'bulk-tab-in-left'}`}
      >
      {/* This tab's own actions — they arrive with the tab instead of reshaping the header. */}
      {dhView === 'imports' ? (
        <div className="flex min-h-[38px] flex-wrap items-center justify-end gap-2">
            {dhView === 'imports' ? (
              <button
                type="button"
                onClick={() => setShowAdvanced((v) => !v)}
                className={`inline-flex items-center gap-1.5 rounded-xl border px-3 py-2 text-[13.5px] font-semibold transition ${
                  showAdvanced || activeAdvancedCount > 0
                    ? 'border-[#412d15] bg-[#412d15] text-[#f4eede]'
                    : 'border-[#e3d9c4] bg-white text-[#5a4526] hover:border-[#cdbf9f] hover:bg-[#faf7f0]'
                }`}
              >
                <FiSliders className="h-3.5 w-3.5" />
                Advanced
                {activeAdvancedCount > 0 ? (
                  <span className="ml-0.5 inline-flex h-4 min-w-4 items-center justify-center rounded-full bg-[#f4eede] px-1 text-[9.5px] font-bold text-[#412d15]">
                    {activeAdvancedCount}
                  </span>
                ) : null}
              </button>
            ) : null}
            {dhView === 'imports' ? (
              <button
                type="button"
                onClick={() => void load()}
                className="inline-flex items-center gap-1.5 rounded-xl border border-[#e3d9c4] bg-white px-3 py-2 text-[13.5px] font-semibold text-[#5a4526] transition hover:border-[#cdbf9f] hover:bg-[#faf7f0]"
              >
                <FiRefreshCw className="h-3.5 w-3.5" />
                Refresh
              </button>
            ) : null}
            {dhView === 'imports' && viewTrash && (summary?.total ?? batches.length) > 0 ? (
              confirmEmpty ? (
                <span className="inline-flex items-center gap-1.5">
                  <button
                    type="button"
                    onClick={() => void handleEmptyTrash()}
                    disabled={emptying}
                    className="inline-flex items-center gap-1.5 rounded-xl bg-rose-600 px-3 py-2 text-[13.5px] font-semibold text-white shadow-sm transition hover:bg-rose-700 disabled:cursor-not-allowed disabled:opacity-60"
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
                    className="inline-flex items-center rounded-xl border border-[#e3d9c4] bg-white px-3 py-2 text-[13.5px] font-semibold text-[#5a4526] transition hover:bg-[#faf7f0]"
                  >
                    Cancel
                  </button>
                </span>
              ) : (
                <button
                  type="button"
                  onClick={() => setConfirmEmpty(true)}
                  title="Permanently delete everything in Trash"
                  className="inline-flex items-center gap-1.5 rounded-xl border border-rose-200 bg-white px-3 py-2 text-[13.5px] font-semibold text-rose-600 transition hover:border-rose-300 hover:bg-rose-50"
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
                className="inline-flex items-center gap-1.5 rounded-xl bg-[#1f150c] px-3.5 py-2 text-[13.5px] font-semibold text-[#f4eede] shadow-sm transition hover:bg-[#412d15] disabled:opacity-60"
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
                className="inline-flex items-center gap-1.5 rounded-xl bg-[#1f150c] px-3.5 py-2 text-[13.5px] font-semibold text-[#f4eede] shadow-sm transition hover:bg-[#412d15]"
              >
                <FiUpload className="h-3.5 w-3.5" />
                Import CSV / Excel
              </button>
            ) : null}
        </div>
      ) : null}
      {dhView === 'docs' ? (
        <OrderDocumentsTable onLoaded={() => setDocsLoadedFor(tabMotion.tab)} />
      ) : loadedView !== viewKey ? (
        <BatchListSkeleton withCards={!viewTrash} />
      ) : (
      <div className="bulk-fade-in space-y-4">
      {!viewTrash && summary ? <BatchSummaryCards summary={summary} /> : null}
      {/* ── Advanced filter toolbar ─────────────────────────────────────── */}
      <DataHistoryFilterToolbar
        statusFilter={filters.statusFilter}
        setStatusFilter={filters.setStatusFilter}
        statusCounts={summary?.statusCounts ?? {}}
        statusMetaLabel={(s) => statusMeta(s).label}
        anyFilterActive={filters.anyFilterActive}
        clearFilters={filters.clearFilters}
        search={filters.search}
        setSearch={filters.setSearch}
        dateFrom={filters.dateFrom}
        setDateFrom={filters.setDateFrom}
        dateTo={filters.dateTo}
        setDateTo={filters.setDateTo}
        dateFilterActive={filters.dateFilterActive}
        sortKey={filters.sortKey}
        setSortKey={filters.setSortKey}
        sortDir={filters.sortDir}
        setSortDir={filters.setSortDir}
        showAdvanced={filters.showAdvanced}
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

      <section
        aria-busy={refreshing}
        className={`rounded-2xl border border-slate-200 bg-white p-3 shadow-sm transition-opacity duration-200 ${refreshing && !loading ? 'opacity-60' : ''}`}
      >
        {loading ? (
          <p className="px-5 py-14 text-center text-sm text-[#6b5c42]">Loading…</p>
        ) : batches.length === 0 && (summary?.total ?? 0) === 0 ? (
          <p className="px-5 py-14 text-center text-sm text-[#6b5c42]">
            {viewTrash
              ? 'Trash is empty — no deleted imports.'
              : isApiTab
                ? (canPullWms ? 'No API batches yet. Use Fetch from WMS to pull the pending shipments in.' : 'No API batches yet. An admin can use Fetch from WMS to pull the pending shipments in.')
                : 'No saved imports yet. Use Import CSV / Excel to add your first file — saved orders show up here.'}
          </p>
        ) : (
          <AdvancedDataTable<ImportBatchSummary>
            tableKey={viewTrash ? 'order-intake-imports-trash-v3' : isApiTab ? 'bulk-api-batches-v1' : 'order-intake-imports-v3'}
            columns={dhColumns}
            data={batches}
            manualPagination
            pageIndex={pageIndex}
            pageSize={pageSize}
            pageCount={pageInfo.pages}
            onPaginationChange={({ pageIndex: i, pageSize: n }) => { setPageIndex(n !== pageSize ? 0 : i); setPageSize(n) }}
            onRowClick={(b) => navigate(bulkBatchPath(b.id))}
            getRowId={(b) => String(b.id)}
            initialColumnPinning={{ left: [], right: ['actions'] }}
            caption={viewTrash ? 'Trash — deleted batches · click a batch to open it'
              : isApiTab ? 'Batches from the WMS and the API · each fetch is one batch · click a batch to open it'
                : 'Saved imports · click a batch to open it'}
            emptyState={
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
            }
          />
        )}
      </section>

      {labelModal}
      </div>
      )}
      </div>
      </AnimatedHeight>
    </div>
  )
}

export type BulkTab = 'imports' | 'api' | 'documents' | 'trash'

/** Bulk Mailer tabs, in order. Import history is the landing tab. */
const BULK_TABS: { key: BulkTab; label: string; hint: string }[] = [
  { key: 'imports', label: 'Import history', hint: 'Saved batches' },
  { key: 'api', label: 'API batches', hint: 'WMS · API' },
  { key: 'documents', label: 'Documents', hint: 'Label · invoice · statement' },
  { key: 'trash', label: 'Trash', hint: 'Deleted batches' },
]

/** At-a-glance counts over the whole view (from the server, not the current page). */
function BatchSummaryCards({ summary }: { summary: BulkSummary }) {
  const cards: { label: string; value: number; tone: string }[] = [
    { label: 'Ready to generate', value: summary.readyToGenerate, tone: 'text-[#1f150c]' },
    { label: 'Generating now', value: summary.generating, tone: 'text-sky-700' },
    { label: 'Needs fixes', value: summary.needsFixes, tone: summary.needsFixes ? 'text-rose-700' : 'text-[#1f150c]' },
    { label: 'Completed this week', value: summary.completedThisWeek, tone: 'text-emerald-700' },
  ]
  return (
    <div data-testid="bulk-summary" className="grid grid-cols-2 gap-2 sm:grid-cols-4">
      {cards.map((c) => (
        <div key={c.label} className="rounded-xl border border-[#efe7d6] bg-[#fcfaf5] px-3 py-2.5">
          <p className="text-[11.5px] font-semibold text-[#6b5c42]">{c.label}</p>
          <p className={`text-[20px] font-semibold tabular-nums ${c.tone}`}>{c.value}</p>
        </div>
      ))}
    </div>
  )
}

/** A row's serviceType error for a ship via code with no carrier service mapped. */
const UNMAPPED_SHIP_VIA = /serviceType '([^']+)' is (?:not mapped|mapped, but not)/

/**
 * The Bulk Mailer tabs. A single highlight slides to the chosen tab (moved
 * with a transform — no re-layout), and the arrow / Home / End keys move
 * between tabs as a tablist should.
 */
export function BulkTabBar({ active, onSelect }: { active: BulkTab; onSelect: (tab: BulkTab) => void }) {
  const listRef = useRef<HTMLDivElement>(null)
  const pillRef = useRef<HTMLSpanElement>(null)

  useLayoutEffect(() => {
    const list = listRef.current
    const pill = pillRef.current
    if (!list || !pill) return
    const place = () => {
      const tab = list.querySelector<HTMLElement>(`[data-tab="${active}"]`)
      if (!tab) return
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
    onSelect(BULK_TABS[next].key)
    listRef.current?.querySelector<HTMLElement>(`[data-tab="${BULK_TABS[next].key}"]`)?.focus()
  }

  return (
    <div
      ref={listRef}
      role="tablist"
      aria-label="Bulk Mailer"
      onKeyDown={onKeyDown}
      className="relative flex flex-wrap items-center gap-1 rounded-xl border border-[#e3d9c4] bg-[#f4eede]/60 p-1"
    >
      <span
        ref={pillRef}
        aria-hidden="true"
        className="bulk-tab-pill pointer-events-none absolute left-0 top-0 rounded-lg bg-white shadow-sm ring-1 ring-[#e3d9c4]"
      />
      {BULK_TABS.map((t) => {
        const selected = active === t.key
        return (
          <button
            key={t.key}
            type="button"
            role="tab"
            data-tab={t.key}
            aria-selected={selected}
            tabIndex={selected ? 0 : -1}
            onClick={() => onSelect(t.key)}
            className={`relative z-[1] inline-flex items-baseline gap-1.5 rounded-lg px-3.5 py-2 text-[13px] font-semibold outline-none transition-colors duration-200 focus-visible:ring-2 focus-visible:ring-[#412d15]/40 ${
              selected ? 'text-[#1f150c]' : 'text-[#6b5c42] hover:text-[#1f150c]'
            }`}
          >
            {t.label}
            <span className={`hidden text-[9.5px] font-medium uppercase tracking-[0.06em] transition-colors duration-200 sm:inline ${selected ? 'text-[#8a7a5a]' : 'text-[#b6a684]'}`}>
              {t.hint}
            </span>
          </button>
        )
      })}
    </div>
  )
}

/** What a tab shows while its first answer is on the way — the shape of the list, not a bare "Loading…". */
function BatchListSkeleton({ withCards }: { withCards: boolean }) {
  return (
    <div data-testid="batch-list-skeleton" aria-busy="true" aria-label="Loading batches" className="space-y-4">
      {withCards ? (
        <div className="grid grid-cols-2 gap-2 sm:grid-cols-4">
          {[0, 1, 2, 3].map((i) => (
            <div key={i} className="h-[66px] animate-pulse rounded-xl border border-[#efe7d6] bg-[#f6f1e6]" />
          ))}
        </div>
      ) : null}
      <div className="space-y-2 rounded-2xl border border-[#efe7d6] bg-white p-3">
        <div className="flex flex-wrap gap-1.5">
          {[72, 64, 88, 96, 80].map((w, i) => (
            <div key={i} className="h-7 animate-pulse rounded-full bg-[#f2ecdf]" style={{ width: w }} />
          ))}
        </div>
        <div className="h-9 animate-pulse rounded-xl bg-[#f6f1e6]" />
      </div>
      <div className="space-y-2 rounded-2xl border border-slate-200 bg-white p-3 shadow-sm">
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
