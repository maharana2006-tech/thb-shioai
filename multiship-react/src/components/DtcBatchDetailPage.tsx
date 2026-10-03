import { lazy, Suspense, useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useNavigate, useParams, useSearchParams } from 'react-router-dom'
import { FiAlertCircle, FiArrowLeft, FiEdit2, FiPrinter, FiX, FiZap } from 'react-icons/fi'
import type { ColumnDef } from '@tanstack/react-table'
import AdvancedDataTable from './workspace/AdvancedDataTable'
import { useFocusTrap } from '../hooks/useFocusTrap'
import {
  batchStatusOf, canEditLine, dtcService, labelStatusOf, lineHasError,
  type DtcBatchDetail, type DtcOrder,
} from '../api/dtcService'
import DtcLineEditModal from './dtc/DtcLineEditModal'
import { orderService } from '../api/orderService'
import { ApiError } from '../api/apiClient'
import { confirmBatchGenerate } from '../utils/dtcConfirm'
import { notify } from '../utils/notify'
import { workspacePaths } from '../routes/workspaceRoutes'

const OrderDetailsModal = lazy(() => import('./modals/OrderDetailsModal'))

/**
 * DTC Shipment History — HstDetails-style line detail for one batch.
 * Columns mirror the reference: Client Code | Order No | Label Order |
 * Carrier | Batch No. | Tot No. | Tracking Id | Ship Date | Label | Label Status.
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
  /** The line being corrected (no label order yet) — see DtcLineEditModal. */
  const [editingLine, setEditingLine] = useState<DtcOrder | null>(null)

  const load = useCallback(async () => {
    if (!batchId || !tenantId) return
    setLoading(true)
    try {
      const res = await dtcService.batchDetail(batchId, { page: pageIndex, size: pageSize, tenantId })
      setData(res.data)
    } catch (e) {
      notify.apiError(e, `Could not load batch ${batchId}.`)
    } finally {
      setLoading(false)
    }
  }, [batchId, tenantId, pageIndex])

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
      await orderService.voidLabel(o.generatedOrderNo)
      notify.success(`Order ${o.generatedOrderNo} voided.`)
      await load()
    } catch (e) {
      notify.apiError(e, `Could not void order ${o.generatedOrderNo}.`)
    }
  }

  /**
   * An errored line is corrected on the manual shipment form, in fix mode for
   * the order this attempt minted: the form shows the carrier's error against
   * the prefilled shipment and regenerating updates that same order rather
   * than buying a second label.
   */
  const fixLine = (o: DtcOrder) => {
    if (o.generatedOrderNo) navigate(`/orders/new?fixOrder=${o.generatedOrderNo}`)
  }

  const columns = useMemo<ColumnDef<DtcOrder, unknown>[]>(() => [
    { id: 'tenantId', accessorKey: 'tenantId', header: 'Client Code', enableSorting: false },
    { id: 'orderNo', header: 'Order No', accessorKey: 'orderNo', enableSorting: false,
      cell: ({ row }) => row.original.orderNo ?? '—' },
    { id: 'generatedOrderNo', header: 'Label Order', accessorKey: 'generatedOrderNo', enableSorting: false,
      cell: ({ row }) => {
        const orderNo = row.original.generatedOrderNo
        if (!orderNo) return <span className="text-[11px] text-[#9a8b70]">—</span>
        return (
          <button
            type="button"
            title={`Open the details for order ${orderNo}`}
            onClick={() => setDetailsOrderNo(orderNo)}
            className="inline-flex items-center rounded-lg border border-[#e3d9c4] bg-white px-2 py-1 text-[11px] font-semibold text-[#5a4526] transition hover:border-[#cdbf9f] hover:bg-[#faf7f0]"
          >
            {orderNo}
          </button>
        )
      } },
    { id: 'carrier', header: 'Carrier', enableSorting: false,
      cell: ({ row }) => row.original.generatedCarrierCode ?? row.original.shipVia ?? row.original.shipViaCode ?? '—' },
    { id: 'batchId', header: 'Batch No.', accessorKey: 'batchId', enableSorting: false },
    { id: 'toteNumber', accessorKey: 'toteNumber', header: 'Tot No.', enableSorting: false },
    { id: 'tracking', header: 'Tracking Id', enableSorting: false,
      cell: ({ row }) => row.original.generatedTrackingNumber ?? '—' },
    { id: 'shipDate', accessorKey: 'shipDate', header: 'Ship Date', enableSorting: false,
      cell: ({ row }) => row.original.shipDate ?? '—' },
    {
      id: 'label', header: 'Label', enableSorting: false,
      cell: ({ row }) => {
        const orderNo = row.original.generatedOrderNo
        if (!orderNo) return <span className="text-[11px] text-[#9a8b70]">—</span>
        return (
          <button
            type="button"
            title={`Open the label PDF for order ${orderNo}`}
            onClick={() => window.open(dtcService.labelPdfUrl(orderNo), '_blank')}
            className="inline-flex items-center gap-1 rounded-lg border border-[#e3d9c4] bg-white px-2 py-1 text-[11px] font-semibold text-[#5a4526] transition hover:border-[#cdbf9f] hover:bg-[#faf7f0]"
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
        const editButton = canEditLine(o) ? (
          <button
            type="button"
            title={`Correct this line before its label is bought${o.generatedMessage ? ` — ${o.generatedMessage}` : ''}`}
            onClick={() => setEditingLine(o)}
            className={`inline-flex items-center gap-1 rounded-lg border px-2 py-1 text-[11px] font-semibold transition ${errored
              ? 'border-amber-300 bg-amber-50 text-amber-800 hover:bg-amber-100'
              : 'border-[#e3d9c4] bg-white text-[#5a4526] hover:bg-[#faf7f0]'}`}
          >
            <FiEdit2 className="h-3 w-3" />
            Edit
          </button>
        ) : null
        if (!o.generatedStatus) {
          return (
            <div className="flex items-center gap-2">
              <span
                title="No label run has touched this line yet"
                className="inline-flex items-center rounded-full border border-slate-200 bg-slate-50 px-2 py-0.5 text-[11px] font-semibold text-slate-500"
              >
                Not generated
              </span>
              {editButton}
            </div>
          )
        }
        return (
          <div className="flex items-center gap-2">
            <span
              title={o.generatedStatus === 'IN_FLIGHT' ? 'The label is being bought right now' : o.generatedMessage ?? undefined}
              className={`inline-flex items-center rounded-full border px-2 py-0.5 text-[11px] font-semibold ${statusPillClass(o.generatedStatus)}`}
            >
              {o.generatedStatus === 'IN_FLIGHT' ? 'Buying…' : o.generatedStatus}
            </span>
            {editButton}
            {errored && o.generatedOrderNo && (
              <button
                type="button"
                title={`Open the manual shipment form to fix order ${o.generatedOrderNo}${o.generatedMessage ? ` — ${o.generatedMessage}` : ''}`}
                onClick={() => fixLine(o)}
                className="inline-flex items-center gap-1 rounded-lg border border-amber-300 bg-amber-50 px-2 py-1 text-[11px] font-semibold text-amber-800 transition hover:bg-amber-100"
              >
                <FiEdit2 className="h-3 w-3" />
                Edit
              </button>
            )}
            {!errored && o.generatedOrderNo && (
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
        )
      },
    },
  ], [data, load])

  if (!tenantId) {
    return (
      <p className="px-5 py-10 text-center text-sm text-[#6b5c42]">
        Missing client (tenant) for this batch — go back to Automatic Label and open the batch from there.
      </p>
    )
  }

  const batch = data?.batch
  const total = data?.totalElements ?? 0
  const totalPages = data?.totalPages ?? 0

  return (
    <div className="space-y-3 pb-8">
      <div className="flex flex-wrap items-center gap-x-3 gap-y-2 px-1 pt-1">
        <h2 className="mr-auto flex items-center gap-2 text-[17px] font-semibold tracking-tight text-[#1f150c]">
          DTC Shipment History — Batch {batchId}
        </h2>
        <button
          type="button"
          onClick={() => navigate(workspacePaths.d2c)}
          className="inline-flex items-center gap-1.5 rounded-lg border border-[#e3d9c4] bg-white px-2.5 py-1.5 text-[12px] font-semibold text-[#5a4526] transition hover:border-[#cdbf9f] hover:bg-[#faf7f0]"
        >
          <FiArrowLeft className="h-3.5 w-3.5" />
          Back
        </button>
        <button
          type="button"
          onClick={generate}
          disabled={!!activeJobId || starting}
          title="Generate shipping labels for every pending line in this batch"
          className="inline-flex items-center gap-1.5 rounded-lg bg-[#1f150c] px-2.5 py-1.5 text-[12px] font-semibold text-[#f4eede] shadow-sm transition hover:bg-[#3a2a18] disabled:opacity-60"
        >
          <FiZap className="h-3.5 w-3.5" />
          {activeJobId && jobProgress ? `Generating ${jobProgress.processed}/${jobProgress.total}…` : 'Generate labels'}
        </button>
        <button
          type="button"
          onClick={() => window.open(dtcService.labelsZipUrl(batchId, tenantId), '_blank')}
          disabled={!batch || batch.generatedCount === 0}
          title={!batch || batch.generatedCount === 0 ? 'Generate labels first — nothing to print yet' : 'Download all generated label PDFs as a ZIP'}
          className="inline-flex items-center gap-1.5 rounded-lg border border-[#e3d9c4] bg-white px-2.5 py-1.5 text-[12px] font-semibold text-[#5a4526] transition hover:border-[#cdbf9f] hover:bg-[#faf7f0] disabled:cursor-not-allowed disabled:opacity-40"
        >
          <FiPrinter className="h-3.5 w-3.5" />
          Print all
        </button>
      </div>

      {batch && (
        <div className="flex flex-wrap items-center gap-x-4 gap-y-1 px-1 text-[12px] text-[#6b5c42]">
          <span>Client <b className="text-[#3d2f1c]">{batch.tenantId}</b></span>
          <span>{batch.totalLines} line{batch.totalLines === 1 ? '' : 's'}</span>
          <span>{batch.generatedCount} generated</span>
          {batch.failedCount > 0 && (
            <span className="inline-flex items-center gap-1 font-semibold text-red-700" title="Use Edit on a failed line to correct the ship-to, weight or ship-via, then Generate labels again.">
              <FiAlertCircle className="h-3.5 w-3.5" />
              {batch.failedCount} failed
            </span>
          )}
          {batch.pendingCount + batch.queuedCount > 0 && <span>{batch.pendingCount + batch.queuedCount} pending</span>}
          <span>Batch status <b className="text-[#3d2f1c]">{batchStatusOf(batch)}</b></span>
          <span>Labels <b className="text-[#3d2f1c]">{labelStatusOf(batch)}</b></span>
        </div>
      )}

      <section
        aria-busy={loading}
        className={`rounded-2xl border border-slate-200 bg-white p-3 shadow-sm transition-opacity duration-200 ${loading && data ? 'opacity-60' : ''}`}
      >
        <AdvancedDataTable<DtcOrder>
          tableKey={`d2c-batch-${batchId}-${tenantId}-v1`}
          columns={columns}
          data={data?.content ?? []}
          onRowClick={(o) => setDetailsLine(o)}
          manualPagination
          pageIndex={pageIndex}
          pageSize={pageSize}
          pageCount={totalPages}
          totalRowCount={total}
          onPaginationChange={({ pageIndex: i }) => setPageIndex(i)}
          getRowId={(o) => String(o.id)}
          csvFilename={`dtc-batch-${batchId}.csv`}
          caption={`${total} shipment line${total === 1 ? '' : 's'} in batch ${batchId}`}
          emptyState={
            <p className="px-5 py-10 text-center text-sm text-[#6b5c42]">
              {loading ? 'Loading…' : `No shipment lines for batch ${batchId}.`}
            </p>
          }
        />
      </section>

      {editingLine && (
        <DtcLineEditModal
          line={editingLine}
          onClose={() => setEditingLine(null)}
          onSaved={() => { setEditingLine(null); void load() }}
        />
      )}

      {detailsLine && (
        <LineDetailsModal
          order={detailsLine}
          onClose={() => setDetailsLine(null)}
          onEdit={lineHasError(detailsLine) && detailsLine.generatedOrderNo
            ? () => fixLine(detailsLine)
            : canEditLine(detailsLine)
              ? () => { setEditingLine(detailsLine); setDetailsLine(null) }
              : undefined}
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

/**
 * Detail modal for one shipment line — opens on any row click, including the
 * lines that have no Multiship order yet (the order drill-down only exists
 * once a label has been attempted, hence the conditional buttons).
 */
function LineDetailsModal({ order, onClose, onOpenOrder, onEdit }: {
  order: DtcOrder
  onClose: () => void
  onOpenOrder?: () => void
  onEdit?: () => void
}) {
  const dialogRef = useRef<HTMLDivElement>(null)
  useFocusTrap(true, dialogRef)

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose() }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-slate-950/45 p-4 backdrop-blur-sm"
      role="dialog"
      aria-modal="true"
      aria-label={`Shipment line ${order.toteNumber ?? ''} details`}
      onClick={onClose}
    >
      <div
        ref={dialogRef}
        className="flex max-h-[88vh] w-full max-w-3xl flex-col overflow-hidden rounded-2xl border border-slate-200 bg-white shadow-[0_30px_80px_rgba(15,23,42,0.35)]"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex items-start justify-between gap-3 border-b border-slate-100 px-5 py-4">
          <div>
            <h3 className="text-[15px] font-semibold tracking-tight text-[#1f150c]">
              Shipment line — Tote {order.toteNumber ?? '—'}
            </h3>
            <p className="mt-0.5 text-[12px] text-[#6b5c42]">
              Client {order.tenantId} · batch {order.batchId}
            </p>
          </div>
          <button
            type="button"
            onClick={onClose}
            aria-label="Close"
            className="rounded-lg border border-[#e3d9c4] bg-white p-1.5 text-[#5a4526] transition hover:bg-[#faf7f0]"
          >
            <FiX className="h-4 w-4" />
          </button>
        </div>
        <div className="overflow-y-auto px-5 py-4">
          <LineDetails order={order} onOpenOrder={onOpenOrder} onEdit={onEdit} />
        </div>
      </div>
    </div>
  )
}

/**
 * Ship to / shipment / label grid for one line, read straight off the
 * dtc_orders row so it works for pending lines too.
 */
function LineDetails({ order: o, onOpenOrder, onEdit }: {
  order: DtcOrder
  onOpenOrder?: () => void
  onEdit?: () => void
}) {
  const address = [o.shipAddr1, o.shipAddr2, o.shipAddr3].filter(Boolean).join(', ')
  const locality = [o.shipToCity, o.shipToState, o.shipToZip].filter(Boolean).join(', ')
  return (
    <div className="grid gap-x-8 gap-y-4 text-[12px] text-[#3d2f1c] sm:grid-cols-2 lg:grid-cols-3">
      <section className="space-y-1">
        <h4 className="text-[10px] font-semibold uppercase tracking-wide text-[#8a7a5a]">Ship to</h4>
        <p className="font-semibold">{o.shipName ?? '—'}</p>
        {o.shipAttn && <p>Attn: {o.shipAttn}</p>}
        {address && <p>{address}</p>}
        <p>{[locality, o.shipToCountryCode ?? o.countryName].filter(Boolean).join(', ') || '—'}</p>
        {o.phone && <p>Phone {o.phone}</p>}
        {o.email && <p>{o.email}</p>}
      </section>

      <section className="space-y-1">
        <h4 className="text-[10px] font-semibold uppercase tracking-wide text-[#8a7a5a]">Shipment</h4>
        <Field label="Order" value={o.orderNo != null ? String(o.orderNo) : null} />
        <Field label="Goods" value={o.goodsDesc} />
        <Field label="Weight" value={o.weight != null ? `${o.weight} lb` : null} />
        <Field label="Ship via" value={shipViaLabel(o)} />
        <Field label="Customer" value={o.custNo} />
        <Field label="PO" value={o.custPo} />
        <Field label="Terms" value={o.termsCode} />
        <Field label="Location" value={o.location} />
        <Field label="Intl" value={o.intlYn} />
      </section>

      <section className="space-y-1">
        <h4 className="text-[10px] font-semibold uppercase tracking-wide text-[#8a7a5a]">Label</h4>
        <Field label="Label status" value={o.generatedStatus === 'IN_FLIGHT' ? 'Buying…' : o.generatedStatus ?? 'Not generated'} />
        <Field label="Carrier" value={o.generatedCarrierCode} />
        <Field label="Tracking" value={o.generatedTrackingNumber} />
        <Field label="Label order" value={o.generatedOrderNo != null ? String(o.generatedOrderNo) : null} />
        <Field label="Generated" value={formatGenerated(o.generatedAt)} />
        {o.generatedMessage && (
          <p
            className={`flex items-start gap-1.5 break-words ${lineHasError(o) ? 'text-red-700' : 'text-[#8a4b2d]'}`}
            title={o.generatedMessage}
          >
            {lineHasError(o) && <FiAlertCircle className="mt-0.5 h-3.5 w-3.5 shrink-0" />}
            <span>{o.generatedMessage}</span>
          </p>
        )}
        <div className="mt-1 flex flex-wrap items-center gap-2">
          {onEdit && (
            <button
              type="button"
              onClick={onEdit}
              className="inline-flex items-center gap-1 rounded-lg border border-amber-300 bg-amber-50 px-2 py-1 text-[11px] font-semibold text-amber-800 transition hover:bg-amber-100"
            >
              <FiEdit2 className="h-3 w-3" />
              Edit shipment
            </button>
          )}
          {onOpenOrder && (
            <button
              type="button"
              onClick={onOpenOrder}
              className="inline-flex items-center rounded-lg border border-[#e3d9c4] bg-white px-2 py-1 text-[11px] font-semibold text-[#5a4526] transition hover:border-[#cdbf9f] hover:bg-[#faf7f0]"
            >
              View order details
            </button>
          )}
        </div>
      </section>
    </div>
  )
}

function Field({ label, value }: { label: string; value: string | null | undefined }) {
  return (
    <p>
      <span className="text-[#8a7a5a]">{label}:</span>{' '}
      <span className={value ? 'font-medium' : 'text-[#9a8b70]'}>{value || '—'}</span>
    </p>
  )
}

/** ERP ship-via arrives twice (code + description); show it once when they match. */
function shipViaLabel(o: DtcOrder) {
  const code = o.shipViaCode?.trim()
  const text = o.shipVia?.trim()
  if (code && text && code.toLowerCase() !== text.toLowerCase()) return `${code} · ${text}`
  return code || text || null
}

/** The ERP/ISO timestamp is opaque to operators; show a locale date+time. */
function formatGenerated(iso: string | null) {
  if (!iso) return null
  const d = new Date(iso)
  return Number.isNaN(d.getTime()) ? iso : d.toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' })
}

function statusPillClass(status: string) {
  if (status === 'GENERATED' || status === 'QUEUED_USPS') return 'border-emerald-200 bg-emerald-50 text-emerald-700'
  if (status === 'FAILED') return 'border-red-200 bg-red-50 text-red-700'
  if (status === 'IN_FLIGHT') return 'border-amber-200 bg-amber-50 text-amber-700'
  return 'border-slate-200 bg-slate-50 text-slate-600'
}
