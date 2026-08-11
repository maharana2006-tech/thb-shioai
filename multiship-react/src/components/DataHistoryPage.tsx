import { useEffect, useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import {
  FiArrowLeft,
  FiChevronDown,
  FiChevronRight,
  FiDatabase,
  FiFileText,
  FiFilter,
  FiRefreshCw,
  FiSearch,
  FiSliders,
  FiX,
  FiZap,
} from 'react-icons/fi'
import PageSectionHeader from './workspace/PageSectionHeader'
import { notify } from '../utils/notify'
import {
  orderImportService,
  type ImportBatchSummary,
  type OrderImportRow,
} from '../api/orderImportService'

/**
 * Data History — every saved CSV/XLSX import. "Commit" in the import modal
 * saves the parsed rows here (no labels generated); this page lists those
 * saved imports and lets you expand one to see its rows.
 */
export default function DataHistoryPage() {
  const navigate = useNavigate()
  const [batches, setBatches] = useState<ImportBatchSummary[]>([])
  const [loading, setLoading] = useState(true)
  const [openId, setOpenId] = useState<number | null>(null)
  const [rowsById, setRowsById] = useState<Record<number, OrderImportRow[] | 'loading'>>({})
  const [generatingId, setGeneratingId] = useState<number | null>(null)
  const [genRowKey, setGenRowKey] = useState<string | null>(null)

  // ── Advanced filter tools ────────────────────────────────────────────────
  type StatusKey = 'ALL' | 'COMPLETE' | 'PARTIAL_COMPLETE' | 'IN_PROGRESS' | 'INITIATE' | 'FAILED'
  type SortKey = 'created' | 'fileName' | 'savedRows' | 'status' | 'labelBatch'
  const [search, setSearch] = useState('')
  const [statusFilter, setStatusFilter] = useState<StatusKey>('ALL')
  const [sortKey, setSortKey] = useState<SortKey>('created')
  const [sortDir, setSortDir] = useState<'ASC' | 'DESC'>('DESC')
  const [showAdvanced, setShowAdvanced] = useState(false)
  const [dateFrom, setDateFrom] = useState('')
  const [dateTo, setDateTo] = useState('')
  const [createdBy, setCreatedBy] = useState('')
  const [batchPresence, setBatchPresence] = useState<'ANY' | 'HAS' | 'NONE'>('ANY')
  const [minSaved, setMinSaved] = useState('')

  // ── Pagination ────────────────────────────────────────────────────────────
  const [page, setPage] = useState(1)
  const [pageSize, setPageSize] = useState(10)

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
      IN_PROGRESS: 0, INITIATE: 1, PARTIAL_COMPLETE: 2, FAILED: 3, COMPLETE: 4,
    }
    const dir = sortDir === 'ASC' ? 1 : -1
    rows.sort((a, b) => {
      let cmp = 0
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
    setPage(1)
  }, [search, statusFilter, sortKey, sortDir, dateFrom, dateTo, createdBy, batchPresence, minSaved, pageSize])

  const totalPages = Math.max(1, Math.ceil(filtered.length / pageSize))
  const safePage = Math.min(page, totalPages)
  const pageStart = (safePage - 1) * pageSize
  const paged = filtered.slice(pageStart, pageStart + pageSize)
  const rangeStart = filtered.length === 0 ? 0 : pageStart + 1
  const rangeEnd = Math.min(pageStart + pageSize, filtered.length)

  const load = async () => {
    setLoading(true)
    try {
      const res = await orderImportService.listHistory()
      setBatches(res.data ?? [])
    } catch (e) {
      notify.error(e instanceof Error ? e.message : 'Could not load import history.')
      setBatches([])
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    void load()
  }, [])

  const toggle = async (id: number) => {
    if (openId === id) {
      setOpenId(null)
      return
    }
    setOpenId(id)
    if (!rowsById[id]) {
      setRowsById((m) => ({ ...m, [id]: 'loading' }))
      try {
        const res = await orderImportService.getHistory(id)
        setRowsById((m) => ({ ...m, [id]: res.data?.rows ?? [] }))
      } catch (e) {
        notify.error(e instanceof Error ? e.message : 'Could not load import rows.')
        setRowsById((m) => ({ ...m, [id]: [] }))
      }
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
  const generate = async (id: number) => {
    setGeneratingId(id)
    setBatches((list) => list.map((b) => (b.id === id ? { ...b, status: 'IN_PROGRESS' } : b)))
    try {
      const res = await orderImportService.generateLabels(id)
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
      notify.error(e instanceof Error ? e.message : 'Label generation failed.')
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
      notify.error(e instanceof Error ? e.message : 'Label generation failed.')
    } finally {
      setGenRowKey(null)
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
      default:
        return { label: status || '—', cls: 'bg-slate-100 text-slate-500 ring-slate-200' }
    }
  }

  return (
    <div className="space-y-4 pb-24">
      <PageSectionHeader
        eyebrow="Operations"
        title="Data history"
        description="Every saved CSV / Excel import. Rows are stored here — no labels are generated on save."
        actions={
          <div className="flex flex-wrap items-center gap-2">
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
            <button
              type="button"
              onClick={() => void load()}
              className="inline-flex items-center gap-1.5 rounded-xl border border-[#e3d9c4] bg-white px-3 py-2 text-[12.5px] font-semibold text-[#5a4526] transition hover:border-[#cdbf9f] hover:bg-[#faf7f0]"
            >
              <FiRefreshCw className="h-3.5 w-3.5" />
              Refresh
            </button>
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

      {/* ── Advanced filter toolbar ─────────────────────────────────────── */}
      <section className="rounded-2xl border border-[#e3d9c4] bg-white p-4 shadow-sm">
        {/* Status chips + clear */}
        <div className="flex flex-col gap-3">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <div className="flex flex-wrap gap-1.5">
              {([
                { key: 'ALL', label: 'All' },
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

      <section className="overflow-hidden rounded-2xl border border-slate-200 bg-white shadow-sm">
        <div className="flex items-center justify-between border-b border-dashed border-[#e3d9c4] px-5 py-3">
          <span className="inline-flex items-center gap-2 font-mono text-[9px] font-bold uppercase tracking-[0.2em] text-[#b6a684]">
            <FiDatabase className="h-3.5 w-3.5" /> Saved imports
          </span>
          <span className="font-mono text-[9px] font-bold uppercase tracking-[0.16em] tabular-nums text-[#b6a684]">
            {filtered.length === 0 ? '0 imports' : `${rangeStart}–${rangeEnd} of ${filtered.length}`}
          </span>
        </div>

        {loading ? (
          <p className="px-5 py-14 text-center text-sm text-[#8a7959]">Loading…</p>
        ) : batches.length === 0 ? (
          <p className="px-5 py-14 text-center text-sm text-[#8a7959]">
            No saved imports yet. Import a CSV/Excel from Orders → Import CSV/Excel, then click Save.
          </p>
        ) : filtered.length === 0 ? (
          <div className="px-5 py-14 text-center">
            <p className="text-sm text-[#8a7959]">No imports match your filters.</p>
            <button
              type="button"
              onClick={clearFilters}
              className="mt-3 inline-flex items-center gap-1 rounded-xl border border-[#e3d9c4] bg-white px-3 py-1.5 text-[12px] font-semibold text-[#5a4526] transition hover:bg-[#faf7f0]"
            >
              <FiX className="h-3.5 w-3.5" /> Clear filters
            </button>
          </div>
        ) : (
          <div className="divide-y divide-[#eee6d6]">
            {paged.map((b) => {
              const open = openId === b.id
              const rows = rowsById[b.id]
              const st = (b.status || '').toUpperCase()
              const canGenerate = st === 'INITIATE' || st === 'PARTIAL_COMPLETE' || st === 'FAILED'
              const isRetry = st === 'PARTIAL_COMPLETE' || st === 'FAILED'
              const busy = generatingId === b.id
              return (
                <div key={b.id}>
                  <div className="flex items-stretch transition hover:bg-[#faf7f0]">
                  <button
                    type="button"
                    onClick={() => void toggle(b.id)}
                    className="grid flex-1 grid-cols-[24px_60px_minmax(0,1fr)_130px_120px] items-center gap-3 px-5 py-3 text-left"
                  >
                    <span className="text-[#8a7959]">
                      {open ? <FiChevronDown className="h-4 w-4" /> : <FiChevronRight className="h-4 w-4" />}
                    </span>
                    <span className="font-mono text-[13px] font-bold text-[#1f150c]">#{b.id}</span>
                    <span className="min-w-0">
                      <span className="flex items-center gap-1.5">
                        <FiFileText className="h-3.5 w-3.5 shrink-0 text-[#b6a684]" />
                        <span className="truncate text-[12.5px] font-semibold text-[#1f150c]" title={b.fileName || undefined}>
                          {b.fileName || 'Untitled import'}
                        </span>
                      </span>
                      <span className="mt-0.5 flex flex-wrap items-center gap-x-1.5 gap-y-1 text-[11px] text-[#8a7959]">
                        <span>{fmtDate(b.createdAt)} · {b.createdBy || '—'}</span>
                        {b.labelBatchId != null ? (
                          <span
                            title="Label batch — find these orders together in All Orders"
                            className="inline-flex items-center gap-1 rounded-full bg-[#412d15] px-2 py-0.5 font-mono text-[9.5px] font-bold uppercase tracking-[0.08em] text-[#f4eede]"
                          >
                            <FiZap className="h-2.5 w-2.5" /> Batch {b.labelBatchId}
                          </span>
                        ) : (
                          <span className="font-mono text-[9.5px] uppercase tracking-[0.08em] text-[#b6a684]">
                            No batch yet
                          </span>
                        )}
                      </span>
                    </span>
                    <span className="flex justify-center">
                      {(() => {
                        const s = statusMeta(b.status)
                        return (
                          <span className={`rounded-full px-2.5 py-0.5 text-[10.5px] font-bold ring-1 ${s.cls}`}>
                            {s.label}
                          </span>
                        )
                      })()}
                    </span>
                    <span className="flex items-center justify-end gap-1.5">
                      <span className="rounded-full bg-emerald-50 px-2 py-0.5 text-[10.5px] font-bold text-emerald-700 ring-1 ring-emerald-200">
                        {b.savedRows} saved
                      </span>
                      {b.invalidRows > 0 ? (
                        <span className="rounded-full bg-rose-50 px-2 py-0.5 text-[10.5px] font-bold text-rose-700 ring-1 ring-rose-200">
                          {b.invalidRows} err
                        </span>
                      ) : null}
                    </span>
                  </button>
                  {canGenerate ? (
                    <button
                      type="button"
                      onClick={() => void generate(b.id)}
                      disabled={busy}
                      title={isRetry ? 'Retry generating labels for the rows that failed' : 'Generate carrier labels for this saved import'}
                      className="my-2 mr-4 inline-flex shrink-0 items-center gap-1.5 self-center rounded-xl bg-[#1f150c] px-3 py-2 text-[12px] font-semibold text-[#f4eede] shadow-sm transition hover:bg-[#412d15] disabled:cursor-not-allowed disabled:bg-[#dcd4c4]"
                    >
                      {busy ? (
                        <span className="inline-block h-3.5 w-3.5 animate-spin rounded-full border-2 border-[#f4eede]/40 border-t-[#f4eede]" />
                      ) : (
                        <FiZap className="h-3.5 w-3.5" />
                      )}
                      {busy ? 'Generating…' : isRetry ? 'Retry labels' : 'Generate labels'}
                    </button>
                  ) : null}
                  </div>

                  {busy ? (
                    <div className="relative h-1 w-full overflow-hidden bg-[#eee6d6]">
                      <div className="data-history-progress-bar absolute inset-y-0 w-1/3 rounded-full bg-[#1f150c]" />
                    </div>
                  ) : null}

                  {open ? (
                    <div className="border-t border-dashed border-[#eee6d6] bg-[#faf7f0]/50 px-5 py-3">
                      {rows === 'loading' || rows === undefined ? (
                        <p className="py-4 text-center text-[12px] text-[#8a7959]">Loading rows…</p>
                      ) : rows.length === 0 ? (
                        <p className="py-4 text-center text-[12px] text-[#8a7959]">No rows stored for this import.</p>
                      ) : (
                        <div className="overflow-auto rounded-xl border border-[#e3d9c4] bg-white">
                          <table className="w-full min-w-[820px] text-left text-[11px] text-[#3f3527]">
                            <thead className="bg-[#faf7f0] text-[9.5px] uppercase tracking-[0.14em] text-[#8a7959]">
                              <tr>
                                <th className="p-2.5">#</th>
                                <th className="p-2.5">Recipient</th>
                                <th className="p-2.5">Address</th>
                                <th className="p-2.5">City / Postal</th>
                                <th className="p-2.5">Carrier</th>
                                <th className="p-2.5 text-right">Weight</th>
                                <th className="p-2.5">Status</th>
                                <th className="p-2.5">Label</th>
                              </tr>
                            </thead>
                            <tbody className="divide-y divide-[#eee6d6]">
                              {rows.map((r) => {
                                const ok = (r.errors?.length ?? 0) === 0
                                const gen = (r.generatedStatus ?? '').toUpperCase()
                                const rowKey = `${b.id}-${r.rowNumber}`
                                const rowBusy = genRowKey === rowKey
                                return (
                                  <tr key={r.rowNumber} className="transition hover:bg-[#faf7f0]/60">
                                    <td className="p-2.5 font-mono text-[10.5px] text-[#8a7959]">{r.rowNumber}</td>
                                    <td className="p-2.5">
                                      <p className="font-semibold text-[#1f150c]">{r.recipientName ?? '—'}</p>
                                      {r.recipientCompany ? (
                                        <p className="text-[10px] text-[#8a7959]">{r.recipientCompany}</p>
                                      ) : null}
                                    </td>
                                    <td className="p-2.5">
                                      <span className="block max-w-[200px] truncate">{r.addressLine1 ?? '—'}</span>
                                    </td>
                                    <td className="p-2.5">
                                      {r.city ?? '—'} · {r.postalCode ?? '—'} {r.countryCode ?? ''}
                                    </td>
                                    <td className="p-2.5">
                                      {r.carrierCode ?? '—'}
                                      {r.accountNumber ? ` · ${r.accountNumber}` : ''}
                                    </td>
                                    <td className="p-2.5 text-right">
                                      {r.weight ?? '—'} {r.weightUnit ?? ''}
                                    </td>
                                    <td className="p-2.5">
                                      {ok ? (
                                        <span className="rounded-full bg-emerald-100 px-1.5 py-0.5 text-[9.5px] font-semibold text-emerald-800">
                                          Saved
                                        </span>
                                      ) : (
                                        <span className="rounded-full bg-rose-100 px-1.5 py-0.5 text-[9.5px] font-semibold text-rose-800">
                                          {r.errors.join(', ')}
                                        </span>
                                      )}
                                    </td>
                                    <td className="p-2.5">
                                      {gen === 'GENERATED' ? (
                                        <span className="inline-flex flex-col gap-0.5">
                                          <span className="w-fit rounded-full bg-emerald-100 px-1.5 py-0.5 text-[9.5px] font-semibold text-emerald-800">
                                            Generated
                                          </span>
                                          {r.generatedTrackingNumber ? (
                                            <span className="font-mono text-[9.5px] text-[#8a7959]">{r.generatedTrackingNumber}</span>
                                          ) : null}
                                        </span>
                                      ) : !ok ? (
                                        <span className="text-[9.5px] text-[#b6a684]">Fix errors first</span>
                                      ) : (
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
                                      )}
                                    </td>
                                  </tr>
                                )
                              })}
                            </tbody>
                          </table>
                        </div>
                      )}
                    </div>
                  ) : null}
                </div>
              )
            })}
          </div>
        )}

        {/* ── Pagination footer ─────────────────────────────────────────── */}
        {!loading && filtered.length > 0 ? (
          <div className="flex flex-col gap-3 border-t border-dashed border-[#e3d9c4] px-5 py-3 sm:flex-row sm:items-center sm:justify-between">
            <div className="flex items-center gap-2 text-[11.5px] text-[#8a7959]">
              <span>Rows per page</span>
              <select
                value={pageSize}
                onChange={(e) => setPageSize(Number(e.target.value))}
                className="rounded-lg border border-[#e3d9c4] bg-white px-2 py-1 text-[11.5px] font-semibold text-[#5a4526] outline-none transition focus:border-[#cdbf9f]"
              >
                {[10, 25, 50, 100].map((n) => (
                  <option key={n} value={n}>{n}</option>
                ))}
              </select>
              <span className="text-[#cdbf9f]">·</span>
              <span>
                Showing <span className="font-semibold text-[#5a4526]">{rangeStart}–{rangeEnd}</span> of {filtered.length}
              </span>
            </div>
            <div className="flex items-center gap-2 text-[11.5px] text-[#5a4526]">
              <button
                type="button"
                disabled={safePage <= 1}
                onClick={() => setPage(safePage - 1)}
                className="rounded-lg border border-[#e3d9c4] bg-white px-3 py-1.5 font-semibold transition hover:bg-[#faf7f0] disabled:cursor-not-allowed disabled:opacity-40"
              >
                Previous
              </button>
              <span className="font-mono text-[11px] tabular-nums text-[#8a7959]">
                Page {safePage} of {totalPages}
              </span>
              <button
                type="button"
                disabled={safePage >= totalPages}
                onClick={() => setPage(safePage + 1)}
                className="rounded-lg border border-[#e3d9c4] bg-white px-3 py-1.5 font-semibold transition hover:bg-[#faf7f0] disabled:cursor-not-allowed disabled:opacity-40"
              >
                Next
              </button>
            </div>
          </div>
        ) : null}
      </section>
    </div>
  )
}
