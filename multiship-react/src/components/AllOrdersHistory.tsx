import { useEffect, useMemo, useState } from 'react'
import { FiSearch, FiRefreshCw } from 'react-icons/fi'
import { orderService, type Order } from '../api/orderService'
import { notify } from '../utils/notify'

/**
 * Data History → "All orders" view. A unified, source-filterable list of every
 * order regardless of how it entered the system — Bulk (CSV/Excel), Manual,
 * API (D2C/B2B), or WMS. Backed by the same /orders list the Orders workspace
 * uses, narrowed by the `source` filter.
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

export default function AllOrdersHistory() {
  const [rows, setRows] = useState<Order[]>([])
  const [loading, setLoading] = useState(true)
  const [source, setSource] = useState<SourceKey>('ALL')
  const [search, setSearch] = useState('')
  const [debounced, setDebounced] = useState('')
  const [page, setPage] = useState(1)
  const [pageSize] = useState(10)
  const [totalPages, setTotalPages] = useState(1)
  const [totalElements, setTotalElements] = useState(0)
  const [reload, setReload] = useState(0)

  // Debounce the search box so we don't fire a request per keystroke.
  useEffect(() => {
    const t = setTimeout(() => setDebounced(search.trim()), 300)
    return () => clearTimeout(t)
  }, [search])

  // Reset to page 1 whenever the filter set changes.
  useEffect(() => {
    setPage(1)
  }, [source, debounced])

  useEffect(() => {
    let cancelled = false
    setLoading(true)
    orderService
      .listOrders({
        source: source === 'ALL' ? undefined : source,
        search: debounced || undefined,
        page: page - 1,
        size: pageSize,
        sortBy: 'createdDate',
        sortDirection: 'DESC',
      })
      .then((res) => {
        if (cancelled) return
        setRows(res.data?.content ?? [])
        setTotalPages(Math.max(res.data?.totalPages ?? 1, 1))
        setTotalElements(res.data?.totalElements ?? 0)
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

  const rangeStart = totalElements === 0 ? 0 : (page - 1) * pageSize + 1
  const rangeEnd = Math.min(page * pageSize, totalElements)

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

  const chips = useMemo(() => SOURCES, [])

  return (
    <div className="space-y-3">
      {/* Source chips + search */}
      <div className="flex flex-wrap items-center gap-2">
        <div className="flex flex-wrap gap-1.5">
          {chips.map((s) => {
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
        <div className="relative ml-auto min-w-[220px] flex-1 sm:max-w-xs">
          <FiSearch className="pointer-events-none absolute left-3 top-1/2 h-3.5 w-3.5 -translate-y-1/2 text-[#b6a684]" />
          <input
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            placeholder="Search order #, city, client, tracking…"
            className="w-full rounded-xl border border-[#e3d9c4] bg-white py-2 pl-9 pr-3 text-[12.5px] text-[#1f150c] placeholder:text-[#b6a684] focus:border-[#cdbf9f] focus:outline-none"
          />
        </div>
        <button
          type="button"
          onClick={() => setReload((n) => n + 1)}
          className="inline-flex items-center gap-1.5 rounded-xl border border-[#e3d9c4] bg-white px-3 py-2 text-[12px] font-semibold text-[#5a4526] transition hover:border-[#cdbf9f] hover:bg-[#faf7f0]"
        >
          <FiRefreshCw className="h-3.5 w-3.5" /> Refresh
        </button>
      </div>

      {/* Table */}
      <div className="overflow-hidden rounded-2xl border border-slate-200 bg-white shadow-sm">
        <div className="flex items-center justify-between border-b border-dashed border-[#e3d9c4] px-5 py-3">
          <span className="font-mono text-[9px] font-bold uppercase tracking-[0.2em] text-[#b6a684]">
            All orders — every source
          </span>
          <span className="font-mono text-[9px] font-bold uppercase tracking-[0.16em] tabular-nums text-[#b6a684]">
            {totalElements === 0 ? '0 orders' : `${rangeStart}–${rangeEnd} of ${totalElements}`}
          </span>
        </div>

        {loading ? (
          <p className="px-5 py-14 text-center text-sm text-[#8a7959]">Loading…</p>
        ) : rows.length === 0 ? (
          <p className="px-5 py-14 text-center text-sm text-[#8a7959]">
            No orders match {source === 'ALL' ? 'your search' : `source “${SOURCE_LABEL[source]}”`}.
          </p>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full border-collapse text-[12.5px]">
              <thead>
                <tr className="bg-[#faf7f0] text-[9px] uppercase tracking-[0.12em] text-[#a1906d]">
                  <th className="px-4 py-2 text-left font-bold">Order #</th>
                  <th className="px-4 py-2 text-left font-bold">Source</th>
                  <th className="px-4 py-2 text-left font-bold">Client</th>
                  <th className="px-4 py-2 text-left font-bold">Destination</th>
                  <th className="px-4 py-2 text-left font-bold">Status</th>
                  <th className="px-4 py-2 text-left font-bold">Created</th>
                  <th className="px-4 py-2 text-left font-bold">Tracking</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-[#eee6d6]">
                {rows.map((o) => {
                  const src = (o.orderDetails.source || 'ERP').toUpperCase()
                  return (
                    <tr key={`${o.orderDetails.orderNo}-${o.orderDetails.orderSuffix}`} className="hover:bg-[#faf7f0]">
                      <td className="whitespace-nowrap px-4 py-2 font-mono font-bold tabular-nums text-[#1f150c]">
                        #{o.orderDetails.orderNo}
                      </td>
                      <td className="px-4 py-2">
                        <span
                          className={`inline-flex rounded-full px-2 py-0.5 text-[10px] font-bold uppercase tracking-wide ring-1 ${SOURCE_TONE[src] || SOURCE_TONE.ERP}`}
                        >
                          {src}
                        </span>
                      </td>
                      <td className="whitespace-nowrap px-4 py-2 font-mono font-semibold text-[#5a4526]">
                        {o.orderDetails.customerCode || '—'}
                      </td>
                      <td className="whitespace-nowrap px-4 py-2 text-[#3f3527]">
                        {[o.shippingDetails.city, o.shippingDetails.state].filter(Boolean).join(', ') || '—'}
                      </td>
                      <td className="px-4 py-2">
                        <span
                          className={`inline-flex rounded-full px-2 py-0.5 text-[10px] font-bold uppercase ring-1 ${statusTone(o.labelDetails.status || o.orderDetails.status)}`}
                        >
                          {(o.labelDetails.status || o.orderDetails.status || 'PENDING').toUpperCase()}
                        </span>
                      </td>
                      <td className="whitespace-nowrap px-4 py-2 text-[#8a7959]">{fmtDate(o.orderDetails.createdDate)}</td>
                      <td className="whitespace-nowrap px-4 py-2 font-mono text-[11.5px] text-[#5a4526]">
                        {o.labelDetails.trackingNumber || <span className="text-[#b6a684]">—</span>}
                      </td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
          </div>
        )}

        {/* Pagination */}
        <div className="flex items-center justify-between border-t border-dashed border-[#e3d9c4] px-5 py-2.5 text-[12px] text-[#5a4526]">
          <span>
            Page {page} of {totalPages}
          </span>
          <div className="flex items-center gap-1.5">
            <button
              type="button"
              disabled={page <= 1}
              onClick={() => setPage((p) => Math.max(1, p - 1))}
              className="rounded-lg border border-[#e3d9c4] bg-white px-3 py-1 font-semibold transition hover:bg-[#faf7f0] disabled:cursor-not-allowed disabled:opacity-40"
            >
              Previous
            </button>
            <button
              type="button"
              disabled={page >= totalPages}
              onClick={() => setPage((p) => Math.min(totalPages, p + 1))}
              className="rounded-lg border border-[#e3d9c4] bg-white px-3 py-1 font-semibold transition hover:bg-[#faf7f0] disabled:cursor-not-allowed disabled:opacity-40"
            >
              Next
            </button>
          </div>
        </div>
      </div>
    </div>
  )
}
