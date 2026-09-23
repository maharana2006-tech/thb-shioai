/**
 * The Bulk Mailer's filters, behind one button. Status, created range, sort
 * and the finer filters (creator, label batch, minimum rows) all live in a
 * popover so the list keeps the screen; the button wears a count of what is
 * active. Pure presentational: all state comes from {@link useHistoryFilters}.
 */
import { useEffect, useRef, useState } from 'react'
import { FiCalendar, FiFilter, FiX } from 'react-icons/fi'
import type {
  BatchPresenceKey,
  HistorySortKey,
  HistoryStatusKey,
} from '../hooks/useHistoryFilters'

/** Each status chip in the colour its pill in the table uses; "All" in the app's espresso. */
const CHIP_TONE: Record<string, { dot: string; active: string; count: string }> = {
  ALL: { dot: 'bg-[#1f150c]', active: 'border-[#1f150c] bg-[#1f150c] text-[#f4eede]', count: 'bg-[#f4eede]/25 text-[#f4eede]' },
  DRAFT: { dot: 'bg-orange-500', active: 'border-orange-200 bg-orange-50 text-orange-800', count: 'bg-white text-orange-700 ring-1 ring-orange-200' },
  COMPLETE: { dot: 'bg-emerald-500', active: 'border-emerald-200 bg-emerald-50 text-emerald-800', count: 'bg-white text-emerald-700 ring-1 ring-emerald-200' },
  PARTIAL_COMPLETE: { dot: 'bg-amber-400', active: 'border-amber-200 bg-amber-50 text-amber-800', count: 'bg-white text-amber-700 ring-1 ring-amber-200' },
  IN_PROGRESS: { dot: 'bg-sky-500', active: 'border-sky-200 bg-sky-50 text-sky-800', count: 'bg-white text-sky-700 ring-1 ring-sky-200' },
  INITIATE: { dot: 'bg-slate-400', active: 'border-slate-300 bg-slate-100 text-slate-700', count: 'bg-white text-slate-600 ring-1 ring-slate-300' },
  FAILED: { dot: 'bg-rose-500', active: 'border-rose-200 bg-rose-50 text-rose-800', count: 'bg-white text-rose-700 ring-1 ring-rose-200' },
}

interface StatusChip {
  key: HistoryStatusKey
  label: string
}

const STATUS_CHIPS: StatusChip[] = [
  { key: 'ALL', label: 'All' },
  { key: 'DRAFT', label: 'Draft' },
  { key: 'COMPLETE', label: 'Complete' },
  { key: 'PARTIAL_COMPLETE', label: 'Partial complete' },
  { key: 'IN_PROGRESS', label: 'In progress' },
  { key: 'INITIATE', label: 'Saved · not generated' },
  { key: 'FAILED', label: 'Failed' },
]

const FIELD = 'w-full rounded-lg border border-[#e3d9c4] bg-white px-2.5 py-1.5 text-[12px] text-[#1f150c] outline-none transition focus:border-[#cdbf9f]'
const FIELD_LABEL = 'mb-1 block text-[10px] font-semibold uppercase tracking-[0.08em] text-[#b6a684]'

export interface DataHistoryFilterToolbarProps {
  // Status chips
  statusFilter: HistoryStatusKey
  setStatusFilter: (v: HistoryStatusKey) => void
  statusCounts: Record<string, number>
  statusMetaLabel: (status?: string | null) => string

  // Clear-all
  anyFilterActive: boolean
  clearFilters: () => void

  // Date range
  dateFrom: string
  setDateFrom: (v: string) => void
  dateTo: string
  setDateTo: (v: string) => void
  dateFilterActive: boolean

  // Sort
  sortKey: HistorySortKey
  setSortKey: (v: HistorySortKey) => void
  sortDir: 'ASC' | 'DESC'
  setSortDir: (v: (prev: 'ASC' | 'DESC') => 'ASC' | 'DESC') => void

  // The finer filters
  createdBy: string
  setCreatedBy: (v: string) => void
  creators: string[]
  batchPresence: BatchPresenceKey
  setBatchPresence: (v: BatchPresenceKey) => void
  minSaved: string
  setMinSaved: (v: string) => void

  // Result summary
  filteredCount: number
  totalCount: number
}

