import { useCallback, useEffect, useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { FiDownloadCloud, FiEye, FiPrinter, FiZap } from 'react-icons/fi'
import type { ColumnDef } from '@tanstack/react-table'
import AdvancedDataTable from './workspace/AdvancedDataTable'
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
  const [tenants, setTenants] = useState<string[]>([])
  const [shipDates, setShipDates] = useState<string[]>([])
  const [data, setData] = useState<DtcBatchPage | null>(null)
  const [loading, setLoading] = useState(true)
  const [syncing, setSyncing] = useState(false)
  /** Batch row currently running a generation job — its button shows progress. */
  const [activeJob, setActiveJob] = useState<{ key: string; jobId: number } | null>(null)
  const [jobProgress, setJobProgress] = useState<{ processed: number; total: number; status: string } | null>(null)

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const res = await dtcService.batches({ page: pageIndex, size: pageSize, tenantId, shipDate })
      setData(res.data)
    } catch (e) {
      notify.apiError(e, 'Could not load D2C batches.')
    } finally {
      setLoading(false)
    }
  }, [pageIndex, pageSize, tenantId, shipDate])

  useEffect(() => { void load() }, [load])

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
    }
  }

  const openZip = (b: DtcBatchStats) => {
    window.open(dtcService.labelsZipUrl(String(b.batchId), b.tenantId), '_blank')
  }

  const columns = useMemo<ColumnDef<DtcBatchStats, unknown>[]>(() => [
    { id: 'tenantId', accessorKey: 'tenantId', header: 'Client', enableSorting: false },
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
            className="inline-flex items-center gap-1 rounded-lg bg-[#1f150c] px-2 py-1 text-[11px] font-semibold text-[#f4eede] shadow-sm transition hover:bg-[#3a2a18]"
          >
            <FiZap className="h-3 w-3" />
            Generate
          </button>
        )
      },
    },
  ], [activeJob, jobProgress, navigate])

  const filters = (
    <>
      <select
        value={tenantId}
        onChange={(e) => { setTenantId(e.target.value); setPageIndex(0) }}
        aria-label="Filter by client"
        className="h-[30px] rounded-lg border border-[#e3d9c4] bg-white px-2 text-[12px] font-semibold text-[#5a4526]"
      >
        <option value="">All clients</option>
        {tenants.map((t) => <option key={t} value={t}>{t}</option>)}
      </select>
      <select
        value={shipDate}
        onChange={(e) => { setShipDate(e.target.value); setPageIndex(0) }}
        aria-label="Filter by ship date"
        className="h-[30px] rounded-lg border border-[#e3d9c4] bg-white px-2 text-[12px] font-semibold text-[#5a4526]"
      >
        <option value="">All ship dates</option>
        {shipDates.map((d) => <option key={d} value={d}>{d}</option>)}
      </select>
    </>
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
                : tenantId || shipDate ? 'No batches match your filters.'
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
