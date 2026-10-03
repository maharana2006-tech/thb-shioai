import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { FiDownloadCloud, FiEye, FiFilter, FiPrinter, FiX, FiZap } from 'react-icons/fi'
import type { ColumnDef } from '@tanstack/react-table'
import AdvancedDataTable from './workspace/AdvancedDataTable'
import Select from './workspace/Select'
import {
  batchStatusOf, dtcService, labelStatusOf,
  type DtcBatchPage, type DtcBatchStats,
} from '../api/dtcService'
import { ApiError } from '../api/apiClient'
import { useAppSession } from '../hooks/useAppSession'
import { normalizeRole } from '../utils/roles'
import { notify } from '../utils/notify'
import { dtcBatchPath } from '../routes/workspaceRoutes'

/**
 * D2C Automatic Label — Dtcal-style batch summary. One row per (client, batch)
 * in dtc_orders, filled by "Sync from Oracle". Per batch: Details → line
 * history, Print → ZIP of generated labels, Generate → "Automatic label" run
 * (background job, polled here).
 */
export default function DtcOrdersPage() {
  const navigate = useNavigate()
  const { role } = useAppSession()
  const canSync = normalizeRole(role) === 'ADMIN'

  const [pageIndex, setPageIndex] = useState(0)
  const [pageSize, setPageSize] = useState(25)
  const [tenantId, setTenantId] = useState('')
  const [shipDate, setShipDate] = useState('')
  const [labelStatus, setLabelStatus] = useState('')
  const [batchStatus, setBatchStatus] = useState('')
  const [createdFrom, setCreatedFrom] = useState('')
  const [createdTo, setCreatedTo] = useState('')
  /** Free-text search box value; `debouncedQ` is what actually hits the API. */
  const [q, setQ] = useState('')
  const [debouncedQ, setDebouncedQ] = useState('')
  const [showFilters, setShowFilters] = useState(false)
  const [tenants, setTenants] = useState<string[]>([])
  const [shipDates, setShipDates] = useState<string[]>([])
  const [data, setData] = useState<DtcBatchPage | null>(null)
  const [loading, setLoading] = useState(true)
  const [syncing, setSyncing] = useState(false)
  /** Batch row currently running a generation job — its button shows progress. */
  const [activeJob, setActiveJob] = useState<{ key: string; jobId: number } | null>(null)
  // The batch whose Generate was just clicked, until the server answers — a second
  // click in that gap would queue a second run and buy every label twice.
  const [startingKey, setStartingKey] = useState<string | null>(null)
  const [jobProgress, setJobProgress] = useState<{ processed: number; total: number; status: string } | null>(null)

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const res = await dtcService.batches({
        page: pageIndex, size: pageSize, tenantId, shipDate,
        q: debouncedQ, labelStatus, batchStatus, createdFrom, createdTo,
      })
      setData(res.data)
    } catch (e) {
      notify.apiError(e, 'Could not load D2C batches.')
    } finally {
      setLoading(false)
    }
  }, [pageIndex, pageSize, tenantId, shipDate, debouncedQ, labelStatus, batchStatus, createdFrom, createdTo])

  useEffect(() => { void load() }, [load])

  useEffect(() => {
    const t = window.setTimeout(() => setDebouncedQ(q.trim()), 300)
    return () => window.clearTimeout(t)
  }, [q])

  /** Outside-click + Escape on the Filters dropdown. */
  const filterPanelRef = useRef<HTMLDivElement>(null)
  useEffect(() => {
    if (!showFilters) return
    const onClickOutside = (e: MouseEvent) => {
      if (filterPanelRef.current && !filterPanelRef.current.contains(e.target as Node)) {
        setShowFilters(false)
      }
    }
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') setShowFilters(false) }
    document.addEventListener('mousedown', onClickOutside)
    document.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('mousedown', onClickOutside)
      document.removeEventListener('keydown', onKey)
    }
  }, [showFilters])

  const filterCount = [tenantId, shipDate, labelStatus, batchStatus, createdFrom, createdTo].filter(Boolean).length

  const clearFilters = () => {
    setTenantId('')
    setShipDate('')
    setLabelStatus('')
    setBatchStatus('')
    setCreatedFrom('')
    setCreatedTo('')
  }

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- a changed filter invalidates the page you are on; must respond to the filter values, not derivable at render
    setPageIndex(0)
  }, [tenantId, shipDate, labelStatus, batchStatus, createdFrom, createdTo, debouncedQ, pageSize])

  const loadFilters = useCallback(async () => {
    try { setTenants((await dtcService.orderTenants()).data ?? []) } catch { /* filter just stays empty */ }
    try { setShipDates((await dtcService.shipDates()).data ?? []) } catch { /* same */ }
  }, [])
  useEffect(() => { void loadFilters() }, [loadFilters])

  // Poll the active generation job until it finishes, then refresh the summary.
  useEffect(() => {
    if (!activeJob) { setJobProgress(null); return }
    let cancelled = false
    const tick = async () => {
      try {
        const job = (await dtcService.generationJob(activeJob.jobId)).data
        if (cancelled) return
        setJobProgress({ processed: job.processedRows, total: job.totalRows, status: job.status })
        if (job.status === 'DONE' || job.status === 'FAILED') {
          setActiveJob(null)
          if (job.status === 'DONE') {
            notify.success(`Batch labels done — ${job.generatedCount} generated, ${job.failedCount} failed, ${job.skippedCount} skipped.`)
          } else {
            notify.error({ title: 'Label generation failed', body: job.errorMessage ?? 'Unknown error.' })
          }
          await load()
        }
      } catch { /* next poll retries; a persistent failure just stops progress display */ }
    }
    void tick()
    const t = setInterval(() => void tick(), 1500)
    return () => { cancelled = true; clearInterval(t) }
  }, [activeJob, load])

  const sync = async () => {
    setSyncing(true)
    try {
      const r = (await dtcService.syncOracle(tenantId)).data
      if (r.message?.startsWith('Sync failed')) notify.apiError(new Error(r.message), r.message)
      else notify.success(`Fetched ${r.fetched} · imported ${r.imported} · skipped ${r.skipped}`)
      setPageIndex(0)
      await Promise.all([load(), loadFilters()])
    } catch (e) {
      notify.apiError(e, 'Oracle sync failed. Is ORACLE_ENABLED=true on the backend?')
    } finally {
      setSyncing(false)
    }
  }

  const generate = async (b: DtcBatchStats) => {
    const key = rowKey(b)
    if (startingKey === key || activeJob?.key === key) return
    setStartingKey(key)
    try {
      const r = await dtcService.generate(String(b.batchId), b.tenantId)
      setActiveJob({ key, jobId: r.data.job.id })
    } catch (e) {
      // 409 — a job is already active for this batch; ride along on it.
      const job = e instanceof ApiError ? (e.payload?.data as { job?: { id: number } } | undefined)?.job : undefined
      if (e instanceof ApiError && e.status === 409 && job?.id) {
        setActiveJob({ key, jobId: job.id })
        notify.info('A generation run is already in progress for this batch — showing its progress.')
      } else {
        notify.apiError(e, 'Could not start label generation.')
      }
    } finally {
      setStartingKey(null)
    }
  }

  const openZip = (b: DtcBatchStats) => {
    window.open(dtcService.labelsZipUrl(String(b.batchId), b.tenantId), '_blank')
  }

  const columns = useMemo<ColumnDef<DtcBatchStats, unknown>[]>(() => [
    { id: 'tenantId', accessorKey: 'tenantId', header: 'Client', enableSorting: false },
    { id: 'batchId', header: 'Batch No.', accessorKey: 'batchId', enableSorting: false },
    {
      id: 'batchStatus', header: 'Batch Status', enableSorting: false,
      cell: ({ row }) => <StatusBadge tone={batchStatusOf(row.original) === 'COMPLETE' ? 'green' : 'amber'}
        label={batchStatusOf(row.original)} />,
    },
    { id: 'orderOption', header: 'Order Option', enableSorting: false, cell: () => 'New Order' },
    {
      id: 'lastSyncedAt', header: 'Created', accessorKey: 'lastSyncedAt', enableSorting: false,
      cell: ({ row }) => formatDateTime(row.original.lastSyncedAt),
    },
    { id: 'firstTote', header: 'Start Id', accessorKey: 'firstTote', enableSorting: false,
      cell: ({ row }) => row.original.firstTote ?? '—' },
    { id: 'lastTote', header: 'End Id', accessorKey: 'lastTote', enableSorting: false,
      cell: ({ row }) => row.original.lastTote ?? '—' },
    {
      id: 'labelStatus', header: 'Label Status', enableSorting: false,
      cell: ({ row }) => {
        const s = labelStatusOf(row.original)
        return <StatusBadge tone={s === 'GENERATED' ? 'green' : s === 'PARTIAL' ? 'sky' : 'slate'} label={s} />
      },
    },
    {
      id: 'details', header: 'Details', enableSorting: false,
      cell: ({ row }) => (
        <button
          type="button"
          title={`Open shipment history for batch ${row.original.batchId}`}
          onClick={() => navigate(dtcBatchPath(row.original.batchId, row.original.tenantId))}
          className="inline-flex items-center gap-1 rounded-lg border border-[#e3d9c4] bg-white px-2 py-1 text-[11px] font-semibold text-[#5a4526] transition hover:border-[#cdbf9f] hover:bg-[#faf7f0]"
        >
          <FiEye className="h-3 w-3" />
          Details
        </button>
      ),
    },
    {
      id: 'print', header: 'Print', enableSorting: false,
      cell: ({ row }) => {
        const b = row.original
        const disabled = b.generatedCount === 0
        return (
          <button
            type="button"
            disabled={disabled}
            title={disabled ? 'Generate labels first — nothing to print yet' : `Download ${b.generatedCount} label PDF${b.generatedCount === 1 ? '' : 's'} as a ZIP`}
            onClick={() => openZip(b)}
            className="inline-flex items-center gap-1 rounded-lg border border-[#e3d9c4] bg-white px-2 py-1 text-[11px] font-semibold text-[#5a4526] transition hover:border-[#cdbf9f] hover:bg-[#faf7f0] disabled:cursor-not-allowed disabled:opacity-40"
          >
            <FiPrinter className="h-3 w-3" />
            Print
          </button>
        )
      },
    },
    {
      id: 'generate', header: 'Generate', enableSorting: false,
      cell: ({ row }) => {
        const b = row.original
        const active = activeJob?.key === rowKey(b)
        if (active && jobProgress) {
          return (
            <span className="inline-flex items-center gap-1 text-[11px] font-semibold text-[#8a6a3b]" title="Automatic label run in progress">
              <FiZap className="h-3 w-3 animate-pulse" />
              {jobProgress.processed}/{jobProgress.total}
            </span>
          )
        }
        return (
          <button
            type="button"
            title={`Generate shipping labels for batch ${b.batchId} (${b.pendingCount} pending)`}
            onClick={() => void generate(b)}
            disabled={active || startingKey === rowKey(b)}
            className="inline-flex items-center gap-1 rounded-lg bg-[#1f150c] px-2 py-1 text-[11px] font-semibold text-[#f4eede] shadow-sm transition hover:bg-[#3a2a18] disabled:cursor-not-allowed disabled:opacity-60"
          >
            <FiZap className="h-3 w-3" />
            Generate
          </button>
        )
      },
    },
  ], [activeJob, jobProgress, navigate, startingKey])

  const filterLabel = 'block text-[10px] font-bold uppercase tracking-[0.14em] text-slate-400'

  /** Filters dropdown: the toolbar button and the panel it opens. */
  const filters = (
    <div className="relative" ref={filterPanelRef}>
      <button
        type="button"
        onClick={() => setShowFilters((v) => !v)}
        aria-haspopup="true"
        aria-expanded={showFilters}
        aria-controls="d2c-filter-panel"
        className={`inline-flex items-center gap-1.5 rounded-md border px-3 py-1.5 text-[12.5px] font-semibold transition ${
          filterCount
            ? 'border-[#1f150c] bg-[#1f150c] text-white'
            : 'border-slate-200 bg-white text-slate-700 hover:bg-slate-50'
        }`}
      >
        <FiFilter className="h-3.5 w-3.5" />
        Filters
        {filterCount ? (
          <span className="rounded-full bg-white/20 px-1.5 text-[10px] font-bold">{filterCount}</span>
        ) : null}
      </button>

      {showFilters && (
        <div
          id="d2c-filter-panel"
          role="region"
          aria-label="Batch filters"
          className="absolute right-0 z-30 mt-1 w-80 rounded-lg border border-slate-200 bg-white p-3 shadow-lg"
        >
          <label className={filterLabel} htmlFor="d2c-f-client">Client</label>
          <Select
            id="d2c-f-client"
            className="mt-1"
            value={tenantId}
            onChange={(e) => setTenantId(e.target.value)}
          >
            <option value="">All clients</option>
            {tenants.map((t) => <option key={t} value={t}>{t}</option>)}
          </Select>

          <label className={`${filterLabel} mt-3`} htmlFor="d2c-f-shipdate">Ship date</label>
          <Select
            id="d2c-f-shipdate"
            className="mt-1"
            value={shipDate}
            onChange={(e) => setShipDate(e.target.value)}
          >
            <option value="">All ship dates</option>
            {shipDates.map((d) => <option key={d} value={d}>{d}</option>)}
          </Select>

          <label className={`${filterLabel} mt-3`} htmlFor="d2c-f-label">Label status</label>
          <Select
            id="d2c-f-label"
            className="mt-1"
            value={labelStatus}
            onChange={(e) => setLabelStatus(e.target.value)}
          >
            <option value="">Any label status</option>
            <option value="GENERATED">Generated</option>
            <option value="PARTIAL">Partial</option>
            <option value="PENDING">Not started</option>
          </Select>

          <label className={`${filterLabel} mt-3`} htmlFor="d2c-f-batch">Batch status</label>
          <Select
            id="d2c-f-batch"
            className="mt-1"
            value={batchStatus}
            onChange={(e) => setBatchStatus(e.target.value)}
          >
            <option value="">Any batch status</option>
            <option value="COMPLETE">Complete</option>
            <option value="OPEN">Open</option>
          </Select>

          <div className="mt-3 grid grid-cols-2 gap-2">
            <div>
              <label className={filterLabel} htmlFor="d2c-f-from">Synced from</label>
              <input
                id="d2c-f-from"
                type="date"
                value={createdFrom}
                onChange={(e) => setCreatedFrom(e.target.value)}
                className="mt-1 w-full rounded-xl border border-slate-200 bg-white px-2 py-2 text-[12.5px] font-semibold text-slate-700 shadow-sm outline-none transition hover:border-slate-300 focus:border-[#412d15] focus:ring-4 focus:ring-[#412d15]/10"
              />
            </div>
            <div>
              <label className={filterLabel} htmlFor="d2c-f-to">Synced to</label>
              <input
                id="d2c-f-to"
                type="date"
                value={createdTo}
                onChange={(e) => setCreatedTo(e.target.value)}
                className="mt-1 w-full rounded-xl border border-slate-200 bg-white px-2 py-2 text-[12.5px] font-semibold text-slate-700 shadow-sm outline-none transition hover:border-slate-300 focus:border-[#412d15] focus:ring-4 focus:ring-[#412d15]/10"
              />
            </div>
          </div>

          {filterCount ? (
            <button
              type="button"
              onClick={clearFilters}
              className="mt-3 inline-flex items-center gap-1 text-[11.5px] font-semibold text-slate-600 hover:text-slate-950"
            >
              <FiX className="h-3 w-3" /> Clear filters
            </button>
          ) : null}
        </div>
      )}
    </div>
  )

  const syncButton = canSync ? (
    <button
      type="button"
      onClick={sync}
      disabled={syncing}
      title={tenantId ? `Sync pending orders for ${tenantId} from Oracle` : 'Sync pending orders for all clients from Oracle'}
      className="inline-flex items-center gap-1.5 rounded-lg bg-[#1f150c] px-2.5 py-1.5 text-[12px] font-semibold text-[#f4eede] shadow-sm transition hover:bg-[#3a2a18] disabled:opacity-60"
    >
      <FiDownloadCloud className={`h-3.5 w-3.5 ${syncing ? 'animate-pulse' : ''}`} />
      {syncing ? 'Syncing…' : 'Sync from Oracle'}
    </button>
  ) : null

  const total = data?.totalElements ?? 0

  return (
    <div className="space-y-3 pb-8">
      <div className="flex flex-wrap items-center gap-x-3 gap-y-2 px-1 pt-1">
        <h2
          className="mr-auto flex items-center gap-2 text-[17px] font-semibold tracking-tight text-[#1f150c]"
          title="D2C Automatic Label — batches synced from the Oracle NDS view"
        >
          <span className="inline-flex h-7 w-7 shrink-0 items-center justify-center rounded-lg bg-[#1f150c] text-[#f4eede] shadow-sm" aria-hidden="true">
            <FiDownloadCloud className="h-3.5 w-3.5" />
          </span>
          D2C Automatic Label
        </h2>
      </div>

      <section
        aria-busy={loading}
        className={`rounded-2xl border border-slate-200 bg-white p-3 shadow-sm transition-opacity duration-200 ${loading && data ? 'opacity-60' : ''}`}
      >
        <AdvancedDataTable<DtcBatchStats>
          tableKey="d2c-batches-v1"
          columns={columns}
          data={data?.content ?? []}
          filterToggle={filters}
          search={{
            value: q,
            onChange: setQ,
            placeholder: 'Search batch no, tote, order no, PO, ship-to name or city',
          }}
          toolbarActions={syncButton}
          manualPagination
          pageIndex={pageIndex}
          pageSize={pageSize}
          pageCount={data?.totalPages ?? 0}
          totalRowCount={total}
          onPaginationChange={({ pageIndex: i, pageSize: n }) => { setPageIndex(n !== pageSize ? 0 : i); setPageSize(n) }}
          getRowId={rowKey}
          csvFilename="d2c-batches.csv"
          caption={`${total} batch${total === 1 ? '' : 'es'} · Generate mints the labels, Print downloads them as a ZIP`}
          emptyState={
            <p className="px-5 py-10 text-center text-sm text-[#6b5c42]">
              {loading ? 'Loading…'
                : filterCount > 0 || debouncedQ ? 'No batches match your search or filters.'
                  : canSync ? 'No D2C batches yet. Use Sync from Oracle to pull the pending orders in.'
                    : 'No D2C batches yet. An admin can use Sync from Oracle to pull them in.'}
            </p>
          }
        />
      </section>
    </div>
  )
}

function rowKey(b: DtcBatchStats): string {
  return `${b.tenantId}:${b.batchId}`
}

function StatusBadge({ tone, label }: { tone: 'green' | 'amber' | 'sky' | 'slate'; label: string }) {
  const tones: Record<typeof tone, string> = {
    green: 'bg-emerald-50 text-emerald-700 border-emerald-200',
    amber: 'bg-amber-50 text-amber-700 border-amber-200',
    sky: 'bg-sky-50 text-sky-700 border-sky-200',
    slate: 'bg-slate-50 text-slate-600 border-slate-200',
  }
  return (
    <span className={`inline-flex items-center rounded-full border px-2 py-0.5 text-[11px] font-semibold ${tones[tone]}`}>
      {label}
    </span>
  )
}

function formatDateTime(iso: string | null) {
  if (!iso) return '—'
  const d = new Date(iso)
  return Number.isNaN(d.getTime()) ? iso : d.toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' })
}
