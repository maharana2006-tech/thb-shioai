import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { notify } from '../utils/notify'
import {
  FiArrowDown,
  FiArrowUp,
  FiCheckCircle,
  FiEdit3,
  FiEye,
  FiFileText,
  FiFilter,
  FiRefreshCw,
  FiRotateCw,
  FiCalendar,
  FiPackage,
  FiSearch,
  FiUpload,
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
} from 'react-icons/fi'
import { ApiError, isAbortError } from '../api/apiClient'
import { orderService, type Order, type QueueStats } from '../api/orderService'
import { clientService } from '../api/clientService'
import type { CarrierAccountRef, OrderAccountResolution } from '../api/accountRefService'
import AccountScenarioBadge from './workspace/AccountScenarioBadge'
import OrderStatusBadge from './workspace/OrderStatusBadge'
import TablePagination from './workspace/TablePagination'
import FillCarrierDetailsModal from './modals/FillCarrierDetailsModal'
import AccountPickerModal from './modals/AccountPickerModal'
import OrderDetailsModal from './modals/OrderDetailsModal'
import TrackingTimelineModal from './tracking/TrackingTimelineModal'
import SchedulePickupModal from './modals/SchedulePickupModal'
import CloseOutModal from './modals/CloseOutModal'
import BulkLabelModal from './modals/BulkLabelModal'
import MultiWarehouseSplitModal from './modals/MultiWarehouseSplitModal'
import OrderImportModal from './modals/OrderImportModal'

type View = 'all' | 'ready' | 'details' | 'client' | 'choose' | 'failed' | 'generated'

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

/** Toolbar button tokens — espresso/cream, consistent across the workspace. */
const BTN_PRIMARY =
  'inline-flex items-center gap-1.5 rounded-xl bg-[#1f150c] px-3.5 py-2 text-[12.5px] font-semibold text-[#f4eede] shadow-sm transition hover:bg-[#412d15] disabled:cursor-not-allowed disabled:bg-[#dcd4c4] disabled:text-white disabled:shadow-none'

/** Compact toolbar tokens — smaller so every action fits on one line. */
const BTN_GHOST_SM =
  'inline-flex items-center gap-1 rounded-lg border border-[#e3d9c4] bg-white px-2 py-1 text-[11px] font-semibold text-[#5a4526] transition hover:border-[#cdbf9f] hover:bg-[#faf7f0] disabled:cursor-not-allowed disabled:opacity-40'
