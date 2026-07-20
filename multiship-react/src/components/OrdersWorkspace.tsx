import { useCallback, useEffect, useMemo, useState } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import toast from 'react-hot-toast'
import {
  FiArrowDown,
  FiArrowUp,
  FiCheckCircle,
  FiEdit3,
  FiExternalLink,
  FiEye,
  FiFileText,
  FiFilter,
  FiRefreshCw,
  FiRotateCw,
  FiSearch,
  FiX,
  FiZap,
} from 'react-icons/fi'
import { ApiError } from '../api/apiClient'
import { orderService, type Order, type QueueStats } from '../api/orderService'
import { clientService } from '../api/clientService'
import type { CarrierAccountRef, OrderAccountResolution } from '../api/accountRefService'
import PageSectionHeader from './workspace/PageSectionHeader'
import AccountScenarioBadge from './workspace/AccountScenarioBadge'
import OrderStatusBadge from './workspace/OrderStatusBadge'
import TablePagination from './workspace/TablePagination'
import FillCarrierDetailsModal from './modals/FillCarrierDetailsModal'
import ClientEditorModal from './modals/ClientEditorModal'
import AccountPickerModal from './modals/AccountPickerModal'
import OrderDetailsModal from './modals/OrderDetailsModal'

type View = 'all' | 'ready' | 'details' | 'client' | 'choose' | 'failed' | 'generated'

