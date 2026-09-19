/**
 * F5-A — advanced filter toolbar for the imports view of
 * DataHistoryPage, extracted from the monolithic component so the
 * ~170-line JSX block that renders status chips, search, date range,
 * sort controls, and the collapsible advanced panel lives in its own
 * file.
 *
 * <p>Pure presentational: all state comes from
 * {@link useHistoryFilters}, wired through props.
 */
import { FiCalendar, FiFilter, FiSearch, FiX } from 'react-icons/fi'
import type {
  BatchPresenceKey,
  HistorySortKey,
  HistoryStatusKey,
} from '../hooks/useHistoryFilters'

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

export interface DataHistoryFilterToolbarProps {
  // Status chips
  statusFilter: HistoryStatusKey
  setStatusFilter: (v: HistoryStatusKey) => void
  statusCounts: Record<string, number>
  statusMetaLabel: (status?: string | null) => string

  // Clear-all
  anyFilterActive: boolean
  clearFilters: () => void

  // Search + date range
  search: string
  setSearch: (v: string) => void
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

  // Advanced panel
  showAdvanced: boolean
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
  search,
  setSearch,
  dateFrom,
  setDateFrom,
  dateTo,
  setDateTo,
  dateFilterActive,
  sortKey,
  setSortKey,
  sortDir,
  setSortDir,
  showAdvanced,
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
  return (
    <section className="rounded-2xl border border-[#e3d9c4] bg-white p-4 shadow-sm">
      {/* Status chips + clear */}
      <div className="flex flex-col gap-3">
        <div className="flex flex-wrap items-center justify-between gap-2">
          <div className="flex flex-wrap gap-1.5">
            {STATUS_CHIPS.map((s) => {
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
                      active
                        ? 'bg-[#f4eede]/25 text-[#f4eede]'
                        : 'bg-white text-[#6b5c42] ring-1 ring-[#e3d9c4]'
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
              className="inline-flex items-center gap-1 rounded-xl border border-[#e3d9c4] bg-white px-2.5 py-1.5 text-[11.5px] font-semibold text-[#6b5c42] transition hover:border-rose-200 hover:bg-rose-50 hover:text-rose-700"
            >
              <FiX className="h-3.5 w-3.5" /> Clear filters
            </button>
          ) : null}
        </div>

        {/* Search + date range + sort */}
        <div className="grid gap-2.5 lg:grid-cols-[minmax(0,1fr)_auto_auto_auto]">
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
          {/* Created-date range — first-class, not buried in the Filters panel. */}
          <div className="flex items-center gap-1.5 rounded-xl border border-[#e3d9c4] bg-white px-2.5 py-1.5">
            <FiCalendar className="h-3.5 w-3.5 shrink-0 text-[#b6a684]" />
            <span className="hidden font-mono text-[9px] font-bold uppercase tracking-[0.14em] text-[#b6a684] sm:inline">
              Created
            </span>
            <input
              type="date"
              value={dateFrom}
              max={dateTo || undefined}
              onChange={(e) => setDateFrom(e.target.value)}
              aria-label="Created from"
              className="rounded-lg border border-[#e3d9c4] bg-[#faf7f0] px-2 py-1 text-[12px] text-[#1f150c] outline-none transition focus:border-[#cdbf9f] focus:bg-white"
            />
            <span className="text-[11px] text-[#b6a684]">to</span>
            <input
              type="date"
              value={dateTo}
              min={dateFrom || undefined}
              onChange={(e) => setDateTo(e.target.value)}
              aria-label="Created to"
              className="rounded-lg border border-[#e3d9c4] bg-[#faf7f0] px-2 py-1 text-[12px] text-[#1f150c] outline-none transition focus:border-[#cdbf9f] focus:bg-white"
            />
            {dateFilterActive ? (
              <button
                type="button"
                onClick={() => {
                  setDateFrom('')
                  setDateTo('')
                }}
                title="Clear the date range"
                aria-label="Clear date range"
                className="rounded-lg p-1 text-[#b6a684] transition hover:bg-[#faf7f0] hover:text-rose-700"
              >
                <FiX className="h-3.5 w-3.5" />
              </button>
            ) : null}
          </div>
          <select
            value={sortKey}
            onChange={(e) => setSortKey(e.target.value as HistorySortKey)}
            className="rounded-xl border border-[#e3d9c4] bg-white px-3 py-2 text-[13px] font-semibold text-[#5a4526] outline-none transition focus:border-[#cdbf9f] focus:ring-4 focus:ring-[#f0e9d8]"
          >
            <option value="created">Sort: Date created</option>
            <option value="fileName">Sort: File name</option>
            <option value="savedRows">Sort: Rows</option>
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
          <div className="grid gap-2.5 rounded-xl border border-dashed border-[#e3d9c4] bg-[#faf7f0]/60 p-3 sm:grid-cols-2 lg:grid-cols-3">
            <label className="block">
              <span className="mb-1 block font-mono text-[9px] font-bold uppercase tracking-[0.14em] text-[#b6a684]">
                Created by
              </span>
              <select
                value={createdBy}
                onChange={(e) => setCreatedBy(e.target.value)}
                className="w-full rounded-lg border border-[#e3d9c4] bg-white px-2.5 py-1.5 text-[12px] text-[#1f150c] outline-none focus:border-[#cdbf9f]"
              >
                <option value="">Anyone</option>
                {creators.map((c) => (
                  <option key={c} value={c}>
                    {c}
                  </option>
                ))}
              </select>
            </label>
            <label className="block">
              <span className="mb-1 block font-mono text-[9px] font-bold uppercase tracking-[0.14em] text-[#b6a684]">
                Label batch
              </span>
              <select
                value={batchPresence}
                onChange={(e) => setBatchPresence(e.target.value as BatchPresenceKey)}
                className="w-full rounded-lg border border-[#e3d9c4] bg-white px-2.5 py-1.5 text-[12px] text-[#1f150c] outline-none focus:border-[#cdbf9f]"
              >
                <option value="ANY">Any</option>
                <option value="HAS">Has a batch</option>
                <option value="NONE">No batch yet</option>
              </select>
            </label>
            <label className="block">
              <span className="mb-1 block font-mono text-[9px] font-bold uppercase tracking-[0.14em] text-[#b6a684]">
                Min rows saved
              </span>
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
        <div className="flex flex-wrap items-center gap-1.5 text-[11px] text-[#6b5c42]">
          <FiFilter className="h-3 w-3 text-[#b6a684]" />
          <span className="font-semibold text-[#5a4526]">{filteredCount}</span>
          <span>
            of {totalCount} {totalCount === 1 ? 'import' : 'imports'} shown
          </span>
          {statusFilter !== 'ALL' ? <span className="text-[#cdbf9f]">·</span> : null}
          {statusFilter !== 'ALL' ? <span>{statusMetaLabel(statusFilter)}</span> : null}
        </div>
      </div>
    </section>
  )
}