const BTN_PRIMARY_SM =
  'inline-flex items-center gap-1 rounded-lg bg-[#1f150c] px-2.5 py-1 text-[11px] font-semibold text-[#f4eede] shadow-sm transition hover:bg-[#412d15] disabled:cursor-not-allowed disabled:bg-[#dcd4c4] disabled:text-white disabled:shadow-none'

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
  const [totalElements, setTotalElements] = useState(0)
  const [totalPages, setTotalPages] = useState(1)
  const [loading, setLoading] = useState(true)

  const [view, setView] = useState<View>('all')
  const [query, setQuery] = useState('')
  const [debouncedQuery, setDebouncedQuery] = useState('')
  const [clientFilter, setClientFilter] = useState('')
  const [dateFrom, setDateFrom] = useState('')
  const [dateTo, setDateTo] = useState('')
  const [clientCodes, setClientCodes] = useState<string[]>([])
  const [sortBy, setSortBy] = useState('orderNo')
  const [sortDirection, setSortDirection] = useState<'ASC' | 'DESC'>('DESC')
  const [showFilters, setShowFilters] = useState(false)
  const filtersRef = useRef<HTMLDivElement>(null)
  const emptyColumnFilters = { orderNo: '', customer: '', city: '', status: '', tracking: '' }
  const [columnFilters, setColumnFilters] = useState(emptyColumnFilters)
  const [debouncedFilters, setDebouncedFilters] = useState(emptyColumnFilters)
  const [page, setPage] = useState(1)
  const [pageSize, setPageSize] = useState(10)
  const [reloadToken, setReloadToken] = useState(0)
  const [selectedOrderNos, setSelectedOrderNos] = useState<number[]>([])
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

  // Filter / pagination change → clear selection. Previously stale
  // selectedOrderNos from the OLD visible rows would silently apply to
  // "Generate selected" against the NEW rows, generating labels for
  // unintended orders. Tab switch already does this at the onClick site
  // (line ~1031); the effect handles every other change site uniformly.
  // Sort direction/by is deliberately excluded — reordering the same
  // rows doesn't invalidate what's selected.
  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- clear stale selection on any filter or pagination change; user-input-driven, not derivable at render
    setSelectedOrderNos([])
  }, [debouncedQuery, clientFilter, dateFrom, dateTo, debouncedFilters, page, pageSize])

  // Each view has its own natural direction; reset when switching.
  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- reset sort defaults when the view changes; each view has its own natural sort, cannot be derived at render (would ignore user re-picks)
    setSortDirection(VIEW_QUERY[view].defaultDirection)
    setSortBy('orderNo')
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
        page: page - 1,
        size: pageSize,
        sortBy,
        sortDirection,
        includeResolution: view !== 'generated',
      })
      .then((response) => {
        if (cancelled) return
        setRows(response.data?.content ?? [])
        setTotalElements(response.data?.totalElements ?? 0)
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
  }, [view, page, pageSize, debouncedQuery, clientFilter, dateFrom, dateTo, sortBy, sortDirection, debouncedFilters, reloadToken])

  const refreshQueues = () => setReloadToken((token) => token + 1)

  /**
   * Sprint 30 — void a label at the carrier. Confirms first (irreversible
   * at the carrier), then calls POST /orders/{n}/void. Refreshes the queue
   * on success so the row moves out of "generated" state.
   */
  const handleVoid = async (orderNo: number, trackingNumber: string | null) => {
    if (!trackingNumber) return
    const ok = window.confirm(
      `Void tracking ${trackingNumber} at the carrier?\n\n`
      + 'This calls the carrier\'s void / cancel endpoint (UPS, FedEx, USPS, DHL). '
      + 'Postage is refunded only if the label hasn\'t been scanned in transit yet — '
      + 'post-scan voids succeed but no refund is issued.',
    )
    if (!ok) return
    setVoidingOrderNo(orderNo)
    try {
      const response = await orderService.voidLabel(orderNo)
      const data = response.data
      if (data?.voided || data?.status === 'ALREADY_VOIDED') {
        notify.success(`Order ${orderNo}: ${data.message}`)
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

  // ----- selection (page-scoped, Ready tab only) -----
  const selectableVisible = view === 'ready' ? rows : []
  const allSelected =
    selectableVisible.length > 0 && selectableVisible.every((o) => selectedOrderNos.includes(o.orderDetails.orderNo))
  const toggleOrder = (orderNo: number) =>
    setSelectedOrderNos((cur) => (cur.includes(orderNo) ? cur.filter((n) => n !== orderNo) : [...cur, orderNo]))

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
        <button
          type="button"
          onClick={() => navigate(`/label/${orderNo}`)}
          className={`${ACTION_BASE} ${ACTION_SOLID}`}
        >
          <FiEye className="h-3 w-3" />
          View Label
        </button>
      )
    }

    if (status === 'ERROR') {
      return (
        <button
          type="button"
          onClick={() => {
            void generateForOrders([order])
          }}
          disabled={isGenerating}
          className={`${ACTION_BASE} ${ACTION_RETRY}`}
        >
          <FiRotateCw className="h-3 w-3" />
          {isGenerating ? 'Retrying…' : 'Retry'}
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

  const handleSort = (key: string) => {
    if (sortBy === key) {
      setSortDirection((cur) => (cur === 'ASC' ? 'DESC' : 'ASC'))
      return
    }
    setSortBy(key)
    setSortDirection('ASC')
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

  /** Compact per-column filter input (rendered in the filter row under the header). */
  const filterInput = (key: 'orderNo' | 'customer' | 'city' | 'tracking', placeholder: string) => (
    <input
      value={columnFilters[key]}
      onChange={(e) => setColumnFilter(key)(e.target.value)}
      placeholder={placeholder}
      className="w-full rounded-lg border border-[#e3d9c4] bg-white px-2 py-1.5 text-[11.5px] font-medium normal-case tracking-normal text-[#1f150c] outline-none transition placeholder:text-[#b6a684] focus:border-[#cdbf9f]"
    />
  )

  const statusFilterSelect = (
    <select
      value={columnFilters.status}
      onChange={(e) => setColumnFilter('status')(e.target.value)}
      className="w-full rounded-lg border border-[#e3d9c4] bg-white px-2 py-1.5 text-[11.5px] font-medium normal-case tracking-normal text-[#1f150c] outline-none transition focus:border-[#cdbf9f]"
    >
      <option value="">Any status</option>
      <option value="PENDING">Pending</option>
      <option value="GENERATED">Generated</option>
      <option value="ERROR">Error</option>
    </select>
  )

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
  const showTracking = view === 'generated'

  // ── Advanced table column model ────────────────────────────────────────────
  // Each column declares a fixed pixel width so `table-fixed` + <colgroup>
  // guarantees cells line up row-to-row. Text truncates (never wraps), numbers
  // use tabular figures, and the Actions column stays pinned to the right.
  type OrderColumn = {
    id: string
    header: string
    sortKey?: string
    width: number
    /** Exactly one column per view is flexible: it absorbs slack so the table
     *  fills the container on wide screens (actions flush right, no gap) and only
     *  scrolls once the container drops below the fixed columns' total. */
    flex?: boolean
    align?: 'left' | 'right' | 'center'
    cell: (order: Order) => ReactNode
    filter?: ReactNode
  }

  const formatCreated = (value?: string | null) =>
    value
      ? new Date(value).toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' })
      : '—'

  const columns: OrderColumn[] = []

  if (view === 'ready') {
    columns.push({
      id: 'select',
      header: '',
      width: 44,
      align: 'center',
      cell: (order) => (
        <input
          type="checkbox"
          checked={selectedOrderNos.includes(order.orderDetails.orderNo)}
          onChange={() => toggleOrder(order.orderDetails.orderNo)}
          className="h-4 w-4 rounded border-[#cdbf9f] text-[#1f150c] focus:ring-[#e3d9c4]"
        />
      ),
    })
  }

  columns.push({
    id: 'orderNo',
    header: 'Order #',
    sortKey: 'orderNo',
    width: 100,
    cell: (order) => (
      <span className="font-mono text-[12.5px] font-bold tabular-nums text-[#1f150c]">
        #{order.orderDetails.orderNo}
      </span>
    ),
    filter: filterInput('orderNo', 'e.g. 11153'),
  })

  columns.push({
    id: 'client',
    header: 'Client',
    sortKey: 'customer',
    width: 116,
    cell: (order) => (
      <span className="block truncate font-mono text-[12px] font-semibold text-[#5a4526]">
        {order.orderDetails.customerCode}
      </span>
    ),
    filter: filterInput('customer', 'e.g. ARHDEV'),
  })

  columns.push({
    id: 'source',
    header: 'Source',
    width: 96,
    cell: (order) => {
      const s = (order.orderDetails.source || 'ERP').toUpperCase()
      const tone: Record<string, string> = {
        MANUAL: 'bg-amber-50 text-amber-700 ring-amber-200',
        API: 'bg-emerald-50 text-emerald-700 ring-emerald-200',
        WMS: 'bg-indigo-50 text-indigo-700 ring-indigo-200',
        ERP: 'bg-slate-100 text-slate-600 ring-slate-200',
        BULK: 'bg-fuchsia-50 text-fuchsia-700 ring-fuchsia-200',
      }
      return (
        <span className={`inline-flex rounded-full px-2 py-0.5 text-[10px] font-bold uppercase tracking-wide ring-1 ${tone[s] || tone.ERP}`}>
          {s}
        </span>
      )
    },
  })

  columns.push({
    id: 'refOrderNumber',
    header: 'Ref Order #',
    width: 104,
    cell: (order) => (
      <span className="block truncate font-mono text-[12px] text-[#5a4526]" title={order.orderDetails.refOrderNumber || undefined}>
        {order.orderDetails.refOrderNumber || <span className="text-[#b3a583]">—</span>}
      </span>
    ),
  })

  columns.push({
    id: 'batchId',
    header: 'Batch',
    width: 90,
    cell: (order) => (
      <span className="block truncate font-mono text-[12px] text-[#5a4526]">
        {order.orderDetails.batchId ?? <span className="text-[#b3a583]">—</span>}
      </span>
    ),
  })

  columns.push({
    id: 'destination',
    header: 'Destination',
    sortKey: 'city',
    width: 150,
    flex: showTracking, // in the Archive/generated view (no carrier column) destination absorbs slack
    cell: (order) => {
      const dest = `${order.shippingDetails.city}, ${order.shippingDetails.state}`
      return (
        <span className="block truncate text-[12.5px] text-[#3f3527]" title={dest}>
          {dest}
        </span>
      )
    },
    filter: filterInput('city', 'city or state'),
  })

  if (showStatusColumn) {
    columns.push({
      id: 'status',
      header: 'Status',
      sortKey: 'status',
      width: 128,
      cell: (order) => <OrderStatusBadge status={order.labelDetails.status} />,
      filter: statusFilterSelect,
    })
    columns.push({
      id: 'created',
      header: 'Created',
      sortKey: 'createdDate',
      width: 96,
      cell: (order) => (
        <span className="whitespace-nowrap text-[12px] text-[#8a7959]">
          {formatCreated(order.orderDetails.createdDate)}
        </span>
      ),
    })
  }

  if (showTracking) {
    columns.push({
      id: 'tracking',
      header: 'Tracking',
      sortKey: 'tracking',
      width: 160,
      cell: (order) => (
        <span
          className="block truncate font-mono text-[12px] text-[#5a4526]"
          title={order.labelDetails.trackingNumber || undefined}
        >
          {order.labelDetails.trackingNumber || '—'}
        </span>
      ),
      filter: filterInput('tracking', 'tracking #'),
    })
    columns.push({
      id: 'generatedAt',
      header: 'Generated',
      sortKey: 'generatedAt',
      width: 124,
      cell: (order) => (
        <span className="whitespace-nowrap text-[12px] text-[#8a7959]">
          {relativeTime(order.labelDetails.generatedAt) || '—'}
        </span>
      ),
    })
  } else {
    columns.push({
      id: 'carrierAccount',
      header: 'Carrier account',
      width: 190,
      // Fixed (not flex): on wide screens `table-fixed` + `w-full` spreads the
      // leftover space evenly across every column, instead of piling it all into
      // this one column and leaving a big empty gap before Actions.
      cell: (order) => <AccountScenarioBadge resolution={order.accountResolution ?? undefined} />,
    })
  }

  if (view === 'failed') {
    columns.push({
      id: 'failure',
      header: 'Failure reason',
      width: 240,
      cell: (order) => (
        <span className="line-clamp-2 text-[11.5px] leading-4 text-rose-700">
          {order.errorDetails?.errorMessage || 'Unknown failure'}
        </span>
      ),
    })
  }

  const actionsWidth = showTracking ? 248 : 216
  const alignClass = (align?: 'left' | 'right' | 'center') =>
    align === 'right' ? 'text-right' : align === 'center' ? 'text-center' : 'text-left'

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
                    onClick={() => setImportOpen(true)}
                    className={BTN_GHOST_SM}
                    title="Upload a CSV or Excel with one order per row">
              <FiUpload className="h-3 w-3" />
              Import CSV/Excel
            </button>
            <button type="button"
                    onClick={() => navigate('/orders/history')}
                    className={BTN_GHOST_SM}
                    title="Saved imports — data saved from CSV/Excel imports">
              <FiDatabase className="h-3 w-3" />
              Data history
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
                className={`inline-flex items-center gap-1.5 rounded-lg px-3 py-1.5 text-[12.5px] font-semibold transition ${
                  view === t.key
                    ? 'bg-white text-[#1f150c] shadow-sm ring-1 ring-[#e3d9c4]'
                    : 'text-[#8a7959] hover:text-[#412d15]'
                }`}
              >
                {t.tone !== 'slate' ? <span className={`h-1.5 w-1.5 rounded-full ${dotTone[t.tone]}`} /> : null}
                {t.label}
                <span
                  className={`font-mono text-[11px] font-semibold tabular-nums ${
                    view === t.key ? 'text-[#8a7959]' : 'text-[#b6a684]'
                  }`}
                >
                  {t.count}
                </span>
              </button>
            ))}
          </div>

          {view === 'ready' && selectableVisible.length ? (
            <button
              type="button"
              onClick={() =>
                setSelectedOrderNos(allSelected ? [] : selectableVisible.map((o) => o.orderDetails.orderNo))
              }
              className="rounded-xl border border-[#e3d9c4] bg-white px-2.5 py-1.5 text-[11.5px] font-semibold text-[#5a4526] transition hover:border-[#cdbf9f] hover:bg-[#faf7f0]"
            >
              {allSelected ? 'Clear selection' : `Select all (${selectableVisible.length})`}
            </button>
          ) : null}
        </div>

        {/* ===== filters row ===== */}
        <div className="mt-3 flex flex-wrap items-center gap-2">
          <label className="flex min-w-[210px] flex-1 items-center gap-2 rounded-xl border border-[#e3d9c4] bg-[#faf7f0] px-3 py-2 transition focus-within:border-[#cdbf9f] sm:max-w-xs">
            <FiSearch className="h-3.5 w-3.5 shrink-0 text-[#b6a684]" />
            <input
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              placeholder="Search order #, client, city, tracking…"
              className="w-full bg-transparent text-[12.5px] text-[#1f150c] outline-none placeholder:text-[#b6a684]"
            />
          </label>

          <select
            value={clientFilter}
            onChange={(e) => setClientFilter(e.target.value)}
            className="rounded-xl border border-[#e3d9c4] bg-white px-2.5 py-2 text-[12px] font-semibold text-[#5a4526] outline-none transition focus:border-[#cdbf9f]"
            aria-label="Filter by client"
          >
            <option value="">All clients</option>
            {clientCodes.map((code) => (
              <option key={code} value={code}>
                {code}
              </option>
            ))}
          </select>

          <label className="inline-flex items-center gap-1.5 rounded-xl border border-[#e3d9c4] bg-white px-2.5 py-1.5 text-[11.5px] font-semibold text-[#8a7959]">
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
          <label className="inline-flex items-center gap-1.5 rounded-xl border border-[#e3d9c4] bg-white px-2.5 py-1.5 text-[11.5px] font-semibold text-[#8a7959]">
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

          <div ref={filtersRef} className="relative">
            <button
              type="button"
              onClick={() => setShowFilters((cur) => !cur)}
              aria-pressed={showFilters}
              aria-expanded={showFilters}
              className={`inline-flex items-center gap-1.5 rounded-xl px-2.5 py-2 text-[12px] font-semibold transition ${
                showFilters || activeFilterCount
                  ? 'bg-[#1f150c] text-[#f4eede] shadow-sm'
                  : 'border border-[#e3d9c4] bg-white text-[#5a4526] hover:border-[#cdbf9f] hover:bg-[#faf7f0]'
              }`}
            >
              <FiFilter className="h-3.5 w-3.5" />
              Filters
              {activeFilterCount ? (
                <span className="rounded-full bg-white/25 px-1.5 py-0.5 text-[10px] tabular-nums">{activeFilterCount}</span>
              ) : null}
            </button>

            {/* Advanced filter popover */}
            {showFilters ? (
              <div
                role="dialog"
                aria-label="Advanced filters"
                className="absolute left-0 top-full z-30 mt-1.5 w-64 rounded-2xl border border-[#e3d9c4] bg-[#faf7f0] p-3.5 shadow-[0_20px_60px_rgba(31,21,12,0.18)]"
              >
                <div className="mb-2.5 flex items-center justify-between">
                  <span className="inline-flex items-center gap-1.5 font-mono text-[9px] font-bold uppercase tracking-[0.2em] text-[#b6a684]">
                    <FiSliders className="h-3 w-3" /> Advanced filters
                  </span>
                  <button
                    type="button"
                    onClick={clearColumnFilters}
                    disabled={!activeFilterCount}
                    className="inline-flex items-center gap-1 rounded-lg border border-[#e3d9c4] bg-white px-2.5 py-1 text-[11px] font-semibold text-[#8a7959] transition hover:border-rose-200 hover:bg-rose-50 hover:text-rose-700 disabled:cursor-not-allowed disabled:opacity-40 disabled:hover:border-[#e3d9c4] disabled:hover:bg-white disabled:hover:text-[#8a7959]"
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

          {activeFilterCount ? (
            <button
              type="button"
              onClick={clearColumnFilters}
              className="rounded-xl border border-[#e3d9c4] bg-white px-2.5 py-2 text-[12px] font-semibold text-[#5a4526] transition hover:border-[#cdbf9f] hover:bg-[#faf7f0]"
            >
              Clear
            </button>
          ) : null}
        </div>

        {/* manifest caption strip */}
        <div className="mt-3.5 flex items-center justify-between border-b border-dashed border-[#e3d9c4] pb-1.5">
          <span className="font-mono text-[9px] font-bold uppercase tracking-[0.2em] text-[#b6a684]">
            Order manifest
          </span>
          <span className="font-mono text-[9px] font-bold uppercase tracking-[0.16em] tabular-nums text-[#b6a684]">
            {rows.length} {rows.length === 1 ? 'line' : 'lines'}
          </span>
        </div>

        <div className="mt-2 overflow-x-auto">
          <table className="w-full min-w-[1040px] table-fixed border-collapse text-[13px] text-[#3f3527]">
            <colgroup>
              {columns.map((col) => (
                <col key={col.id} style={col.flex ? undefined : { width: `${col.width}px` }} />
              ))}
              <col style={{ width: `${actionsWidth}px` }} />
            </colgroup>

            <thead>
              <tr className="border-b border-dashed border-[#d8cbb0] font-mono text-[9px] font-bold uppercase tracking-[0.18em] text-[#a1906d]">
                {columns.map((col) => (
                  <th key={col.id} className={`px-2.5 py-3 align-middle ${alignClass(col.align)}`}>
                    {col.sortKey ? (
                      <button
                        type="button"
                        onClick={() => handleSort(col.sortKey!)}
                        className={`inline-flex items-center gap-1 uppercase tracking-[0.16em] transition hover:text-[#1f150c] ${
                          sortBy === col.sortKey ? 'text-[#1f150c]' : ''
                        }`}
                      >
                        {col.header}
                        {sortBy === col.sortKey ? (
                          sortDirection === 'ASC' ? (
                            <FiArrowUp className="h-3 w-3" />
                          ) : (
                            <FiArrowDown className="h-3 w-3" />
                          )
                        ) : (
                          <span className="text-[#cdbf9f]">↕</span>
                        )}
                      </button>
                    ) : (
                      col.header
                    )}
                  </th>
                ))}
                <th className="sticky right-0 z-20 bg-white px-2.5 py-3 text-right align-middle shadow-[-10px_0_12px_-10px_rgba(31,21,12,0.14)]">
                  Actions
                </th>
              </tr>

              {/* Per-column filters were replaced by the Advanced filter panel above. */}
            </thead>

            <tbody className="divide-y divide-dashed divide-[#e6dcc7]">
              {rows.map((order) => {
                const orderNo = order.orderDetails.orderNo

                return (
                  <tr key={orderNo} className="group transition hover:bg-[#faf7f0]">
                    {columns.map((col) => (
                      <td key={col.id} className={`px-2.5 py-3 align-middle ${alignClass(col.align)}`}>
                        {col.cell(order)}
                      </td>
                    ))}
                    <td className="sticky right-0 z-10 bg-white px-2.5 py-3 text-right align-middle shadow-[-10px_0_12px_-10px_rgba(31,21,12,0.14)] transition group-hover:bg-[#faf7f0]">
                      <span className="inline-flex items-center justify-end gap-1.5">
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
                          className="rounded-lg border border-[#e6dcc7] bg-[#faf7f0] p-1.5 text-[#8a7959] transition hover:border-[#dccfb4] hover:bg-[#f2ebda] hover:text-[#412d15]"
                        >
                          <FiFileText className="h-3.5 w-3.5" />
                        </button>
                        {renderPrimaryAction(order)}
                      </span>
                    </td>
                  </tr>
                )
              })}

              {!rows.length ? (
                <tr>
                  <td colSpan={columns.length + 1} className="px-2.5 py-14 text-center text-sm text-[#8a7959]">
                    {loading ? (
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
                    )}
                  </td>
                </tr>
              ) : null}
            </tbody>
          </table>
        </div>

        {totalElements > 0 ? (
          <TablePagination
            page={page}
            pageSize={pageSize}
            totalPages={totalPages}
            compact
            onPageChange={setPage}
            onPageSizeChange={setPageSize}
          />
        ) : null}
      </section>

      {/* ===== sticky action bar: live bulk progress OR selection ===== */}
      {bulkProgress || (view === 'ready' && selectedOrderNos.length) ? (
        <div className="fixed inset-x-0 bottom-5 z-30 flex justify-center px-4">
          <div className="flex items-center gap-3 rounded-2xl border border-slate-200 bg-white px-4 py-2.5 shadow-[0_18px_50px_rgba(15,23,42,0.22)]">
            {bulkProgress ? (
              <>
                <span className="inline-block h-3.5 w-3.5 animate-spin rounded-full border-2 border-slate-300 border-t-slate-900" />
                <span className="text-[12.5px] font-semibold text-slate-950 tabular-nums">
                  Generating {bulkProgress.done}/{bulkProgress.total}…
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
                <span className="text-[12.5px] font-semibold text-slate-950 tabular-nums">
                  {selectedOrderNos.length} selected
                </span>
                <button
                  type="button"
                  onClick={() => {
                    void generateForOrders(
                      rows.filter((o) => selectedOrderNos.includes(o.orderDetails.orderNo))
                    )
                  }}
                  disabled={busy}
                  className={BTN_PRIMARY}
                >
                  <FiZap className="h-3.5 w-3.5" />
                  Generate selected
                </button>
                <button
                  type="button"
                  onClick={() => setSelectedOrderNos([])}
                  aria-label="Clear selection"
                  className="rounded-xl border border-[#e3d9c4] bg-white p-2 text-[#8a7959] transition hover:border-[#cdbf9f] hover:bg-[#faf7f0]"
                >
                  <FiX className="h-3.5 w-3.5" />
                </button>
              </>
            )}
          </div>
        </div>
      ) : null}

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

      {trackingOrderNo !== null ? (
        <TrackingTimelineModal orderNo={trackingOrderNo} onClose={() => setTrackingOrderNo(null)} />
      ) : null}

      {pickupOpen ? (
        <SchedulePickupModal onClose={() => setPickupOpen(false)} />
      ) : null}

      {closeOutOpen ? (
        <CloseOutModal
          onClose={() => setCloseOutOpen(false)}
          trackingNumbers={rows
            .map((o) => o.labelDetails.trackingNumber)
            .filter((t): t is string => Boolean(t))}
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
    </div>
  )
}