export default function DataHistoryFilterToolbar({
  statusFilter,
  setStatusFilter,
  statusCounts,
  statusMetaLabel,
  anyFilterActive,
  clearFilters,
  dateFrom,
  setDateFrom,
  dateTo,
  setDateTo,
  dateFilterActive,
  sortKey,
  setSortKey,
  sortDir,
  setSortDir,
  createdBy,
  setCreatedBy,
  creators,
  batchPresence,
  setBatchPresence,
  minSaved,
  setMinSaved,
  filteredCount,
  totalCount,
}: DataHistoryFilterToolbarProps) {
  const [open, setOpen] = useState(false)
  const ref = useRef<HTMLDivElement>(null)
  useEffect(() => {
    if (!open) return
    const clickAway = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false)
    }
    const escape = (e: KeyboardEvent) => { if (e.key === 'Escape') setOpen(false) }
    document.addEventListener('mousedown', clickAway)
    document.addEventListener('keydown', escape)
    return () => {
      document.removeEventListener('mousedown', clickAway)
      document.removeEventListener('keydown', escape)
    }
  }, [open])

  // What the button counts: the search box is visible on its own, so not that.
  const activeCount = (statusFilter !== 'ALL' ? 1 : 0) + (dateFilterActive ? 1 : 0)
    + (createdBy ? 1 : 0) + (batchPresence !== 'ANY' ? 1 : 0) + (minSaved ? 1 : 0)
  const lit = open || activeCount > 0

  return (
    <div ref={ref} className="relative">
      <button
        type="button"
        onClick={() => setOpen((v) => !v)}
        aria-expanded={open}
        aria-haspopup="dialog"
        title="Status, date, sort and more"
        className={`inline-flex items-center gap-1.5 rounded-lg border px-2.5 py-1.5 text-[12px] font-semibold transition ${
          lit ? 'border-[#412d15] bg-[#412d15] text-[#f4eede]' : 'border-[#e3d9c4] bg-white text-[#5a4526] hover:border-[#cdbf9f] hover:bg-[#faf7f0]'
        }`}
      >
        <FiFilter className="h-3.5 w-3.5" />
        Filters
        {statusFilter !== 'ALL' ? (
          <span className="hidden max-w-[10rem] truncate font-medium opacity-80 sm:inline">· {statusMetaLabel(statusFilter)}</span>
        ) : null}
        {activeCount > 0 ? (
          <span className="ml-0.5 inline-flex h-4 min-w-4 items-center justify-center rounded-full bg-[#f4eede] px-1 text-[9.5px] font-bold text-[#412d15]">
            {activeCount}
          </span>
        ) : null}
      </button>

      {open ? (
        <div
          role="dialog"
          aria-label="Filters"
          className="absolute right-0 z-30 mt-1.5 w-[min(40rem,calc(100vw-2rem))] space-y-3 rounded-xl border border-[#e3d9c4] bg-white p-3 shadow-[0_12px_32px_rgba(31,21,12,0.14)]"
        >
          {/* Status */}
          <div>
            <span className={FIELD_LABEL}>Status</span>
            <div className="flex flex-wrap gap-1.5">
              {STATUS_CHIPS.map((s) => {
                const active = statusFilter === s.key
                const n = statusCounts[s.key] ?? 0
                const tone = CHIP_TONE[s.key]
                return (
                  <button
                    key={s.key}
                    type="button"
                    onClick={() => setStatusFilter(s.key)}
                    aria-pressed={active}
                    className={`inline-flex items-center gap-1.5 rounded-lg border px-2.5 py-1 text-[11.5px] font-semibold transition ${
                      active ? tone.active : n === 0 ? 'border-transparent bg-[#faf7f0] text-[#a1906d] hover:bg-[#f0e9d8]' : 'border-transparent bg-[#faf7f0] text-[#5a4526] hover:bg-[#f0e9d8]'
                    }`}
                  >
                    {/* The status's own colour, the same one its pill in the table uses. */}
                    <span className={`h-2 w-2 rounded-full ${tone.dot} ${!active && n === 0 ? 'opacity-40' : ''}`} aria-hidden="true" />
                    {s.label}
                    <span
                      className={`inline-flex h-4 min-w-4 items-center justify-center rounded-full px-1 text-[9.5px] font-bold tabular-nums ${
                        active ? tone.count : 'bg-white text-[#6b5c42] ring-1 ring-[#e3d9c4]'
                      }`}
                    >
                      {n}
                    </span>
                  </button>
                )
              })}
            </div>
          </div>

          {/* Created range + sort */}
          <div className="grid gap-2.5 sm:grid-cols-[auto_minmax(0,1fr)]">
            <div>
              <span className={FIELD_LABEL}>Created</span>
              <div className="flex items-center gap-1.5">
                <span className="inline-flex h-5 w-5 shrink-0 items-center justify-center rounded-md bg-sky-50 text-sky-600" aria-hidden="true">
                  <FiCalendar className="h-3 w-3" />
                </span>
                <input
                  type="date"
                  value={dateFrom}
                  max={dateTo || undefined}
                  onChange={(e) => setDateFrom(e.target.value)}
                  aria-label="Created from"
                  className="rounded-lg border border-[#e3d9c4] bg-white px-2 py-1 text-[12px] text-[#1f150c] outline-none transition focus:border-[#cdbf9f]"
                />
                <span className="text-[11px] text-[#b6a684]">to</span>
                <input
                  type="date"
                  value={dateTo}
                  min={dateFrom || undefined}
                  onChange={(e) => setDateTo(e.target.value)}
                  aria-label="Created to"
                  className="rounded-lg border border-[#e3d9c4] bg-white px-2 py-1 text-[12px] text-[#1f150c] outline-none transition focus:border-[#cdbf9f]"
                />
                {dateFilterActive ? (
                  <button
                    type="button"
                    onClick={() => { setDateFrom(''); setDateTo('') }}
                    title="Clear the date range"
                    aria-label="Clear date range"
                    className="rounded-lg p-1 text-[#b6a684] transition hover:bg-[#faf7f0] hover:text-rose-700"
                  >
                    <FiX className="h-3.5 w-3.5" />
                  </button>
                ) : null}
              </div>
            </div>
            <div>
              <span className={FIELD_LABEL}>Sort</span>
              <div className="flex items-center gap-1.5">
                <select
                  value={sortKey}
                  onChange={(e) => setSortKey(e.target.value as HistorySortKey)}
                  aria-label="Sort by"
                  className={FIELD}
                >
                  <option value="created">Date created</option>
                  <option value="fileName">File name</option>
                  <option value="savedRows">Rows</option>
                  <option value="status">Status</option>
                  <option value="labelBatch">Batch #</option>
                </select>
                <button
                  type="button"
                  onClick={() => setSortDir((d) => (d === 'ASC' ? 'DESC' : 'ASC'))}
                  title="Toggle sort direction"
                  className="shrink-0 rounded-lg border border-[#e3d9c4] bg-white px-2.5 py-1.5 text-[12px] font-semibold text-[#5a4526] transition hover:bg-[#faf7f0]"
                >
                  {sortDir === 'ASC' ? 'Ascending ↑' : 'Descending ↓'}
                </button>
              </div>
            </div>
          </div>

          {/* The finer filters */}
          <div className="grid gap-2.5 sm:grid-cols-3">
            <label className="block">
              <span className={FIELD_LABEL}>Created by</span>
              <select value={createdBy} onChange={(e) => setCreatedBy(e.target.value)} className={FIELD}>
                <option value="">Anyone</option>
                {creators.map((c) => (
                  <option key={c} value={c}>{c}</option>
                ))}
              </select>
            </label>
            <label className="block">
              <span className={FIELD_LABEL}>Label batch</span>
              <select value={batchPresence} onChange={(e) => setBatchPresence(e.target.value as BatchPresenceKey)} className={FIELD}>
                <option value="ANY">Any</option>
                <option value="HAS">Has a batch</option>
                <option value="NONE">No batch yet</option>
              </select>
            </label>
            <label className="block">
              <span className={FIELD_LABEL}>Min rows saved</span>
              <input
                type="number"
                min={0}
                value={minSaved}
                onChange={(e) => setMinSaved(e.target.value)}
                placeholder="0"
                className={FIELD}
              />
            </label>
          </div>

          {/* Result summary + clear */}
          <div className="flex items-center justify-between gap-2 border-t border-[#f2ecdf] pt-2.5 text-[11px] text-[#6b5c42]">
            <span>
              <span className="font-semibold text-[#5a4526]">{filteredCount}</span> of {totalCount} {totalCount === 1 ? 'import' : 'imports'} shown
            </span>
            {anyFilterActive ? (
              <button
                type="button"
                onClick={clearFilters}
                className="inline-flex items-center gap-1 rounded-lg border border-[#e3d9c4] bg-white px-2.5 py-1 text-[11.5px] font-semibold text-[#6b5c42] transition hover:border-rose-200 hover:bg-rose-50 hover:text-rose-700"
              >
                <FiX className="h-3.5 w-3.5" /> Clear filters
              </button>
            ) : null}
          </div>
        </div>
      ) : null}
    </div>
  )
}
