import { useCallback, useEffect, useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { FiArrowLeft, FiDownloadCloud, FiRefreshCw } from 'react-icons/fi'
import type { ColumnDef, SortingState } from '@tanstack/react-table'
import AdvancedDataTable from './workspace/AdvancedDataTable'
import { dtcService, type DtcOrder, type DtcOrderPage } from '../api/dtcService'
import { useAppSession } from '../hooks/useAppSession'
import { normalizeRole } from '../utils/roles'
import { notify } from '../utils/notify'

/**
 * D2C History — the dtc_orders table, filled by "Sync from Oracle"
 * (POST /dtc/sync/oracle). Server-side paging, sorting, tenant filter and search.
 */
export default function DtcOrdersPage() {
  const navigate = useNavigate()
  const { role } = useAppSession()
  const canSync = normalizeRole(role) === 'ADMIN'

  const [pageIndex, setPageIndex] = useState(0)
  const [pageSize, setPageSize] = useState(25)
  const [sorting, setSorting] = useState<SortingState>([{ id: 'createdAt', desc: true }])
  const [search, setSearch] = useState('')
  const [q, setQ] = useState('')
  const [tenantId, setTenantId] = useState('')
  const [tenants, setTenants] = useState<string[]>([])
  const [data, setData] = useState<DtcOrderPage | null>(null)
  const [loading, setLoading] = useState(true)
  const [syncing, setSyncing] = useState(false)

  // Debounce the search box so every keystroke isn't a request.
  useEffect(() => {
    const t = setTimeout(() => { setQ(search.trim()); setPageIndex(0) }, 300)
    return () => clearTimeout(t)
  }, [search])

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const s = sorting[0]
      const res = await dtcService.orders({
        page: pageIndex, size: pageSize,
        sort: s?.id ?? 'createdAt', dir: s && !s.desc ? 'ASC' : 'DESC',
        tenantId, q,
      })
      setData(res.data)
    } catch (e) {
      notify.apiError(e, 'Could not load D2C orders.')
    } finally {
      setLoading(false)
    }
  }, [pageIndex, pageSize, sorting, tenantId, q])

  useEffect(() => { void load() }, [load])

  const loadTenants = useCallback(async () => {
    try { setTenants((await dtcService.orderTenants()).data ?? []) } catch { /* filter just stays empty */ }
  }, [])
  useEffect(() => { void loadTenants() }, [loadTenants])

  const sync = async () => {
    setSyncing(true)
    try {
      const r = (await dtcService.syncOracle(tenantId)).data
      if (r.message?.startsWith('Sync failed')) notify.apiError(new Error(r.message), r.message)
      else notify.success(`Fetched ${r.fetched} · imported ${r.imported} · skipped ${r.skipped}`)
      setPageIndex(0)
      await Promise.all([load(), loadTenants()])
    } catch (e) {
      notify.apiError(e, 'Oracle sync failed. Is ORACLE_ENABLED=true on the backend?')
    } finally {
      setSyncing(false)
    }
  }

  const columns = useMemo<ColumnDef<DtcOrder, unknown>[]>(() => [
    { id: 'batchId', accessorKey: 'batchId', header: 'Batch' },
    { id: 'toteNumber', accessorKey: 'toteNumber', header: 'Tote' },
    { id: 'orderNo', accessorKey: 'orderNo', header: 'Order #' },
    { id: 'tenantId', accessorKey: 'tenantId', header: 'Tenant' },
    { id: 'custPo', accessorKey: 'custPo', header: 'Cust PO', enableSorting: false },
    { id: 'shipName', accessorKey: 'shipName', header: 'Ship to' },
    {
      id: 'shipToCity', header: 'City / State', accessorKey: 'shipToCity',
      cell: ({ row }) => [row.original.shipToCity, row.original.shipToState].filter(Boolean).join(', ') || '—',
    },
    { id: 'shipToCountryCode', accessorKey: 'shipToCountryCode', header: 'Country', enableSorting: false },
    {
      id: 'shipVia', header: 'Ship via', accessorKey: 'shipVia',
      cell: ({ row }) => row.original.shipVia || row.original.shipViaCode || '—',
    },
    { id: 'weight', accessorKey: 'weight', header: 'Weight' },
    { id: 'shipDate', accessorKey: 'shipDate', header: 'Ship date' },
    {
      id: 'intlYn', accessorKey: 'intlYn', header: 'Intl', enableSorting: false,
      cell: ({ row }) => (row.original.intlYn === 'Y' ? 'Yes' : 'No'),
    },
    {
      id: 'createdAt', accessorKey: 'createdAt', header: 'Synced',
      cell: ({ row }) => formatDateTime(row.original.createdAt),
    },
  ], [])

  const tenantFilter = (
    <select
      value={tenantId}
      onChange={(e) => { setTenantId(e.target.value); setPageIndex(0) }}
      aria-label="Filter by tenant"
      className="h-[30px] rounded-lg border border-[#e3d9c4] bg-white px-2 text-[12px] font-semibold text-[#5a4526]"
    >
      <option value="">All tenants</option>
      {tenants.map((t) => <option key={t} value={t}>{t}</option>)}
    </select>
  )

  const syncButton = canSync ? (
    <button
      type="button"
      onClick={sync}
      disabled={syncing}
      title={tenantId ? `Sync pending orders for ${tenantId} from Oracle` : 'Sync pending orders for all tenants from Oracle'}
      className="inline-flex items-center gap-1.5 rounded-lg bg-[#1f150c] px-2.5 py-1.5 text-[12px] font-semibold text-[#f4eede] shadow-sm transition hover:bg-[#3a2a18] disabled:opacity-60"
    >
      <FiRefreshCw className={`h-3.5 w-3.5 ${syncing ? 'animate-spin' : ''}`} />
      {syncing ? 'Syncing…' : 'Sync from Oracle'}
    </button>
  ) : null

  const total = data?.totalElements ?? 0

  return (
    <div className="space-y-3 pb-8">
      <div className="flex flex-wrap items-center gap-x-3 gap-y-2 px-1 pt-1">
        <h2
          className="mr-auto flex items-center gap-2 text-[17px] font-semibold tracking-tight text-[#1f150c]"
          title="Pending D2C orders synced from the Oracle NDS view"
        >
          <span className="inline-flex h-7 w-7 shrink-0 items-center justify-center rounded-lg bg-[#1f150c] text-[#f4eede] shadow-sm" aria-hidden="true">
            <FiDownloadCloud className="h-3.5 w-3.5" />
          </span>
          D2C History
        </h2>
        <button
          type="button"
          onClick={() => navigate('/orders')}
          className="inline-flex items-center gap-1.5 rounded-lg border border-[#e3d9c4] bg-white px-2.5 py-1.5 text-[12px] font-semibold text-[#5a4526] transition hover:border-[#cdbf9f] hover:bg-[#faf7f0]"
        >
          <FiArrowLeft className="h-3.5 w-3.5" />
          Orders
        </button>
      </div>

      <section
        aria-busy={loading}
        className={`rounded-2xl border border-slate-200 bg-white p-3 shadow-sm transition-opacity duration-200 ${loading && data ? 'opacity-60' : ''}`}
      >
        <AdvancedDataTable<DtcOrder>
          tableKey="d2c-orders-v1"
          columns={columns}
          data={data?.content ?? []}
          search={{ value: search, onChange: setSearch, placeholder: 'Search batch, tote, order #, PO, name or city…' }}
          filterToggle={tenantFilter}
          toolbarActions={syncButton}
          manualPagination
          manualSorting
          sorting={sorting}
          onSortingChange={(next) => { setSorting(next.length ? next : [{ id: 'createdAt', desc: true }]); setPageIndex(0) }}
          pageIndex={pageIndex}
          pageSize={pageSize}
          pageCount={data?.totalPages ?? 0}
          totalRowCount={total}
          onPaginationChange={({ pageIndex: i, pageSize: n }) => { setPageIndex(n !== pageSize ? 0 : i); setPageSize(n) }}
          getRowId={(o) => String(o.id)}
          renderExpanded={(o) => <OrderDetail order={o} />}
          csvFilename="d2c-orders.csv"
          caption={`${total} synced order${total === 1 ? '' : 's'} · click a row for full details`}
          emptyState={
            <p className="px-5 py-10 text-center text-sm text-[#6b5c42]">
              {loading ? 'Loading…'
                : q || tenantId ? 'No orders match your filters.'
                  : canSync ? 'No D2C orders yet. Use Sync from Oracle to pull the pending orders in.'
                    : 'No D2C orders yet. An admin can use Sync from Oracle to pull them in.'}
            </p>
          }
        />
      </section>
    </div>
  )
}

