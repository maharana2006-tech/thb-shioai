import { useCallback, useEffect, useState } from 'react'
import { wmsService } from '../api/wmsService'
import { orderImportService } from '../api/orderImportService'
import type { ImportBatchSummary, OrderImportRow } from '../api/orderImportService'
import { notify } from '../utils/notify'
import { GridCell, DH_COLUMNS, bucketRowErrors, type DhColumn } from './batchGrid'

/**
 * The "API" section of Order Intake. Every "Fetch from WMS" lands as ONE batch
 * (source = WMS). Batches show as cards — open one and its shipments appear in
 * an editable grid: fix cells inline (validation re-runs on every edit) and
 * generate carrier labels per-row or for the whole batch, exactly like a
 * CSV/XLSX import. Generated orders are stamped source = API.
 */

// The columns worth editing for a WMS shipment (customs/item columns are left
// to the CSV importer). Order mirrors DH_COLUMNS so validation keys line up.
const API_KEYS = new Set([
  'orderRef', 'clientCode', 'recipientName', 'recipientCompany', 'recipientPhone', 'recipientEmail',
  'addressLine1', 'addressLine2', 'city', 'state', 'postalCode', 'countryCode',
  'carrierCode', 'accountNumber', 'serviceType', 'weight', 'weightUnit', 'reference',
])
const API_COLUMNS: DhColumn[] = DH_COLUMNS.filter((c) => API_KEYS.has(c.key))

const fmtDateTime = (v?: string | null) =>
  v
    ? new Date(v).toLocaleString('en-US', {
        month: 'short', day: 'numeric', year: 'numeric', hour: 'numeric', minute: '2-digit',
      })
    : '—'

const CAN_GENERATE = new Set(['INITIATE', 'PARTIAL_COMPLETE', 'FAILED'])

