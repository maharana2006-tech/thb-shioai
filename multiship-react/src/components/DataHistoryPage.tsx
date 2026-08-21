import { useEffect, useMemo, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import {
  FiArrowLeft,
  FiFileText,
  FiFilter,
  FiHome,
  FiRefreshCw,
  FiSearch,
  FiSliders,
  FiTrash2,
  FiRotateCcw,
  FiX,
  FiZap,
} from 'react-icons/fi'
import type { ColumnDef } from '@tanstack/react-table'
import PageSectionHeader from './workspace/PageSectionHeader'
import AdvancedDataTable from './workspace/AdvancedDataTable'
import AllOrdersHistory from './AllOrdersHistory'
import OrderImportModal from './modals/OrderImportModal'
import { notify } from '../utils/notify'
import {
  orderImportService,
  type ImportBatchSummary,
  type OrderImportRow,
} from '../api/orderImportService'
import { useAppSession } from '../hooks/useAppSession'
import { normalizeRole } from '../utils/roles'

/**
 * Data History — every saved CSV/XLSX import. "Commit" in the import modal
 * saves the parsed rows here (no labels generated); this page lists those
 * saved imports and lets you expand one to see its rows.
 */
export default function DataHistoryPage() {
  const navigate = useNavigate()
  /** Audit R2 #329 — TENANT role should see the page read-only.
   *  Backend already 403s on cross-tenant access (OrderImportServiceImpl
   *  history() / historyDetail() / generateLabelsForBatch enforce
   *  clientCode filtering + tenantScope), but the FE was showing the
   *  Generate/Retry buttons anyway → click → silent 403 → confusion.
   *  Now hidden for TENANT so the read-only intent is visible upfront. */
  const { role } = useAppSession()
  const canWrite = normalizeRole(role) !== 'TENANT'
  const [batches, setBatches] = useState<ImportBatchSummary[]>([])
  const [loading, setLoading] = useState(true)
  const [openId, setOpenId] = useState<number | null>(null)
  const [rowsById, setRowsById] = useState<Record<number, OrderImportRow[] | 'loading'>>({})
  const [generatingId, setGeneratingId] = useState<number | null>(null)
  // Bill-to account: the batch whose "Bills to" selector is mid-save.
  const [billingSavingId, setBillingSavingId] = useState<number | null>(null)
  // Confirm-before-generate when a batch bills to the platform account.
  const [confirmGenId, setConfirmGenId] = useState<number | null>(null)
  const [genRowKey, setGenRowKey] = useState<string | null>(null)
  // Inline correction: the cell being saved (rowKey), for a per-cell spinner.
  const [savingCell, setSavingCell] = useState<string | null>(null)
  // Soft delete: whether we're viewing the Trash, and the row being deleted/restored.
  const [viewTrash, setViewTrash] = useState(false)
  const [trashBusyId, setTrashBusyId] = useState<number | null>(null)
  // Empty Trash: two-step confirm before the irreversible purge.
  const [confirmEmpty, setConfirmEmpty] = useState(false)
  const [emptying, setEmptying] = useState(false)

  // ── Advanced filter tools ────────────────────────────────────────────────
  type StatusKey = 'ALL' | 'COMPLETE' | 'PARTIAL_COMPLETE' | 'IN_PROGRESS' | 'INITIATE' | 'DRAFT' | 'FAILED'
  type SortKey = 'created' | 'fileName' | 'savedRows' | 'status' | 'labelBatch'
  const [search, setSearch] = useState('')
  const [statusFilter, setStatusFilter] = useState<StatusKey>('ALL')
  const [sortKey, setSortKey] = useState<SortKey>('created')
  const [sortDir, setSortDir] = useState<'ASC' | 'DESC'>('DESC')
  const [showAdvanced, setShowAdvanced] = useState(false)
  // Order Intake has three views: "orders" (unified per-order list across
  // Bulk / Manual / API / WMS), "import" (inline CSV/Excel upload + validation),
  // and "imports" (history of bulk import batches).
  const [dhView, setDhView] = useState<'orders' | 'import' | 'imports'>('orders')
  const [dateFrom, setDateFrom] = useState('')
  const [dateTo, setDateTo] = useState('')
  const [createdBy, setCreatedBy] = useState('')
  const [batchPresence, setBatchPresence] = useState<'ANY' | 'HAS' | 'NONE'>('ANY')
  const [minSaved, setMinSaved] = useState('')

  // ── Pagination ────────────────────────────────────────────────────────────
  // page + pageSize state kept as scaffolding for the pagination UI wired in
  // by ad81192 ("import history"); page value and setPageSize setter aren't
  // read yet (no pager control mounted). Disables silence lint until the UI
  // catches up — see setPage usage in the filter-reset effect below.
  // eslint-disable-next-line @typescript-eslint/no-unused-vars -- scaffold for the paginator UI; setPage is used in the reset effect below
  const [_page, setPage] = useState(1)
  // eslint-disable-next-line @typescript-eslint/no-unused-vars -- scaffold; pageSize is read by the reset effect, setter waits for the pager UI
  const [pageSize, _setPageSize] = useState(10)

  /** Distinct createdBy values for the advanced "Created by" dropdown. */
  const creators = useMemo(
    () => Array.from(new Set(batches.map((b) => b.createdBy).filter((v): v is string => !!v))).sort(),
    [batches],
  )

  /** Per-status counts so the filter chips can show how many match each state. */
  const statusCounts = useMemo(() => {
    const c: Record<string, number> = { ALL: batches.length }
    for (const b of batches) {
      const k = (b.status || '').toUpperCase()
      c[k] = (c[k] ?? 0) + 1
    }
    return c
  }, [batches])

  const activeAdvancedCount =
    (dateFrom ? 1 : 0) + (dateTo ? 1 : 0) + (createdBy ? 1 : 0) + (batchPresence !== 'ANY' ? 1 : 0) + (minSaved ? 1 : 0)
  const anyFilterActive =
    search.trim() !== '' || statusFilter !== 'ALL' || activeAdvancedCount > 0

  const clearFilters = () => {
    setSearch('')
    setStatusFilter('ALL')
    setDateFrom('')
    setDateTo('')
    setCreatedBy('')
    setBatchPresence('ANY')
    setMinSaved('')
  }

  /** Apply search + status + advanced filters, then sort. Pure client-side —
   *  the history list is already fully loaded. */
  const filtered = useMemo(() => {
    const q = search.trim().toLowerCase()
    const from = dateFrom ? new Date(dateFrom + 'T00:00:00').getTime() : null
    const to = dateTo ? new Date(dateTo + 'T23:59:59').getTime() : null
    const min = minSaved ? Number(minSaved) : null

    const rows = batches.filter((b) => {
      if (statusFilter !== 'ALL' && (b.status || '').toUpperCase() !== statusFilter) return false
      if (q) {
        const hay = `${b.fileName ?? ''} ${b.createdBy ?? ''} #${b.id} batch ${b.labelBatchId ?? ''}`.toLowerCase()
        if (!hay.includes(q)) return false
      }
      if (createdBy && b.createdBy !== createdBy) return false
      if (batchPresence === 'HAS' && b.labelBatchId == null) return false
      if (batchPresence === 'NONE' && b.labelBatchId != null) return false
      if (min != null && b.savedRows < min) return false
      if (from != null || to != null) {
        const t = b.createdAt ? new Date(b.createdAt).getTime() : NaN
        if (Number.isNaN(t)) return false
        if (from != null && t < from) return false
        if (to != null && t > to) return false
      }
      return true
    })

    const statusOrder: Record<string, number> = {
      DRAFT: 0, IN_PROGRESS: 0, INITIATE: 1, PARTIAL_COMPLETE: 2, FAILED: 3, COMPLETE: 4,
    }
    const dir = sortDir === 'ASC' ? 1 : -1
    rows.sort((a, b) => {
      let cmp: number
      switch (sortKey) {
        case 'fileName':
          cmp = (a.fileName || '').localeCompare(b.fileName || '')
          break
        case 'savedRows':
          cmp = a.savedRows - b.savedRows
          break
        case 'labelBatch':
          cmp = (a.labelBatchId ?? -1) - (b.labelBatchId ?? -1)
          break
        case 'status':
          cmp = (statusOrder[(a.status || '').toUpperCase()] ?? 9) - (statusOrder[(b.status || '').toUpperCase()] ?? 9)
          break
        default: {
          const ta = a.createdAt ? new Date(a.createdAt).getTime() : 0
          const tb = b.createdAt ? new Date(b.createdAt).getTime() : 0
          cmp = ta - tb
        }
      }
      if (cmp === 0) cmp = a.id - b.id
      return cmp * dir
    })
    return rows
  }, [batches, search, statusFilter, sortKey, sortDir, dateFrom, dateTo, createdBy, batchPresence, minSaved])

  // Reset to the first page whenever the filter/sort set changes, so the user
  // never lands on an out-of-range page after narrowing the results.
  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- snap paging back to 1 on filter change; user-input-driven, not derivable at render
    setPage(1)
  }, [search, statusFilter, sortKey, sortDir, dateFrom, dateTo, createdBy, batchPresence, minSaved, pageSize])


  const load = async () => {
    setLoading(true)
    try {
      const res = await orderImportService.listHistory(viewTrash)
      setBatches(res.data ?? [])
    } catch (e) {
      notify.apiError(e, viewTrash ? 'Could not load Trash.' : 'Could not load import history.')
      setBatches([])
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- data fetch on mount + when switching between live/Trash views
    void load()
    setOpenId(null)
    setConfirmEmpty(false)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [viewTrash])

  /** Empty the Trash — PERMANENTLY delete every batch currently in Trash. */
  const handleEmptyTrash = async () => {
    setEmptying(true)
    try {
      const res = await orderImportService.emptyTrash()
      setBatches([])
      setOpenId(null)
      notify.success(res.message ?? 'Trash emptied.')
    } catch (e) {
      notify.apiError(e, 'Could not empty Trash.')
    } finally {
      setEmptying(false)
      setConfirmEmpty(false)
    }
  }

  /** Move a batch to Trash (soft delete). It stays recoverable from the Trash view. */
  const handleDelete = async (id: number, fileName?: string | null) => {
    setTrashBusyId(id)
    try {
      await orderImportService.deleteBatch(id)
      setBatches((list) => list.filter((b) => b.id !== id))
      if (openId === id) setOpenId(null)
      notify.success(`"${fileName || `Import #${id}`}" moved to Trash · restore it from Trash anytime.`)
    } catch (e) {
      notify.apiError(e, 'Could not delete import.')
    } finally {
      setTrashBusyId(null)
    }
  }

  /** Restore a batch from Trash back to the live Data History list. */
  const handleRestore = async (id: number, fileName?: string | null) => {
    setTrashBusyId(id)
    try {
      await orderImportService.restoreBatch(id)
      setBatches((list) => list.filter((b) => b.id !== id))
      if (openId === id) setOpenId(null)
      notify.success(`"${fileName || `Import #${id}`}" restored.`)
    } catch (e) {
      notify.apiError(e, 'Could not restore import.')
    } finally {
      setTrashBusyId(null)
    }
  }

  /** Show a success / info / error toast that matches the generation outcome,
   *  so a FAILED batch never appears under a green "Success" header. */
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

  /** Kick off label generation for a batch. Optimistically flips the row to
   *  "In progress" while the carrier calls run, then reflects the result. */

  /** Persist a batch's bill-to account mode (survives reload + auditable). */
  const setBilling = async (id: number, mode: 'AUTO' | 'PLATFORM') => {
    setBillingSavingId(id)
    // Optimistic: reflect the choice immediately.
    setBatches((list) => list.map((b) => (b.id === id ? { ...b, billingMode: mode } : b)))
    if (mode !== 'PLATFORM') setConfirmGenId((c) => (c === id ? null : c))
    try {
      await orderImportService.setBillingMode(id, mode)
    } catch (e) {
      notify.apiError(e, 'Could not update the bill-to account.')
      await load() // revert to server truth on failure
    } finally {
      setBillingSavingId(null)
    }
  }

  /** Fix #302 F3.2 — RETRY (isRetry) uses onlyFailed=true so already-generated
   *  rows aren't re-sent to the carrier + re-billed. Platform billing mode
   *  (billingMode) forces the house account for every row. Double-click is
   *  guarded by the generatingId===id busy check at the call site. */
  const generate = async (id: number, isRetry: boolean) => {
    const platform = batches.find((b) => b.id === id)?.billingMode === 'PLATFORM'
    setConfirmGenId(null)
    setGeneratingId(id)
    setBatches((list) => list.map((b) => (b.id === id ? { ...b, status: 'IN_PROGRESS' } : b)))
    try {
      const res = await orderImportService.generateLabels(id, { onlyFailed: isRetry, usePlatformAccount: platform })
      const updated = res.data
      if (updated) {
        setBatches((list) =>
          list.map((b) =>
            b.id === id
              ? { ...b, status: updated.status, savedRows: updated.savedRows, invalidRows: updated.invalidRows, labelBatchId: updated.labelBatchId ?? b.labelBatchId }
              : b,
          ),
        )
        // Refresh the expanded rows so tracking numbers show.
        if (updated.rows) setRowsById((m) => ({ ...m, [id]: updated.rows }))
        notifyForStatus(updated.status, res.message ?? 'Label generation finished.')
      } else {
        await load()
      }
    } catch (e) {
      notify.apiError(e, 'Label generation failed.')
      await load()
    } finally {
      setGeneratingId(null)
    }
  }

  /** Generate a label for a single row inside a batch. */
  const generateRow = async (batchId: number, rowNumber: number) => {
    const key = `${batchId}-${rowNumber}`
    setGenRowKey(key)
    try {
      const res = await orderImportService.generateRowLabel(batchId, rowNumber)
      const updated = res.data
      if (updated) {
        if (updated.rows) setRowsById((m) => ({ ...m, [batchId]: updated.rows }))
        setBatches((list) =>
          list.map((b) =>
            b.id === batchId
              ? { ...b, status: updated.status, savedRows: updated.savedRows, invalidRows: updated.invalidRows, labelBatchId: updated.labelBatchId ?? b.labelBatchId }
              : b,
          ),
        )
        // Notify on THIS row's outcome, not the whole batch.
        const thisRow = updated.rows?.find((r) => r.rowNumber === rowNumber)
        if ((thisRow?.generatedStatus ?? '').toUpperCase() === 'GENERATED') {
          notify.success(`Label generated for row ${rowNumber}.`)
        } else {
          notify.error({
            title: `Row ${rowNumber} — label failed`,
            body: thisRow?.generatedMessage || res.message || 'The carrier rejected this shipment.',
          })
        }
      }
    } catch (e) {
      notify.apiError(e, 'Label generation failed.')
    } finally {
      setGenRowKey(null)
    }
  }

  /**
   * Persist one edited cell in place. Applies the typed value to the row,
   * PUTs it (updateRow re-validates the whole batch server-side), and drops
   * the fresh rows + counts back into state so the grid repaints — red cells,
   * ready/held status, and the batch counters all update. No-op when the value
   * is unchanged.
   */
  const commitCell = async (batchId: number, row: OrderImportRow, col: DhColumn, raw: string) => {
    let next: unknown = raw
    if (col.numeric) next = raw === '' ? null : Number(raw)
    else if (col.upper) next = raw.toUpperCase()
    const current = (row as unknown as Record<string, unknown>)[col.key]
    if (String(current ?? '') === String(next ?? '')) return // unchanged
    const edited = { ...row, [col.key]: next } as OrderImportRow
    const key = `${batchId}-${row.rowNumber}`
    setSavingCell(key)
    try {
      const res = await orderImportService.updateRow(batchId, row.rowNumber, edited)
      const updated = res.data
      if (updated) {
        if (updated.rows) setRowsById((m) => ({ ...m, [batchId]: updated.rows }))
        setBatches((list) =>
          list.map((b) =>
            b.id === batchId
              ? { ...b, status: updated.status, savedRows: updated.savedRows, invalidRows: updated.invalidRows }
              : b,
          ),
        )
      } else {
        notify.error(res.message ?? 'Save failed.')
      }
    } catch (e) {
      notify.apiError(e, 'Save failed.')
    } finally {
      setSavingCell(null)
    }
  }

  const fmtDate = (v?: string | null) => {
    if (!v) return '—'
    const d = new Date(v)
    return Number.isNaN(d.getTime())
      ? v
      : d.toLocaleString('en-US', { month: 'short', day: 'numeric', year: 'numeric', hour: '2-digit', minute: '2-digit' })
  }

  /** Map an import status to a friendly label + pill classes. */
  const statusMeta = (status?: string | null): { label: string; cls: string } => {
    switch ((status || '').toUpperCase()) {
      case 'COMPLETE':
        return { label: 'Complete', cls: 'bg-emerald-50 text-emerald-700 ring-emerald-200' }
      case 'PARTIAL_COMPLETE':
        return { label: 'Partial complete', cls: 'bg-amber-50 text-amber-700 ring-amber-200' }
      case 'FAILED':
        return { label: 'Failed', cls: 'bg-rose-50 text-rose-700 ring-rose-200' }
      case 'IN_PROGRESS':
        return { label: 'In progress', cls: 'bg-sky-50 text-sky-700 ring-sky-200' }
      case 'INITIATE':
        return { label: 'Initiated', cls: 'bg-slate-100 text-slate-600 ring-slate-200' }
      case 'DRAFT':
        return { label: 'Draft', cls: 'bg-orange-50 text-orange-700 ring-orange-200' }
      default:
        return { label: status || '—', cls: 'bg-slate-100 text-slate-500 ring-slate-200' }
    }
  }

  /** Lazy-load a batch's rows when its row is expanded. */
  const ensureRows = (id: number) => {
    if (rowsById[id]) return
    setRowsById((m) => ({ ...m, [id]: 'loading' }))
    orderImportService
      .getHistory(id)
      .then((res) => setRowsById((m) => ({ ...m, [id]: res.data?.rows ?? [] })))
      .catch((e) => {
        notify.apiError(e, 'Could not load import rows.')
        setRowsById((m) => ({ ...m, [id]: [] }))
      })
  }

  // Columns for the Import-history table (reorder/resize via AdvancedDataTable).
  const dhColumns = useMemo<ColumnDef<ImportBatchSummary, unknown>[]>(
    () => [
      {
        id: 'serial',
        header: 'Serial no.',
        enableSorting: false,
        size: 70,
        accessorFn: (b) => b.id,
        cell: ({ row }) => <span className="font-mono text-[13px] font-bold text-[#1f150c]">#{row.original.id}</span>,
        meta: { headerLabel: 'Serial no.' },
      },
      {
        id: 'file',
        header: 'File',
        enableSorting: false,
        size: 240,
        accessorFn: (b) => b.fileName ?? '',
        cell: ({ row }) => {
          const b = row.original
          return (
            <span className="block min-w-0">
              <span className="flex items-center gap-1.5">
                <FiFileText className="h-3.5 w-3.5 shrink-0 text-[#b6a684]" />
                <span className="truncate text-[12.5px] font-semibold text-[#1f150c]" title={b.fileName || undefined}>
                  {b.fileName || 'Untitled import'}
                </span>
              </span>
              <span className="mt-0.5 flex flex-wrap items-center gap-x-1.5 gap-y-1 text-[11px] text-[#8a7959]">
                <span>
                  {fmtDate(b.createdAt)} · {b.createdBy || '—'}
                </span>
                {b.labelBatchId != null ? (
                  <span
                    title="Label batch — find these orders together in All Orders"
                    className="inline-flex items-center gap-1 rounded-full bg-[#412d15] px-2 py-0.5 font-mono text-[9.5px] font-bold uppercase tracking-[0.08em] text-[#f4eede]"
                  >
                    <FiZap className="h-2.5 w-2.5" /> Batch {b.labelBatchId}
                  </span>
                ) : (
                  <span className="font-mono text-[9.5px] uppercase tracking-[0.08em] text-[#b6a684]">No batch yet</span>
                )}
              </span>
            </span>
          )
        },
        meta: { headerLabel: 'File' },
      },
      {
        id: 'status',
        header: 'Status',
        enableSorting: false,
        size: 130,
        accessorFn: (b) => b.status ?? '',
        cell: ({ row }) => {
          const s = statusMeta(row.original.status)
          return <span className={`rounded-full px-2.5 py-0.5 text-[10.5px] font-bold ring-1 ${s.cls}`}>{s.label}</span>
        },
        meta: { headerLabel: 'Status' },
      },
      {
        id: 'rows',
        header: 'Rows',
        enableSorting: false,
        size: 120,
        accessorFn: (b) => b.savedRows,
        cell: ({ row }) => {
          const b = row.original
          return (
            <span className="flex items-center gap-1.5">
              <span className="rounded-full bg-emerald-50 px-2 py-0.5 text-[10.5px] font-bold text-emerald-700 ring-1 ring-emerald-200">
                {b.savedRows} saved
              </span>
              {b.invalidRows > 0 ? (
                <span className="rounded-full bg-rose-50 px-2 py-0.5 text-[10.5px] font-bold text-rose-700 ring-1 ring-rose-200">
                  {b.invalidRows} err
                </span>
              ) : null}
            </span>
          )
        },
        meta: { headerLabel: 'Rows' },
      },
      {
        id: 'actions',
        header: 'Actions',
        enableSorting: false,
        size: 400,
        cell: ({ row }) => {
          const b = row.original
          const st = (b.status || '').toUpperCase()
          const canGenerate = canWrite && (st === 'INITIATE' || st === 'PARTIAL_COMPLETE' || st === 'FAILED')
          const isRetry = st === 'PARTIAL_COMPLETE' || st === 'FAILED'
          const busy = generatingId === b.id
          const platform = b.billingMode === 'PLATFORM'
          const confirming = confirmGenId === b.id
          return (
            <div className="flex items-center justify-end gap-1.5">
              {viewTrash ? (
                canWrite ? (
                  <button
                    type="button"
                    onClick={() => void handleRestore(b.id, b.fileName)}
                    disabled={trashBusyId === b.id}
                    title="Restore this import from Trash"
                    className="inline-flex items-center gap-1.5 rounded-xl border border-[#412d15] bg-white px-3 py-2 text-[12px] font-semibold text-[#412d15] transition hover:bg-[#faf7f0] disabled:cursor-not-allowed disabled:opacity-50"
                  >
                    {trashBusyId === b.id ? (
                      <span className="inline-block h-3.5 w-3.5 animate-spin rounded-full border-2 border-[#412d15]/30 border-t-[#412d15]" />
                    ) : (
                      <FiRotateCcw className="h-3.5 w-3.5" />
                    )}
                    Restore
                  </button>
                ) : null
              ) : (
                <>
                  {canGenerate ? (
                    <>
                      <span
                        title="Which carrier account this batch bills to. Platform bills the house account and rebills the client with markup."
                        className={`${confirming ? 'hidden' : 'inline-flex'} items-center gap-1.5 rounded-xl border px-2.5 py-1.5 text-[11px] font-semibold ${
                          platform ? 'border-[#412d15] bg-[#412d15]/5 text-[#412d15]' : 'border-[#e3d9c4] bg-white text-[#5a4526]'
                        }`}
                      >
                        <FiHome className="h-3.5 w-3.5 shrink-0" />
                        <span className="hidden sm:inline text-[9.5px] uppercase tracking-[0.08em] text-[#b6a684]">Bills to</span>
                        <select
                          value={platform ? 'PLATFORM' : 'AUTO'}
                          disabled={busy || billingSavingId === b.id}
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
                            onClick={() => void generate(b.id, isRetry)}
                            disabled={busy}
                            className="inline-flex items-center gap-1.5 rounded-xl bg-[#412d15] px-3 py-2 text-[12px] font-semibold text-[#f4eede] shadow-sm transition hover:bg-[#5a4526] disabled:cursor-not-allowed disabled:bg-[#dcd4c4]"
                          >
                            <FiHome className="h-3.5 w-3.5" />
                            Confirm — bill to platform
                          </button>
                          <button
                            type="button"
                            onClick={() => setConfirmGenId(null)}
                            disabled={busy}
                            className="inline-flex items-center rounded-xl border border-[#e3d9c4] bg-white px-3 py-2 text-[12px] font-semibold text-[#5a4526] transition hover:bg-[#faf7f0]"
                          >
                            Cancel
                          </button>
                        </>
                      ) : (
                        <button
                          type="button"
                          onClick={() => (platform ? setConfirmGenId(b.id) : void generate(b.id, isRetry))}
                          disabled={busy}
                          title={isRetry ? 'Retry generating labels — only rows that FAILED or are un-generated will be re-sent' : 'Generate carrier labels for this saved import'}
                          className="inline-flex items-center gap-1.5 rounded-xl bg-[#1f150c] px-3 py-2 text-[12px] font-semibold text-[#f4eede] shadow-sm transition hover:bg-[#412d15] disabled:cursor-not-allowed disabled:bg-[#dcd4c4]"
                        >
                          {busy ? (
                            <span className="inline-block h-3.5 w-3.5 animate-spin rounded-full border-2 border-[#f4eede]/40 border-t-[#f4eede]" />
                          ) : (
                            <FiZap className="h-3.5 w-3.5" />
                          )}
                          {busy ? 'Generating…' : isRetry ? 'Retry labels' : 'Generate labels'}
                        </button>
                      )}
                    </>
                  ) : null}
                  {canWrite ? (
                    <button
                      type="button"
                      onClick={() => void handleDelete(b.id, b.fileName)}
                      disabled={trashBusyId === b.id}
                      title="Move this import to Trash (recoverable)"
                      aria-label="Delete import"
                      className="inline-flex items-center justify-center rounded-xl border border-[#e3d9c4] bg-white p-2 text-[#8a7959] transition hover:border-rose-300 hover:bg-rose-50 hover:text-rose-600 disabled:cursor-not-allowed disabled:opacity-50"
                    >
                      {trashBusyId === b.id ? (
                        <span className="inline-block h-3.5 w-3.5 animate-spin rounded-full border-2 border-rose-300/40 border-t-rose-500" />
                      ) : (
                        <FiTrash2 className="h-3.5 w-3.5" />
                      )}
                    </button>
                  ) : null}
                </>
              )}
            </div>
          )
        },
        meta: { headerLabel: 'Actions' },
      },
    ],
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [canWrite, viewTrash, trashBusyId, confirmGenId, billingSavingId, generatingId],
  )

  /** Expanded content for a batch row — the all-columns editable grid. */
  const renderBatchExpanded = (b: ImportBatchSummary) => {
    const rows = rowsById[b.id]
    return (
      <div className="border-t border-dashed border-[#eee6d6] bg-[#faf7f0]/50 px-5 py-3">
        {rows === 'loading' || rows === undefined ? (
          <p className="py-4 text-center text-[12px] text-[#8a7959]">Loading rows…</p>
        ) : rows.length === 0 ? (
          <p className="py-4 text-center text-[12px] text-[#8a7959]">No rows stored for this import.</p>
        ) : (
          <>
            <p className="mb-1.5 text-[10.5px] text-[#b6a684]">
              All columns shown — click any cell to edit; it saves and re-validates on blur. Scroll right for more.
            </p>
            <div className="overflow-x-auto rounded-xl border border-[#e3d9c4] bg-white">
              <table className="w-full border-collapse text-[11px] text-[#3f3527]">
                <thead>
                  <tr className="bg-[#faf7f0] text-[8.5px] uppercase tracking-[0.1em] text-[#8a7959]">
                    <th className="sticky left-0 z-20 border-b border-r border-[#e3d9c4] bg-[#faf7f0] px-2 py-1.5 text-left font-bold">Row</th>
                    {DH_COLUMNS.map((c) => (
                      <th key={c.key} className="whitespace-nowrap border-b border-[#e3d9c4] px-2 py-1.5 text-left font-bold">{c.key}</th>
                    ))}
                    <th className="whitespace-nowrap border-b border-[#e3d9c4] px-2 py-1.5 text-left font-bold">Label</th>
                  </tr>
                </thead>
                <tbody>
                  {rows.map((r) => {
                    const ok = (r.errors?.length ?? 0) === 0
                    const gen = (r.generatedStatus ?? '').toUpperCase()
                    const generated = gen === 'GENERATED'
                    const rowKey = `${b.id}-${r.rowNumber}`
                    const rowBusy = genRowKey === rowKey
                    const saving = savingCell === rowKey
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
                              <span title={statusTitle} className="cursor-help rounded-full bg-rose-100 px-1.5 py-0.5 text-[9px] font-semibold text-rose-800">{r.errors!.length} err</span>
                            )}
                            {saving ? <span className="inline-block h-2.5 w-2.5 animate-spin rounded-full border-2 border-[#cdbf9f] border-t-[#5a4526]" /> : null}
                          </div>
                        </td>
                        {DH_COLUMNS.map((c) => {
                          const raw = (r as unknown as Record<string, unknown>)[c.key]
                          return (
                            <td key={c.key} className="border-b border-[#f2ecdf] px-1 py-1 align-top">
                              <div className={c.w}>
                                <GridCell
                                  value={raw == null ? '' : String(raw)}
                                  readOnly={generated}
                                  bad={(byField[c.key]?.length ?? 0) > 0}
                                  errors={byField[c.key]}
                                  mono={c.mono}
                                  onCommit={(v) => void commitCell(b.id, r, c, v)}
                                />
                              </div>
                            </td>
                          )
                        })}
                        <td className="whitespace-nowrap border-b border-[#f2ecdf] px-2 py-1">
                          {generated ? (
                            <span className="inline-flex flex-col gap-0.5">
                              {r.generatedTrackingNumber ? (
                                <span className="font-mono text-[9.5px] text-[#8a7959]">{r.generatedTrackingNumber}</span>
                              ) : (
                                <span className="text-[9.5px] text-[#8a7959]">—</span>
                              )}
                            </span>
                          ) : !canWrite ? (
                            <span className="text-[9.5px] text-[#b6a684]">Read-only view</span>
                          ) : ok ? (
                            <button
                              type="button"
                              onClick={() => void generateRow(b.id, r.rowNumber)}
                              disabled={rowBusy}
                              className="inline-flex items-center gap-1 rounded-lg bg-[#1f150c] px-2 py-1 text-[10px] font-semibold text-[#f4eede] transition hover:bg-[#412d15] disabled:cursor-not-allowed disabled:bg-[#dcd4c4]"
                            >
                              {rowBusy ? (
                                <span className="inline-block h-3 w-3 animate-spin rounded-full border-2 border-[#f4eede]/40 border-t-[#f4eede]" />
                              ) : (
                                <FiZap className="h-3 w-3" />
                              )}
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
          </>
        )}
      </div>
    )
  }

  return (
    <div className="space-y-4 pb-24">
      <PageSectionHeader
        eyebrow="Operations"
        title="Order Intake"
        description="Every order in one place — Bulk, Manual, API, and WMS — plus the CSV/Excel importer and saved import history."
        actions={
          <div className="flex flex-wrap items-center gap-2">
            {dhView === 'imports' ? (
              <button
                type="button"
                onClick={() => setShowAdvanced((v) => !v)}
                className={`inline-flex items-center gap-1.5 rounded-xl border px-3 py-2 text-[12.5px] font-semibold transition ${
                  showAdvanced || activeAdvancedCount > 0
                    ? 'border-[#412d15] bg-[#412d15] text-[#f4eede]'
                    : 'border-[#e3d9c4] bg-white text-[#5a4526] hover:border-[#cdbf9f] hover:bg-[#faf7f0]'
                }`}
              >
                <FiSliders className="h-3.5 w-3.5" />
                Advanced
                {activeAdvancedCount > 0 ? (
                  <span className="ml-0.5 inline-flex h-4 min-w-4 items-center justify-center rounded-full bg-[#f4eede] px-1 text-[9.5px] font-bold text-[#412d15]">
                    {activeAdvancedCount}
                  </span>
                ) : null}
              </button>
            ) : null}
            {dhView === 'imports' ? (
              <button
                type="button"
                onClick={() => void load()}
                className="inline-flex items-center gap-1.5 rounded-xl border border-[#e3d9c4] bg-white px-3 py-2 text-[12.5px] font-semibold text-[#5a4526] transition hover:border-[#cdbf9f] hover:bg-[#faf7f0]"
              >
                <FiRefreshCw className="h-3.5 w-3.5" />
                Refresh
              </button>
            ) : null}
            {dhView === 'imports' && viewTrash && batches.length > 0 ? (
              confirmEmpty ? (
                <span className="inline-flex items-center gap-1.5">
                  <button
                    type="button"
                    onClick={() => void handleEmptyTrash()}
                    disabled={emptying}
                    className="inline-flex items-center gap-1.5 rounded-xl bg-rose-600 px-3 py-2 text-[12.5px] font-semibold text-white shadow-sm transition hover:bg-rose-700 disabled:cursor-not-allowed disabled:opacity-60"
                  >
                    {emptying ? (
                      <span className="inline-block h-3.5 w-3.5 animate-spin rounded-full border-2 border-white/40 border-t-white" />
                    ) : (
                      <FiTrash2 className="h-3.5 w-3.5" />
                    )}
                    Delete {batches.length} forever
                  </button>
                  <button
                    type="button"
                    onClick={() => setConfirmEmpty(false)}
                    disabled={emptying}
                    className="inline-flex items-center rounded-xl border border-[#e3d9c4] bg-white px-3 py-2 text-[12.5px] font-semibold text-[#5a4526] transition hover:bg-[#faf7f0]"
                  >
                    Cancel
                  </button>
                </span>
              ) : (
                <button
                  type="button"
                  onClick={() => setConfirmEmpty(true)}
                  title="Permanently delete everything in Trash"
                  className="inline-flex items-center gap-1.5 rounded-xl border border-rose-200 bg-white px-3 py-2 text-[12.5px] font-semibold text-rose-600 transition hover:border-rose-300 hover:bg-rose-50"
                >
                  <FiTrash2 className="h-3.5 w-3.5" />
                  Empty Trash
                </button>
              )
            ) : null}
            {dhView === 'imports' ? (
              <button
                type="button"
                onClick={() => setViewTrash((v) => !v)}
                title={viewTrash ? 'Back to live imports' : 'View deleted imports (Trash)'}
                className={`inline-flex items-center gap-1.5 rounded-xl border px-3 py-2 text-[12.5px] font-semibold transition ${
                  viewTrash
                    ? 'border-[#412d15] bg-[#412d15] text-[#f4eede]'
                    : 'border-[#e3d9c4] bg-white text-[#5a4526] hover:border-[#cdbf9f] hover:bg-[#faf7f0]'
                }`}
              >
                {viewTrash ? <FiArrowLeft className="h-3.5 w-3.5" /> : <FiTrash2 className="h-3.5 w-3.5" />}
                {viewTrash ? 'Back to imports' : 'Trash'}
              </button>
            ) : null}
            <button
              type="button"
              onClick={() => navigate('/orders')}
              className="inline-flex items-center gap-1.5 rounded-xl bg-[#1f150c] px-3.5 py-2 text-[12.5px] font-semibold text-[#f4eede] shadow-sm transition hover:bg-[#412d15]"
            >
              <FiArrowLeft className="h-3.5 w-3.5" />
              Back to orders
            </button>
          </div>
        }
      />

      {/* View tabs — Imports (bulk batches) vs All orders (every source) */}
      <div className="flex flex-wrap items-center gap-1.5">
        {([
          { key: 'orders', label: 'All orders', hint: 'Bulk · Manual · API · WMS' },
          { key: 'import', label: 'Import CSV/Excel', hint: 'Upload & validate' },
          { key: 'imports', label: 'Import history', hint: 'Saved batches' },
        ] as { key: 'orders' | 'import' | 'imports'; label: string; hint: string }[]).map((t) => {
          const active = dhView === t.key
          return (
            <button
              key={t.key}
              type="button"
              onClick={() => setDhView(t.key)}
              className={`inline-flex items-baseline gap-1.5 rounded-xl px-3.5 py-2 text-[12.5px] font-semibold transition ${
                active
                  ? 'bg-[#1f150c] text-[#f4eede]'
                  : 'border border-[#e3d9c4] bg-white text-[#5a4526] hover:border-[#cdbf9f] hover:bg-[#faf7f0]'
              }`}
            >
              {t.label}
              <span className={`text-[9.5px] font-medium uppercase tracking-[0.06em] ${active ? 'text-[#cdbf9f]' : 'text-[#b6a684]'}`}>
                {t.hint}
              </span>
            </button>
          )
        })}
      </div>

      {dhView === 'orders' ? (
        <AllOrdersHistory />
      ) : dhView === 'import' ? (
        <OrderImportModal
          inline
          onImported={() => {
            // After a save, jump to the batch history and refresh it.
            setDhView('imports')
            void load()
          }}
        />
      ) : (
      <>
      {/* ── Advanced filter toolbar ─────────────────────────────────────── */}
      <section className="rounded-2xl border border-[#e3d9c4] bg-white p-4 shadow-sm">
        {/* Status chips + clear */}
        <div className="flex flex-col gap-3">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <div className="flex flex-wrap gap-1.5">
              {([
                { key: 'ALL', label: 'All' },
                { key: 'DRAFT', label: 'Draft' },
                { key: 'COMPLETE', label: 'Complete' },
                { key: 'PARTIAL_COMPLETE', label: 'Partial complete' },
                { key: 'IN_PROGRESS', label: 'In progress' },
                { key: 'INITIATE', label: 'Initiated' },
                { key: 'FAILED', label: 'Failed' },
              ] as { key: StatusKey; label: string }[]).map((s) => {
                const active = statusFilter === s.key
                const n = statusCounts[s.key] ?? 0
                return (
                  <button
                    key={s.key}
                    type="button"
                    onClick={() => setStatusFilter(s.key)}
                    className={`inline-flex items-center gap-1.5 rounded-xl px-3 py-1.5 text-[12px] font-semibold transition ${
                      active
                        ? 'bg-[#1f150c] text-[#f4eede]'
                        : 'bg-[#faf7f0] text-[#5a4526] hover:bg-[#f0e9d8]'
                    }`}
                  >
                    {s.label}
                    <span
                      className={`inline-flex h-4 min-w-4 items-center justify-center rounded-full px-1 text-[9.5px] font-bold ${
                        active ? 'bg-[#f4eede]/25 text-[#f4eede]' : 'bg-white text-[#8a7959] ring-1 ring-[#e3d9c4]'
                      }`}
                    >
                      {n}
                    </span>
                  </button>
                )
              })}
            </div>
            {anyFilterActive ? (
              <button
                type="button"
                onClick={clearFilters}
                className="inline-flex items-center gap-1 rounded-xl border border-[#e3d9c4] bg-white px-2.5 py-1.5 text-[11.5px] font-semibold text-[#8a7959] transition hover:border-rose-200 hover:bg-rose-50 hover:text-rose-700"
              >
                <FiX className="h-3.5 w-3.5" /> Clear filters
              </button>
            ) : null}
          </div>

          {/* Search + sort */}
          <div className="grid gap-2.5 lg:grid-cols-[minmax(0,1fr)_auto_auto]">
            <label className="relative block">
              <FiSearch className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-[#b6a684]" />
              <input
                type="search"
                value={search}
                onChange={(e) => setSearch(e.target.value)}
                placeholder="Search file name, batch #, or user…"
                className="w-full rounded-xl border border-[#e3d9c4] bg-[#faf7f0] py-2 pl-9 pr-3 text-[13px] text-[#1f150c] outline-none transition placeholder:text-[#b6a684] focus:border-[#cdbf9f] focus:bg-white focus:ring-4 focus:ring-[#f0e9d8]"
              />
            </label>
            <select
              value={sortKey}
              onChange={(e) => setSortKey(e.target.value as SortKey)}
              className="rounded-xl border border-[#e3d9c4] bg-white px-3 py-2 text-[13px] font-semibold text-[#5a4526] outline-none transition focus:border-[#cdbf9f] focus:ring-4 focus:ring-[#f0e9d8]"
            >
              <option value="created">Sort: Date created</option>
              <option value="fileName">Sort: File name</option>
              <option value="savedRows">Sort: Rows saved</option>
              <option value="status">Sort: Status</option>
              <option value="labelBatch">Sort: Batch #</option>
            </select>
            <button
              type="button"
              onClick={() => setSortDir((d) => (d === 'ASC' ? 'DESC' : 'ASC'))}
              title="Toggle sort direction"
              className="rounded-xl border border-[#e3d9c4] bg-white px-3 py-2 text-[13px] font-semibold text-[#5a4526] transition hover:bg-[#faf7f0]"
            >
              {sortDir === 'ASC' ? 'Ascending ↑' : 'Descending ↓'}
            </button>
          </div>

          {/* Advanced panel */}
          {showAdvanced ? (
            <div className="grid gap-2.5 rounded-xl border border-dashed border-[#e3d9c4] bg-[#faf7f0]/60 p-3 sm:grid-cols-2 lg:grid-cols-5">
              <label className="block">
                <span className="mb-1 block font-mono text-[9px] font-bold uppercase tracking-[0.14em] text-[#b6a684]">Created from</span>
                <input
                  type="date"
                  value={dateFrom}
                  onChange={(e) => setDateFrom(e.target.value)}
                  className="w-full rounded-lg border border-[#e3d9c4] bg-white px-2.5 py-1.5 text-[12px] text-[#1f150c] outline-none focus:border-[#cdbf9f]"
                />
              </label>
              <label className="block">
                <span className="mb-1 block font-mono text-[9px] font-bold uppercase tracking-[0.14em] text-[#b6a684]">Created to</span>
                <input
                  type="date"
                  value={dateTo}
                  onChange={(e) => setDateTo(e.target.value)}
                  className="w-full rounded-lg border border-[#e3d9c4] bg-white px-2.5 py-1.5 text-[12px] text-[#1f150c] outline-none focus:border-[#cdbf9f]"
                />
              </label>
              <label className="block">
                <span className="mb-1 block font-mono text-[9px] font-bold uppercase tracking-[0.14em] text-[#b6a684]">Created by</span>
                <select
                  value={createdBy}
                  onChange={(e) => setCreatedBy(e.target.value)}
                  className="w-full rounded-lg border border-[#e3d9c4] bg-white px-2.5 py-1.5 text-[12px] text-[#1f150c] outline-none focus:border-[#cdbf9f]"
                >
                  <option value="">Anyone</option>
                  {creators.map((c) => (
                    <option key={c} value={c}>{c}</option>
                  ))}
                </select>
              </label>
              <label className="block">
                <span className="mb-1 block font-mono text-[9px] font-bold uppercase tracking-[0.14em] text-[#b6a684]">Label batch</span>
                <select
                  value={batchPresence}
                  onChange={(e) => setBatchPresence(e.target.value as 'ANY' | 'HAS' | 'NONE')}
                  className="w-full rounded-lg border border-[#e3d9c4] bg-white px-2.5 py-1.5 text-[12px] text-[#1f150c] outline-none focus:border-[#cdbf9f]"
                >
                  <option value="ANY">Any</option>
                  <option value="HAS">Has a batch</option>
                  <option value="NONE">No batch yet</option>
                </select>
              </label>
              <label className="block">
                <span className="mb-1 block font-mono text-[9px] font-bold uppercase tracking-[0.14em] text-[#b6a684]">Min rows saved</span>
                <input
                  type="number"
                  min={0}
                  value={minSaved}
                  onChange={(e) => setMinSaved(e.target.value)}
                  placeholder="0"
                  className="w-full rounded-lg border border-[#e3d9c4] bg-white px-2.5 py-1.5 text-[12px] text-[#1f150c] outline-none focus:border-[#cdbf9f]"
                />
              </label>
            </div>
          ) : null}

          {/* Result summary */}
          <div className="flex flex-wrap items-center gap-1.5 text-[11px] text-[#8a7959]">
            <FiFilter className="h-3 w-3 text-[#b6a684]" />
            <span className="font-semibold text-[#5a4526]">{filtered.length}</span>
            <span>of {batches.length} {batches.length === 1 ? 'import' : 'imports'} shown</span>
            {statusFilter !== 'ALL' ? <span className="text-[#cdbf9f]">·</span> : null}
            {statusFilter !== 'ALL' ? <span>{statusMeta(statusFilter).label}</span> : null}
          </div>
        </div>
      </section>

      <section className="rounded-2xl border border-slate-200 bg-white p-3 shadow-sm">
        {loading ? (
          <p className="px-5 py-14 text-center text-sm text-[#8a7959]">Loading…</p>
        ) : batches.length === 0 ? (
          <p className="px-5 py-14 text-center text-sm text-[#8a7959]">
            {viewTrash
              ? 'Trash is empty — no deleted imports.'
              : 'No saved imports yet. Import a CSV/Excel from the Import CSV/Excel tab, then click Save.'}
          </p>
        ) : (
          <AdvancedDataTable<ImportBatchSummary>
            tableKey={viewTrash ? 'order-intake-imports-trash-v2' : 'order-intake-imports-v2'}
            columns={dhColumns}
            data={filtered}
            renderExpanded={renderBatchExpanded}
            onRowExpand={(b) => ensureRows(b.id)}
            initialColumnPinning={{ left: [], right: ['actions'] }}
            caption={viewTrash ? 'Trash — deleted imports · click a row to view its rows' : 'Saved imports · click a row to view & edit its rows'}
            emptyState={
              <div className="px-5 py-10 text-center">
                <p className="text-sm text-[#8a7959]">No imports match your filters.</p>
                <button
                  type="button"
                  onClick={clearFilters}
                  className="mt-3 inline-flex items-center gap-1 rounded-xl border border-[#e3d9c4] bg-white px-3 py-1.5 text-[12px] font-semibold text-[#5a4526] transition hover:bg-[#faf7f0]"
                >
                  <FiX className="h-3.5 w-3.5" /> Clear filters
                </button>
              </div>
            }
          />
        )}
      </section>
      </>
      )}
    </div>
  )
}

// Every backend validation message is prefixed with the column it belongs to
// ("postalCode is required", "hsCode is required for international shipments…").
// This maps a message to its field so the offending input can go red; messages
// with no field prefix (group-level customs rules) stay row-level.
const EDIT_FIELD_KEYS = [
  'orderRef', 'clientCode', 'billTo', 'warehouseCode',
  'recipientName', 'recipientCompany', 'recipientPhone', 'recipientEmail',
  'addressLine1', 'addressLine2', 'city', 'state', 'postalCode', 'countryCode',
  'carrierCode', 'accountNumber', 'serviceType', 'packageType',
  'weight', 'weightUnit', 'currency', 'reference',
  'itemDescription', 'itemSku', 'itemQuantity', 'itemUnitValue',
  'hsCode', 'countryOfOrigin',
] as const

/** Split a row's error strings into per-field buckets + row-level leftovers. */
function bucketRowErrors(errors: string[]): {
  byField: Record<string, string[]>
  rowLevel: string[]
} {
  const byField: Record<string, string[]> = {}
  const rowLevel: string[] = []
  for (const msg of errors) {
    const first = msg.split(/[\s']/, 1)[0]
    if ((EDIT_FIELD_KEYS as readonly string[]).includes(first)) {
      ;(byField[first] ??= []).push(msg)
    } else {
      rowLevel.push(msg)
    }
  }
  return { byField, rowLevel }
}

/** Column model for the Data-History spreadsheet grid — every import field,
 *  in template order. `numeric`/`upper` shape how an edited value is written
 *  back; `w` is the cell input min-width. Mirrors the import modal's grid. */
type DhColumn = { key: string; mono?: boolean; upper?: boolean; numeric?: boolean; w: string }
const DH_COLUMNS: DhColumn[] = [
  { key: 'orderRef', mono: true, w: 'w-24' },
  { key: 'clientCode', mono: true, upper: true, w: 'w-24' },
  { key: 'billTo', mono: true, upper: true, w: 'w-24' },
  { key: 'warehouseCode', mono: true, upper: true, w: 'w-24' },
  { key: 'recipientName', w: 'w-40' },
  { key: 'recipientCompany', w: 'w-40' },
  { key: 'recipientPhone', w: 'w-28' },
  { key: 'recipientEmail', w: 'w-44' },
  { key: 'addressLine1', w: 'w-48' },
  { key: 'addressLine2', w: 'w-40' },
  { key: 'city', w: 'w-32' },
  { key: 'state', mono: true, upper: true, w: 'w-16' },
  { key: 'postalCode', mono: true, w: 'w-24' },
  { key: 'countryCode', mono: true, upper: true, w: 'w-16' },
  { key: 'carrierCode', mono: true, upper: true, w: 'w-24' },
  { key: 'accountNumber', mono: true, w: 'w-32' },
  { key: 'serviceType', mono: true, w: 'w-28' },
  { key: 'packageType', mono: true, w: 'w-24' },
  { key: 'weight', numeric: true, w: 'w-16' },
  { key: 'weightUnit', mono: true, upper: true, w: 'w-16' },
  { key: 'currency', mono: true, upper: true, w: 'w-16' },
  { key: 'reference', mono: true, w: 'w-28' },
  { key: 'itemDescription', w: 'w-48' },
  { key: 'itemSku', mono: true, w: 'w-24' },
  { key: 'itemQuantity', numeric: true, w: 'w-16' },
  { key: 'itemUnitValue', numeric: true, w: 'w-20' },
  { key: 'hsCode', mono: true, w: 'w-24' },
  { key: 'countryOfOrigin', mono: true, upper: true, w: 'w-16' },
]

/**
 * A read-only grid cell that becomes an input on click. Shows the value as
 * plain text (red + tooltip when the field failed validation); clicking opens
 * an inline editor that commits on blur or Enter, cancels on Escape. Generated
 * rows are read-only. The commit fires once on exit, not per keystroke.
 */
function GridCell({
  value,
  onCommit,
  bad = false,
  mono = false,
  errors,
  readOnly = false,
}: {
  value: string
  onCommit: (v: string) => void
  bad?: boolean
  mono?: boolean
  errors?: string[]
  readOnly?: boolean
}) {
  const [editing, setEditing] = useState(false)
  const [draft, setDraft] = useState(value)
  const inputRef = useRef<HTMLInputElement>(null)
  useEffect(() => {
    if (editing) { inputRef.current?.focus(); inputRef.current?.select() }
  }, [editing])
  const begin = () => { if (readOnly) return; setDraft(value); setEditing(true) }
  const commit = () => { setEditing(false); if (draft !== value) onCommit(draft) }
  const cancel = () => { setEditing(false); setDraft(value) }
  if (editing) {
    return (
      <input
        ref={inputRef}
        value={draft}
        onChange={(e) => setDraft(e.target.value)}
        onBlur={commit}
        onKeyDown={(e) => {
          if (e.key === 'Enter') { e.preventDefault(); commit() }
          else if (e.key === 'Escape') { e.preventDefault(); cancel() }
        }}
        className={`w-full rounded-[5px] border border-[#412d15] bg-white px-1.5 py-0.5 text-[10.5px] text-[#1f150c] outline-none ring-1 ring-[#412d15] ${mono ? 'font-mono' : ''}`}
      />
    )
  }
  const tooltip = bad && errors && errors.length > 0 ? errors.join('\n') : value || undefined
  return (
    <button
      type="button"
      onClick={begin}
      title={tooltip}
      className={`block w-full truncate rounded-[5px] px-1.5 py-0.5 text-left text-[10.5px] transition ${mono ? 'font-mono' : ''} ${
        readOnly ? 'cursor-default text-[#8a7959]'
          : bad ? 'cursor-text bg-rose-50 text-rose-800 ring-1 ring-inset ring-rose-300 hover:ring-rose-400'
          : 'cursor-text text-[#3f3527] hover:bg-[#efe7d4]'
      }`}
    >
      {value || <span className="text-[#cdbf9f]">—</span>}
    </button>
  )
}
