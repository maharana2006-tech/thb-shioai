import { Suspense, useCallback, useEffect, useMemo, useState } from 'react'
import { lazyWithRetry } from '../utils/lazyWithRetry'
import { useNavigate, useParams, useSearchParams } from 'react-router-dom'
import { FiAlertCircle, FiArrowLeft, FiChevronDown, FiEdit2, FiFilter, FiPrinter, FiX, FiZap } from 'react-icons/fi'
import AdvancedDataTable, { type ColumnDef } from './workspace/AdvancedDataTable'
import { useDismissable } from '../hooks/useDismissable'
import {
  batchStatusOf, canEditLine, dtcService, labelStatusOf, lineHasError, printableCount,
  type DtcBatchDetail, type DtcOrder,
} from '../api/dtcService'
import DtcLineDetailsModal from './dtc/DtcLineDetailsModal'
import { orderService } from '../api/orderService'
import { ApiError } from '../api/apiClient'
import { confirmBatchGenerate } from '../utils/dtcConfirm'
import { summarizeCarrierError } from '../utils/carrierErrorMap'
import { notify } from '../utils/notify'
import { workspacePaths } from '../routes/workspaceRoutes'

const OrderDetailsModal = lazyWithRetry(() => import('./modals/OrderDetailsModal'))

/**
 * DTC Shipment History — HstDetails-style line detail for one batch.
 * Columns mirror the reference: Client Code | Order No | Label Order |
 * Carrier | Batch No. | Tote No. | Tracking Id | Ship Date | Label | Label Status.
 * Order No is the ERP order the line was synced from, so every line has one,
 * labelled or not. Label Order is the Multiship order a label run minted, and
 * Label Status this app's result for the line; it stays "Not generated" until a run
 * touches it. The ERP also syncs its own order status code (S/J), but nothing decodes
 * it, so it is left off the screen. Print reuses the order label PDF endpoint; Void
 * reuses POST /orders/{orderNo}/void against the generated order number. A line that carries an error gets an Edit button
 * that opens the manual shipment form in fix mode for its order
 * (/orders/new?fixOrder=…), where the operator corrects the data and
 * regenerates the same order; the batch Generate then picks the line up.
 */