export default function ApiBatchList() {
  const [batches, setBatches] = useState<ImportBatchSummary[]>([])
  const [loading, setLoading] = useState(true)
  const [fetching, setFetching] = useState(false)
  const [openId, setOpenId] = useState<number | null>(null)
  const [rowsById, setRowsById] = useState<Record<number, OrderImportRow[]>>({})
  const [rowsLoading, setRowsLoading] = useState<number | null>(null)
  const [savingKey, setSavingKey] = useState<string | null>(null)
  const [genRowKey, setGenRowKey] = useState<string | null>(null)
  const [generatingId, setGeneratingId] = useState<number | null>(null)

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const res = await wmsService.batches()
      setBatches(res.data ?? [])
    } catch (e) {
      notify.apiError(e, 'Could not load API batches.')
      setBatches([])
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    void load()
  }, [load])

  const applyUpdate = (
    batchId: number,
    updated?: { status?: string | null; savedRows?: number; invalidRows?: number; labelBatchId?: number | null; rows?: OrderImportRow[] } | null,
  ) => {
    if (!updated) return
    if (updated.rows) setRowsById((m) => ({ ...m, [batchId]: updated.rows! }))
    setBatches((list) =>
      list.map((b) =>
        b.id === batchId
          ? {
              ...b,
              status: updated.status ?? b.status,
              savedRows: updated.savedRows ?? b.savedRows,
              invalidRows: updated.invalidRows ?? b.invalidRows,
              labelBatchId: updated.labelBatchId ?? b.labelBatchId,
            }
          : b,
      ),
    )
  }

  const loadRows = async (id: number) => {
    setRowsLoading(id)
    try {
      const res = await wmsService.batch(id)
      setRowsById((m) => ({ ...m, [id]: res.data?.rows ?? [] }))
    } catch (e) {
      notify.apiError(e, 'Could not load this batch.')
      setRowsById((m) => ({ ...m, [id]: [] }))
    } finally {
      setRowsLoading(null)
    }
  }

  const toggle = async (id: number) => {
    if (openId === id) {
      setOpenId(null)
      return
    }
    setOpenId(id)
    if (!rowsById[id]) await loadRows(id)
  }

  /** Pull the WMS's current pending shipments — creates one new batch. */
  const fetchFromWms = async () => {
    setFetching(true)
    try {
      const res = await wmsService.pull()
      const r = res.data
      if (r && !r.configured) {
        notify.info('WMS is not configured on the server.')
      } else if (r) {
        if (r.imported > 0) {
          notify.success(`WMS: ${r.imported} shipment(s) imported as a new batch${r.failed ? ` · ${r.failed} skipped` : ''}`)
        } else if (r.importBatchId != null) {
          // Same pending shipments as an earlier fetch — reuse that batch instead of duplicating it.
          notify.info('These shipments were already fetched — opening the existing batch.')
        } else {
          notify.info('The WMS has no pending shipments to fetch.')
        }
        if (r.importBatchId != null) {
          await load()
          setOpenId(r.importBatchId)
          await loadRows(r.importBatchId)
        }
      }
    } catch (e) {
      notify.apiError(e, 'Could not reach the WMS.')
    } finally {
      setFetching(false)
    }
  }

  /** Inline-edit one cell → re-validate the batch server-side. */
  const commitCell = async (batchId: number, row: OrderImportRow, col: DhColumn, raw: string) => {
    let next: unknown = raw
    if (col.numeric) next = raw === '' ? null : Number(raw)
    else if (col.upper) next = raw.toUpperCase()
    const current = (row as unknown as Record<string, unknown>)[col.key]
    if (String(current ?? '') === String(next ?? '')) return
    const edited = { ...row, [col.key]: next } as OrderImportRow
    const key = `${batchId}-${row.rowNumber}`
    setSavingKey(key)
    try {
      const res = await orderImportService.updateRow(batchId, row.rowNumber, edited)
      if (res.data) applyUpdate(batchId, res.data)
      else notify.error(res.message ?? 'Save failed.')
    } catch (e) {
      notify.apiError(e, 'Save failed.')
    } finally {
      setSavingKey(null)
    }
  }

  /** Generate a label for one row. */
  const generateRow = async (batchId: number, rowNumber: number) => {
    const key = `${batchId}-${rowNumber}`
    setGenRowKey(key)
    try {
      const res = await orderImportService.generateRowLabel(batchId, rowNumber)
      applyUpdate(batchId, res.data)
      const thisRow = res.data?.rows?.find((r) => r.rowNumber === rowNumber)
      if ((thisRow?.generatedStatus ?? '').toUpperCase() === 'GENERATED') {
        notify.success(`Label generated for row ${rowNumber}.`)
      } else {
        notify.error({
          title: `Row ${rowNumber} — label failed`,
          body: thisRow?.generatedMessage || res.message || 'The carrier rejected this shipment.',
        })
      }
    } catch (e) {
      notify.apiError(e, 'Label generation failed.')
    } finally {
      setGenRowKey(null)
    }
  }

  /** Generate labels for the whole batch (or retry the failed/ungenerated rows). */
  const generateBatch = async (batchId: number, isRetry: boolean) => {
    setGeneratingId(batchId)
    setBatches((list) => list.map((b) => (b.id === batchId ? { ...b, status: 'IN_PROGRESS' } : b)))
    try {
      const res = await orderImportService.generateLabels(batchId, { onlyFailed: isRetry })
      applyUpdate(batchId, res.data)
      notify.success(res.message ?? 'Label generation finished.')
    } catch (e) {
      notify.apiError(e, 'Label generation failed.')
      await load()
    } finally {
      setGeneratingId(null)
    }
  }

  return (
    <div className="space-y-3">
      {/* Toolbar */}
      <div className="flex items-center justify-between gap-2">
        <p className="text-[12px] text-[#8a7959]">
          {loading
            ? 'Loading…'
            : `${batches.length} ${batches.length === 1 ? 'batch' : 'batches'} · each fetch is one batch — open to edit & generate`}
        </p>
        <div className="flex items-center gap-2">
          <button
            type="button"
            onClick={() => void fetchFromWms()}
            disabled={fetching}
            title="Pull the WMS's current pending shipments in as a new batch"
            className="inline-flex items-center gap-1.5 rounded-xl bg-[#1f150c] px-3 py-1.5 text-[12px] font-semibold text-[#f4eede] shadow-sm transition hover:bg-[#412d15] disabled:cursor-not-allowed disabled:bg-[#dcd4c4]"
          >
            {fetching ? (
              <span className="inline-block h-3.5 w-3.5 animate-spin rounded-full border-2 border-[#f4eede]/40 border-t-[#f4eede]" />
            ) : null}
            {fetching ? 'Fetching…' : 'Fetch from WMS'}
          </button>
          <button
            type="button"
            onClick={() => void load()}
            className="inline-flex items-center gap-1.5 rounded-xl border border-[#e3d9c4] bg-white px-3 py-1.5 text-[12px] font-semibold text-[#5a4526] transition hover:border-[#cdbf9f] hover:bg-[#faf7f0]"
          >
            Refresh
          </button>
        </div>
      </div>

      {loading ? null : batches.length === 0 ? (
        <p className="rounded-2xl border border-dashed border-[#e3d9c4] bg-white px-5 py-14 text-center text-sm text-[#8a7959]">
          No API batches yet. Use <span className="font-semibold text-[#5a4526]">Fetch from WMS</span> to pull the
          warehouse&rsquo;s current shippable orders in as a batch.
        </p>
      ) : (
        <ul className="space-y-2.5">
          {batches.map((b) => {
            const open = openId === b.id
            const rows = rowsById[b.id]
            const rowsBusy = rowsLoading === b.id
            const st = (b.status || '').toUpperCase()
            const isRetry = st === 'PARTIAL_COMPLETE' || st === 'FAILED'
            const canGen = CAN_GENERATE.has(st)
            const genBusy = generatingId === b.id
            return (
              <li key={b.id} className="overflow-hidden rounded-2xl border border-[#e3d9c4] bg-white shadow-sm">
                {/* Card header */}
                <div className="flex items-center gap-3 px-4 py-3">
                  <button
                    type="button"
                    onClick={() => void toggle(b.id)}
                    aria-expanded={open}
                    className="flex min-w-0 flex-1 items-center gap-3 text-left"
                  >
                    <span
                      className={`flex h-6 w-6 shrink-0 items-center justify-center rounded-full border border-[#e3d9c4] text-[#8a7959] transition ${open ? 'rotate-90 bg-[#1f150c] text-[#f4eede]' : ''}`}
                      aria-hidden
                    >
                      ›
                    </span>
                    <span className="min-w-0">
                      <span className="flex flex-wrap items-center gap-2">
                        <span className="truncate text-[13px] font-bold text-[#1f150c]">
                          {b.fileName || `Batch #${b.id}`}
                        </span>
                        <span className="inline-flex shrink-0 items-center rounded-full bg-emerald-50 px-1.5 py-0.5 text-[9px] font-bold uppercase tracking-[0.08em] text-emerald-700 ring-1 ring-emerald-200">
                          WMS
                        </span>
                        <span
                          title="This fetch batch"
                          className="inline-flex shrink-0 items-center gap-1 rounded-full bg-[#412d15] px-2 py-0.5 font-mono text-[9.5px] font-bold uppercase tracking-[0.08em] text-[#f4eede]"
                        >
                          Batch #{b.id}
                        </span>
                        {b.labelBatchId != null ? (
                          <span
                            title="Find these orders together in All Orders"
                            className="inline-flex shrink-0 items-center gap-1 rounded-full bg-[#5a4526] px-2 py-0.5 font-mono text-[9.5px] font-bold uppercase tracking-[0.08em] text-[#f4eede]"
                          >
                            Orders #{b.labelBatchId}
                          </span>
                        ) : null}
                        {b.invalidRows ? (
                          <span className="inline-flex shrink-0 items-center rounded-full bg-rose-100 px-1.5 py-0.5 text-[9.5px] font-bold text-rose-700">
                            {b.invalidRows} to fix
                          </span>
                        ) : null}
                      </span>
                      <span className="mt-0.5 block text-[11px] text-[#8a7959]">
                        {fmtDateTime(b.createdAt)} · {b.createdBy || '—'} · {st.replace('_', ' ').toLowerCase()}
                      </span>
                    </span>
                  </button>
                  <span className="shrink-0 rounded-full bg-[#f4eede] px-2.5 py-1 text-[11px] font-bold text-[#5a4526]">
                    {b.totalRows} {b.totalRows === 1 ? 'shipment' : 'shipments'}
                  </span>
                  {canGen ? (
                    <button
                      type="button"
                      onClick={() => void generateBatch(b.id, isRetry)}
                      disabled={genBusy || !!b.invalidRows}
                      title={
                        b.invalidRows
                          ? 'Fix the flagged rows before generating labels'
                          : isRetry
                            ? 'Retry generating labels for the rows that failed or aren’t generated yet'
                            : 'Generate carrier labels for every row in this batch'
                      }
                      className="inline-flex shrink-0 items-center gap-1.5 rounded-xl bg-[#1f150c] px-3 py-1.5 text-[12px] font-semibold text-[#f4eede] shadow-sm transition hover:bg-[#412d15] disabled:cursor-not-allowed disabled:bg-[#dcd4c4]"
                    >
                      {genBusy ? (
                        <span className="inline-block h-3.5 w-3.5 animate-spin rounded-full border-2 border-[#f4eede]/40 border-t-[#f4eede]" />
                      ) : null}
                      {genBusy ? 'Generating…' : isRetry ? 'Retry labels' : 'Generate labels'}
                    </button>
                  ) : null}
                </div>

                {/* Expanded editable grid */}
                {open ? (
                  <div className="border-t border-[#f2ecdf] bg-[#fdfbf6]">
                    {rowsBusy ? (
                      <p className="px-4 py-6 text-center text-[12px] text-[#8a7959]">Loading shipments…</p>
                    ) : rows && rows.length ? (
                      <div className="overflow-x-auto">
                        <p className="px-4 pt-3 text-[11px] text-[#8a7959]">
                          Click any cell to edit — it saves and re-validates on blur. Fix the flagged cells, then generate.
                        </p>
                        <table className="w-full text-left text-[12px]">
                          <thead>
                            <tr className="text-[10px] uppercase tracking-[0.05em] text-[#b6a684]">
                              <th className="sticky left-0 z-10 bg-[#fdfbf6] px-2 py-2 font-bold">Row</th>
                              {API_COLUMNS.map((c) => (
                                <th key={c.key} className="whitespace-nowrap px-2 py-2 font-bold">
                                  {c.key}
                                </th>
                              ))}
                              <th className="px-3 py-2 font-bold">Label</th>
                            </tr>
                          </thead>
                          <tbody>
                            {rows.map((r) => {
                              const ok = (r.errors?.length ?? 0) === 0
                              const generated = (r.generatedStatus ?? '').toUpperCase() === 'GENERATED'
                              const rowKey = `${b.id}-${r.rowNumber}`
                              const rowBusy = genRowKey === rowKey
                              const { byField } = bucketRowErrors(r.errors ?? [])
                              const statusTitle = (r.errors ?? []).map((m) => '✗ ' + m).join('\n') || undefined
                              return (
                                <tr key={r.rowNumber} className={ok ? 'bg-white' : 'bg-rose-50/40'}>
                                  <td className={`sticky left-0 z-10 whitespace-nowrap border-b border-r border-[#e3d9c4] px-2 py-1 ${ok ? 'bg-white' : 'bg-rose-50'}`}>
                                    <div className="flex items-center gap-1.5">
                                      <span className="font-mono text-[10px] font-bold text-[#8a7959]">{r.rowNumber}</span>
                                      {generated ? (
                                        <span className="rounded-full bg-emerald-100 px-1.5 py-0.5 text-[9px] font-semibold text-emerald-800">Generated</span>
                                      ) : ok ? (
                                        <span className="rounded-full bg-emerald-100 px-1.5 py-0.5 text-[9px] font-semibold text-emerald-800">Ready</span>
                                      ) : (
                                        <span title={statusTitle} className="cursor-help rounded-full bg-rose-100 px-1.5 py-0.5 text-[9px] font-semibold text-rose-800">
                                          {r.errors!.length} err
                                        </span>
                                      )}
                                    </div>
                                  </td>
                                  {API_COLUMNS.map((c) => {
                                    const raw = (r as unknown as Record<string, unknown>)[c.key]
                                    return (
                                      <td key={c.key} className="border-b border-[#f2ecdf] px-1 py-1">
                                        <div className={c.w}>
                                          <GridCell
                                            value={raw == null ? '' : String(raw)}
                                            readOnly={generated || c.key === 'orderRef' || savingKey === rowKey}
                                            bad={(byField[c.key]?.length ?? 0) > 0}
                                            errors={byField[c.key]}
                                            mono={c.mono}
                                            onCommit={(v) => void commitCell(b.id, r, c, v)}
                                          />
                                        </div>
                                      </td>
                                    )
                                  })}
                                  <td className="whitespace-nowrap border-b border-[#f2ecdf] px-3 py-1">
                                    {generated ? (
                                      r.generatedTrackingNumber ? (
                                        <span className="font-mono text-[9.5px] text-[#8a7959]">{r.generatedTrackingNumber}</span>
                                      ) : (
                                        <span className="text-[9.5px] text-[#8a7959]">—</span>
                                      )
                                    ) : ok ? (
                                      <button
                                        type="button"
                                        onClick={() => void generateRow(b.id, r.rowNumber)}
                                        disabled={rowBusy}
                                        className="inline-flex items-center gap-1 rounded-lg bg-[#1f150c] px-2 py-1 text-[10px] font-semibold text-[#f4eede] transition hover:bg-[#412d15] disabled:cursor-not-allowed disabled:bg-[#dcd4c4]"
                                      >
                                        {rowBusy ? (
                                          <span className="inline-block h-3 w-3 animate-spin rounded-full border-2 border-[#f4eede]/40 border-t-[#f4eede]" />
                                        ) : null}
                                        {rowBusy ? 'Generating…' : 'Generate label'}
                                      </button>
                                    ) : (
                                      <span className="text-[9.5px] text-[#b6a684]">Fix errors first</span>
                                    )}
                                  </td>
                                </tr>
                              )
                            })}
                          </tbody>
                        </table>
                      </div>
                    ) : (
                      <p className="px-4 py-6 text-center text-[12px] text-[#8a7959]">No shipments in this batch.</p>
                    )}
                  </div>
                ) : null}
              </li>
            )
          })}
        </ul>
      )}
    </div>
  )
}
