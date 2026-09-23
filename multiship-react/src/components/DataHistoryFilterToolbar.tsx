/** The Bulk Mailer's filters — the popover's editors and the applied-filter chips. */
import { useMemo, useState } from 'react'
import { FiArrowDown, FiArrowUp, FiCalendar, FiHash, FiLayers, FiSearch, FiTag, FiUser } from 'react-icons/fi'
import {
  CHIP_BTN, CHIP_OFF, CHIP_ON, Check, CountBadge, DateRangeField, FIELD_INPUT, FIELD_LABEL, FilterChips,
  FilterPopover, OPTION, OPTION_ON, rangeLabel, type FilterChip, type RailItem,
} from './ui/FilterPopover'
import type {
  BatchPresenceKey,
  HistorySortKey,
  HistoryStatusKey,
} from '../hooks/useHistoryFilters'

/** Every import status: its label, the filter hint, the table pill's classes and the filter dot. */
export const BATCH_STATUS: Record<string, { label: string; hint: string; cls: string; dot: string }> = {
  DRAFT: { label: 'Draft', hint: 'Saved with rows still to fix', cls: 'bg-orange-50 text-orange-700 ring-orange-200', dot: 'bg-orange-500' },
  INITIATE: { label: 'Saved · not generated', hint: 'Valid, no labels bought yet', cls: 'bg-slate-100 text-slate-600 ring-slate-200', dot: 'bg-slate-400' },
  IN_PROGRESS: { label: 'In progress', hint: 'Labels being bought now', cls: 'bg-sky-50 text-sky-700 ring-sky-200', dot: 'bg-sky-500' },
  PARTIAL_COMPLETE: { label: 'Partial complete', hint: 'Some rows failed', cls: 'bg-amber-50 text-amber-700 ring-amber-200', dot: 'bg-amber-400' },
  COMPLETE: { label: 'Complete', hint: 'Every label generated', cls: 'bg-emerald-50 text-emerald-700 ring-emerald-200', dot: 'bg-emerald-500' },
  FAILED: { label: 'Failed', hint: 'The run did not finish', cls: 'bg-rose-50 text-rose-700 ring-rose-200', dot: 'bg-rose-500' },
  CANCELLED: { label: 'Cancelled', hint: 'Stopped by an operator', cls: 'bg-amber-50 text-amber-700 ring-amber-200', dot: 'bg-amber-400' },
}
/** Label + pill classes for any status string, unknown ones included. */
export const statusMeta = (status?: string | null) =>
  BATCH_STATUS[(status || '').toUpperCase()] ?? { label: status || '—', cls: 'bg-slate-100 text-slate-500 ring-slate-200' }

/** The status filter's choices, in order. */
const ANY_STATUS = { label: 'Any status', hint: 'Every import', dot: 'bg-[#1f150c]' }
const STATUS_OPTIONS: HistoryStatusKey[] = ['ALL', 'DRAFT', 'INITIATE', 'IN_PROGRESS', 'PARTIAL_COMPLETE', 'COMPLETE', 'FAILED']
const statusOption = (k: HistoryStatusKey) => (k === 'ALL' ? ANY_STATUS : BATCH_STATUS[k])

const SORT_OPTIONS: { key: HistorySortKey; label: string; asc: string; desc: string }[] = [
  { key: 'created', label: 'Date created', asc: 'Oldest first', desc: 'Newest first' },
  { key: 'fileName', label: 'File name', asc: 'A → Z', desc: 'Z → A' },
  { key: 'savedRows', label: 'Rows', asc: 'Fewest first', desc: 'Most first' },
  { key: 'status', label: 'Status', asc: 'A → Z', desc: 'Z → A' },
  { key: 'labelBatch', label: 'Batch #', asc: 'Lowest first', desc: 'Highest first' },
]

const BATCH_OPTIONS: { key: BatchPresenceKey; label: string; hint: string }[] = [
  { key: 'ANY', label: 'Any', hint: 'With or without a label batch' },
  { key: 'HAS', label: 'Has a batch', hint: 'Labels were generated at least once' },
  { key: 'NONE', label: 'No batch yet', hint: 'Nothing bought for it so far' },
]

