import { Fragment, useCallback, useEffect, useState } from 'react'
import { FiDownloadCloud, FiHome, FiRefreshCw, FiZap } from 'react-icons/fi'
import { wmsService } from '../api/wmsService'
import { orderImportService } from '../api/orderImportService'
import type { ImportBatchSummary, OrderImportRow } from '../api/orderImportService'
import { notify } from '../utils/notify'
import { GridCell, DH_COLUMNS, RowIssuesIcon, RowChannelChip, bucketRowErrors, type DhColumn } from './batchGrid'
import { BTN_PRIMARY, BTN_GHOST, BTN_PRIMARY_SM } from './ui/buttons'

/**
 * The "API" section of Order Intake. Every "Fetch from WMS" lands as ONE batch
 * (source = WMS). Batches show as cards — open one and its shipments appear in
 * an editable grid: fix cells inline (validation re-runs on every edit) and
 * generate carrier labels per-row or for the whole batch, exactly like a
 * CSV/XLSX import. Generated orders are stamped source = API.
 */

// Full column set — identical to the Import-history grid. A trimmed subset
// used to hide billTo/packageType/currency and every customs column, which
// made international WMS rows UNFIXABLE: validation demanded hsCode /
// countryOfOrigin / item fields the grid gave you no way to enter.
const API_COLUMNS: DhColumn[] = DH_COLUMNS

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
  // Parity with the Import-history bulk flow: live "X of N" progress polled
  // while a batch generates, the bill-to account mode, and the platform-
  // billing confirm step.
  const [genProgressById, setGenProgressById] = useState<Record<number, { done: number; total: number }>>({})
  const [billingSavingId, setBillingSavingId] = useState<number | null>(null)
  const [confirmGenId, setConfirmGenId] = useState<number | null>(null)

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
    // load() is a useCallback that fetches + sets loading/error state;
    // the setState-in-effect warning fires because it eventually calls
    // setLoading, but this is the idiomatic React data-loading pattern
    // (fire-and-forget async load on mount / dep change).
    // eslint-disable-next-line react-hooks/set-state-in-effect
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
  /** Persist a batch's bill-to account mode (survives reload + auditable). */
  const setBilling = async (id: number, mode: 'AUTO' | 'PLATFORM') => {
    setBillingSavingId(id)
    setBatches((list) => list.map((b) => (b.id === id ? { ...b, billingMode: mode } : b)))
    if (mode !== 'PLATFORM') setConfirmGenId((c) => (c === id ? null : c))
    try {
      await orderImportService.setBillingMode(id, mode)
    } catch (e) {
      notify.apiError(e, 'Could not update the bill-to account.')
      await load()
    } finally {
      setBillingSavingId(null)
    }
  }

  /** Status-aware completion notice — a partial batch shouldn't toast green. */
  const notifyForStatus = (status: string | null | undefined, message: string) => {
    switch ((status || '').toUpperCase()) {
      case 'COMPLETE':
        notify.success(message)
        break
      case 'PARTIAL_COMPLETE':
        notify.info({ title: 'Partially generated', body: message })
        break
      case 'FAILED':
      case 'INITIATE':
        notify.error({ title: 'Label generation failed', body: message })
        break
      default:
        notify.info(message)
    }
  }

  const generateBatch = async (batchId: number, isRetry: boolean) => {
    const platform = batches.find((b) => b.id === batchId)?.billingMode === 'PLATFORM'
    setConfirmGenId(null)
    setGeneratingId(batchId)
    setGenProgressById((m) => ({ ...m, [batchId]: { done: 0, total: 0 } }))
    setBatches((list) => list.map((b) => (b.id === batchId ? { ...b, status: 'IN_PROGRESS' } : b)))
    // Poll the server's live counter ALONGSIDE the generate request (a
    // separate GET) so the button shows a real "X of N" bar while the POST
    // runs — same pattern as the Import-history bulk flow.
    let polling = true
    const pollProgress = async () => {
      while (polling) {
        try {
          const pr = await orderImportService.generationProgress(batchId)
          const d = pr.data
          if (polling && d && d.running && d.total > 0) {
            setGenProgressById((m) => ({ ...m, [batchId]: { done: d.done, total: d.total } }))
          }
        } catch {
          /* transient poll error — keep going, the POST result is authoritative */
        }
        await new Promise((r) => setTimeout(r, 400))
      }
    }
    void pollProgress()
    try {
      const res = await orderImportService.generateLabels(batchId, { onlyFailed: isRetry, usePlatformAccount: platform })
      applyUpdate(batchId, res.data)
      notifyForStatus(res.data?.status, res.message ?? 'Label generation finished.')
    } catch (e) {
      notify.apiError(e, 'Label generation failed.')
      await load()
    } finally {
      polling = false
      setGeneratingId(null)
      setGenProgressById((m) => {
        const next = { ...m }
        delete next[batchId]
        return next
      })
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
            className={BTN_PRIMARY}
          >
            {fetching ? (
              <span className="inline-block h-3.5 w-3.5 animate-spin rounded-full border-2 border-[#f4eede]/40 border-t-[#f4eede]" />
            ) : (
              <FiDownloadCloud className="h-3.5 w-3.5" />
            )}
            {fetching ? 'Fetching…' : 'Fetch from WMS'}
          </button>
          <button type="button" onClick={() => void load()} className={BTN_GHOST}>
            <FiRefreshCw className="h-3.5 w-3.5" />
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
            const progress = genProgressById[b.id]
            const platform = b.billingMode === 'PLATFORM'
            const confirming = confirmGenId === b.id
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
                  {/* Keep the controls mounted while THIS batch generates — the
                      click optimistically flips status to IN_PROGRESS (not in
                      CAN_GENERATE), so without `|| genBusy` the progress bar
                      would unmount the instant you click. */}
                  {(canGen || genBusy) ? (
                    <>
                      <span
                        title="Which carrier account this batch bills to. Platform bills the house account and rebills the client with markup."
                        className={`${confirming ? 'hidden' : 'inline-flex'} shrink-0 items-center gap-1.5 rounded-xl border px-2.5 py-1.5 text-[11px] font-semibold ${
                          platform ? 'border-[#412d15] bg-[#412d15]/5 text-[#412d15]' : 'border-[#e3d9c4] bg-white text-[#5a4526]'
                        }`}
                      >
                        <FiHome className="h-3.5 w-3.5 shrink-0" />
                        <span className="hidden sm:inline text-[9.5px] uppercase tracking-[0.08em] text-[#b6a684]">Bills to</span>
                        <select
                          value={platform ? 'PLATFORM' : 'AUTO'}
                          disabled={genBusy || billingSavingId === b.id}
                          onChange={(e) => void setBilling(b.id, e.target.value as 'AUTO' | 'PLATFORM')}
                          className="cursor-pointer border-0 bg-transparent pr-1 text-[11px] font-semibold text-inherit focus:outline-none disabled:cursor-not-allowed"
                        >
                          <option value="AUTO">Client account</option>
                          <option value="PLATFORM">Platform account</option>
                        </select>
                      </span>
                      {platform && confirming ? (
                        <>
                          <button
                            type="button"
                            onClick={() => void generateBatch(b.id, isRetry)}
                            disabled={genBusy}
                            className="inline-flex shrink-0 items-center gap-1.5 rounded-xl bg-[#412d15] px-3 py-2 text-[12px] font-semibold text-[#f4eede] shadow-sm transition hover:bg-[#5a4526] disabled:cursor-not-allowed disabled:bg-[#dcd4c4]"
                          >
                            <FiHome className="h-3.5 w-3.5" />
                            Confirm — bill to platform
                          </button>
                          <button
                            type="button"
                            onClick={() => setConfirmGenId(null)}
                            disabled={genBusy}
                            className="inline-flex shrink-0 items-center rounded-xl border border-[#e3d9c4] bg-white px-3 py-2 text-[12px] font-semibold text-[#5a4526] transition hover:bg-[#faf7f0]"
                          >
                            Cancel
                          </button>
                        </>
                      ) : genBusy ? (
                        // Live progress while generating: a real X-of-N bar once
                        // the first poll lands, an indeterminate shimmer until then.
                        (() => {
                          const total = progress?.total ?? 0
                          const done = Math.min(progress?.done ?? 0, total)
                          const pct = total > 0 ? Math.round((done / total) * 100) : 0
                          return (
                            <div
                              className="flex min-w-[150px] shrink-0 flex-col gap-1 rounded-xl bg-[#1f150c] px-3 py-1.5 text-[#f4eede]"
                              role="progressbar"
                              aria-valuemin={0}
                              aria-valuemax={total || undefined}
                              aria-valuenow={total > 0 ? done : undefined}
                              title={total > 0 ? `Generating labels — ${done} of ${total} done` : 'Generating labels…'}
                            >
                              <div className="flex items-center justify-between text-[11px] font-semibold">
                                <span className="inline-flex items-center gap-1.5">
                                  <span className="inline-block h-3 w-3 animate-spin rounded-full border-2 border-[#f4eede]/40 border-t-[#f4eede]" />
                                  Generating…
                                </span>
                                {total > 0 ? <span className="tabular-nums">{done}/{total}</span> : null}
                              </div>
                              <div className="h-1.5 w-full overflow-hidden rounded-full bg-[#f4eede]/20">
                                {total > 0 ? (
                                  <div
                                    className="h-full rounded-full bg-[#f4eede] transition-[width] duration-300 ease-out"
                                    style={{ width: `${pct}%` }}
                                  />
                                ) : (
                                  <div className="h-full w-1/3 animate-pulse rounded-full bg-[#f4eede]/70" />
                                )}
                              </div>
                            </div>
                          )
                        })()
                      ) : (
                        <button
                          type="button"
                          onClick={() => (platform ? setConfirmGenId(b.id) : void generateBatch(b.id, isRetry))}
                          disabled={!!b.invalidRows}
                          title={
                            b.invalidRows
                              ? 'Fix the flagged rows before generating labels'
                              : isRetry
                                ? 'Retry generating labels for the rows that failed or aren’t generated yet'
                                : 'Generate carrier labels for every row in this batch'
                          }
                          className={`${BTN_PRIMARY_SM} shrink-0`}
                        >
                          <FiZap className="h-3.5 w-3.5" />
                          {isRetry ? 'Retry labels' : 'Generate labels'}
                        </button>
                      )}
                    </>
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
                              const gen = (r.generatedStatus ?? '').toUpperCase()
                              const generated = gen === 'GENERATED'
                              // A row the carrier rejected must NOT read "Ready" —
                              // that hid real failures behind a green badge.
                              const failed = gen === 'FAILED'
                              const rowKey = `${b.id}-${r.rowNumber}`
                              const rowBusy = genRowKey === rowKey
                              const { byField, rowLevel } = bucketRowErrors(r.errors ?? [])
                              const statusTitle = (r.errors ?? []).map((m) => '✗ ' + m).join('\n') || undefined
                              const warnings = r.warnings ?? []
                              const hasExplain = !ok || (failed && !!r.generatedMessage) || warnings.length > 0
                              return (
                                <Fragment key={r.rowNumber}>
                                <tr className={ok && !failed ? 'bg-white' : 'bg-rose-50/40'}>
                                  <td className={`sticky left-0 z-10 whitespace-nowrap border-b border-r border-[#e3d9c4] px-2 py-1 ${ok && !failed ? 'bg-white' : 'bg-rose-50'}`}>
                                    <div className="flex items-center gap-1.5">
                                      <span className="font-mono text-[10px] font-bold text-[#8a7959]">{r.rowNumber}</span>
                                      {generated ? (
                                        <span className="rounded-full bg-emerald-100 px-1.5 py-0.5 text-[9px] font-semibold text-emerald-800">Generated</span>
                                      ) : failed ? (
                                        <span title={r.generatedMessage || 'The carrier rejected this shipment'} className="cursor-help rounded-full bg-rose-100 px-1.5 py-0.5 text-[9px] font-semibold text-rose-800">Failed</span>
                                      ) : ok ? (
                                        <span className="rounded-full bg-emerald-100 px-1.5 py-0.5 text-[9px] font-semibold text-emerald-800">Ready</span>
                                      ) : (
                                        <span title={statusTitle} className="cursor-help rounded-full bg-rose-100 px-1.5 py-0.5 text-[9px] font-semibold text-rose-800">
                                          {r.errors!.length} error{r.errors!.length === 1 ? '' : 's'}
                                        </span>
                                      )}
                                      <RowChannelChip recipientCompany={r.recipientCompany} />
                                      {/* Left-side ⓘ — lives in the STICKY cell so
                                          the issues stay one hover away no matter
                                          how far the wide grid is scrolled. */}
                                      {hasExplain ? (
                                        <RowIssuesIcon
                                          side="left"
                                          rowNumber={r.rowNumber}
                                          byField={byField}
                                          rowLevel={rowLevel}
                                          carrierMessage={failed ? r.generatedMessage : null}
                                          warnings={warnings}
                                        />
                                      ) : null}
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
                                    ) : (ok || failed) ? (
                                      <button
                                        type="button"
                                        onClick={() => void generateRow(b.id, r.rowNumber)}
                                        disabled={rowBusy}
                                        title={failed ? 'Retry — re-sends this same order to the carrier (no duplicate order is created)' : 'Generate a carrier label for this row'}
                                        className={failed
                                          ? 'inline-flex items-center gap-1 rounded-lg bg-rose-700 px-2 py-1 text-[10px] font-semibold text-[#f4eede] transition hover:bg-rose-800 disabled:cursor-not-allowed disabled:bg-[#dcd4c4]'
                                          : BTN_PRIMARY_SM}
                                      >
                                        {rowBusy ? (
                                          <span className="inline-block h-3 w-3 animate-spin rounded-full border-2 border-[#f4eede]/40 border-t-[#f4eede]" />
                                        ) : (
                                          <FiZap className="h-3 w-3" />
                                        )}
                                        {rowBusy ? 'Generating…' : failed ? 'Retry label' : 'Generate label'}
                                      </button>
                                    ) : (
                                      <span className="text-[9.5px] text-[#b6a684]">Fix errors first</span>
                                    )}
                                    {hasExplain ? (
                                      <RowIssuesIcon
                                        side="right"
                                        rowNumber={r.rowNumber}
                                        byField={byField}
                                        rowLevel={rowLevel}
                                        carrierMessage={failed ? r.generatedMessage : null}
                                        warnings={warnings}
                                      />
                                    ) : null}
                                  </td>
                                </tr>
                                </Fragment>
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
