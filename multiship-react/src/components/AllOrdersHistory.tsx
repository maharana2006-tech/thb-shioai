import { useEffect, useMemo, useState } from 'react'
import type { ColumnDef } from '@tanstack/react-table'
import { orderService, type Order } from '../api/orderService'
import { notify } from '../utils/notify'
import AdvancedDataTable from './workspace/AdvancedDataTable'
import ApiBatchList from './ApiBatchList'
import { BTN_GHOST_SM } from './ui/buttons'
import IssuesInfoIcon from './ui/IssuesInfoIcon'
import { summarizeCarrierError } from '../utils/carrierErrorMap'

/**
 * Order Intake → "All orders" view. A unified list of every order regardless of
 * how it entered the system — Bulk (CSV/Excel), Manual, API (D2C/B2B), or WMS.
 * Backed by the same /orders list the Orders workspace uses, narrowed by the
 * `source` filter. Rendered through AdvancedDataTable so columns can be
 * reordered, resized, shown/hidden, and the layout is remembered.
 */
// Three real sources: Bulk (CSV/Excel), Manual, and API. Orders from the WMS
// app (and any legacy ERP rows) arrive over the API, so they roll up under API.
const SOURCES = ['ALL', 'BULK', 'MANUAL', 'API'] as const
type SourceKey = (typeof SOURCES)[number]

const SOURCE_TONE: Record<string, string> = {
  MANUAL: 'bg-amber-50 text-amber-700 ring-amber-200',
  API: 'bg-emerald-50 text-emerald-700 ring-emerald-200',
  BULK: 'bg-fuchsia-50 text-fuchsia-700 ring-fuchsia-200',
}

const SOURCE_LABEL: Record<SourceKey, string> = {
  ALL: 'All sources',
  BULK: 'Bulk (CSV/Excel)',
  MANUAL: 'Manual',
  API: 'API',
}

/** Everything that isn't a manual entry or a bulk import is API (WMS/ERP included). */
const normSource = (s?: string | null) => {
  const u = (s || 'API').toUpperCase()
  return u === 'MANUAL' || u === 'BULK' ? u : 'API'
}

const fmtDate = (v?: string | null) =>
  v ? new Date(v).toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' }) : '—'

const statusTone = (s?: string | null) => {
  switch ((s || '').toUpperCase()) {
    case 'GENERATED':
      return 'bg-emerald-50 text-emerald-700 ring-emerald-200'
    case 'ERROR':
      return 'bg-rose-50 text-rose-700 ring-rose-200'
    default:
      return 'bg-slate-100 text-slate-600 ring-slate-200'
  }
}