export default function DtcBatchDetailPage() {
  const navigate = useNavigate()
  const { batchId = '' } = useParams()
  const [searchParams] = useSearchParams()
  const tenantId = searchParams.get('tenant') ?? ''

  const [pageIndex, setPageIndex] = useState(0)
  const pageSize = 50
  const [data, setData] = useState<DtcBatchDetail | null>(null)
  const [loading, setLoading] = useState(true)
  const [activeJobId, setActiveJobId] = useState<number | null>(null)
  // Locks Generate from the click until the server has answered — a second click
  // in that gap would queue a second run and buy every label twice.
  const [starting, setStarting] = useState(false)
  const [jobProgress, setJobProgress] = useState<{ processed: number; total: number; status: string } | null>(null)
  /** Order whose details the drill-down modal shows (set by the Order No cell). */
  const [detailsOrderNo, setDetailsOrderNo] = useState<number | null>(null)
  /** Shipment line the detail modal shows (set by clicking anywhere on a row). */
  const [detailsLine, setDetailsLine] = useState<DtcOrder | null>(null)
  /** Search box value; `debouncedQ` is what hits the API. */
  const [q, setQ] = useState('')
  const [debouncedQ, setDebouncedQ] = useState('')
  const [showFilters, setShowFilters] = useState(false)
  const closeFilters = useCallback(() => setShowFilters(false), [])
  const filterRef = useDismissable(showFilters, closeFilters)
  const [status, setStatus] = useState('')
  const [carrier, setCarrier] = useState('')
  const [shipDate, setShipDate] = useState('')

  useEffect(() => {
    const t = window.setTimeout(() => setDebouncedQ(q.trim()), 300)
    return () => window.clearTimeout(t)
  }, [q])

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- a changed filter invalidates the page you are on
    setPageIndex(0)
  }, [debouncedQ, status, carrier, shipDate])

  const load = useCallback(async () => {
    if (!batchId || !tenantId) return
    setLoading(true)
    try {
      const res = await dtcService.batchDetail(batchId, {
        page: pageIndex, size: pageSize, tenantId, q: debouncedQ, status, carrier, shipDate,
      })
      setData(res.data)
    } catch (e) {
      notify.apiError(e, `Could not load batch ${batchId}.`)
    } finally {
      setLoading(false)
    }
  }, [batchId, tenantId, pageIndex, debouncedQ, status, carrier, shipDate])

  useEffect(() => { void load() }, [load])

  useEffect(() => {
    if (!activeJobId) { setJobProgress(null); return }
    let cancelled = false
    const tick = async () => {
      try {
        const job = (await dtcService.generationJob(activeJobId)).data
        if (cancelled) return
        setJobProgress({ processed: job.processedRows, total: job.totalRows, status: job.status })
        if (job.status === 'DONE' || job.status === 'FAILED') {
          setActiveJobId(null)
          if (job.status === 'DONE') {
            notify.success(`Batch labels done — ${job.generatedCount} generated, ${job.failedCount} failed, ${job.skippedCount} skipped.`)
          } else {
            notify.error({ title: 'Label generation failed', body: job.errorMessage ?? 'Unknown error.' })
          }
          await load()
        }
      } catch { /* next poll retries */ }
    }
    void tick()
    const t = setInterval(() => void tick(), 1500)
    return () => { cancelled = true; clearInterval(t) }
  }, [activeJobId, load])

  const generate = async () => {
    if (starting || activeJobId) return
    setStarting(true)
    try {
      // No counts yet → nothing to tell the operator what will be bought; wait for them.
      if (!data?.batch) { notify.info('Still loading this batch — try again in a moment.'); return }
      if (!(await confirmBatchGenerate(data.batch))) return
      const r = await dtcService.generate(batchId, tenantId)
      setActiveJobId(r.data.job.id)
    } catch (e) {
      // 409 — a run is already active for this batch; follow it instead.
      const job = e instanceof ApiError ? (e.payload?.data as { job?: { id: number } } | undefined)?.job : undefined
      if (e instanceof ApiError && e.status === 409 && job?.id) {
        setActiveJobId(job.id)
        notify.info('A generation run is already in progress for this batch — showing its progress.')
      } else {
        notify.apiError(e, 'Could not start label generation.')
      }
    } finally {
      setStarting(false)
    }
  }

  const voidLabel = async (o: DtcOrder) => {
    if (!o.generatedOrderNo) return
    const ok = await notify.confirm(`Void label for order ${o.generatedOrderNo} (${o.shipName ?? o.toteNumber})? This cancels the label at the carrier.`, {
      title: 'Void label',
      confirmLabel: 'Void',
      danger: true,
    })
    if (!ok) return
    try {
      // A carrier refusal comes back as 200 with voided=false — the label is still live.
      const res = await orderService.voidLabel(o.generatedOrderNo)
      const r = res.data
      if (r?.voided || r?.status === 'ALREADY_VOIDED') {
        notify.success(`Order ${o.generatedOrderNo} voided.`)
      } else {
        notify.error(`${r?.carrierCode ?? 'The carrier'} refused to void order ${o.generatedOrderNo} — the label is still live.${r?.message ? ` ${r.message}` : ''}`)
      }
      await load()
    } catch (e) {
      notify.apiError(e, `Could not void order ${o.generatedOrderNo}.`)
    }
  }

  /**
   * An errored line is corrected on the manual shipment form. When the run
   * minted an order, the form opens in fix mode for it (regenerating keeps the
   * order number); when it failed before any order existed, the form is
   * pre-filled from the line itself and links the label it buys back to it.
   */
  const fixLine = (o: DtcOrder) => {
    navigate(o.generatedOrderNo
      ? `/orders/new?fixOrder=${o.generatedOrderNo}`
      : `/orders/new?dtcLine=${o.id}&batch=${Number(o.batchId)}&tenant=${encodeURIComponent(o.tenantId)}`)
  }
  /** Only a line whose label run failed can be fixed — a pending line has nothing to fix yet. */
  const fixable = (o: DtcOrder) => lineHasError(o) && (!!o.generatedOrderNo || canEditLine(o))

  const columns = useMemo<ColumnDef<DtcOrder, unknown>[]>(() => [
    { id: 'tenantId', accessorKey: 'tenantId', header: 'Client Code', enableSorting: false },
    { id: 'orderNo', header: 'Order No', accessorKey: 'orderNo', enableSorting: false,
      cell: ({ row }) => (
        <button
          type="button"
          onClick={() => setDetailsLine(row.original)}
          title="Open this shipment line"
          className="font-mono text-[12px] font-semibold text-[var(--e-412d15)] underline decoration-[var(--e-cdbf9f)] underline-offset-2 transition hover:decoration-[var(--e-412d15)]"
        >
          {row.original.orderNo ?? '—'}
        </button>
      ) },
    { id: 'generatedOrderNo', header: 'Label Order', accessorKey: 'generatedOrderNo', enableSorting: false,
      cell: ({ row }) => {
        const orderNo = row.original.generatedOrderNo
        if (!orderNo) return <span className="text-[11px] text-[var(--e-9a8b70)]">—</span>
        return (
          <button
            type="button"
            title={`Open the details for order ${orderNo}`}
            onClick={() => setDetailsOrderNo(orderNo)}
            className="font-mono text-[12px] font-semibold text-[var(--e-412d15)] underline decoration-[var(--e-cdbf9f)] underline-offset-2 transition hover:decoration-[var(--e-412d15)]"
          >
            {orderNo}
          </button>
        )
      } },
    { id: 'carrier', header: 'Carrier', enableSorting: false,
      cell: ({ row }) => row.original.generatedCarrierCode ?? row.original.shipVia ?? row.original.shipViaCode ?? '—' },
    { id: 'batchId', header: 'Batch No.', accessorKey: 'batchId', enableSorting: false },
    { id: 'toteNumber', accessorKey: 'toteNumber', header: 'Tote No.', enableSorting: false },
    { id: 'tracking', header: 'Tracking Id', enableSorting: false, size: 200,
      cell: ({ row }) => {
        const t = row.original.generatedTrackingNumber
        return t ? <span className="block truncate font-mono text-[11.5px]" title={t}>{t}</span> : '—'
      } },
    { id: 'shipDate', accessorKey: 'shipDate', header: 'Ship Date', enableSorting: false,
      cell: ({ row }) => row.original.shipDate ?? '—' },
    {
      id: 'label', header: 'Label', enableSorting: false,
      cell: ({ row }) => {
        const orderNo = row.original.generatedOrderNo
        // Only a live label prints: not one whose run failed, and not a voided
        // one (cancelled at the carrier — it must not go onto a parcel).
        if (!orderNo || row.original.generatedStatus !== 'GENERATED'
            || data?.voidStatuses?.[String(orderNo)] === 'VOIDED') {
          return <span className="text-[11px] text-[var(--e-9a8b70)]">—</span>
        }
        return (
          <button
            type="button"
            title={`Open the label PDF for order ${orderNo}`}
            onClick={() => window.open(dtcService.labelPdfUrl(orderNo), '_blank')}
            className="inline-flex items-center gap-1 rounded-lg border border-[var(--e-e3d9c4)] bg-white px-2 py-1 text-[11px] font-semibold text-[var(--e-5a4526)] transition hover:border-[var(--e-cdbf9f)] hover:bg-[var(--e-faf7f0)]"
          >
            <FiPrinter className="h-3 w-3" />
            Print
          </button>
        )
      },
    },
    {
      id: 'status', header: 'Label Status', enableSorting: false,
      cell: ({ row }) => {
        const o = row.original
        const trackingStatus = o.generatedOrderNo
          ? data?.voidStatuses?.[String(o.generatedOrderNo)]
          : undefined
        if (trackingStatus === 'VOIDED') {
          return <span className="inline-flex items-center rounded-full border border-rose-200 bg-rose-50 px-2 py-0.5 text-[11px] font-semibold text-rose-700">VOIDED</span>
        }
        const errored = lineHasError(o)
        const editButton = fixable(o) ? (
          <button
            type="button"
            title={o.generatedOrderNo
              ? `Open the manual shipment form to fix order ${o.generatedOrderNo}`
              : 'Open the manual shipment form pre-filled from this line'}
            onClick={() => fixLine(o)}
            className="inline-flex items-center gap-1 rounded-lg border border-amber-300 bg-amber-50 px-2 py-1 text-[11px] font-semibold text-amber-800 transition hover:bg-amber-100"
          >
            <FiEdit2 className="h-3 w-3" />
            Edit
          </button>
        ) : null
        const pill = !o.generatedStatus ? (
          <span
            title="No label run has touched this line yet"
            className="inline-flex items-center rounded-full border border-slate-200 bg-slate-50 px-2 py-0.5 text-[11px] font-semibold text-slate-500"
          >
            Not generated
          </span>
        ) : (
          <span
            title={o.generatedStatus === 'IN_FLIGHT' ? 'The label is being bought right now' : undefined}
            className={`inline-flex items-center rounded-full border px-2 py-0.5 text-[11px] font-semibold ${statusPillClass(o.generatedStatus)}`}
          >
            {o.generatedStatus === 'IN_FLIGHT' ? 'Buying…' : o.generatedStatus}
          </span>
        )
        return (
          <div className="min-w-0">
            <div className="flex items-center gap-2">
              {pill}
              {editButton}
              {!errored && o.generatedOrderNo && o.generatedStatus === 'GENERATED' && (
                <button
                  type="button"
                  title={`Void label for order ${o.generatedOrderNo}`}
                  onClick={() => void voidLabel(o)}
                  className="inline-flex items-center rounded-lg border border-rose-200 bg-white px-2 py-1 text-[11px] font-semibold text-rose-700 transition hover:bg-rose-50"
                >
                  Void
                </button>
              )}
            </div>
            {/* Why it failed, readable at a glance — the full carrier text stays on hover. */}
            {errored && o.generatedMessage ? (
              <p className="mt-1 max-w-[20rem] truncate text-[11px] text-red-700" title={o.generatedMessage}>
                {summarizeCarrierError(o.generatedMessage)}
              </p>
            ) : null}
          </div>
        )
      },
    },
  ], [data, load])

  if (!tenantId) {
    return (
      <p className="px-5 py-10 text-center text-sm text-[var(--e-6b5c42)]">
        Missing client (tenant) for this batch — go back to Automatic Label and open the batch from there.
      </p>
    )
  }

  const batch = data?.batch
  const total = data?.totalElements ?? 0
  const totalPages = data?.totalPages ?? 0
  const filterCount = [status, carrier, shipDate].filter(Boolean).length
  const counts = data?.statusCounts ?? {}
  const anyFilter = filterCount > 0 || !!debouncedQ
  const statusPills = STATUS_FILTERS.filter((f) => !f.value || counts[f.value] || status === f.value)
  const selectCls = 'h-9 w-full cursor-pointer appearance-none rounded-lg border border-[var(--e-e3d9c4)] bg-white bg-[length:12px] bg-[right_0.7rem_center] bg-no-repeat pl-3 pr-8 text-[12.5px] font-semibold text-[var(--e-3d2f1c)] outline-none transition hover:border-[var(--e-cdbf9f)] focus:border-[var(--e-412d15)] focus:ring-4 focus:ring-[var(--e-f0e9d8)]'
  const sectionLabel = 'mb-1.5 block text-[10.5px] font-semibold uppercase tracking-[0.1em] text-[var(--e-a1906d)]'

  /** Filters dropdown: Label status pills (with each status's line count), carrier and ship date. */
  const filters = (
    <div className="relative" ref={filterRef}>
      <button
        type="button"
        onClick={() => setShowFilters((v) => !v)}
        aria-haspopup="true"
        aria-expanded={showFilters}
        aria-controls="d2c-line-filter-panel"
        className={`inline-flex h-8 items-center gap-1.5 rounded-lg border px-3 text-[12.5px] font-semibold transition ${
          filterCount
            ? 'border-[var(--e-1f150c)] bg-[var(--e-1f150c)] text-[var(--e-f4eede)] hover:bg-[var(--e-412d15)]'
            : 'border-[var(--e-e3d9c4)] bg-white text-[var(--e-5a4526)] hover:border-[var(--e-cdbf9f)] hover:bg-[var(--e-faf7f0)]'}`}
      >
        <FiFilter className="h-3.5 w-3.5" />
        Filters
        {filterCount ? <span className="rounded-full bg-white/20 px-1.5 text-[10.5px] font-bold tabular-nums">{filterCount}</span> : null}
        <FiChevronDown className={`h-3.5 w-3.5 transition-transform ${showFilters ? 'rotate-180' : ''}`} />
      </button>

      {showFilters && (
        <div
          id="d2c-line-filter-panel"
          role="region"
          aria-label="Shipment line filters"
          className="bulk-pop-in absolute right-0 z-30 mt-1.5 w-[22rem] overflow-hidden rounded-xl border border-[var(--e-e3d9c4)] bg-white shadow-[0_16px_40px_rgba(31,21,12,0.16)]"
        >
          <div className="flex items-center justify-between border-b border-[var(--e-f2ecdf)] bg-[var(--e-fcfaf5)] px-4 py-2.5">
            <p className="text-[13px] font-semibold text-[var(--e-1f150c)]">Filter lines</p>
            {filterCount ? (
              <button
                type="button"
                onClick={() => { setStatus(''); setCarrier(''); setShipDate('') }}
                className="inline-flex items-center gap-1 text-[11.5px] font-semibold text-[var(--e-8a7a5a)] transition hover:text-[var(--e-1f150c)]"
              >
                <FiX className="h-3 w-3" /> Clear all
              </button>
            ) : null}
          </div>

          <div className="space-y-4 px-4 py-3.5">
            <div>
              <span className={sectionLabel}>Label status</span>
              <div role="radiogroup" aria-label="Label status" className="flex flex-wrap gap-1.5">
                {statusPills.map((f) => {
                  const active = status === f.value
                  const n = f.value ? counts[f.value] ?? 0 : batch?.totalLines ?? 0
                  return (
                    <button
                      key={f.value || 'all'}
                      type="button"
                      role="radio"
                      aria-checked={active}
                      onClick={() => setStatus(f.value)}
                      className={`inline-flex h-7 items-center gap-1.5 rounded-full border px-2.5 text-[11.5px] font-semibold transition ${
                        active
                          ? 'border-[var(--e-1f150c)] bg-[var(--e-1f150c)] text-[var(--e-f4eede)]'
                          : 'border-[var(--e-e3d9c4)] bg-white text-[var(--e-5a4526)] hover:border-[var(--e-cdbf9f)] hover:bg-[var(--e-faf7f0)]'}`}
                    >
                      {f.dot ? <span className={`h-1.5 w-1.5 rounded-full ${f.dot}`} aria-hidden="true" /> : null}
                      {f.label}
                      <span className={`rounded-full px-1.5 text-[10px] tabular-nums ${active ? 'bg-white/15' : 'bg-[var(--e-f4eede)] text-[var(--e-6b5c42)]'}`}>{n}</span>
                    </button>
                  )
                })}
              </div>
            </div>

            <div className="grid grid-cols-2 gap-3">
              <label className="block">
                <span className={sectionLabel}>Carrier</span>
                <select value={carrier} onChange={(e) => setCarrier(e.target.value)} className={selectCls} style={{ backgroundImage: CHEVRON }}>
                  <option value="">All carriers</option>
                  {(data?.carriers ?? []).map((c) => <option key={c} value={c}>{c}</option>)}
                </select>
              </label>
              <label className="block">
                <span className={sectionLabel}>Ship date</span>
                <select value={shipDate} onChange={(e) => setShipDate(e.target.value)} className={selectCls} style={{ backgroundImage: CHEVRON }}>
                  <option value="">All dates</option>
                  {(data?.shipDates ?? []).map((d) => <option key={d} value={d}>{d}</option>)}
                </select>
              </label>
            </div>
          </div>

          <div className="flex items-center justify-between border-t border-[var(--e-f2ecdf)] bg-[var(--e-fcfaf5)] px-4 py-2.5">
            <p className="text-[11.5px] text-[var(--e-8a7a5a)]">
              <b className="tabular-nums text-[var(--e-1f150c)]">{total}</b> of {batch?.totalLines ?? total} lines
            </p>
            <button
              type="button"
              onClick={closeFilters}
              className="rounded-lg bg-[var(--e-1f150c)] px-3 py-1.5 text-[12px] font-semibold text-[var(--e-f4eede)] shadow-sm transition hover:bg-[var(--e-412d15)]"
            >
              Done
            </button>
          </div>
        </div>
      )}
    </div>
  )

  return (
    <div className="space-y-3 pb-8">
      <div className="flex flex-wrap items-center gap-x-3 gap-y-2 px-1 pt-1">
        <h2 className="mr-auto flex items-center gap-2 text-[17px] font-semibold tracking-tight text-[var(--e-1f150c)]">
          DTC Shipment History — Batch {batchId}
        </h2>
        <button
          type="button"
          onClick={() => navigate(workspacePaths.d2c)}
          className="inline-flex items-center gap-1.5 rounded-lg border border-[var(--e-e3d9c4)] bg-white px-2.5 py-1.5 text-[12px] font-semibold text-[var(--e-5a4526)] transition hover:border-[var(--e-cdbf9f)] hover:bg-[var(--e-faf7f0)]"
        >
          <FiArrowLeft className="h-3.5 w-3.5" />
          Back
        </button>
        <button
          type="button"
          onClick={generate}
          disabled={!!activeJobId || starting}
          title="Generate shipping labels for every pending line in this batch"
          className="inline-flex items-center gap-1.5 rounded-lg bg-[var(--e-1f150c)] px-2.5 py-1.5 text-[12px] font-semibold text-[var(--e-f4eede)] shadow-sm transition hover:bg-[var(--e-3a2a18)] disabled:opacity-60"
        >
          <FiZap className="h-3.5 w-3.5" />
          {activeJobId && jobProgress ? `Generating ${jobProgress.processed}/${jobProgress.total}…` : 'Generate labels'}
        </button>
        <button
          type="button"
          onClick={() => window.open(dtcService.labelsZipUrl(batchId, tenantId), '_blank')}
          disabled={!batch || printableCount(batch) === 0}
          title={!batch || printableCount(batch) === 0
            ? (batch?.voidedCount ? 'Every label of this batch was voided — nothing to print' : 'Generate labels first — nothing to print yet')
            : 'Download all live label PDFs as a ZIP (voided labels are left out)'}
          className="inline-flex items-center gap-1.5 rounded-lg border border-[var(--e-e3d9c4)] bg-white px-2.5 py-1.5 text-[12px] font-semibold text-[var(--e-5a4526)] transition hover:border-[var(--e-cdbf9f)] hover:bg-[var(--e-faf7f0)] disabled:cursor-not-allowed disabled:opacity-40"
        >
          <FiPrinter className="h-3.5 w-3.5" />
          Print all
        </button>
      </div>

      {batch && (
        <div className="flex flex-wrap items-center gap-x-4 gap-y-1 px-1 text-[12px] text-[var(--e-6b5c42)]">
          <span>Client <b className="text-[var(--e-3d2f1c)]">{batch.tenantId}</b></span>
          <span>{batch.totalLines} line{batch.totalLines === 1 ? '' : 's'}</span>
          <span>{batch.generatedCount} generated</span>
          {batch.voidedCount ? <span className="font-semibold text-rose-700">{batch.voidedCount} voided</span> : null}
          {batch.failedCount > 0 && (
            <span className="inline-flex items-center gap-1 font-semibold text-red-700" title="Use Edit on a failed line to correct the ship-to, weight or ship-via, then Generate labels again.">
              <FiAlertCircle className="h-3.5 w-3.5" />
              {batch.failedCount} failed
            </span>
          )}
          {batch.pendingCount + batch.queuedCount > 0 && <span>{batch.pendingCount + batch.queuedCount} pending</span>}
          <span>Batch status <b className="text-[var(--e-3d2f1c)]">{batchStatusOf(batch)}</b></span>
          <span>Labels <b className="text-[var(--e-3d2f1c)]">{labelStatusOf(batch)}</b></span>
        </div>
      )}

      <section
        aria-busy={loading}
        className={`rounded-2xl border border-slate-200 bg-white p-3 shadow-sm transition-opacity duration-200 ${loading && data ? 'opacity-60' : ''}`}
      >
        <AdvancedDataTable<DtcOrder>
          tableKey={`d2c-batch-${batchId}-${tenantId}-v2`}
          columns={columns}
          data={data?.content ?? []}
          filterToggle={filters}
          search={{
            value: q,
            onChange: setQ,
            placeholder: 'Search order no, label order, tote, tracking, PO or ship-to name',
          }}
          manualPagination
          pageIndex={pageIndex}
          pageSize={pageSize}
          pageCount={totalPages}
          totalRowCount={total}
          onPaginationChange={({ pageIndex: i }) => setPageIndex(i)}
          getRowId={(o) => String(o.id)}
          csvFilename={`dtc-batch-${batchId}.csv`}
          caption={anyFilter
            ? `${total} of ${batch?.totalLines ?? total} shipment lines in batch ${batchId} match`
            : `${total} shipment line${total === 1 ? '' : 's'} in batch ${batchId}`}
          emptyState={
            <p className="px-5 py-10 text-center text-sm text-[var(--e-6b5c42)]">
              {loading ? 'Loading…'
                : anyFilter ? 'No shipment lines match your search or filters.'
                  : `No shipment lines for batch ${batchId}.`}
            </p>
          }
        />
      </section>

      {detailsLine && (
        <DtcLineDetailsModal
          line={detailsLine}
          onClose={() => setDetailsLine(null)}
          onEdit={fixable(detailsLine) ? () => fixLine(detailsLine) : undefined}
          onOpenOrder={
            detailsLine.generatedOrderNo
              ? () => {
                  setDetailsOrderNo(detailsLine.generatedOrderNo!)
                  setDetailsLine(null)
                }
              : undefined
          }
        />
      )}

      {detailsOrderNo !== null && (
        <Suspense fallback={null}>
          <OrderDetailsModal orderNo={detailsOrderNo} onClose={() => setDetailsOrderNo(null)} />
        </Suspense>
      )}
    </div>
  )
}