/** Server-side query behind each view of the workspace. */
const VIEW_QUERY: Record<View, { status?: string; resolution?: string; defaultDirection: 'ASC' | 'DESC' }> = {
  all: { defaultDirection: 'ASC' },
  ready: { status: 'PENDING', resolution: 'READY', defaultDirection: 'ASC' },
  details: { status: 'PENDING', resolution: 'NEEDS_DETAILS', defaultDirection: 'ASC' },
  client: { status: 'PENDING', resolution: 'CLIENT_MISSING', defaultDirection: 'ASC' },
  choose: { status: 'PENDING', resolution: 'CHOOSE_ACCOUNT', defaultDirection: 'ASC' },
  failed: { status: 'ERROR', defaultDirection: 'ASC' },
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
  'inline-flex w-[132px] items-center justify-center gap-1.5 rounded-xl px-2.5 py-1.5 text-[11px] font-semibold transition disabled:cursor-not-allowed'
const ACTION_SOLID =
  'bg-[#1f150c] text-[#f4eede] hover:bg-[#412d15] disabled:bg-[#dcd4c4] disabled:text-white'
const ACTION_OUTLINE =
  'border border-[#412d15]/30 bg-white text-[#412d15] hover:border-[#412d15] hover:bg-[#412d15]/[0.04] disabled:opacity-50'
const ACTION_RETRY =
  'border border-amber-400 bg-white text-amber-700 hover:border-amber-500 hover:bg-amber-50 disabled:opacity-50'

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
  const [sortDirection, setSortDirection] = useState<'ASC' | 'DESC'>('ASC')
  const [showFilters, setShowFilters] = useState(false)
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
  const [addClientCode, setAddClientCode] = useState<string | null>(null)
  const [pickerTarget, setPickerTarget] = useState<Order | null>(null)
  const [detailsOrderNo, setDetailsOrderNo] = useState<number | null>(null)

  // The header's global search lands here as /orders?q=…
  useEffect(() => {
    const params = new URLSearchParams(location.search)
    const q = params.get('q')
    if (q) {
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

  useEffect(() => {
    setPage(1)
  }, [view, debouncedQuery, pageSize, clientFilter, dateFrom, dateTo, sortBy, sortDirection, debouncedFilters])

  // Each view has its own natural direction; reset when switching.
  useEffect(() => {
    setSortDirection(VIEW_QUERY[view].defaultDirection)
    setSortBy('orderNo')
  }, [view])

  // Known clients feed the filter dropdown (best effort).
  useEffect(() => {
    clientService
      .listClients({ size: 100 })
      .then((response) => setClientCodes((response.data?.content ?? []).map((client) => client.clientCode)))
      .catch(() => {})
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
        toast.error(error instanceof Error ? error.message : 'Failed to load orders.')
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
      toast(`${needing} selected orders need carrier details first.`, { icon: '✏️' })
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
            }
            return
          }

          // CUSTOMS_REQUIRED (422): international shipment whose client has no
          // Importer/Broker profile for the destination — a one-time setup on
          // the Clients page; nothing to do per-order.
          if (error instanceof ApiError && error.errorCode === 'CUSTOMS_REQUIRED') {
            if (orderNos.length === 1) {
              toast.error(error.message || 'Set up this client’s Importer/Broker for the destination country (Settings › Clients).')
            }
            return
          }

          // CLIENT_NOT_FOUND (422): the order's client is unregistered —
          // open the add-client form prefilled with the code.
          if (error instanceof ApiError && error.errorCode === 'CLIENT_NOT_FOUND') {
            if (orderNos.length === 1) {
              setAddClientCode(data?.clientCode || null)
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
      toast.error(`${ok} generated, ${failures.length} failed (${failures[0]}) — see the Failed tab.`)
    } else if (ok) {
      toast.success(`${ok} label${ok === 1 ? '' : 's'} generated.`)
    }
    if (needing) toast(`${needing} orders still need carrier details.`, { icon: '✏️' })
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
      toast('The ready queue is already clear.', { icon: '🎉' })
      return
    }

    if (readyCount > readyOrders.length) {
      toast(`Generating the first ${readyOrders.length} of ${readyCount} ready orders — run again for the rest.`, {
        icon: '⚡',
      })
    }

    await generateForOrders(readyOrders)
  }

  const retryAfterDetailsSaved = async (orderNo: number) => {
    setGeneratingOrderNos((cur) => [...new Set([...cur, orderNo])])
    let generated = false
    try {
      await orderService.generateLabel(orderNo)
      toast.success(`Label generated for order #${orderNo}.`)
      setRows((cur) => cur.filter((o) => o.orderDetails.orderNo !== orderNo))
      generated = true
    } catch (error) {
      if (error instanceof ApiError && error.errorCode === 'NEEDS_CARRIER_DETAILS') {
        toast.error('The account is still incomplete — check the credentials.')
      } else if (error instanceof ApiError && error.errorCode === 'NO_DEFAULT_ACCOUNT') {
        toast.error('No account could be resolved — ask an admin to set a company default on the Carrier page.')
      } else if (error instanceof ApiError && error.errorCode === 'CUSTOMS_REQUIRED') {
        toast.error(error.message || 'Set up this client’s Importer/Broker for the destination country (Settings › Clients).')
      } else if (error instanceof ApiError && (error.errorCode === 'LABEL_ALREADY_GENERATED' || error.status === 409)) {
        setRows((cur) => cur.filter((o) => o.orderDetails.orderNo !== orderNo))
        generated = true
      } else {
        toast.error(error instanceof Error ? error.message : `Order #${orderNo} failed — see the Failed tab.`)
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
      toast.success(`Label generated for #${orderNo} via ${account.accountNumber}.`)
      navigate(`/label/${orderNo}`)
      return
    } catch (error) {
      if (error instanceof ApiError && (error.errorCode === 'LABEL_ALREADY_GENERATED' || error.status === 409)) {
        navigate(`/label/${orderNo}`)
        return
      }
      toast.error(error instanceof Error ? error.message : `Order #${orderNo} failed — see the Failed tab.`)
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
          onClick={() => setPickerTarget(order)}
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
          onClick={() => setAddClientCode(order.orderDetails.customerCode)}
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

  /** Clickable column header with sort arrows. */
  const sortableHeader = (label: string, key: string) => (
    <th className="px-2.5 py-3">
      <button
        type="button"
        onClick={() => handleSort(key)}
        className={`inline-flex items-center gap-1 uppercase tracking-[0.16em] transition hover:text-slate-950 ${
          sortBy === key ? 'text-slate-950' : ''
        }`}
      >
        {label}
        {sortBy === key ? (
          sortDirection === 'ASC' ? (
            <FiArrowUp className="h-3 w-3" />
          ) : (
            <FiArrowDown className="h-3 w-3" />
          )
        ) : (
          <span className="text-slate-300">↕</span>
        )}
      </button>
    </th>
  )

  const filterCell = (key: 'orderNo' | 'customer' | 'city' | 'tracking', placeholder: string) => (
    <th className="px-2 pb-2.5">
      <input
        value={columnFilters[key]}
        onChange={(e) => setColumnFilter(key)(e.target.value)}
        placeholder={placeholder}
        className="w-full rounded-lg border border-slate-200 bg-white px-2 py-1.5 text-[11.5px] font-medium normal-case tracking-normal text-slate-950 outline-none transition placeholder:text-slate-300 focus:border-sky-600"
      />
    </th>
  )

  const showStatusColumn = view === 'all'
  const showTracking = view === 'generated'

  return (
    <div className="space-y-4 pb-24">
      <PageSectionHeader
        eyebrow="Operations"
        title="Orders & Labels"
        description="Every order in one queue — generate in bulk, fix exceptions inline, review the archive."
        actions={
          <div className="flex flex-wrap items-center gap-2">
            <button
              type="button"
              onClick={refreshQueues}
              className="inline-flex items-center gap-1.5 rounded-xl border border-slate-200 bg-white px-3 py-2 text-[12.5px] font-semibold text-slate-600 transition hover:bg-slate-50"
            >
              <FiRefreshCw className="h-3.5 w-3.5" />
              Refresh
            </button>
            <button
              type="button"
              onClick={() => {
                void generateAllReady()
              }}
              disabled={busy || !readyCount}
              className="inline-flex items-center gap-1.5 rounded-xl bg-[#1f150c] px-3.5 py-2 text-[12.5px] font-semibold text-white transition hover:bg-[#412d15] disabled:cursor-not-allowed disabled:bg-slate-300"
            >
              <FiZap className="h-3.5 w-3.5" />
              Generate all ready ({readyCount})
            </button>
          </div>
        }
      />


      {/* ===== workspace card ===== */}
      <section className="rounded-2xl border border-slate-200 bg-white p-5 shadow-sm">
        <div className="flex flex-wrap items-center gap-2.5">
          <div className="mr-auto flex flex-wrap gap-0.5 rounded-lg border border-slate-200/70 bg-slate-100 p-0.5" role="tablist">
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
                className={`inline-flex items-center gap-1.5 rounded-md px-3 py-1.5 text-[12.5px] font-semibold transition ${
                  view === t.key ? 'bg-white text-[#1f150c] shadow-sm' : 'text-slate-500 hover:text-slate-800'
                }`}
              >
                {t.tone !== 'slate' ? <span className={`h-1.5 w-1.5 rounded-full ${dotTone[t.tone]}`} /> : null}
                {t.label}
                <span className="text-[11px] font-semibold tabular-nums text-slate-400">{t.count}</span>
              </button>
            ))}
          </div>

          {view === 'ready' && selectableVisible.length ? (
            <button
              type="button"
              onClick={() =>
                setSelectedOrderNos(allSelected ? [] : selectableVisible.map((o) => o.orderDetails.orderNo))
              }
              className="rounded-xl border border-slate-200 bg-white px-2.5 py-1.5 text-[11.5px] font-semibold text-slate-600 transition hover:bg-slate-50"
            >
              {allSelected ? 'Clear selection' : `Select all (${selectableVisible.length})`}
            </button>
          ) : null}
        </div>

        {/* ===== filters row ===== */}
        <div className="mt-3 flex flex-wrap items-center gap-2">
          <label className="flex min-w-[210px] flex-1 items-center gap-2 rounded-xl border border-slate-200 bg-slate-50 px-3 py-2 sm:max-w-xs">
            <FiSearch className="h-3.5 w-3.5 shrink-0 text-slate-400" />
            <input
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              placeholder="Search order #, client, city, tracking…"
              className="w-full bg-transparent text-[12.5px] text-slate-950 outline-none"
            />
          </label>

          <select
            value={clientFilter}
            onChange={(e) => setClientFilter(e.target.value)}
            className="rounded-xl border border-slate-200 bg-white px-2.5 py-2 text-[12px] font-semibold text-slate-600 outline-none transition focus:border-sky-600"
            aria-label="Filter by client"
          >
            <option value="">All clients</option>
            {clientCodes.map((code) => (
              <option key={code} value={code}>
                {code}
              </option>
            ))}
          </select>

          <label className="inline-flex items-center gap-1.5 rounded-xl border border-slate-200 bg-white px-2.5 py-1.5 text-[11.5px] font-semibold text-slate-500">
            From
            <input
              type="date"
              value={dateFrom}
              max={dateTo || undefined}
              onChange={(e) => setDateFrom(e.target.value)}
              className="bg-transparent text-[12px] font-medium text-slate-950 outline-none"
              aria-label="Created from date"
            />
          </label>
          <label className="inline-flex items-center gap-1.5 rounded-xl border border-slate-200 bg-white px-2.5 py-1.5 text-[11.5px] font-semibold text-slate-500">
            To
            <input
              type="date"
              value={dateTo}
              min={dateFrom || undefined}
              onChange={(e) => setDateTo(e.target.value)}
              className="bg-transparent text-[12px] font-medium text-slate-950 outline-none"
              aria-label="Created to date"
            />
          </label>

          <button
            type="button"
            onClick={() => setShowFilters((cur) => !cur)}
            aria-pressed={showFilters}
            className={`inline-flex items-center gap-1.5 rounded-xl px-2.5 py-2 text-[12px] font-semibold transition ${
              showFilters || activeFilterCount
                ? 'bg-[#1f150c] text-white'
                : 'border border-slate-200 bg-white text-slate-600 hover:bg-slate-50'
            }`}
          >
            <FiFilter className="h-3.5 w-3.5" />
            Filters
            {activeFilterCount ? (
              <span className="rounded-full bg-white/25 px-1.5 py-0.5 text-[10px] tabular-nums">{activeFilterCount}</span>
            ) : null}
          </button>

          {activeFilterCount ? (
            <button
              type="button"
              onClick={clearColumnFilters}
              className="rounded-xl border border-slate-200 bg-white px-2.5 py-2 text-[12px] font-semibold text-slate-600 transition hover:bg-slate-50"
            >
              Clear
            </button>
          ) : null}
        </div>

        <div className="mt-3.5 overflow-x-auto">
          <table className="w-full min-w-[880px] text-[13px] text-slate-700">
            <thead className="border-b border-slate-200 text-left text-[10px] uppercase tracking-[0.16em] text-slate-500">
              <tr>
                {view === 'ready' ? <th className="px-2.5 py-3">Select</th> : null}
                {sortableHeader('Order #', 'orderNo')}
                {sortableHeader('Client', 'customer')}
                {sortableHeader('Destination', 'city')}
                {showStatusColumn ? sortableHeader('Status', 'status') : null}
                {showStatusColumn ? sortableHeader('Created', 'createdDate') : null}
                {showTracking ? (
                  <>
                    {sortableHeader('Tracking', 'tracking')}
                    {sortableHeader('Generated', 'generatedAt')}
                  </>
                ) : (
                  <th className="px-2.5 py-3">Carrier account</th>
                )}
                {view === 'failed' ? <th className="px-2.5 py-3">Failure reason</th> : null}
                <th className="px-2.5 py-3 text-right">Actions</th>
              </tr>

              {showFilters ? (
                <tr className="border-b border-slate-100 bg-slate-50/60">
                  {view === 'ready' ? <th className="px-2 pb-2.5" /> : null}
                  {filterCell('orderNo', 'e.g. 11153')}
                  {filterCell('customer', 'e.g. ARHDEV')}
                  {filterCell('city', 'city or state')}
                  {showStatusColumn ? (
                    <th className="px-2 pb-2.5">
                      <select
                        value={columnFilters.status}
                        onChange={(e) => setColumnFilter('status')(e.target.value)}
                        className="w-full rounded-lg border border-slate-200 bg-white px-2 py-1.5 text-[11.5px] font-medium normal-case tracking-normal text-slate-950 outline-none transition focus:border-sky-600"
                      >
                        <option value="">Any status</option>
                        <option value="PENDING">Pending</option>
                        <option value="GENERATED">Generated</option>
                        <option value="ERROR">Error</option>
                      </select>
                    </th>
                  ) : null}
                  {showStatusColumn ? <th className="px-2 pb-2.5" /> : null}
                  {showTracking ? (
                    <>
                      {filterCell('tracking', 'tracking #')}
                      <th className="px-2 pb-2.5" />
                    </>
                  ) : (
                    <th className="px-2 pb-2.5" />
                  )}
                  {view === 'failed' ? <th className="px-2 pb-2.5" /> : null}
                  <th className="px-2 pb-2.5" />
                </tr>
              ) : null}
            </thead>
            <tbody className="divide-y divide-slate-200">
              {rows.map((order) => {
                const orderNo = order.orderDetails.orderNo
                const resolution = order.accountResolution ?? null

                return (
                  <tr key={orderNo} className="transition hover:bg-slate-50/80">
                    {view === 'ready' ? (
                      <td className="px-2.5 py-3">
                        <input
                          type="checkbox"
                          checked={selectedOrderNos.includes(orderNo)}
                          onChange={() => toggleOrder(orderNo)}
                          className="h-4 w-4 rounded border-slate-300 text-slate-950 focus:ring-slate-300"
                        />
                      </td>
                    ) : null}
                    <td className="px-2.5 py-3 font-semibold text-slate-950">{orderNo}</td>
                    <td className="px-2.5 py-3">{order.orderDetails.customerCode}</td>
                    <td className="px-2.5 py-3">
                      {order.shippingDetails.city}, {order.shippingDetails.state}
                    </td>

                    {showStatusColumn ? (
                      <td className="px-2.5 py-3">
                        <OrderStatusBadge status={order.labelDetails.status} />
                      </td>
                    ) : null}
                    {showStatusColumn ? (
                      <td className="px-2.5 py-3 text-[12px] text-slate-500">
                        {order.orderDetails.createdDate
                          ? new Date(order.orderDetails.createdDate).toLocaleDateString('en-US', {
                              month: 'short',
                              day: 'numeric',
                              year: 'numeric',
                            })
                          : '—'}
                      </td>
                    ) : null}

                    {showTracking ? (
                      <>
                        <td className="px-2.5 py-3 font-mono text-[12px] text-slate-600">
                          {order.labelDetails.trackingNumber || '—'}
                        </td>
                        <td className="px-2.5 py-3 text-[12px] text-slate-500">
                          {relativeTime(order.labelDetails.generatedAt) || '—'}
                        </td>
                      </>
                    ) : (
                      <td className="px-2.5 py-3">
                        <AccountScenarioBadge resolution={resolution ?? undefined} />
                      </td>
                    )}

                    {view === 'failed' ? (
                      <td className="max-w-[260px] px-2.5 py-3">
                        <span className="line-clamp-2 text-[11.5px] leading-4 text-rose-700">
                          {order.errorDetails?.errorMessage || 'Unknown failure'}
                        </span>
                      </td>
                    ) : null}

                    <td className="px-2.5 py-3 text-right">
                      <span className="inline-flex items-center justify-end gap-1.5">
                        {view === 'generated' && order.labelDetails.trackingUrl ? (
                          <a
                            href={order.labelDetails.trackingUrl}
                            target="_blank"
                            rel="noreferrer"
                            className="inline-flex items-center gap-1.5 rounded-xl border border-[#e6dcc7] bg-[#faf7f0] px-2.5 py-1.5 text-[11px] font-semibold text-[#5a4526] transition hover:border-[#dccfb4] hover:bg-[#f2ebda]"
                          >
                            <FiExternalLink className="h-3 w-3" />
                            Track
                          </a>
                        ) : null}
                        <button
                          type="button"
                          onClick={() => setDetailsOrderNo(orderNo)}
                          aria-label={`Details for order ${orderNo}`}
                          className="rounded-xl border border-[#e6dcc7] bg-[#faf7f0] p-1.5 text-[#8a7959] transition hover:border-[#dccfb4] hover:bg-[#f2ebda] hover:text-[#412d15]"
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
                  <td colSpan={9} className="px-2.5 py-14 text-center text-sm text-slate-500">
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
                  className="inline-flex items-center gap-1.5 rounded-xl bg-[#1f150c] px-3.5 py-2 text-[12px] font-semibold text-white transition hover:bg-[#412d15] disabled:cursor-not-allowed disabled:bg-slate-300"
                >
                  <FiZap className="h-3.5 w-3.5" />
                  Generate selected
                </button>
                <button
                  type="button"
                  onClick={() => setSelectedOrderNos([])}
                  aria-label="Clear selection"
                  className="rounded-xl border border-slate-200 bg-white p-2 text-slate-500 transition hover:bg-slate-50"
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
          onClose={() => setPickerTarget(null)}
          onPick={(account) => {
            void generateWithAccount(pickerTarget.orderDetails.orderNo, account)
          }}
        />
      ) : null}

      {addClientCode ? (
        <ClientEditorModal
          lockedCode={addClientCode}
          onClose={() => setAddClientCode(null)}
          onSaved={(client) => {
            setAddClientCode(null)
            toast.success(`Client ${client.clientCode} registered — its orders are ready to generate.`)
            refreshQueues()
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
    </div>
  )
}