export default function AllOrdersHistory() {
  const [rows, setRows] = useState<Order[]>([])
  const [loading, setLoading] = useState(true)
  const [source, setSource] = useState<SourceKey>('ALL')
  const [search, setSearch] = useState('')
  const [debounced, setDebounced] = useState('')
  const [page, setPage] = useState(0) // 0-based for AdvancedDataTable
  const [pageSize, setPageSize] = useState(10)
  const [pageCount, setPageCount] = useState(1)
  const [total, setTotal] = useState(0)
  const [reload, setReload] = useState(0)

  useEffect(() => {
    const t = setTimeout(() => setDebounced(search.trim()), 300)
    return () => clearTimeout(t)
  }, [search])

  // Reset to page 0 whenever the filter set changes.
  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- filter-derived page reset
    setPage(0)
  }, [source, debounced])

  useEffect(() => {
    // The API section is a batch view (ApiBatchList), not the flat order list.
    if (source === 'API') return
    let cancelled = false
    // eslint-disable-next-line react-hooks/set-state-in-effect -- fetch-lifecycle loading flag
    setLoading(true)
    orderService
      .listOrders({
        source: source === 'ALL' ? undefined : source,
        search: debounced || undefined,
        page,
        size: pageSize,
        sortBy: 'createdDate',
        sortDirection: 'DESC',
      })
      .then((res) => {
        if (cancelled) return
        setRows(res.data?.content ?? [])
        setPageCount(Math.max(res.data?.totalPages ?? 1, 1))
        setTotal(res.data?.totalElements ?? 0)
      })
      .catch((e) => {
        if (cancelled) return
        notify.apiError(e, 'Could not load orders.')
        setRows([])
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [source, debounced, page, pageSize, reload])

  const columns = useMemo<ColumnDef<Order, unknown>[]>(
    () => [
      {
        id: 'orderNo',
        header: 'Order #',
        accessorFn: (o) => o.orderDetails.orderNo,
        cell: ({ row }) => (
          <span className="font-mono text-[12.5px] font-bold tabular-nums text-[#1f150c]">
            #{row.original.orderDetails.orderNo}
          </span>
        ),
        meta: { headerLabel: 'Order #', exportValue: (o: Order) => o.orderDetails.orderNo },
      },
      {
        id: 'source',
        header: 'Source',
        accessorFn: (o) => normSource(o.orderDetails.source),
        enableSorting: false,
        cell: ({ row }) => {
          const s = normSource(row.original.orderDetails.source)
          return (
            <span className={`inline-flex rounded-full px-2 py-0.5 text-[10px] font-bold uppercase tracking-wide ring-1 ${SOURCE_TONE[s] || SOURCE_TONE.API}`}>
              {s}
            </span>
          )
        },
        meta: { headerLabel: 'Source', exportValue: (o: Order) => normSource(o.orderDetails.source) },
      },
      {
        id: 'client',
        header: 'Client',
        accessorFn: (o) => o.orderDetails.customerCode,
        cell: ({ row }) => (
          <span className="font-mono text-[12px] font-semibold text-[#5a4526]">{row.original.orderDetails.customerCode || '—'}</span>
        ),
        meta: { headerLabel: 'Client', exportValue: (o: Order) => o.orderDetails.customerCode },
      },
      {
        id: 'destination',
        header: 'Destination',
        accessorFn: (o) => [o.shippingDetails.city, o.shippingDetails.state].filter(Boolean).join(', '),
        cell: ({ row }) => (
          <span className="text-[#3f3527]">
            {[row.original.shippingDetails.city, row.original.shippingDetails.state].filter(Boolean).join(', ') || '—'}
          </span>
        ),
        meta: { headerLabel: 'Destination' },
      },
      {
        id: 'status',
        header: 'Status',
        accessorFn: (o) => (o.labelDetails.status || o.orderDetails.status || 'PENDING').toUpperCase(),
        cell: ({ row }) => {
          const s = (row.original.labelDetails.status || row.original.orderDetails.status || 'PENDING').toUpperCase()
          const err = row.original.errorDetails?.errorMessage
          return (
            <span className="inline-flex items-center gap-1.5">
              <span className={`inline-flex rounded-full px-2 py-0.5 text-[10px] font-bold uppercase ring-1 ${statusTone(s)}`}>{s}</span>
              {/* ⓘ next to an ERROR badge — hover/focus reveals the full
                  humanized failure reason right in the list. */}
              {s === 'ERROR' && err ? (
                <IssuesInfoIcon
                  side="left"
                  ariaLabel={`Order ${row.original.orderDetails.orderNo} error`}
                  items={[{ tag: 'carrier', text: summarizeCarrierError(err) }]}
                />
              ) : null}
            </span>
          )
        },
        meta: { headerLabel: 'Status' },
      },
      {
        id: 'created',
        header: 'Created',
        accessorFn: (o) => o.orderDetails.createdDate,
        cell: ({ row }) => <span className="text-[#8a7959]">{fmtDate(row.original.orderDetails.createdDate)}</span>,
        meta: { headerLabel: 'Created' },
      },
      {
        id: 'tracking',
        header: 'Tracking',
        accessorFn: (o) => o.labelDetails.trackingNumber ?? '',
        enableSorting: false,
        cell: ({ row }) =>
          row.original.labelDetails.trackingNumber ? (
            <span className="font-mono text-[11.5px] text-[#5a4526]">{row.original.labelDetails.trackingNumber}</span>
          ) : (
            <span className="text-[#b6a684]">—</span>
          ),
        meta: { headerLabel: 'Tracking' },
      },
    ],
    [],
  )

  return (
    <div className="space-y-3">
      {/* Source chips */}
      <div className="flex flex-wrap gap-1.5">
        {SOURCES.map((s) => {
          const active = source === s
          return (
            <button
              key={s}
              type="button"
              onClick={() => setSource(s)}
              className={`rounded-xl px-3 py-1.5 text-[12px] font-semibold transition ${
                active
                  ? 'bg-[#1f150c] text-[#f4eede]'
                  : 'border border-[#e3d9c4] bg-white text-[#5a4526] hover:border-[#cdbf9f] hover:bg-[#faf7f0]'
              }`}
            >
              {SOURCE_LABEL[s]}
            </button>
          )
        })}
      </div>

      {source === 'API' ? (
        // The API section is a batch view — one card per WMS/API fetch, open to reveal its shipments.
        <ApiBatchList />
      ) : (
        <div className="rounded-2xl border border-slate-200 bg-white p-3 shadow-sm">
          <AdvancedDataTable<Order>
            tableKey="order-intake-all-orders"
            columns={columns}
            data={rows}
            manualPagination
            pageIndex={page}
            pageSize={pageSize}
            pageCount={pageCount}
            onPaginationChange={(next) => {
              setPage(next.pageIndex)
              setPageSize(next.pageSize)
            }}
            search={{ value: search, onChange: setSearch, placeholder: 'Search order #, city, client, tracking…' }}
            csvFilename="order-intake-orders"
            caption={
              loading
                ? 'Loading…'
                : `Showing ${rows.length ? page * pageSize + 1 : 0}–${page * pageSize + rows.length} of ${total} orders`
            }
            emptyState={
              <p className="px-5 py-14 text-center text-sm text-[#8a7959]">
                No orders match {source === 'ALL' ? 'your search' : `source “${SOURCE_LABEL[source]}”`}.
              </p>
            }
            toolbarActions={
              <button type="button" onClick={() => setReload((n) => n + 1)} className={BTN_GHOST_SM}>
                Refresh
              </button>
            }
          />
        </div>
      )}
    </div>
  )
}