const ROW_PRESETS = [1, 10, 50, 100, 500]

type Field = 'status' | 'created' | 'createdBy' | 'labelBatch' | 'rows' | 'sort'

export interface DataHistoryFilterToolbarProps {
  // Status
  statusFilter: HistoryStatusKey
  setStatusFilter: (v: HistoryStatusKey) => void
  statusCounts: Record<string, number>

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

export default function DataHistoryFilterToolbar(props: DataHistoryFilterToolbarProps) {
  const {
    statusFilter, setStatusFilter, statusCounts,
    clearFilters,
    dateFrom, setDateFrom, dateTo, setDateTo, dateFilterActive,
    sortKey, setSortKey, sortDir, setSortDir,
    createdBy, setCreatedBy, creators,
    batchPresence, setBatchPresence,
    minSaved, setMinSaved,
    filteredCount, totalCount,
  } = props

  const [creatorQuery, setCreatorQuery] = useState('')
  // What the button counts: the search box is visible on its own, so not that.
  const activeCount = (statusFilter !== 'ALL' ? 1 : 0) + (dateFilterActive ? 1 : 0)
    + (createdBy ? 1 : 0) + (batchPresence !== 'ANY' ? 1 : 0) + (minSaved ? 1 : 0)
  const sortMeta = SORT_OPTIONS.find((s) => s.key === sortKey) ?? SORT_OPTIONS[0]

  /** The rail: each field with what it is set to. */
  const rail: RailItem<Field>[] = [
    { key: 'status', label: 'Status', icon: <FiTag className="h-3.5 w-3.5" />,
      value: statusFilter === 'ALL' ? 'Any' : BATCH_STATUS[statusFilter].label, active: statusFilter !== 'ALL' },
    { key: 'created', label: 'Created', icon: <FiCalendar className="h-3.5 w-3.5" />,
      value: dateFilterActive ? rangeLabel(dateFrom, dateTo) : 'Any time', active: dateFilterActive },
    { key: 'createdBy', label: 'Created by', icon: <FiUser className="h-3.5 w-3.5" />,
      value: createdBy || 'Anyone', active: !!createdBy },
    { key: 'labelBatch', label: 'Label batch', icon: <FiHash className="h-3.5 w-3.5" />,
      value: BATCH_OPTIONS.find((b) => b.key === batchPresence)?.label ?? 'Any', active: batchPresence !== 'ANY' },
    { key: 'rows', label: 'Rows', icon: <FiLayers className="h-3.5 w-3.5" />,
      value: minSaved ? `${minSaved}+ saved` : 'Any', active: !!minSaved },
    { key: 'sort', label: 'Sort', icon: sortDir === 'ASC' ? <FiArrowUp className="h-3.5 w-3.5" /> : <FiArrowDown className="h-3.5 w-3.5" />,
      value: `${sortMeta.label} · ${sortDir === 'ASC' ? sortMeta.asc : sortMeta.desc}`, active: false },
  ]

  const visibleCreators = useMemo(() => {
    const q = creatorQuery.trim().toLowerCase()
    return q ? creators.filter((c) => c.toLowerCase().includes(q)) : creators
  }, [creators, creatorQuery])

  return (
    <FilterPopover<Field>
      rail={rail}
      initialField="status"
      activeCount={activeCount}
      shown={filteredCount}
      total={totalCount}
      noun={totalCount === 1 ? 'import' : 'imports'}
      clearFilters={clearFilters}
    >
      {(field) => (
        <>
              {field === 'status' ? (
                <ul className="space-y-0.5">
                  {STATUS_OPTIONS.map((key) => {
                    const s = statusOption(key)
                    const on = statusFilter === key
                    const n = key === 'ALL' ? (statusCounts.ALL ?? totalCount) : (statusCounts[key] ?? 0)
                    return (
                      <li key={key}>
                        <button type="button" onClick={() => setStatusFilter(key)} aria-pressed={on} className={`${OPTION} ${on ? OPTION_ON : ''}`}>
                          <span className={`h-2.5 w-2.5 shrink-0 rounded-full ${s.dot} ${n === 0 && !on ? 'opacity-40' : ''}`} aria-hidden="true" />
                          <span className="min-w-0 flex-1">
                            <span className={`block text-[12.5px] font-semibold leading-tight ${n === 0 && !on ? 'text-[#a1906d]' : 'text-[#1f150c]'}`}>{s.label}</span>
                            <span className="block text-[10.5px] leading-tight text-[#a1906d]">{s.hint}</span>
                          </span>
                          <CountBadge n={n} />
                          {on ? <Check /> : null}
                        </button>
                      </li>
                    )
                  })}
                </ul>
              ) : null}

              {field === 'created' ? (
                <DateRangeField from={dateFrom} to={dateTo} onChange={(f, t) => { setDateFrom(f); setDateTo(t) }} label="Created" />
              ) : null}

              {field === 'createdBy' ? (
                <div className="space-y-2">
                  {creators.length > 6 ? (
                    <label className="flex items-center gap-2 rounded-lg border border-[#e3d9c4] bg-[#fcfaf5] px-2.5 py-1.5 focus-within:border-[#412d15]">
                      <FiSearch className="h-3.5 w-3.5 text-[#b6a684]" />
                      <input value={creatorQuery} onChange={(e) => setCreatorQuery(e.target.value)} placeholder="Find a user…" className="w-full bg-transparent text-[12.5px] outline-none placeholder:text-[#b6a684]" />
                    </label>
                  ) : null}
                  <ul className="space-y-0.5">
                    <li>
                      <button type="button" onClick={() => setCreatedBy('')} aria-pressed={!createdBy} className={`${OPTION} ${!createdBy ? OPTION_ON : ''}`}>
                        <span className="inline-flex h-6 w-6 shrink-0 items-center justify-center rounded-full bg-[#f4eede] text-[#6b5c42]" aria-hidden="true"><FiUser className="h-3 w-3" /></span>
                        <span className="text-[12.5px] font-semibold">Anyone</span>
                        {!createdBy ? <Check /> : null}
                      </button>
                    </li>
                    {visibleCreators.map((c) => {
                      const on = createdBy === c
                      return (
                        <li key={c}>
                          <button type="button" onClick={() => setCreatedBy(c)} aria-pressed={on} className={`${OPTION} ${on ? OPTION_ON : ''}`}>
                            <span className="inline-flex h-6 w-6 shrink-0 items-center justify-center rounded-full bg-[#412d15] text-[10px] font-bold uppercase text-[#f4eede]" aria-hidden="true">{c.slice(0, 1)}</span>
                            <span className="truncate text-[12.5px] font-semibold">{c}</span>
                            {on ? <Check /> : null}
                          </button>
                        </li>
                      )
                    })}
                    {visibleCreators.length === 0 ? <li className="px-2.5 py-2 text-[12px] text-[#a1906d]">No user matches.</li> : null}
                  </ul>
                </div>
              ) : null}

              {field === 'labelBatch' ? (
                <ul className="space-y-0.5">
                  {BATCH_OPTIONS.map((b) => {
                    const on = batchPresence === b.key
                    return (
                      <li key={b.key}>
                        <button type="button" onClick={() => setBatchPresence(b.key)} aria-pressed={on} className={`${OPTION} ${on ? OPTION_ON : ''}`}>
                          <span className="inline-flex h-6 w-6 shrink-0 items-center justify-center rounded-md bg-[#f4eede] text-[#6b5c42]" aria-hidden="true"><FiHash className="h-3 w-3" /></span>
                          <span className="min-w-0 flex-1">
                            <span className="block text-[12.5px] font-semibold leading-tight">{b.label}</span>
                            <span className="block text-[10.5px] leading-tight text-[#a1906d]">{b.hint}</span>
                          </span>
                          {on ? <Check /> : null}
                        </button>
                      </li>
                    )
                  })}
                </ul>
              ) : null}

              {field === 'rows' ? (
                <div className="space-y-3">
                  <label className="block">
                    <span className="mb-1 block text-[11px] text-[#6b5c42]">Show imports with at least</span>
                    <span className="flex items-center gap-2">
                      <input type="number" min={0} value={minSaved} onChange={(e) => setMinSaved(e.target.value)} placeholder="0" aria-label="Min rows saved" className={`${FIELD_INPUT} max-w-[8rem] tabular-nums`} />
                      <span className="text-[12px] text-[#6b5c42]">saved rows</span>
                    </span>
                  </label>
                  <div className="flex flex-wrap gap-1.5">
                    <button type="button" onClick={() => setMinSaved('')} className={`${CHIP_BTN} ${!minSaved ? CHIP_ON : CHIP_OFF}`}>Any</button>
                    {ROW_PRESETS.map((n) => (
                      <button key={n} type="button" onClick={() => setMinSaved(String(n))} className={`${CHIP_BTN} ${minSaved === String(n) ? CHIP_ON : CHIP_OFF}`}>{n}+</button>
                    ))}
                  </div>
                </div>
              ) : null}

              {field === 'sort' ? (
                <div className="space-y-3">
                  <ul className="space-y-0.5">
                    {SORT_OPTIONS.map((s) => {
                      const on = sortKey === s.key
                      return (
                        <li key={s.key}>
                          <button type="button" onClick={() => setSortKey(s.key)} aria-pressed={on} className={`${OPTION} ${on ? OPTION_ON : ''}`}>
                            <span className="text-[12.5px] font-semibold">{s.label}</span>
                            {on ? <Check /> : null}
                          </button>
                        </li>
                      )
                    })}
                  </ul>
                  <div>
                    <p className={FIELD_LABEL}>Direction</p>
                    <div className="inline-flex rounded-lg border border-[#e3d9c4] bg-[#fcfaf5] p-0.5" role="group" aria-label="Sort direction">
                      {(['DESC', 'ASC'] as const).map((d) => {
                        const on = sortDir === d
                        return (
                          <button
                            key={d}
                            type="button"
                            onClick={() => setSortDir(() => d)}
                            aria-pressed={on}
                            className={`inline-flex items-center gap-1 rounded-md px-2.5 py-1 text-[11.5px] font-semibold transition ${on ? 'bg-white text-[#1f150c] shadow-sm ring-1 ring-[#e3d9c4]' : 'text-[#6b5c42] hover:text-[#1f150c]'}`}
                          >
                            {d === 'DESC' ? <FiArrowDown className="h-3 w-3" /> : <FiArrowUp className="h-3 w-3" />}
                            {d === 'DESC' ? sortMeta.desc : sortMeta.asc}
                          </button>
                        )
                      })}
                    </div>
                  </div>
                </div>
              ) : null}
        </>
      )}
    </FilterPopover>
  )
}

/** The applied filters as chips under the toolbar — each one removable. Nothing when none apply. */
export function BulkFilterChips({
  statusFilter, setStatusFilter,
  dateFrom, setDateFrom, dateTo, setDateTo,
  createdBy, setCreatedBy,
  batchPresence, setBatchPresence,
  minSaved, setMinSaved,
  clearFilters,
}: Pick<DataHistoryFilterToolbarProps,
  'statusFilter' | 'setStatusFilter' | 'dateFrom' | 'setDateFrom' | 'dateTo' | 'setDateTo'
  | 'createdBy' | 'setCreatedBy' | 'batchPresence' | 'setBatchPresence' | 'minSaved' | 'setMinSaved' | 'clearFilters'>) {
  const chips: FilterChip[] = []
  if (statusFilter !== 'ALL') chips.push({ key: 'status', label: 'Status', value: BATCH_STATUS[statusFilter].label, clear: () => setStatusFilter('ALL') })
  if (dateFrom || dateTo) chips.push({ key: 'created', label: 'Created', value: rangeLabel(dateFrom, dateTo), clear: () => { setDateFrom(''); setDateTo('') } })
  if (createdBy) chips.push({ key: 'by', label: 'By', value: createdBy, clear: () => setCreatedBy('') })
  if (batchPresence !== 'ANY') chips.push({ key: 'batch', label: 'Batch', value: BATCH_OPTIONS.find((b) => b.key === batchPresence)?.label ?? batchPresence, clear: () => setBatchPresence('ANY') })
  if (minSaved) chips.push({ key: 'rows', label: 'Rows', value: `${minSaved}+`, clear: () => setMinSaved('') })
  return <FilterChips chips={chips} clearFilters={clearFilters} testId="bulk-filter-chips" />
}
