import { useEffect, useMemo, useState } from 'react'
import type { ColumnDef } from '@tanstack/react-table'
import { orderService, type Order } from '../api/orderService'
import { notify } from '../utils/notify'
import AdvancedDataTable from './workspace/AdvancedDataTable'

/**
 * Order Intake → "All orders" view. A unified list of every order regardless of
 * how it entered the system — Bulk (CSV/Excel), Manual, API (D2C/B2B), or WMS.
 * Backed by the same /orders list the Orders workspace uses, narrowed by the
 * `source` filter. Rendered through AdvancedDataTable so columns can be
 * reordered, resized, shown/hidden, and the layout is remembered.
 */
const SOURCES = ['ALL', 'BULK', 'MANUAL', 'API', 'WMS', 'ERP'] as const
type SourceKey = (typeof SOURCES)[number]

const SOURCE_TONE: Record<string, string> = {
  MANUAL: 'bg-amber-50 text-amber-700 ring-amber-200',
  API: 'bg-emerald-50 text-emerald-700 ring-emerald-200',
  WMS: 'bg-indigo-50 text-indigo-700 ring-indigo-200',
  ERP: 'bg-slate-100 text-slate-600 ring-slate-200',
  BULK: 'bg-fuchsia-50 text-fuchsia-700 ring-fuchsia-200',
}

const SOURCE_LABEL: Record<SourceKey, string> = {
  ALL: 'All sources',
  BULK: 'Bulk (CSV/Excel)',
  MANUAL: 'Manual',
  API: 'API (D2C/B2B)',
  WMS: 'WMS',
  ERP: 'ERP',
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
    setPage(0)
  }, [source, debounced])

  useEffect(() => {
    let cancelled = false
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
        accessorFn: (o) => (o.orderDetails.source ?? 'ERP').toUpperCase(),
        enableSorting: false,
        cell: ({ row }) => {
          const s = (row.original.orderDetails.source || 'ERP').toUpperCase()
          return (
            <span className={`inline-flex rounded-full px-2 py-0.5 text-[10px] font-bold uppercase tracking-wide ring-1 ${SOURCE_TONE[s] || SOURCE_TONE.ERP}`}>
              {s}
            </span>
          )
        },
        meta: { headerLabel: 'Source', exportValue: (o: Order) => (o.orderDetails.source ?? 'ERP').toUpperCase() },
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
          return <span className={`inline-flex rounded-full px-2 py-0.5 text-[10px] font-bold uppercase ring-1 ${statusTone(s)}`}>{s}</span>
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
          <button
            type="button"
            onClick={() => setReload((n) => n + 1)}
            className="inline-flex items-center gap-1.5 rounded-xl border border-[#e3d9c4] bg-white px-3 py-1.5 text-[12px] font-semibold text-[#5a4526] transition hover:border-[#cdbf9f] hover:bg-[#faf7f0]"
          >
            Refresh
          </button>
        }
      />
      </div>
    </div>
  )
}
