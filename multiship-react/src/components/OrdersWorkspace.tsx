import { lazy, Suspense, useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import type { ColumnDef, SortingState } from '@tanstack/react-table'
import { notify } from '../utils/notify'
// Espresso/cream button tokens — shared across the app (see components/ui/buttons).
import { BTN_PRIMARY, BTN_GHOST_SM, BTN_PRIMARY_SM } from './ui/buttons'
import IssuesInfoIcon from './ui/IssuesInfoIcon'
import {
  FiCheckCircle,
  FiEdit3,
  FiEye,
  FiFileText,
  FiFilter,
  FiRefreshCw,
  FiCalendar,
  FiPackage,
  FiTruck,
  FiX,
  FiXCircle,
  FiZap,
  FiPlus,
  FiDatabase,
  FiHash,
  FiUser,
  FiMapPin,
  FiTag,
  FiSliders,
  FiSlash,
  FiCopy,
} from 'react-icons/fi'
import { ApiError, isAbortError } from '../api/apiClient'
import { normalizeCarrierCode } from '../utils/carrierUtils'
import { orderService, type Order, type QueueStats } from '../api/orderService'
import { summarizeCarrierError } from '../utils/carrierErrorMap'
import { clientService } from '../api/clientService'
import type { CarrierAccountRef, OrderAccountResolution } from '../api/accountRefService'
import AccountScenarioBadge from './workspace/AccountScenarioBadge'
// PR #555 — inline compact status dot+label supersedes OrderStatusBadge.
// import OrderStatusBadge from './workspace/OrderStatusBadge'
import AdvancedDataTable from './workspace/AdvancedDataTable'
// Bundle audit #434 follow-up: modals are only rendered behind
// `xxxOpen ?` guards, so React.lazy defers each chunk fetch until an
// operator actually opens the modal. Fallback is null — the modal
// itself is the visible transition so a spinner in its place would
// double up. First-open cost is one small RTT for the modal's chunk;
// cached thereafter.
const FillCarrierDetailsModal = lazy(() => import('./modals/FillCarrierDetailsModal'))
const AccountPickerModal = lazy(() => import('./modals/AccountPickerModal'))
const OrderDetailsModal = lazy(() => import('./modals/OrderDetailsModal'))
const TrackingTimelineModal = lazy(() => import('./tracking/TrackingTimelineModal'))
const SchedulePickupModal = lazy(() => import('./modals/SchedulePickupModal'))
const CloseOutModal = lazy(() => import('./modals/CloseOutModal'))
const BulkLabelModal = lazy(() => import('./modals/BulkLabelModal'))
const MultiWarehouseSplitModal = lazy(() => import('./modals/MultiWarehouseSplitModal'))
const OrderImportModal = lazy(() => import('./modals/OrderImportModal'))

type View = 'all' | 'ready' | 'details' | 'client' | 'choose' | 'failed' | 'generated'

/** "City, State" for the destination column — omits a blank/null state so an
 *  international address with no province doesn't render "City, null". */
const formatDestination = (city?: string | null, state?: string | null): string =>
  [city, state].map((v) => (v ?? '').trim()).filter(Boolean).join(', ')

/** Server-side query behind each view of the workspace. */
const VIEW_QUERY: Record<View, { status?: string; resolution?: string; defaultDirection: 'ASC' | 'DESC' }> = {
  all: { defaultDirection: 'DESC' },
  ready: { status: 'PENDING', resolution: 'READY', defaultDirection: 'DESC' },
  details: { status: 'PENDING', resolution: 'NEEDS_DETAILS', defaultDirection: 'DESC' },
  client: { status: 'PENDING', resolution: 'CLIENT_MISSING', defaultDirection: 'DESC' },
  choose: { status: 'PENDING', resolution: 'CHOOSE_ACCOUNT', defaultDirection: 'DESC' },
  failed: { status: 'ERROR', defaultDirection: 'DESC' },
  generated: { status: 'GENERATED', defaultDirection: 'DESC' },
}

/** How many ready orders one "Generate all ready" run will pull from the server. */
const BULK_FETCH_PAGES = 5
const BULK_FETCH_SIZE = 100

/**
 * Shared row-action styling. Every primary action shares one fixed width so the
 * Actions column lines up cleanly row-to-row instead of looking ragged. Colours
 * stay in the espresso family: solid espresso = "proceed / generate", espresso
 * outline = "needs your input", amber outline = "recover from error".
 */
const ACTION_BASE =
  'inline-flex min-w-[96px] items-center justify-center gap-1.5 whitespace-nowrap rounded-lg px-3 py-1.5 text-[11px] font-semibold transition disabled:cursor-not-allowed'
const ACTION_SOLID =
  'bg-[#1f150c] text-[#f4eede] shadow-sm ring-1 ring-inset ring-white/10 hover:bg-[#412d15] disabled:bg-[#dcd4c4] disabled:text-white disabled:shadow-none disabled:ring-0'
const ACTION_OUTLINE =
  'border border-[#d8cbb0] bg-white text-[#412d15] hover:border-[#412d15] hover:bg-[#faf7f0] disabled:opacity-50'
const ACTION_RETRY =
  'border border-amber-300 bg-amber-50 text-amber-800 hover:border-amber-400 hover:bg-amber-100 disabled:opacity-50'

const relativeTime = (value?: string | null) => {
  if (!value) return null
  const then = new Date(value).getTime()
  if (Number.isNaN(then)) return value
  const mins = Math.max(0, Math.round((Date.now() - then) / 60000))
  if (mins < 1) return 'just now'
  if (mins < 60) return `${mins} min ago`
  const hours = Math.round(mins / 60)
  if (hours < 24) return `${hours}h ago`
  return `${Math.round(hours / 24)}d ago`
}

/**
 * The single Orders & Labels workspace: browse every order, work the
 * generation pipeline (ready / needs details / no client / failed), and
 * review the archive — all server-side filtered against the unified list.
 */
export default function OrdersWorkspace() {
  const navigate = useNavigate()
  const location = useLocation()

  const [stats, setStats] = useState<QueueStats | null>(null)
  const [rows, setRows] = useState<Order[]>([])
  const [totalPages, setTotalPages] = useState(1)
  const [loading, setLoading] = useState(true)

  const [view, setView] = useState<View>('all')
  const [query, setQuery] = useState('')
  const [debouncedQuery, setDebouncedQuery] = useState('')
  const [clientFilter, setClientFilter] = useState('')
  const [dateFrom, setDateFrom] = useState('')
  const [dateTo, setDateTo] = useState('')
  // Order source filter: '' (all) | MANUAL | BULK | API | WMS | ERP.
  const [sourceFilter, setSourceFilter] = useState('')
  const [channelFilter, setChannelFilter] = useState('')
  const [clientCodes, setClientCodes] = useState<string[]>([])
  // Sprint 51 migration — sort is owned by the shared AdvancedDataTable now.
  // sortBy / sortDirection remain the fetch-effect inputs (derived below).
  const [sorting, setSorting] = useState<SortingState>([{ id: 'orderNo', desc: true }])
  const sortBy = sorting[0]?.id ?? 'orderNo'
  const sortDirection: 'ASC' | 'DESC' = sorting[0]?.desc ? 'DESC' : 'ASC'
  const [showFilters, setShowFilters] = useState(false)
  const filtersRef = useRef<HTMLDivElement>(null)
  const emptyColumnFilters = { orderNo: '', customer: '', city: '', status: '', tracking: '' }
  const [columnFilters, setColumnFilters] = useState(emptyColumnFilters)
  const [debouncedFilters, setDebouncedFilters] = useState(emptyColumnFilters)
  const [page, setPage] = useState(1)
  const [pageSize, setPageSize] = useState(10)
  const [reloadToken, setReloadToken] = useState(0)
  /**
   * Selection state (2026-09-13 overhaul).
   *
   *   mode='individual' → {@link selectionSet} is the SELECTED order_nos.
   *   mode='all-filtered' → {@link selectionSet} is the EXCLUDED order_nos
   *     (Excel/Sheets pattern: "all M matching the filter EXCEPT these N").
   *     {@link allFilteredIds} holds the full set of matching ids fetched
   *     once from GET /orders/ids; the filter signature it was fetched
   *     for is stored so a filter/search change invalidates the cache.
   *
   * Persistence: selection survives page navigation (Gmail behaviour).
   * A filter/search change clears the selection (see the effect below).
   */
  type SelectionMode = 'individual' | 'all-filtered'
  const [selectionMode, setSelectionMode] = useState<SelectionMode>('individual')
  const [selectionSet, setSelectionSet] = useState<Set<number>>(new Set())
  const [allFilteredIds, setAllFilteredIds] = useState<number[] | null>(null)
  const [allFilteredSignature, setAllFilteredSignature] = useState<string | null>(null)
  // Range-select anchor — the last non-shift-click index within the
  // current page's rows. Shift-click extends selection from the anchor
  // to the current index (inclusive), mirroring Gmail/GitHub muscle
  // memory. Reset whenever the underlying rows array changes.
  const lastClickedIndexRef = useRef<number | null>(null)
  const [generatingOrderNos, setGeneratingOrderNos] = useState<number[]>([])
  const [bulkProgress, setBulkProgress] = useState<{ done: number; total: number } | null>(null)
  const [fillDetailsTarget, setFillDetailsTarget] = useState<{
    orderNo: number
    resolution: OrderAccountResolution
  } | null>(null)
  const [pickerTarget, setPickerTarget] = useState<Order | null>(null)
  const [pickerCarrier, setPickerCarrier] = useState<string | null>(null)
  const [pickerSuggested, setPickerSuggested] = useState<string | null>(null)
  const [detailsOrderNo, setDetailsOrderNo] = useState<number | null>(null)
  // Sprint 23 — Track column: opens the TrackingTimelineModal with live
  // scan events from the connector's authenticated tracking API.
  const [trackingOrderNo, setTrackingOrderNo] = useState<number | null>(null)
  // Sprint 30 — Void action: currently-voiding orderNo (for spinner);
  // handler confirms with the user before calling the carrier.
  const [voidingOrderNo, setVoidingOrderNo] = useState<number | null>(null)
  // Sprint 33 — schedule pickup modal (bulk action from the header).
  const [pickupOpen, setPickupOpen] = useState(false)
  // Sprint 34 — end-of-day close-out modal.
  const [closeOutOpen, setCloseOutOpen] = useState(false)
  // Sprint 37 — bulk-label modal.
  const [bulkLabelOpen, setBulkLabelOpen] = useState(false)
  const [splitOpen, setSplitOpen] = useState(false)
  // Sprint 40 — CSV / XLSX import modal.
  const [importOpen, setImportOpen] = useState(false)

  // The header's global search lands here as /orders?q=…
  useEffect(() => {
    const params = new URLSearchParams(location.search)
    const q = params.get('q')
    if (q) {
      // eslint-disable-next-line react-hooks/set-state-in-effect -- sync workspace state from URL query params on nav; user-driven navigation trigger, not derivable at render
      setQuery(q)
      setView('all')
    }
    // Dashboard deep-links land on a specific pipeline tab (?view=ready…).
    const v = params.get('view')
    if (v && v in VIEW_QUERY) {
      setView(v as View)
    }
  }, [location.search])

  useEffect(() => {
    const timer = setTimeout(() => setDebouncedQuery(query.trim()), 350)
    return () => clearTimeout(timer)
  }, [query])

  useEffect(() => {
    const timer = setTimeout(() => setDebouncedFilters(columnFilters), 350)
    return () => clearTimeout(timer)
  }, [columnFilters])

  // Close the Filters popover on outside click / Escape.
  useEffect(() => {
    if (!showFilters) return
    const onDocClick = (e: MouseEvent) => {
      if (!filtersRef.current?.contains(e.target as Node)) setShowFilters(false)
    }
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setShowFilters(false)
    }
    document.addEventListener('mousedown', onDocClick)
    document.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('mousedown', onDocClick)
      document.removeEventListener('keydown', onKey)
    }
  }, [showFilters])

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- snap paging back to 1 on filter/view change; user-input-driven, not derivable at render
    setPage(1)
  }, [view, debouncedQuery, pageSize, clientFilter, dateFrom, dateTo, sortBy, sortDirection, debouncedFilters])

  /**
   * Signature of the current filter set — used both to invalidate the
   * cached all-filtered id list AND to trigger the "clear selection on
   * filter change" effect below. Page and sort are DELIBERATELY
   * excluded: paging or reordering doesn't change WHAT the operator
   * picked, only HOW it's presented. The 2026-09-13 overhaul made
   * selection survive page navigation (Gmail behaviour).
   */
  const filterSignature = useMemo(() => JSON.stringify({
    view, q: debouncedQuery, client: clientFilter, from: dateFrom, to: dateTo,
    filters: debouncedFilters, source: sourceFilter, channel: channelFilter,
  }), [view, debouncedQuery, clientFilter, dateFrom, dateTo, debouncedFilters, sourceFilter, channelFilter])

  // Filter change → clear selection and invalidate the all-filtered
  // cache. Prior behaviour also cleared on page/pageSize change, which
  // is now DELIBERATELY dropped: paging through results shouldn't
  // discard picks made on page 1.
  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- clear stale selection on filter change; user-input-driven, not derivable at render
    setSelectionMode('individual')
    setSelectionSet(new Set())
    setAllFilteredIds(null)
    setAllFilteredSignature(null)
    lastClickedIndexRef.current = null
  }, [filterSignature])

  // Each view has its own natural direction; reset when switching.
  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- reset sort defaults when the view changes; each view has its own natural sort, cannot be derived at render (would ignore user re-picks)
    setSorting([{ id: 'orderNo', desc: VIEW_QUERY[view].defaultDirection === 'DESC' }])
  }, [view])

  // Known clients feed the filter dropdown (best effort).
  useEffect(() => {
    clientService
      .listClients({ size: 100 })
      .then((response) => setClientCodes((response.data?.content ?? []).map((client) => client.clientCode)))
      // Sprint 51 FE-L3 — log instead of silently swallowing a secondary load.
      .catch((e) => {
        if (!isAbortError(e)) console.debug('[secondary load] listClients', e)
      })
  }, [reloadToken])

  // Tab counts (all tabs at once) — one aggregate query server-side.
  useEffect(() => {
    let cancelled = false
    orderService
      .getQueueStats()
      .then((response) => {
        if (!cancelled && response.data) setStats(response.data)
      })
      .catch(() => {
        /* the rows request surfaces errors; stats are cosmetic counts */
      })
    return () => {
      cancelled = true
    }
  }, [reloadToken])

  // Rows for the active view — server-side filter + search + sort + pagination.
  useEffect(() => {
    let cancelled = false
    const spec = VIEW_QUERY[view]
    // eslint-disable-next-line react-hooks/set-state-in-effect -- flip loading spinner before async paginated order-list fetch
    setLoading(true)

    orderService
      .listOrders({
        status: spec.status ?? (debouncedFilters.status || undefined),
        resolution: spec.resolution,
        search: debouncedQuery || undefined,
        tenantId: clientFilter || undefined,
        customer: debouncedFilters.customer || undefined,
        city: debouncedFilters.city || undefined,
        orderNo: debouncedFilters.orderNo || undefined,
        tracking: debouncedFilters.tracking || undefined,
        createdFrom: dateFrom || undefined,
        createdTo: dateTo || undefined,
        source: sourceFilter || undefined,
        channel: channelFilter || undefined,
        page: page - 1,
        size: pageSize,
        sortBy,
        sortDirection,
        includeResolution: view !== 'generated',
      })
      .then((response) => {
        if (cancelled) return
        setRows(response.data?.content ?? [])
        setTotalPages(Math.max(response.data?.totalPages ?? 1, 1))
      })
      .catch((error) => {
        if (cancelled) return
        notify.apiError(error, 'Failed to load orders.')
        setRows([])
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })

    return () => {
      cancelled = true
    }
  }, [view, page, pageSize, debouncedQuery, clientFilter, dateFrom, dateTo, sourceFilter, channelFilter, sortBy, sortDirection, debouncedFilters, reloadToken])

  const refreshQueues = () => setReloadToken((token) => token + 1)

  /**
   * Sprint 30 — void a label at the carrier. Confirms first (irreversible
   * at the carrier), then calls POST /orders/{n}/void. Refreshes the queue
   * on success so the row moves out of "generated" state.
   */
  /** In-app confirm target — replaces the old native window.confirm(), which
   *  was unstyleable, inconsistent with the app's modal pattern, and blocked
   *  the tab for automation. The modal carries the context that matters:
   *  tracking number, irreversibility, and the refund caveat. */
  const [confirmVoid, setConfirmVoid] = useState<{ orderNo: number; trackingNumber: string; reissue?: boolean; packages?: number } | null>(null)

  // Escape closes the void confirmation (parity with every other modal).
  useEffect(() => {
    if (!confirmVoid) return
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') setConfirmVoid(null) }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [confirmVoid])

  const handleVoid = async (orderNo: number, trackingNumber: string | null) => {
    if (!trackingNumber) return
    setConfirmVoid({ orderNo, trackingNumber })
  }

  /** Edit & reissue: void the live label, then open the pre-filled form to regenerate in place. */
  const handleEditReissue = (orderNo: number, trackingNumber: string | null, packages?: number | null) => {
    if (!trackingNumber) return
    setConfirmVoid({ orderNo, trackingNumber, reissue: true, packages: packages ?? undefined })
  }

  const executeVoid = async (orderNo: number) => {
    const reissue = confirmVoid?.orderNo === orderNo && confirmVoid.reissue === true
    setConfirmVoid(null)
    setVoidingOrderNo(orderNo)
    try {
      const response = await orderService.voidLabel(orderNo)
      const data = response.data
      if (data?.voided || data?.status === 'ALREADY_VOIDED') {
        notify.success(`Order ${orderNo}: ${data.message}`)
        if (reissue) {
          navigate(`/orders/new?fixOrder=${orderNo}`)
          return
        }
        refreshQueues()
      } else {
        notify.error(`Void failed: ${data?.message ?? 'Unknown error.'}`)
      }
    } catch (e) {
      notify.apiError(e, 'Void call failed.')
    } finally {
      setVoidingOrderNo(null)
    }
  }

  const readyCount = stats?.ready ?? 0
  const detailsCount = stats?.needsDetails ?? 0
  const chooseCount = stats?.chooseAccount ?? 0
  const clientMissingCount = stats?.clientMissing ?? 0
  const failedCount = stats?.failed ?? 0
  const generatedCount = stats?.generated ?? 0
  const totalCount = readyCount + detailsCount + chooseCount + clientMissingCount + failedCount + generatedCount

  // Which tabs exist right now (exceptions vanish at zero).
  const tabs = useMemo(() => {
    const list: Array<{ key: View; label: string; count: number; tone: string }> = [
      { key: 'all', label: 'All orders', count: totalCount, tone: 'slate' },
      { key: 'ready', label: 'Ready', count: readyCount, tone: 'emerald' },
    ]
    if (detailsCount) list.push({ key: 'details', label: 'Needs details', count: detailsCount, tone: 'amber' })
    if (clientMissingCount) list.push({ key: 'client', label: 'No client', count: clientMissingCount, tone: 'violet' })
    if (chooseCount) list.push({ key: 'choose', label: 'Pick account', count: chooseCount, tone: 'sky' })
    if (failedCount) list.push({ key: 'failed', label: 'Failed', count: failedCount, tone: 'rose' })
    list.push({ key: 'generated', label: 'Archive', count: generatedCount, tone: 'slate' })
    return list
  }, [totalCount, readyCount, detailsCount, chooseCount, clientMissingCount, failedCount, generatedCount])

  // If the active exception tab empties out, fall back to All orders.
  useEffect(() => {
    if (loading) return
    if (!tabs.some((t) => t.key === view)) {
      // eslint-disable-next-line react-hooks/set-state-in-effect -- fall back to 'all' when the current tab disappears from the count-driven tab list; depends on async count fetch so not derivable at render
      setView('all')
    }
  }, [view, tabs, loading])

  // ===== Selection (Ready / All / Generated tabs; individual + all-filtered) =====
  // 2026-09-13 — widened from Ready-only. Ready powers "Generate
  // selected"; All and Generated power "Void selected" + "Copy order #s".
  // Other tabs (details/client/choose/failed) stay read-only — their
  // rows aren't actionable via bulk in the current workflow set.
  const SELECTABLE_VIEWS: readonly View[] = ['ready', 'all', 'generated']
  const selectionEnabled = SELECTABLE_VIEWS.includes(view)
  const selectableVisible = selectionEnabled ? rows : []

  /**
   * True when the given order_no is currently selected under the
   * active selection mode.
   *   individual → set membership
   *   all-filtered → in the cached all-filtered id list AND NOT in the
   *     exclusion set
   */
  const isOrderSelected = useCallback((orderNo: number): boolean => {
    if (selectionMode === 'all-filtered') {
      if (!allFilteredIds) return false
      if (selectionSet.has(orderNo)) return false
      return allFilteredIds.includes(orderNo)
    }
    return selectionSet.has(orderNo)
  }, [selectionMode, selectionSet, allFilteredIds])

  /**
   * Effective count and materialised id list for the current selection
   * — used by the sticky action bar and the "Generate selected" call.
   * In all-filtered mode this walks the cached id list minus exclusions;
   * otherwise it's just the size of the set.
   */
  const selectedCount = useMemo(() => {
    if (selectionMode === 'all-filtered' && allFilteredIds) {
      return allFilteredIds.length - selectionSet.size
    }
    return selectionSet.size
  }, [selectionMode, selectionSet, allFilteredIds])

  const materialisedSelection = useCallback((): number[] => {
    if (selectionMode === 'all-filtered' && allFilteredIds) {
      return allFilteredIds.filter((id) => !selectionSet.has(id))
    }
    return Array.from(selectionSet)
  }, [selectionMode, selectionSet, allFilteredIds])

  /** All rows visible on the current page are currently selected. */
  const pageAllSelected = selectableVisible.length > 0
        && selectableVisible.every((o) => isOrderSelected(o.orderDetails.orderNo))

  /**
   * Toggle a single row. Records the click index for the shift-click
   * range extension the next click may perform. When called from
   * shift-click ({@link shiftKey}=true), extends the selection from
   * the last-clicked-index anchor to this row's index — same behaviour
   * as Gmail/GitHub.
   */
  const toggleOrder = useCallback((orderNo: number, rowIndex?: number, shiftKey?: boolean) => {
    const rowsSnapshot = selectableVisible
    // Shift-click range extension: only when we have an anchor AND we
    // know the current row's index in the visible list.
    if (shiftKey && lastClickedIndexRef.current != null && rowIndex != null) {
      const anchor = lastClickedIndexRef.current
      const lo = Math.min(anchor, rowIndex)
      const hi = Math.max(anchor, rowIndex)
      // Extend semantics: everything in [lo, hi] gets the SAME state
      // as the anchor row (Gmail behaviour — extending your existing
      // selection, not toggling in place).
      const anchorRow = rowsSnapshot[anchor]
      if (!anchorRow) return
      const anchorSelected = isOrderSelected(anchorRow.orderDetails.orderNo)
      setSelectionSet((cur) => {
        const next = new Set(cur)
        for (let i = lo; i <= hi; i++) {
          const rowOrderNo = rowsSnapshot[i]?.orderDetails.orderNo
          if (rowOrderNo == null) continue
          if (selectionMode === 'all-filtered') {
            // In all-filtered mode, "select" = remove exclusion, "unselect" = add exclusion.
            if (anchorSelected) next.delete(rowOrderNo)
            else next.add(rowOrderNo)
          } else {
            if (anchorSelected) next.add(rowOrderNo)
            else next.delete(rowOrderNo)
          }
        }
        return next
      })
      lastClickedIndexRef.current = rowIndex
      return
    }
    // Plain click: toggle this one row + update the anchor.
    if (rowIndex != null) lastClickedIndexRef.current = rowIndex
    if (selectionMode === 'all-filtered') {
      // Exclusion tracking: click on a currently-selected row adds it
      // to exclusions; click on an excluded row removes the exclusion.
      setSelectionSet((cur) => {
        const next = new Set(cur)
        if (next.has(orderNo)) next.delete(orderNo)
        else next.add(orderNo)
        return next
      })
    } else {
      setSelectionSet((cur) => {
        const next = new Set(cur)
        if (next.has(orderNo)) next.delete(orderNo)
        else next.add(orderNo)
        return next
      })
    }
  }, [selectableVisible, isOrderSelected, selectionMode])

  /** Select every row on the current page (adds to whatever's already
   *  selected in individual mode; no-op in all-filtered mode since
   *  everything's already selected there). */
  const selectPage = useCallback(() => {
    if (selectionMode === 'all-filtered') return
    setSelectionSet((cur) => {
      const next = new Set(cur)
      for (const o of selectableVisible) next.add(o.orderDetails.orderNo)
      return next
    })
  }, [selectableVisible, selectionMode])

  /** Clear all selection state. */
  const clearSelection = useCallback(() => {
    setSelectionMode('individual')
    setSelectionSet(new Set())
    setAllFilteredIds(null)
    setAllFilteredSignature(null)
    lastClickedIndexRef.current = null
  }, [])

  /** Fetch every order id matching the current filter and switch to
   *  all-filtered mode. Cached by filterSignature so repeat clicks are
   *  free until the operator changes a filter. */
  /** Assemble the /orders/ids fetch params for the current view +
   *  filter set. View maps to status/resolution via VIEW_QUERY so
   *  "Select all matching" respects the operator's active tab. */
  const buildIdsParams = useCallback(() => {
    const vq = VIEW_QUERY[view]
    return {
      status: vq.status,
      resolution: vq.resolution,
      tenantId: clientFilter || undefined,
      search: debouncedQuery.trim() || undefined,
      customer: debouncedFilters.customer?.trim() || undefined,
      city: debouncedFilters.city?.trim() || undefined,
      orderNo: debouncedFilters.orderNo?.trim() || undefined,
      tracking: debouncedFilters.tracking?.trim() || undefined,
      createdFrom: dateFrom || undefined,
      createdTo: dateTo || undefined,
      source: sourceFilter || undefined,
      channel: channelFilter || undefined,
    }
  }, [view, clientFilter, debouncedQuery, debouncedFilters, dateFrom, dateTo, sourceFilter, channelFilter])

  const selectAllFiltered = useCallback(async () => {
    try {
      // Reuse cache if the filter signature matches the last fetch.
      let ids = allFilteredIds
      if (!ids || allFilteredSignature !== filterSignature) {
        const res = await orderService.listOrderIds(buildIdsParams())
        ids = res.data ?? []
        setAllFilteredIds(ids)
        setAllFilteredSignature(filterSignature)
      }
      setSelectionMode('all-filtered')
      setSelectionSet(new Set()) // exclusions = none initially
    } catch (e) {
      notify.apiError(e, 'Could not fetch all matching orders.')
    }
  }, [allFilteredIds, allFilteredSignature, filterSignature, buildIdsParams])

  /** Invert selection within the current filter. Fetches the full id
   *  list if not already cached, then computes the complement. */
  const invertSelection = useCallback(async () => {
    let ids = allFilteredIds
    if (!ids || allFilteredSignature !== filterSignature) {
      try {
        const res = await orderService.listOrderIds(buildIdsParams())
        ids = res.data ?? []
        setAllFilteredIds(ids)
        setAllFilteredSignature(filterSignature)
      } catch (e) {
        notify.apiError(e, 'Could not fetch all matching orders to invert.')
        return
      }
    }
    // Materialise current selection then flip.
    const currentlySelected = new Set(materialisedSelection())
    const inverted = ids.filter((id) => !currentlySelected.has(id))
    setSelectionMode('individual')
    setSelectionSet(new Set(inverted))
  }, [allFilteredIds, allFilteredSignature, filterSignature, buildIdsParams, materialisedSelection])

  /** Back-compat alias — accepts either an array or a functional
   *  updater. Existing call sites pre-refactor used both shapes. */
  const setSelectedOrderNos = (
      nosOrUpdater: number[] | ((cur: number[]) => number[]),
  ) => {
    const nos = typeof nosOrUpdater === 'function'
        ? nosOrUpdater(materialisedSelection())
        : nosOrUpdater
    if (nos.length === 0) clearSelection()
    else {
      setSelectionMode('individual')
      setSelectionSet(new Set(nos))
    }
  }
  const selectedOrderNos = useMemo(() => materialisedSelection(), [materialisedSelection])
  const allSelected = pageAllSelected

  const openFillDetails = (orderNo: number, resolution: OrderAccountResolution) =>
    setFillDetailsTarget({ orderNo, resolution })

  /** Pull every READY order from the server (bounded) for "Generate all ready". */
  const fetchAllReadyOrders = useCallback(async (): Promise<Order[]> => {
    const collected: Order[] = []

    for (let serverPage = 0; serverPage < BULK_FETCH_PAGES; serverPage += 1) {
      const response = await orderService.listOrders({
        status: 'PENDING',
        resolution: 'READY',
        page: serverPage,
        size: BULK_FETCH_SIZE,
        sortBy: 'orderNo',
        sortDirection: 'ASC',
        includeResolution: true,
      })

      const content = response.data?.content ?? []
      collected.push(...content)
      if (response.data?.last || !content.length) break
    }

    return collected
  }, [])

  // ----- generation with live progress + optimistic clearing -----
  const generateForOrders = async (ordersToGenerate: Order[]) => {
    const generatable = ordersToGenerate.filter(
      (o) => o.accountResolution?.scenario !== 'NEEDS_DETAILS'
    )
    const needing = ordersToGenerate.length - generatable.length

    if (!generatable.length) {
      if (ordersToGenerate.length === 1) {
        const order = ordersToGenerate[0]
        if (order.accountResolution) {
          openFillDetails(order.orderDetails.orderNo, order.accountResolution)
          return
        }
      }
      notify.info(`${needing} selected orders need carrier details first.`)
      return
    }

    const orderNos = generatable.map((o) => o.orderDetails.orderNo)
    setGeneratingOrderNos((cur) => [...new Set([...cur, ...orderNos])])
    if (orderNos.length > 1) setBulkProgress({ done: 0, total: orderNos.length })

    let ok = 0
    let done = 0
    const failures: string[] = []
    const needsDetailsHits: Array<{ orderNo: number; resolution: OrderAccountResolution }> = []

    await Promise.all(
      orderNos.map(async (orderNo) => {
        try {
          await orderService.generateLabel(orderNo)
          // Optimistic: clear the row from pipeline lists immediately.
          ok += 1
          if (view !== 'all') {
            setRows((cur) => cur.filter((o) => o.orderDetails.orderNo !== orderNo))
          }
        } catch (error) {
          // NEEDS_CARRIER_DETAILS (422): business precondition — the order
          // needs carrier details first; prefill rides in the error payload.
          const data = error instanceof ApiError ? error.payload?.data : null
          if (error instanceof ApiError && (error.errorCode === 'NEEDS_CARRIER_DETAILS' || (error.status === 422 && data?.needsDetails))) {
            if (orderNos.length === 1) {
              needsDetailsHits.push({
                orderNo,
                resolution: {
                  orderNo,
                  scenario: 'NEEDS_DETAILS',
                  carrierCode: data.prefillCarrierCode || null,
                  accountNumber: data.prefillAccountNumber || null,
                  accountName: null,
                  environment: data.prefillEnvironment || null,
                  missingFields: data.missingFields || null,
                  prefillClientId: data.prefillClientId || null,
                },
              })
            }
            return
          }

          // ACCOUNT_SELECTION_REQUIRED (422): nothing resolved — open the
          // account picker for a single generation.
          if (error instanceof ApiError && error.errorCode === 'ACCOUNT_SELECTION_REQUIRED') {
            if (orderNos.length === 1) {
              const target = generatable.find((o) => o.orderDetails.orderNo === orderNo)
              if (target) setPickerTarget(target)
              const payload = error.payload as { carrierCode?: string | null; prefillAccountNumber?: string | null } | undefined
              setPickerCarrier(payload?.carrierCode ?? null)
              setPickerSuggested(payload?.prefillAccountNumber ?? null)
            }
            return
          }

          // CUSTOMS_REQUIRED (422): international shipment whose client has no
          // Importer/Broker profile for the destination — a one-time setup on
          // the Clients page; nothing to do per-order.
          if (error instanceof ApiError && error.errorCode === 'CUSTOMS_REQUIRED') {
            if (orderNos.length === 1) {
              notify.error(error.message || 'Set up this client’s Importer/Broker for the destination country (Settings › Clients).')
            }
            return
          }

          // CLIENT_NOT_FOUND (422): the order's client is unregistered —
          // open the add-client form prefilled with the code.
          if (error instanceof ApiError && error.errorCode === 'CLIENT_NOT_FOUND') {
            if (orderNos.length === 1 && data?.clientCode) {
              // Navigate to the new-client page prefilled with the missing code
              // (was ClientEditorModal with lockedCode before the page rewrite).
              navigate(`/settings/clients/new?code=${encodeURIComponent(data.clientCode)}`)
            }
            return
          }

          // LABEL_ALREADY_GENERATED (409): treat like a success and let the
          // refresh reconcile it into the archive.
          if (error instanceof ApiError && (error.errorCode === 'LABEL_ALREADY_GENERATED' || error.status === 409)) {
            ok += 1
            if (view !== 'all') {
              setRows((cur) => cur.filter((o) => o.orderDetails.orderNo !== orderNo))
            }
            return
          }

          failures.push(`#${orderNo}: ${error instanceof Error ? error.message : 'generation failed'}`)
        } finally {
          done += 1
          if (orderNos.length > 1) setBulkProgress({ done, total: orderNos.length })
        }
      })
    )

    if (failures.length) {
      // Show up to 3 failure reasons directly; the remainder is summarised
      // so the operator sees more than the first row's error without an
      // overwhelming toast. Full detail still lives in the Failed tab.
      const shown = failures.slice(0, 3).join('; ')
      const extra = failures.length > 3 ? ` (+${failures.length - 3} more)` : ''
      notify.error(`${ok} generated, ${failures.length} failed — ${shown}${extra}. See the Failed tab.`)
    } else if (ok) {
      notify.success(`${ok} label${ok === 1 ? '' : 's'} generated.`)
    }
    if (needing) notify.info(`${needing} orders still need carrier details.`)
    if (needsDetailsHits[0]) openFillDetails(needsDetailsHits[0].orderNo, needsDetailsHits[0].resolution)

    setSelectedOrderNos((cur) => cur.filter((n) => !orderNos.includes(n)))
    setBulkProgress(null)
    setGeneratingOrderNos((cur) => cur.filter((n) => !orderNos.includes(n)))

    // A single successful generation jumps straight to the printable label;
    // bulk runs stay on the queue to keep working.
    if (orderNos.length === 1 && ok === 1 && !failures.length) {
      navigate(`/label/${orderNos[0]}`)
      return
    }

    refreshQueues()
  }

  /** Label shown next to the bulk-progress bar. Reset by
   *  generateForOrders / voidSelected before they set bulkProgress. */
  const [bulkProgressLabel, setBulkProgressLabel] = useState('Generating')

  /**
   * Void every LABELLED order in the current selection. Skips PENDING /
   * VOIDED / ERROR rows — voiding an already-voided or never-labelled
   * order is a carrier no-op (or 4xx). Reports success / skip / failure
   * counts. Reuses bulkProgress for the sticky-bar bar.
   */
  const voidSelected = useCallback(async () => {
    const selected = materialisedSelection()
    if (!selected.length) return
    // Filter to rows we KNOW are voidable — for on-page rows we check
    // labelDetails.status; off-page rows are attempted optimistically
    // (backend returns a clear 4xx for non-voidable rows, which we log
    // as skipped so the operator sees the counts).
    const rowByOrderNo = new Map(rows.map((o) => [o.orderDetails.orderNo, o] as const))
    const targets = selected.filter((no) => {
      const r = rowByOrderNo.get(no)
      if (!r) return true // off-page — attempt optimistically
      const st = (r.labelDetails.status || 'PENDING').toUpperCase()
      return st === 'GENERATED'
    })
    if (!targets.length) {
      notify.info('Nothing to void — the selection has no labelled orders.')
      return
    }
    if (!window.confirm(`Void ${targets.length} label(s)? This calls the carrier's void API for each; already-voided rows are skipped silently.`)) {
      return
    }
    setBulkProgressLabel('Voiding')
    setBulkProgress({ done: 0, total: targets.length })
    let ok = 0
    let skipped = 0
    const failures: string[] = []
    await Promise.all(targets.map(async (orderNo) => {
      try {
        await orderService.voidLabel(orderNo)
        ok += 1
      } catch (e) {
        // 4xx typically means "not voidable" — track as skip rather than error.
        if (e instanceof ApiError && e.status >= 400 && e.status < 500) skipped += 1
        else failures.push(`#${orderNo}`)
      } finally {
        setBulkProgress((cur) => cur ? { ...cur, done: cur.done + 1 } : cur)
      }
    }))
    setBulkProgress(null)
    setBulkProgressLabel('Generating')
    const parts: string[] = []
    if (ok) parts.push(`${ok} voided`)
    if (skipped) parts.push(`${skipped} not voidable`)
    if (failures.length) parts.push(`${failures.length} failed (${failures.slice(0, 5).join(', ')}${failures.length > 5 ? '…' : ''})`)
    notify.info(parts.length ? parts.join(' · ') : 'No changes.')
    setReloadToken((n) => n + 1)
    clearSelection()
  }, [materialisedSelection, rows, clearSelection])

  /** Copy the current selection's order numbers to the clipboard, one
   *  per line. Small utility that saves the operator a manual scrape
   *  when they need to send the list to accounting / support / CSV. */
  const copySelectedOrderNos = useCallback(async () => {
    const selected = materialisedSelection()
    if (!selected.length) return
    const text = selected.join('\n')
    try {
      await navigator.clipboard.writeText(text)
      notify.info(`${selected.length} order number(s) copied to clipboard.`)
    } catch {
      notify.info('Clipboard access blocked — check browser permissions.')
    }
  }, [materialisedSelection])

  const generateAllReady = async () => {
    const readyOrders = await fetchAllReadyOrders()

    if (!readyOrders.length) {
      notify.info('The ready queue is already clear.')
      return
    }

    if (readyCount > readyOrders.length) {
      notify.info(`Generating the first ${readyOrders.length} of ${readyCount} ready orders — run again for the rest.`)
    }

    await generateForOrders(readyOrders)
  }

  const retryAfterDetailsSaved = async (orderNo: number) => {
    setGeneratingOrderNos((cur) => [...new Set([...cur, orderNo])])
    let generated = false
    try {
      await orderService.generateLabel(orderNo)
      notify.success(`Label generated for order #${orderNo}.`)
      setRows((cur) => cur.filter((o) => o.orderDetails.orderNo !== orderNo))
      generated = true
    } catch (error) {
      if (error instanceof ApiError && error.errorCode === 'NEEDS_CARRIER_DETAILS') {
        notify.error('The account is still incomplete — check the credentials.')
      } else if (error instanceof ApiError && error.errorCode === 'NO_DEFAULT_ACCOUNT') {
        notify.error('No account could be resolved — ask an admin to set a company default on the Carrier page.')
      } else if (error instanceof ApiError && error.errorCode === 'CUSTOMS_REQUIRED') {
        notify.error(error.message || 'Set up this client’s Importer/Broker for the destination country (Settings › Clients).')
      } else if (error instanceof ApiError && (error.errorCode === 'LABEL_ALREADY_GENERATED' || error.status === 409)) {
        setRows((cur) => cur.filter((o) => o.orderDetails.orderNo !== orderNo))
        generated = true
      } else {
        notify.apiError(error, `Order #${orderNo} failed — see the Failed tab.`)
      }
    } finally {
      setGeneratingOrderNos((cur) => cur.filter((n) => n !== orderNo))
    }

    if (generated) {
      navigate(`/label/${orderNo}`)
      return
    }
    refreshQueues()
  }

  const generateWithAccount = async (orderNo: number, account: CarrierAccountRef) => {
    setPickerTarget(null)
    setGeneratingOrderNos((cur) => [...new Set([...cur, orderNo])])
    try {
      await orderService.generateLabel(orderNo, account.id)
      notify.success(`Label generated for #${orderNo} via ${account.accountNumber}.`)
      navigate(`/label/${orderNo}`)
      return
    } catch (error) {
      if (error instanceof ApiError && (error.errorCode === 'LABEL_ALREADY_GENERATED' || error.status === 409)) {
        navigate(`/label/${orderNo}`)
        return
      }
      notify.apiError(error, `Order #${orderNo} failed — see the Failed tab.`)
    } finally {
      setGeneratingOrderNos((cur) => cur.filter((n) => n !== orderNo))
    }
    refreshQueues()
  }

  /** State-appropriate primary action for a row (used on All + pipeline tabs). */
  const renderPrimaryAction = (order: Order) => {
    const orderNo = order.orderDetails.orderNo
    const status = (order.labelDetails.status || 'PENDING').toUpperCase()
    const resolution = order.accountResolution ?? null
    const isGenerating = generatingOrderNos.includes(orderNo)

    if (status === 'GENERATED') {
      return (
        <span className="inline-flex items-center gap-1">
          <button
            type="button"
            onClick={() => navigate(`/label/${orderNo}`)}
            className={`${ACTION_BASE} ${ACTION_SOLID}`}
          >
            <FiEye className="h-3 w-3" />
            View Label
          </button>
          {/* Correct a labelled order without re-keying: void the label, then
              the same order reopens pre-filled and regenerates in place
              (regenerate accepts a VOIDED order; it refuses a live one). */}
          <button
            type="button"
            onClick={() => handleEditReissue(orderNo, order.labelDetails.trackingNumber ?? null, order.orderDetails.packageCount ?? null)}
            disabled={voidingOrderNo === orderNo}
            title="Void this label and reopen the order pre-filled to fix and regenerate it"
            className={`${ACTION_BASE} ${ACTION_RETRY}`}
          >
            <FiEdit3 className="h-3 w-3" />
            Edit &amp; reissue
          </button>
        </span>
      )
    }

    if (status === 'ERROR') {
      // Edit → open the shipment form pre-filled with this order's data + the
      // carrier error, so the operator corrects it and regenerates IN PLACE.
      // (The old "Retry" re-ran from stored data and could bounce to a Settings
      // page when the cause was a missing client / customs profile.)
      return (
        <button
          type="button"
          onClick={() => navigate(`/orders/new?fixOrder=${orderNo}`)}
          className={`${ACTION_BASE} ${ACTION_RETRY}`}
        >
          <FiEdit3 className="h-3 w-3" />
          Edit
        </button>
      )
    }

    if (resolution?.scenario === 'CHOOSE_ACCOUNT') {
      return (
        <button
          type="button"
          onClick={() => {
            setPickerTarget(order)
            setPickerCarrier(resolution?.carrierCode ?? null)
            setPickerSuggested(resolution?.accountNumber ?? null)
          }}
          disabled={isGenerating}
          className={`${ACTION_BASE} ${ACTION_OUTLINE}`}
        >
          <FiEdit3 className="h-3 w-3" />
          {isGenerating ? 'Generating…' : 'Choose Account'}
        </button>
      )
    }

    if (resolution?.scenario === 'CLIENT_MISSING') {
      return (
        <button
          type="button"
          onClick={() => navigate(`/settings/clients/new?code=${encodeURIComponent(order.orderDetails.customerCode)}`)}
          className={`${ACTION_BASE} ${ACTION_OUTLINE}`}
        >
          <FiEdit3 className="h-3 w-3" />
          Add Client
        </button>
      )
    }

    if (resolution?.scenario === 'NEEDS_DETAILS') {
      return (
        <button
          type="button"
          onClick={() => openFillDetails(orderNo, resolution)}
          disabled={isGenerating}
          className={`${ACTION_BASE} ${ACTION_OUTLINE}`}
        >
          <FiEdit3 className="h-3 w-3" />
          Fill Details
        </button>
      )
    }

    if (status === 'VOIDED') {
      // The label was cancelled: the way back is the reissue form (adjust,
      // then regenerate under the same number, with the void on its history)
      // — not a bare re-generate through the account chooser.
      return (
        <button
          type="button"
          onClick={() => navigate(`/orders/new?fixOrder=${orderNo}`)}
          className={`${ACTION_BASE} ${ACTION_RETRY}`}
          title="Reopen this order pre-filled and regenerate a new label"
        >
          <FiEdit3 className="h-3 w-3" />
          Reissue label
        </button>
      )
    }

    return (
      <button
        type="button"
        onClick={() => {
          void generateForOrders([order])
        }}
        disabled={isGenerating || !resolution}
        className={`${ACTION_BASE} ${ACTION_SOLID}`}
      >
        {isGenerating ? 'Generating…' : 'Generate Label'}
      </button>
    )
  }

  const setColumnFilter = (key: keyof typeof emptyColumnFilters) => (value: string) =>
    setColumnFilters((cur) => ({ ...cur, [key]: value }))

  const activeFilterCount =
    Object.values(columnFilters).filter(Boolean).length + (dateFrom ? 1 : 0) + (dateTo ? 1 : 0)
  const clearColumnFilters = () => {
    setColumnFilters(emptyColumnFilters)
    setDateFrom('')
    setDateTo('')
  }

  const busy = generatingOrderNos.length > 0
  const dotTone: Record<string, string> = {
    emerald: 'bg-emerald-500',
    amber: 'bg-amber-400',
    rose: 'bg-rose-500',
    violet: 'bg-violet-500',
    sky: 'bg-sky-400',
  }

  // ── Advanced filter panel (shown when the Filters button is toggled) ────────
  const advInputCls =
    'w-full rounded-lg border border-[#e3d9c4] bg-white px-2.5 py-1 text-[12px] font-medium text-[#1f150c] outline-none transition placeholder:text-[#b6a684] focus:border-[#cdbf9f] focus:ring-2 focus:ring-[#f0e9d8]'
  /** A labeled advanced-filter field: mono uppercase caption + icon, then control.
   *  Full width so fields stack one per row in the popover. */
  const advField = (icon: ReactNode, label: string, control: ReactNode) => (
    <label className="block w-full">
      <span className="mb-1 flex items-center gap-1 font-mono text-[8.5px] font-bold uppercase tracking-[0.12em] text-[#a1906d]">
        <span className="text-[#cdbf9f]">{icon}</span>
        {label}
      </span>
      {control}
    </label>
  )

  const showStatusColumn = view === 'all'
  // PR #556 — Tracking column now visible on 'all' and 'generated' tabs.
  // Pre-fix: gated to 'generated' only, so the default landing view (All)
  // never rendered a Track column even for orders that had tracking. The
  // 'all' view mixes PENDING/GENERATED/ERROR/VOIDED rows; PENDING rows will
  // just show a "—" in the chip cell (guarded in the cell renderer). No
  // point exposing on Ready/Details/Client/Choose since those are PENDING-
  // resolution filters and no tracking exists yet.
  const showTracking = view === 'all' || view === 'generated'

  const formatCreated = (value?: string | null) =>
    value
      ? new Date(value).toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' })
      : '—'

  // PR #555 — auto-hide low-signal columns (Ref #, Batch) when every row on
  // the current page has an empty value. Consumed as `initialHiddenColumns`
  // on <AdvancedDataTable> so tenants that don't feed these fields don't see
  // a permanently-dashed column. This runs on data change but only affects
  // FIRST-load default visibility; once a user toggles the column menu,
  // their preference persists in localStorage and wins over this default.
  const autoHiddenColumns = useMemo<string[]>(() => {
    if (!rows.length) return []
    const hidden: string[] = []
    if (rows.every((r) => !r.orderDetails.refOrderNumber)) hidden.push('refOrderNumber')
    if (rows.every((r) => r.orderDetails.batchId == null)) hidden.push('batchId')
    return hidden
  }, [rows])

  // ── Sprint 51 migration — column defs feed into the shared AdvancedDataTable.
  // Column `id`s that match sortKey names (`orderNo`, `customer`, `city`,
  // `status`, `createdDate`, `tracking`, `generatedAt`) let TanStack's sorting
  // state map directly onto the server-side sort params.
  const columns = useMemo<ColumnDef<Order>[]>(() => {
    const defs: ColumnDef<Order>[] = []

    if (selectionEnabled) {
      defs.push({
        id: 'select',
        header: () => {
          // Header now toggles PAGE selection only. Escalation to
          // "all M matching the filter" happens via the Gmail-style
          // banner rendered below the table.
          const selectable = selectableVisible
          return (
            <input
              type="checkbox"
              aria-label={pageAllSelected ? 'Deselect this page' : 'Select this page'}
              checked={pageAllSelected}
              onChange={() => {
                if (pageAllSelected) {
                  // Remove page's rows from selection (works for both modes).
                  if (selectionMode === 'all-filtered') {
                    setSelectionSet((cur) => {
                      const next = new Set(cur)
                      for (const o of selectable) next.add(o.orderDetails.orderNo)
                      return next
                    })
                  } else {
                    setSelectionSet((cur) => {
                      const next = new Set(cur)
                      for (const o of selectable) next.delete(o.orderDetails.orderNo)
                      return next
                    })
                  }
                } else {
                  selectPage()
                }
              }}
              className="h-4 w-4 rounded border-[#cdbf9f] text-[#1f150c] focus:ring-[#e3d9c4]"
            />
          )
        },
        enableSorting: false,
        cell: ({ row }) => (
          <input
            type="checkbox"
            aria-label={`Select order ${row.original.orderDetails.orderNo}`}
            checked={isOrderSelected(row.original.orderDetails.orderNo)}
            onClick={(e) => {
              // Range-select — shift-click extends from the last-clicked
              // anchor to this row (Gmail/GitHub muscle memory).
              toggleOrder(row.original.orderDetails.orderNo, row.index, e.shiftKey)
              // We handle the state change ourselves; stop React from
              // firing the onChange handler with a fresh toggle.
              e.preventDefault()
            }}
            onChange={() => { /* handled by onClick */ }}
            className="h-4 w-4 rounded border-[#cdbf9f] text-[#1f150c] focus:ring-[#e3d9c4]"
          />
        ),
        meta: { headerLabel: 'Select', hideable: false, exportable: false },
      })
    }

    defs.push({
      id: 'orderNo',
      accessorFn: (o) => o.orderDetails.orderNo,
      header: 'Order #',
      // PR #555 — explicit sizes so table-layout: fixed can allocate space
      // predictably instead of react-table's 160-default per column.
      size: 96,
      cell: ({ row }) => (
        <span className="font-mono text-[13.5px] font-bold tabular-nums text-[#1f150c]">
          #{row.original.orderDetails.orderNo}
        </span>
      ),
      meta: {
        headerLabel: 'Order #',
        exportValue: (o: Order) => o.orderDetails.orderNo,
      },
    })

    defs.push({
      id: 'customer',
      accessorFn: (o) => o.orderDetails.customerCode,
      header: 'Client',
      size: 96,
      cell: ({ row }) => (
        <span className="block truncate font-mono text-[12px] font-semibold text-[#5a4526]">
          {row.original.orderDetails.customerCode}
        </span>
      ),
      meta: {
        headerLabel: 'Client',
        exportValue: (o: Order) => o.orderDetails.customerCode,
      },
    })

    defs.push({
      id: 'source',
      accessorFn: (o) => {
        const src = (o.orderDetails.source ?? 'API').toUpperCase()
        return src === 'API' && o.orderDetails.channel ? `${src} ${o.orderDetails.channel}` : src
      },
      header: 'Src',
      enableSorting: false,
      // ONE column for source + channel (client request: the two single-letter
      // chip columns read as duplicates side by side). Source chip (M/A/B)
      // first, channel chip (D/B) beside it when the order is classified.
      // Full labels preserved on hover; server folds WMS/ERP/legacy into API.
      // Channel is spelled out (B2B / D2C) on API rows only — a lone "B" / "D"
      // was read as unclassified.
      size: 92,
      cell: ({ row }) => {
        const s = (row.original.orderDetails.source || 'API').toUpperCase()
        const sTone: Record<string, string> = {
          MANUAL: 'bg-amber-50 text-amber-700 ring-amber-200',
          API: 'bg-emerald-50 text-emerald-700 ring-emerald-200',
          BULK: 'bg-fuchsia-50 text-fuchsia-700 ring-fuchsia-200',
        }
        const sLabel: Record<string, string> = {
          MANUAL: 'Manual — created via /orders/new',
          API: 'API — imported via external partner / WMS',
          BULK: 'Bulk — imported via CSV/Excel',
        }
        const c = (row.original.orderDetails.channel || '').toUpperCase()
        // Client request: the D2C / B2B classification is shown for API
        // (partner / WMS) orders only — manual and bulk rows show the source.
        const classified = s === 'API' && (c === 'D2C' || c === 'B2B')
        const cTone = c === 'B2B'
          ? 'bg-indigo-50 text-indigo-700 ring-indigo-200'
          : 'bg-sky-50 text-sky-700 ring-sky-200'
        return (
          <span className="inline-flex items-center gap-1">
            <span
              title={sLabel[s] || 'Order source'}
              className={`inline-flex h-5 w-5 items-center justify-center rounded-full text-[10px] font-bold ring-1 ${sTone[s] || sTone.API}`}
            >
              {s.charAt(0)}
            </span>
            {classified ? (
              <span
                title={c === 'B2B' ? 'B2B — business-to-business shipment' : 'D2C — direct-to-consumer shipment'}
                data-testid="order-channel-chip"
                className={`inline-flex h-5 items-center rounded-full px-1.5 text-[9px] font-bold uppercase tracking-wide ring-1 ${cTone}`}
              >
                {c}
              </span>
            ) : null}
          </span>
        )
      },
      meta: {
        headerLabel: 'Source / Channel',
        exportValue: (o: Order) => {
          const src = (o.orderDetails.source ?? 'API').toUpperCase()
          return src === 'API' && o.orderDetails.channel ? `${src} ${o.orderDetails.channel.toUpperCase()}` : src
        },
      },
    })

    defs.push({
      id: 'refOrderNumber',
      accessorFn: (o) => o.orderDetails.refOrderNumber ?? '',
      header: 'Ref #',
      enableSorting: false,
      // PR #555 — auto-hidden when all rows on the current page have an
      // empty refOrderNumber (see hiddenColumnsOnEmpty below). Users who
      // deliberately want it visible can toggle via the column menu.
      size: 90,
      cell: ({ row }) => (
        <span
          className="block truncate font-mono text-[12px] text-[#5a4526]"
          title={row.original.orderDetails.refOrderNumber || undefined}
        >
          {row.original.orderDetails.refOrderNumber || <span className="text-[#b3a583]">—</span>}
        </span>
      ),
      meta: {
        headerLabel: 'Ref #',
        exportValue: (o: Order) => o.orderDetails.refOrderNumber ?? '',
      },
    })

    defs.push({
      id: 'batchId',
      accessorFn: (o) => o.orderDetails.batchId ?? '',
      header: 'Batch',
      enableSorting: false,
      // PR #555 — auto-hidden when all rows on the current page have an
      // empty batchId.
      size: 72,
      cell: ({ row }) => (
        <span className="block truncate font-mono text-[12px] text-[#5a4526]">
          {row.original.orderDetails.batchId ?? <span className="text-[#b3a583]">—</span>}
        </span>
      ),
      meta: {
        headerLabel: 'Batch',
        exportValue: (o: Order) => o.orderDetails.batchId ?? '',
      },
    })

    defs.push({
      id: 'city',
      accessorFn: (o) => formatDestination(o.shippingDetails.city, o.shippingDetails.state),
      header: 'Dest',
      // PR #555 compaction — cell shows "NJ · 07728" (state + zip), tooltip
      // carries the full address block (recipient name + street + city +
      // state + zip + country). Country implicit ~90% traffic; if you need
      // it, hover. Sort-key still on the underlying city, state so ordering
      // is unchanged.
      size: 110,
      cell: ({ row }) => {
        const s = row.original.shippingDetails
        // Cell body — state + zip. Fall back to city if state missing.
        const cellText = s.state && s.zipCode
          ? `${s.state} · ${s.zipCode}`
          : s.state
            ? s.state
            : s.city
              ? s.city
              : '—'
        // Tooltip = "City, ST ZIP" (full destination) — the only address
        // fields the /orders list endpoint exposes today. If ShippingDetails
        // grows recipient / street fields in a future DTO refresh, this
        // tooltip should expand to a multi-line address block.
        const tooltipParts = [s.city, s.state, s.zipCode].filter(Boolean)
        const tooltip = tooltipParts.length > 0 ? tooltipParts.join(' ') : 'No destination on file'
        return (
          <span
            className="block truncate text-[13.5px] tabular-nums text-[#3f3527]"
            title={tooltip}
          >
            {cellText}
          </span>
        )
      },
      meta: {
        headerLabel: 'Destination',
        exportValue: (o: Order) => formatDestination(o.shippingDetails.city, o.shippingDetails.state),
      },
    })

    if (showStatusColumn) {
      defs.push({
        id: 'status',
        accessorFn: (o) => o.labelDetails.status,
        header: 'Status',
        // PR #555 compaction — colored dot + short 4-char label (GEN /
        // PEND / ERR / VOID) with full status on tooltip. IssuesInfoIcon
        // still displayed on ERROR with the humanized carrier reason so
        // ops can hover once for the full picture. This is also why the
        // dedicated "Failure reason" column is gone in the failed view —
        // it's fully surfaced here.
        size: 72,
        cell: ({ row }) => {
          const raw = (row.original.labelDetails.status || 'UNKNOWN').toUpperCase()
          const err = row.original.errorDetails?.errorMessage
          const map: Record<string, { short: string; dot: string; label: string }> = {
            GENERATED: { short: 'GEN', dot: 'bg-emerald-500', label: 'Generated — label created and billed' },
            PENDING: { short: 'PEND', dot: 'bg-amber-500', label: 'Pending — label not yet generated' },
            ERROR: { short: 'ERR', dot: 'bg-rose-500', label: 'Error — hover the ⓘ for the carrier reason' },
            VOIDED: { short: 'VOID', dot: 'bg-slate-400', label: 'Voided — shipment cancelled' },
          }
          const entry = map[raw] || { short: raw.slice(0, 4), dot: 'bg-slate-300', label: raw }
          return (
            <span className="inline-flex items-center gap-1.5">
              <span
                title={entry.label}
                className="inline-flex items-center gap-1 rounded-full bg-slate-50 px-1.5 py-0.5 text-[10px] font-bold uppercase tracking-wide text-slate-700 ring-1 ring-slate-200"
              >
                <span className={`h-1.5 w-1.5 rounded-full ${entry.dot}`} />
                {entry.short}
              </span>
              {raw === 'ERROR' && err ? (
                <IssuesInfoIcon
                  side="left"
                  ariaLabel={`Order ${row.original.orderDetails.orderNo} error`}
                  items={[{ tag: 'carrier', text: summarizeCarrierError(err) }]}
                />
              ) : null}
            </span>
          )
        },
        meta: {
          headerLabel: 'Status',
          exportValue: (o: Order) => o.labelDetails.status,
        },
      })
      defs.push({
        id: 'createdDate',
        accessorFn: (o) => o.orderDetails.createdDate ?? '',
        header: 'Created',
        size: 100,
        cell: ({ row }) => (
          <span
            className="whitespace-nowrap text-[12px] text-[#6b5c42]"
            title={row.original.orderDetails.createdDate || 'unknown creation date'}
          >
            {formatCreated(row.original.orderDetails.createdDate)}
          </span>
        ),
        meta: {
          headerLabel: 'Created',
          exportValue: (o: Order) => o.orderDetails.createdDate ?? '',
        },
      })
    }

    if (showTracking) {
      defs.push({
        id: 'tracking',
        accessorFn: (o) => o.labelDetails.trackingNumber ?? '',
        header: 'Track',
        // PR #555 compaction — last-4-digits chip (…6784) with the full
        // tracking number on hover + click-to-open the carrier tracking
        // URL. Saves ~120px vs the previous "1Z999AA10123456784" full
        // number cell.
        size: 78,
        cell: ({ row }) => {
          const tn = row.original.labelDetails.trackingNumber
          const url = row.original.labelDetails.trackingUrl
          if (!tn) return <span className="text-[#b3a583]">—</span>
          const last4 = tn.length > 4 ? tn.slice(-4) : tn
          const chip = (
            <span
              title={`Tracking ${tn}${url ? '\n(click to open carrier page)' : ''}`}
              className="inline-flex items-center rounded-full bg-sky-50 px-2 py-0.5 font-mono text-[11px] font-semibold text-sky-800 ring-1 ring-sky-200"
            >
              …{last4}
            </span>
          )
          return url ? (
            <a href={url} target="_blank" rel="noreferrer" className="inline-block hover:opacity-80">
              {chip}
            </a>
          ) : chip
        },
        meta: {
          headerLabel: 'Tracking',
          exportValue: (o: Order) => o.labelDetails.trackingNumber ?? '',
        },
      })
      defs.push({
        id: 'generatedAt',
        accessorFn: (o) => o.labelDetails.generatedAt ?? '',
        header: 'Gen',
        size: 84,
        cell: ({ row }) => (
          <span
            className="whitespace-nowrap text-[12px] text-[#6b5c42]"
            title={row.original.labelDetails.generatedAt || 'not generated yet'}
          >
            {relativeTime(row.original.labelDetails.generatedAt) || '—'}
          </span>
        ),
        meta: {
          headerLabel: 'Generated',
          exportValue: (o: Order) => o.labelDetails.generatedAt ?? '',
        },
      })
    } else {
      defs.push({
        id: 'carrierAccount',
        header: 'Account',
        enableSorting: false,
        // PR #555 — AccountScenarioBadge is already an icon+short-label chip.
        // Give it a bounded width so it truncates rather than pushing other
        // columns.
        size: 140,
        cell: ({ row }) => (
          <AccountScenarioBadge resolution={row.original.accountResolution ?? undefined} />
        ),
        meta: {
          headerLabel: 'Account',
          exportValue: (o: Order) =>
            o.accountResolution?.accountNumber ?? o.carrierAccount?.accountCode ?? '',
        },
      })
    }

    // PR #555 — dedicated Failure-reason column removed. On ERROR rows the
    // Status column already renders an IssuesInfoIcon that shows the
    // humanized carrier error on hover; a separate wrapped-two-line column
    // was chewing ~200px in the failed view without adding info that
    // wasn't already one hover away.

    defs.push({
      id: 'actions',
      header: () => <span className="block text-right">Actions</span>,
      enableSorting: false,
      // PR #555 — actions column carries up to 4 icon buttons + 1 primary
      // action (Generate/Regenerate). Bounded so it doesn't push other
      // columns off screen on mid-size laptops.
      size: 172,
      cell: ({ row }) => {
        const order = row.original
        const orderNo = order.orderDetails.orderNo
        return (
          <span className="inline-flex w-full items-center justify-end gap-1.5">
            {order.labelDetails.trackingNumber ? (
              <button
                type="button"
                onClick={() => setTrackingOrderNo(orderNo)}
                title={`Live tracking for ${order.labelDetails.trackingNumber}`}
                aria-label={`Track order ${orderNo}`}
                className="rounded-lg border border-[#e6dcc7] bg-[#faf7f0] p-1.5 text-[#5a4526] transition hover:border-[#dccfb4] hover:bg-[#f2ebda]"
              >
                <FiTruck className="h-3.5 w-3.5" />
              </button>
            ) : null}
            {order.labelDetails.trackingNumber
              && (order.labelDetails.status || '').toUpperCase() !== 'VOIDED' ? (
              <button
                type="button"
                disabled={voidingOrderNo === orderNo}
                onClick={() => void handleVoid(orderNo, order.labelDetails.trackingNumber)}
                title={`Void ${order.labelDetails.trackingNumber} at the carrier`}
                aria-label={`Void order ${orderNo}`}
                className="rounded-lg border border-rose-200 bg-rose-50 p-1.5 text-rose-700 transition hover:border-rose-300 hover:bg-rose-100 disabled:opacity-40"
              >
                {voidingOrderNo === orderNo ? (
                  <span className="inline-block h-3.5 w-3.5 animate-spin rounded-full border-2 border-rose-300 border-t-rose-700" />
                ) : (
                  <FiXCircle className="h-3.5 w-3.5" />
                )}
              </button>
            ) : null}
            <button
              type="button"
              onClick={() => setDetailsOrderNo(orderNo)}
              aria-label={`Details for order ${orderNo}`}
              className="rounded-lg border border-[#e6dcc7] bg-[#faf7f0] p-1.5 text-[#6b5c42] transition hover:border-[#dccfb4] hover:bg-[#f2ebda] hover:text-[#412d15]"
            >
              <FiFileText className="h-3.5 w-3.5" />
            </button>
            {renderPrimaryAction(order)}
          </span>
        )
      },
      meta: { headerLabel: 'Actions', hideable: false, exportable: false },
    })

    return defs
    // Cells close over selection + generation handlers; re-memo when those
    // change so the row buttons/checkboxes reflect current state.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [
    view,
    showStatusColumn,
    showTracking,
    selectedOrderNos,
    selectableVisible,
    voidingOrderNo,
    generatingOrderNos,
  ])

  return (
    <div className="pb-24">
      <div className="mb-4 flex flex-nowrap items-center justify-end gap-1.5 overflow-x-auto">
            <button type="button" onClick={refreshQueues} className={BTN_GHOST_SM}>
              <FiRefreshCw className="h-3 w-3" />
              Refresh
            </button>
            <button
              type="button"
              onClick={() => {
                void generateAllReady()
              }}
              disabled={busy || !readyCount}
              className={BTN_GHOST_SM}
            >
              <FiZap className="h-3 w-3" />
              Generate all ready ({readyCount})
            </button>
            <button type="button" onClick={() => setPickupOpen(true)} className={BTN_GHOST_SM}
                    title="Book a driver to collect labelled parcels">
              <FiCalendar className="h-3 w-3" />
              Schedule pickup
            </button>
            <button type="button" onClick={() => setCloseOutOpen(true)} className={BTN_GHOST_SM}
                    title="Close out today's shipments so the driver can scan the manifest">
              <FiFileText className="h-3 w-3" />
              Close out day
            </button>
            <button type="button"
                    onClick={() => setBulkLabelOpen(true)}
                    disabled={rows.length === 0}
                    className={BTN_GHOST_SM}
                    title="Generate labels for every visible row and download a ZIP">
              <FiPackage className="h-3 w-3" />
              Bulk labels ({rows.length})
            </button>
            <button type="button"
                    onClick={() => setSplitOpen(true)}
                    className={BTN_GHOST_SM}
                    title="Split one shipment across multiple warehouses (Sprint 47)">
              <FiTruck className="h-3 w-3" />
              Split across warehouses
            </button>
            <button type="button"
                    onClick={() => navigate('/orders/history')}
                    className={BTN_GHOST_SM}
                    title="Order History — all orders (Bulk/Manual/API/WMS), the CSV/Excel importer, and import history">
              <FiDatabase className="h-3 w-3" />
              Order History
            </button>
            <button type="button" onClick={() => navigate('/orders/new')} className={BTN_PRIMARY_SM}>
              <FiPlus className="h-3 w-3" />
              New shipment
            </button>
      </div>

      {/* ===== workspace card ===== */}
      <section className="rounded-2xl border border-slate-200 bg-white p-5 shadow-sm">
        <div className="flex flex-wrap items-center gap-2.5">
          <div className="mr-auto flex flex-wrap gap-0.5 rounded-xl border border-[#e3d9c4] bg-[#f4eede]/60 p-1" role="tablist">
            {tabs.map((t) => (
              <button
                key={t.key}
                type="button"
                role="tab"
                aria-selected={view === t.key}
                onClick={() => {
                  setView(t.key)
                  setSelectedOrderNos([])
                }}
                className={`inline-flex items-center gap-1.5 rounded-lg px-3 py-1.5 text-[13.5px] font-semibold transition ${
                  view === t.key
                    ? 'bg-white text-[#1f150c] shadow-sm ring-1 ring-[#e3d9c4]'
                    : 'text-[#6b5c42] hover:text-[#412d15]'
                }`}
              >
                {t.tone !== 'slate' ? <span className={`h-1.5 w-1.5 rounded-full ${dotTone[t.tone]}`} /> : null}
                {t.label}
                <span
                  className={`font-mono text-[11px] font-semibold tabular-nums ${
                    view === t.key ? 'text-[#6b5c42]' : 'text-[#b6a684]'
                  }`}
                >
                  {t.count}
                </span>
              </button>
            ))}
          </div>

          {selectionEnabled && selectableVisible.length ? (
            <div className="flex flex-wrap items-center gap-1.5">
              {/* Select-page button — clearly scoped to the visible rows,
                  distinct from "all matching the filter" below. */}
              <button
                type="button"
                onClick={() => pageAllSelected ? clearSelection() : selectPage()}
                className="rounded-xl border border-[#e3d9c4] bg-white px-2.5 py-1.5 text-[11.5px] font-semibold text-[#5a4526] transition hover:border-[#cdbf9f] hover:bg-[#faf7f0]"
              >
                {pageAllSelected ? 'Deselect page' : `Select this page (${selectableVisible.length})`}
              </button>
              {/* Invert — flips selection within the current filter.
                  Fetches all matching ids on first use, caches by filter
                  signature so repeat clicks are free. */}
              <button
                type="button"
                onClick={() => void invertSelection()}
                title="Flip the current selection within the current filter"
                className="rounded-xl border border-[#e3d9c4] bg-white px-2.5 py-1.5 text-[11.5px] font-semibold text-[#5a4526] transition hover:border-[#cdbf9f] hover:bg-[#faf7f0]"
              >
                Invert
              </button>
            </div>
          ) : null}
        </div>

        {/*
          Gmail-style escalation banner (2026-09-13). When the whole
          visible page is picked AND we're still in individual mode,
          offer to expand the selection to every matching order across
          all pages. Also shown when all-filtered is active so the
          operator can bail back to page-scoped selection.
        */}
        {selectionEnabled && selectableVisible.length && selectionMode === 'individual' && pageAllSelected ? (
          <div className="mt-2 flex items-center justify-between gap-3 rounded-xl border border-sky-200 bg-sky-50 px-3 py-2 text-[11.5px] text-sky-900">
            <span>
              All <span className="font-semibold">{selectableVisible.length}</span> orders on this page are selected.
            </span>
            <button
              type="button"
              onClick={() => void selectAllFiltered()}
              className="rounded-lg border border-sky-300 bg-white px-2.5 py-1 font-semibold text-sky-900 hover:bg-sky-100"
            >
              Select all matching this filter
            </button>
          </div>
        ) : null}
        {selectionEnabled && selectionMode === 'all-filtered' && allFilteredIds ? (
          <div className="mt-2 flex items-center justify-between gap-3 rounded-xl border border-sky-300 bg-sky-100 px-3 py-2 text-[11.5px] text-sky-900">
            <span>
              All <span className="font-semibold">{selectedCount}</span> orders matching the current filter are selected
              {selectionSet.size > 0 ? <> · <span className="font-semibold">{selectionSet.size}</span> excluded</> : null}.
            </span>
            <button
              type="button"
              onClick={clearSelection}
              className="rounded-lg border border-sky-300 bg-white px-2.5 py-1 font-semibold text-sky-900 hover:bg-sky-50"
            >
              Clear selection
            </button>
          </div>
        ) : null}

        {/* ===== data table (shared AdvancedDataTable) ===== */}
        <div className="mt-3">
          <AdvancedDataTable<Order>
            tableKey="orders"
            columns={columns}
            data={rows}
            // PR #555 — auto-hide Ref # and Batch when every row on the
            // current page has an empty value (tenants that don't feed
            // those fields shouldn't see a dead-value column). Only
            // applies on FIRST load per tableKey; once a user toggles
            // column visibility via the column menu, their preference
            // is persisted in localStorage and wins over this default.
            initialHiddenColumns={autoHiddenColumns}
            manualPagination
            manualSorting
            sorting={sorting}
            onSortingChange={setSorting}
            pageIndex={page - 1}
            pageSize={pageSize}
            pageCount={totalPages}
            onPaginationChange={(next) => {
              setPage(next.pageIndex + 1)
              setPageSize(next.pageSize)
            }}
            search={{
              value: query,
              onChange: setQuery,
              placeholder: 'Search order #, client, city, tracking…',
            }}
            filterToggle={
              <div ref={filtersRef} className="relative flex flex-wrap items-center gap-2">
                <select
                  value={clientFilter}
                  onChange={(e) => setClientFilter(e.target.value)}
                  className="rounded-xl border border-[#e3d9c4] bg-white px-2.5 py-1.5 text-[12px] font-semibold text-[#5a4526] outline-none transition focus:border-[#cdbf9f]"
                  aria-label="Filter by client"
                >
                  <option value="">All clients</option>
                  {clientCodes.map((code) => (
                    <option key={code} value={code}>
                      {code}
                    </option>
                  ))}
                </select>

                <label className="inline-flex items-center gap-1.5 rounded-xl border border-[#e3d9c4] bg-white px-2.5 py-1.5 text-[11.5px] font-semibold text-[#6b5c42]">
                  From
                  <input
                    type="date"
                    value={dateFrom}
                    max={dateTo || undefined}
                    onChange={(e) => setDateFrom(e.target.value)}
                    className="bg-transparent text-[12px] font-medium text-[#1f150c] outline-none"
                    aria-label="Created from date"
                  />
                </label>
                <label className="inline-flex items-center gap-1.5 rounded-xl border border-[#e3d9c4] bg-white px-2.5 py-1.5 text-[11.5px] font-semibold text-[#6b5c42]">
                  To
                  <input
                    type="date"
                    value={dateTo}
                    min={dateFrom || undefined}
                    onChange={(e) => setDateTo(e.target.value)}
                    className="bg-transparent text-[12px] font-medium text-[#1f150c] outline-none"
                    aria-label="Created to date"
                  />
                </label>

                <button
                  type="button"
                  onClick={() => setShowFilters((cur) => !cur)}
                  aria-pressed={showFilters}
                  aria-expanded={showFilters}
                  className={`inline-flex items-center gap-1.5 rounded-xl px-2.5 py-1.5 text-[12px] font-semibold transition ${
                    showFilters || activeFilterCount
                      ? 'bg-[#1f150c] text-[#f4eede] shadow-sm'
                      : 'border border-[#e3d9c4] bg-white text-[#5a4526] hover:border-[#cdbf9f] hover:bg-[#faf7f0]'
                  }`}
                >
                  <FiFilter className="h-3.5 w-3.5" />
                  Filters
                  {activeFilterCount ? (
                    <span className="rounded-full bg-white/25 px-1.5 py-0.5 text-[10px] tabular-nums">
                      {activeFilterCount}
                    </span>
                  ) : null}
                </button>

                {activeFilterCount ? (
                  <button
                    type="button"
                    onClick={clearColumnFilters}
                    className="rounded-xl border border-[#e3d9c4] bg-white px-2.5 py-1.5 text-[12px] font-semibold text-[#5a4526] transition hover:border-[#cdbf9f] hover:bg-[#faf7f0]"
                  >
                    Clear
                  </button>
                ) : null}

                {/* Advanced filter popover — anchored to this container. */}
                {showFilters ? (
                  <div
                    role="dialog"
                    aria-label="Advanced filters"
                    className="absolute right-0 top-full z-30 mt-1.5 w-64 rounded-2xl border border-[#e3d9c4] bg-[#faf7f0] p-3.5 shadow-[0_20px_60px_rgba(31,21,12,0.18)]"
                  >
                    <div className="mb-2.5 flex items-center justify-between">
                      <span className="inline-flex items-center gap-1.5 font-mono text-[9px] font-bold uppercase tracking-[0.2em] text-[#b6a684]">
                        <FiSliders className="h-3 w-3" /> Advanced filters
                      </span>
                      <button
                        type="button"
                        onClick={clearColumnFilters}
                        disabled={!activeFilterCount}
                        className="inline-flex items-center gap-1 rounded-lg border border-[#e3d9c4] bg-white px-2.5 py-1 text-[11px] font-semibold text-[#6b5c42] transition hover:border-rose-200 hover:bg-rose-50 hover:text-rose-700 disabled:cursor-not-allowed disabled:opacity-40 disabled:hover:border-[#e3d9c4] disabled:hover:bg-white disabled:hover:text-[#6b5c42]"
                      >
                        <FiX className="h-3.5 w-3.5" /> Clear all
                      </button>
                    </div>
                    <div className="flex flex-col gap-2.5">
                      {advField(
                        <FiHash className="h-3 w-3" />,
                        'Order #',
                        <input
                          value={columnFilters.orderNo}
                          onChange={(e) => setColumnFilter('orderNo')(e.target.value)}
                          placeholder="e.g. 900044"
                          className={advInputCls}
                        />,
                      )}
                      {advField(
                        <FiUser className="h-3 w-3" />,
                        'Client code',
                        <input
                          value={columnFilters.customer}
                          onChange={(e) => setColumnFilter('customer')(e.target.value)}
                          placeholder="e.g. ARHDEV"
                          className={advInputCls}
                        />,
                      )}
                      {advField(
                        <FiMapPin className="h-3 w-3" />,
                        'Destination',
                        <input
                          value={columnFilters.city}
                          onChange={(e) => setColumnFilter('city')(e.target.value)}
                          placeholder="City or state"
                          className={advInputCls}
                        />,
                      )}
                      {advField(
                        <FiTruck className="h-3 w-3" />,
                        'Tracking #',
                        <input
                          value={columnFilters.tracking}
                          onChange={(e) => setColumnFilter('tracking')(e.target.value)}
                          placeholder="Carrier tracking number"
                          className={advInputCls}
                        />,
                      )}
                      {showStatusColumn
                        ? advField(
                            <FiTag className="h-3 w-3" />,
                            'Status',
                            <select
                              value={columnFilters.status}
                              onChange={(e) => setColumnFilter('status')(e.target.value)}
                              className={advInputCls}
                            >
                              <option value="">Any status</option>
                              <option value="PENDING">Pending</option>
                              <option value="GENERATED">Generated</option>
                              <option value="ERROR">Error</option>
                            </select>,
                          )
                        : null}
                      {advField(
                        <FiCalendar className="h-3 w-3" />,
                        'Created from',
                        <input
                          type="date"
                          value={dateFrom}
                          max={dateTo || undefined}
                          onChange={(e) => setDateFrom(e.target.value)}
                          className={advInputCls}
                        />,
                      )}
                      {advField(
                        <FiCalendar className="h-3 w-3" />,
                        'Created to',
                        <input
                          type="date"
                          value={dateTo}
                          min={dateFrom || undefined}
                          onChange={(e) => setDateTo(e.target.value)}
                          className={advInputCls}
                        />,
                      )}
                      {advField(
                        <FiDatabase className="h-3 w-3" />,
                        'Source',
                        <select
                          value={sourceFilter}
                          onChange={(e) => {
                            setSourceFilter(e.target.value)
                            // D2C / B2B is an API-only classification (client request).
                            if (e.target.value !== 'API') setChannelFilter('')
                          }}
                          className={advInputCls}
                        >
                          <option value="">Any source</option>
                          <option value="MANUAL">Manual</option>
                          <option value="BULK">Bulk (CSV/Excel)</option>
                          <option value="API">API</option>
                        </select>,
                      )}
                      {sourceFilter === 'API' && advField(
                        <FiDatabase className="h-3 w-3" />,
                        'Channel',
                        <select
                          value={channelFilter}
                          onChange={(e) => setChannelFilter(e.target.value)}
                          className={advInputCls}
                        >
                          <option value="">Any channel</option>
                          <option value="D2C">D2C (consumer)</option>
                          <option value="B2B">B2B (business)</option>
                        </select>,
                      )}
                    </div>

                    <div className="mt-3 flex items-center justify-end gap-2 border-t border-dashed border-[#e3d9c4] pt-2.5">
                      <button
                        type="button"
                        onClick={() => setShowFilters(false)}
                        className="inline-flex items-center gap-1 rounded-xl bg-[#1f150c] px-3 py-1.5 text-[11.5px] font-semibold text-[#f4eede] transition hover:bg-[#412d15]"
                      >
                        Done
                      </button>
                    </div>
                  </div>
                ) : null}
              </div>
            }
            csvFilename="orders"
            caption={
              <div className="flex items-center justify-between border-b border-dashed border-[#e3d9c4] pb-1.5">
                <span className="font-mono text-[9px] font-bold uppercase tracking-[0.2em] text-[#b6a684]">
                  Order manifest — {rows.length} {rows.length === 1 ? 'line' : 'lines'}
                </span>
              </div>
            }
            emptyState={
              loading ? (
                'Loading orders…'
              ) : debouncedQuery || clientFilter || activeFilterCount ? (
                'Nothing matches the current filters.'
              ) : view === 'ready' ? (
                <span className="inline-flex items-center gap-1.5">
                  <FiCheckCircle className="h-4 w-4 text-emerald-600" />
                  The ready queue is clear. 🎉
                </span>
              ) : view === 'details' ? (
                'No orders are waiting on carrier details.'
              ) : view === 'choose' ? (
                'No orders are waiting on a manual account choice.'
              ) : view === 'client' ? (
                'Every order belongs to a registered client.'
              ) : view === 'failed' ? (
                <span className="inline-flex items-center gap-1.5">
                  <FiCheckCircle className="h-4 w-4 text-emerald-600" />
                  No failed generations.
                </span>
              ) : view === 'generated' ? (
                'No labels have been generated yet.'
              ) : (
                'No orders yet.'
              )
            }
          />
        </div>
      </section>

      {/* ===== sticky action bar: live bulk progress OR selection =====
           Per-view action set (2026-09-13):
             ready      → Generate selected
             all/gen    → Void selected (labelled subset) + Copy order #s
      */}
      {bulkProgress || (selectionEnabled && selectedCount > 0) ? (
        <div className="fixed inset-x-0 bottom-5 z-30 flex justify-center px-4">
          <div className="flex items-center gap-3 rounded-2xl border border-slate-200 bg-white px-4 py-2.5 shadow-[0_18px_50px_rgba(15,23,42,0.22)]">
            {bulkProgress ? (
              <>
                <span className="inline-block h-3.5 w-3.5 animate-spin rounded-full border-2 border-slate-300 border-t-slate-900" />
                <span className="text-[13.5px] font-semibold text-slate-950 tabular-nums">
                  {bulkProgressLabel} {bulkProgress.done}/{bulkProgress.total}…
                </span>
                <div className="h-1.5 w-32 overflow-hidden rounded-full bg-slate-100">
                  <div
                    className="h-full rounded-full bg-slate-900 transition-all"
                    style={{ width: `${(bulkProgress.done / bulkProgress.total) * 100}%` }}
                  />
                </div>
              </>
            ) : (
              <>
                <span className="text-[13.5px] font-semibold text-slate-950 tabular-nums">
                  {selectedCount} selected{selectionMode === 'all-filtered' ? ' (all matching filter)' : ''}
                </span>
                {view === 'ready' ? (
                  <button
                    type="button"
                    onClick={() => {
                      // 2026-09-13 overhaul: use the materialised selection
                      // (may span multiple pages when all-filtered is active).
                      // For off-page orders we synthesise a minimal Order-
                      // shaped stub since generateForOrders only reads
                      // orderNo + accountResolution.scenario, and 'ready'
                      // view guarantees resolution=READY.
                      const rowByOrderNo = new Map(rows.map((o) => [o.orderDetails.orderNo, o] as const))
                      const selection = materialisedSelection()
                      const orderObjs = selection.map((no) => rowByOrderNo.get(no) ?? ({
                        orderDetails: { orderNo: no },
                        accountResolution: { scenario: 'READY' },
                      } as unknown as Order))
                      void generateForOrders(orderObjs)
                    }}
                    disabled={busy}
                    className={BTN_PRIMARY}
                  >
                    <FiZap className="h-3.5 w-3.5" />
                    Generate selected
                  </button>
                ) : null}
                {(view === 'all' || view === 'generated') ? (
                  <>
                    <button
                      type="button"
                      onClick={() => void voidSelected()}
                      disabled={busy}
                      title="Void every LABELLED order in the selection. Skips PENDING / VOIDED rows."
                      className={BTN_PRIMARY}
                    >
                      <FiSlash className="h-3.5 w-3.5" />
                      Void selected
                    </button>
                    <button
                      type="button"
                      onClick={() => void copySelectedOrderNos()}
                      title="Copy the selected order numbers to the clipboard, one per line"
                      className="inline-flex items-center gap-1.5 rounded-xl border border-[#e3d9c4] bg-white px-3 py-2 text-[13.5px] font-semibold text-[#5a4526] transition hover:border-[#cdbf9f] hover:bg-[#faf7f0]"
                    >
                      <FiCopy className="h-3.5 w-3.5" />
                      Copy order #s
                    </button>
                  </>
                ) : null}
                <button
                  type="button"
                  onClick={clearSelection}
                  aria-label="Clear selection"
                  className="rounded-xl border border-[#e3d9c4] bg-white p-2 text-[#6b5c42] transition hover:border-[#cdbf9f] hover:bg-[#faf7f0]"
                >
                  <FiX className="h-3.5 w-3.5" />
                </button>
              </>
            )}
          </div>
        </div>
      ) : null}

      {/* Bundle audit #434 follow-up — all modals below are lazy(). One
          shared Suspense boundary is enough because at most one modal is
          open at a time (state guards are mutually exclusive in practice)
          and each modal is its own render-conditional. Fallback is null
          — the modal is the visible transition, so a spinner in its slot
          would flash and then vanish. */}
      <Suspense fallback={null}>
        {pickerTarget ? (
          <AccountPickerModal
            orderNo={pickerTarget.orderDetails.orderNo}
            clientCode={pickerTarget.orderDetails.customerCode}
            carrierCode={pickerCarrier}
            suggestedAccountNumber={pickerSuggested}
            onClose={() => {
              setPickerTarget(null)
              setPickerCarrier(null)
              setPickerSuggested(null)
            }}
            onPick={(account) => {
              void generateWithAccount(pickerTarget.orderDetails.orderNo, account)
            }}
          />
        ) : null}

        {fillDetailsTarget ? (
          <FillCarrierDetailsModal
            orderNo={fillDetailsTarget.orderNo}
            resolution={fillDetailsTarget.resolution}
            onClose={() => setFillDetailsTarget(null)}
            onSaved={() => retryAfterDetailsSaved(fillDetailsTarget.orderNo)}
          />
        ) : null}

        {detailsOrderNo !== null ? (
          <OrderDetailsModal orderNo={detailsOrderNo} onClose={() => setDetailsOrderNo(null)} />
        ) : null}

        {/* Void confirmation — in-app modal (see handleVoid). */}
        {confirmVoid ? (
          <div
            role="dialog"
            aria-modal="true"
            aria-label={`Void order ${confirmVoid.orderNo}`}
            className="fixed inset-0 z-50 flex items-center justify-center bg-[#1f150c]/45 p-4 backdrop-blur-[1px]"
            onClick={() => setConfirmVoid(null)}
          >
            <div
              className="w-full max-w-[430px] overflow-hidden rounded-2xl border border-rose-200 bg-white shadow-2xl"
              onClick={(e) => e.stopPropagation()}
            >
              <div className="flex items-start gap-3 border-b border-rose-100 bg-rose-50 px-5 py-4">
                <span className="mt-0.5 inline-flex h-9 w-9 shrink-0 items-center justify-center rounded-xl bg-rose-100 text-rose-700">
                  <FiXCircle className="h-4 w-4" />
                </span>
                <div>
                  <p className="text-[10px] font-bold uppercase tracking-[0.16em] text-rose-500">Void label</p>
                  <h3 className="text-[15px] font-semibold text-[#1f150c]">
                    Void order #{confirmVoid.orderNo}?
                  </h3>
                  <p className="mt-0.5 font-mono text-[11px] text-rose-700">{confirmVoid.trackingNumber}</p>
                  {confirmVoid.packages && confirmVoid.packages > 1 ? (
                    <p className="mt-0.5 text-[11px] font-semibold text-rose-700">
                      All {confirmVoid.packages} package labels of this shipment will be voided together.
                    </p>
                  ) : null}
                </div>
              </div>
              <div className="space-y-2 px-5 py-4 text-[13.5px] leading-relaxed text-[#5a4526]">
                <p>
                  This cancels the label at the carrier — <span className="font-semibold">it cannot be undone</span>.
                  The tracking number dies and the label must not be used on a parcel.
                </p>
                <p>
                  Postage is refunded only if the label hasn't been scanned in transit yet;
                  post-scan voids succeed but no refund is issued.
                </p>
              </div>
              <div className="flex items-center justify-end gap-2 border-t border-[#eee6d6] bg-[#faf7f0] px-5 py-3">
                <button
                  type="button"
                  onClick={() => setConfirmVoid(null)}
                  className="rounded-xl border border-[#e3d9c4] bg-white px-3.5 py-2 text-[13.5px] font-semibold text-[#5a4526] transition hover:bg-[#faf7f0]"
                >
                  Keep the label
                </button>
                <button
                  type="button"
                  onClick={() => void executeVoid(confirmVoid.orderNo)}
                  className="inline-flex items-center gap-1.5 rounded-xl bg-rose-700 px-3.5 py-2 text-[13.5px] font-semibold text-white shadow-sm transition hover:bg-rose-800"
                >
                  <FiXCircle className="h-3.5 w-3.5" />
                  Void the label
                </button>
              </div>
            </div>
          </div>
        ) : null}

        {trackingOrderNo !== null ? (
          <TrackingTimelineModal orderNo={trackingOrderNo} onClose={() => setTrackingOrderNo(null)} />
        ) : null}

        {pickupOpen ? (
          <SchedulePickupModal onClose={() => setPickupOpen(false)} />
        ) : null}

        {closeOutOpen ? (
          <CloseOutModal
            onClose={() => setCloseOutOpen(false)}
            // Only LIVE labels belong on a manifest — a voided (or errored)
            // tracking is dead at the carrier, and prefilling it put a
            // cancelled number straight into the driver's scan list.
            trackingNumbers={rows
              .filter((o) => (o.labelDetails.status || '').toUpperCase() === 'GENERATED'
                && o.labelDetails.isGenerated)
              .map((o) => o.labelDetails.trackingNumber)
              .filter((t): t is string => Boolean(t))}
            defaults={{
              // Default the carrier to the one the prefilled trackings ship on
              // (the hardcoded UPS default sat over an all-FedEx list).
              carrierCode: (() => {
                const counts = new Map<string, number>()
                for (const o of rows) {
                  if ((o.labelDetails.status || '').toUpperCase() !== 'GENERATED') continue
                  const c = normalizeCarrierCode(o.shippingDetails.shipVia)?.toUpperCase()
                  if (c) counts.set(c, (counts.get(c) ?? 0) + 1)
                }
                let best = 'UPS'; let n = 0
                for (const [c, k] of counts) if (k > n) { best = c; n = k }
                return best
              })(),
            }}
          />
        ) : null}

        {bulkLabelOpen ? (
          <BulkLabelModal
            onClose={() => setBulkLabelOpen(false)}
            orderNumbers={rows.map((o) => o.orderDetails.orderNo)}
          />
        ) : null}

        {splitOpen ? <MultiWarehouseSplitModal onClose={() => setSplitOpen(false)} /> : null}

        {importOpen ? (
          <OrderImportModal onClose={() => setImportOpen(false)} />
        ) : null}
      </Suspense>
    </div>
  )
}