/** Label Status filter pills, in the order a batch moves through them. */
const STATUS_FILTERS: { value: string; label: string; dot?: string }[] = [
  { value: '', label: 'All' },
  { value: 'NOT_GENERATED', label: 'Not generated', dot: 'bg-slate-400' },
  { value: 'IN_FLIGHT', label: 'Buying', dot: 'bg-amber-500' },
  { value: 'QUEUED_USPS', label: 'Queued at USPS', dot: 'bg-sky-500' },
  { value: 'GENERATED', label: 'Generated', dot: 'bg-emerald-500' },
  { value: 'FAILED', label: 'Failed', dot: 'bg-red-500' },
  { value: 'VOIDED', label: 'Voided', dot: 'bg-rose-400' },
]

const CHEVRON = `url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24' fill='none' stroke='%23412d15' stroke-width='2.5' stroke-linecap='round' stroke-linejoin='round'%3E%3Cpolyline points='6 9 12 15 18 9'/%3E%3C/svg%3E")`

function statusPillClass(status: string) {
  if (status === 'GENERATED' || status === 'QUEUED_USPS') return 'border-emerald-200 bg-emerald-50 text-emerald-700'
  if (status === 'FAILED') return 'border-red-200 bg-red-50 text-red-700'
  if (status === 'IN_FLIGHT') return 'border-amber-200 bg-amber-50 text-amber-700'
  return 'border-slate-200 bg-slate-50 text-slate-600'
}