/** Expanded row: everything the table columns leave out. */
function OrderDetail({ order: o }: { order: DtcOrder }) {
  const address = [o.shipAttn, o.shipAddr1, o.shipAddr2, o.shipAddr3,
    [o.shipToCity, o.shipToState, o.shipToZip].filter(Boolean).join(' '),
    o.countryName || o.shipToCountryCode].filter(Boolean)
  const fields: [string, string | number | null][] = [
    ['Order suffix', o.orderSuffix], ['Order status', o.orderStatus],
    ['Customer #', o.custNo], ['Terms', o.termsCode],
    ['Phone', o.phone], ['Email', o.email],
    ['Unit value', o.unitValue], ['Price', o.price], ['Freight cost', o.freightCost],
    ['Goods', o.goodsDesc], ['Location', o.location], ['Track', o.track],
    ['3rd-party account', o.thirdPartyAccount], ['FF schema', o.ffSchemaSubstr],
  ]
  return (
    <div className="grid gap-4 px-4 py-3 text-[12px] text-[#3d2f1c] sm:grid-cols-[minmax(180px,1fr)_3fr]">
      <div>
        <div className="mb-1 font-semibold text-[#6b5c42]">Ship to</div>
        <div className="font-semibold">{o.shipName || '—'}</div>
        {address.map((line, i) => <div key={i}>{line}</div>)}
      </div>
      <dl className="grid grid-cols-2 gap-x-4 gap-y-1 sm:grid-cols-4">
        {fields.map(([label, value]) => (
          <div key={label} className="min-w-0">
            <dt className="text-[#6b5c42]">{label}</dt>
            <dd className="truncate font-medium">{value === null || value === '' ? '—' : String(value)}</dd>
          </div>
        ))}
      </dl>
    </div>
  )
}

function formatDateTime(iso: string | null) {
  if (!iso) return '—'
  const d = new Date(iso)
  return Number.isNaN(d.getTime()) ? iso : d.toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' })
}
