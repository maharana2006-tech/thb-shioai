/**
 * The Bulk Mailer's filters, behind one button.
 *
 * The popover is a two-pane filter builder: a rail of the things you can
 * filter by on the left (each showing what it is set to), the editor for the
 * chosen one on the right, a result count and Clear / Done in the footer.
 * What is applied also shows as chips under the toolbar ({@link BulkFilterChips}),
 * each removable on its own. Pure presentational: all state comes from
 * {@link useHistoryFilters}.
 */
import { useCallback, useMemo, useRef, useState } from 'react'
import {
  FiArrowDown,
  FiArrowUp,
  FiCalendar,
  FiCheck,
  FiChevronRight,
  FiFilter,
  FiHash,
  FiLayers,
  FiSearch,
  FiTag,
  FiUser,
  FiX,
} from 'react-icons/fi'
import { useDismissable } from '../hooks/useDismissable'
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
/** The popover's width when the screen allows it. */
const POPOVER_W = 592

type Field = 'status' | 'created' | 'createdBy' | 'labelBatch' | 'rows' | 'sort'

/** YYYY-MM-DD in local time. */
const isoDay = (d: Date) => {
  const y = d.getFullYear()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${y}-${m}-${day}`
}
const daysAgo = (n: number) => { const d = new Date(); d.setDate(d.getDate() - n); return isoDay(d) }
const monthStart = () => { const d = new Date(); d.setDate(1); return isoDay(d) }

const DATE_PRESETS: { label: string; from: () => string; to: () => string }[] = [
  { label: 'Today', from: () => daysAgo(0), to: () => daysAgo(0) },
  { label: 'Last 7 days', from: () => daysAgo(6), to: () => daysAgo(0) },
  { label: 'Last 30 days', from: () => daysAgo(29), to: () => daysAgo(0) },
  { label: 'This month', from: monthStart, to: () => daysAgo(0) },
]

/** "21 Sep" / "21 Sep 2025" for a chip. */
const shortDay = (iso: string) => {
  const d = new Date(`${iso}T00:00:00`)
  if (Number.isNaN(d.getTime())) return iso
  const sameYear = d.getFullYear() === new Date().getFullYear()
  return d.toLocaleDateString(undefined, { day: 'numeric', month: 'short', ...(sameYear ? {} : { year: 'numeric' }) })
}
const rangeLabel = (from: string, to: string) =>
  from && to ? (from === to ? shortDay(from) : `${shortDay(from)} – ${shortDay(to)}`) : from ? `from ${shortDay(from)}` : `until ${shortDay(to)}`

const OPTION = 'flex w-full items-center gap-2.5 rounded-lg px-2.5 py-2 text-left transition hover:bg-[#faf7f0]'
const OPTION_ON = 'bg-[#f4eede]/70 hover:bg-[#f4eede]'
const FIELD_INPUT = 'w-full rounded-lg border border-[#e3d9c4] bg-white px-2.5 py-1.5 text-[12.5px] text-[#1f150c] outline-none transition focus:border-[#412d15] focus:ring-4 focus:ring-[#f0e9d8]'
const CHIP_BTN = 'inline-flex items-center gap-1 rounded-full border px-2.5 py-1 text-[11.5px] font-semibold transition'
const CHIP_OFF = 'border-[#e3d9c4] bg-white text-[#5a4526] hover:border-[#cdbf9f] hover:bg-[#faf7f0]'
const CHIP_ON = 'border-[#1f150c] bg-[#1f150c] text-[#f4eede]'

export interface DataHistoryFilterToolbarProps {
  // Status
  statusFilter: HistoryStatusKey
  setStatusFilter: (v: HistoryStatusKey) => void
  statusCounts: Record<string, number>

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

export default function DataHistoryFilterToolbar(props: DataHistoryFilterToolbarProps) {
  const {
    statusFilter, setStatusFilter, statusCounts,
    anyFilterActive, clearFilters,
    dateFrom, setDateFrom, dateTo, setDateTo, dateFilterActive,
    sortKey, setSortKey, sortDir, setSortDir,
    createdBy, setCreatedBy, creators,
    batchPresence, setBatchPresence,
    minSaved, setMinSaved,
    filteredCount, totalCount,
  } = props

  const [open, setOpen] = useState(false)
  // Where the popover sits: from the button's left edge, pulled back just enough to stay on screen.
  const [shift, setShift] = useState(0)
  const toggle = () => {
    const rect = ref.current?.getBoundingClientRect()
    if (rect && typeof window !== 'undefined') {
      // The visual viewport is what the person can actually see (and what 100vw is).
      const vw = window.visualViewport?.width ?? window.innerWidth
      const width = Math.min(POPOVER_W, vw - 32)
      const overflow = rect.left + width - (vw - 16)
      setShift(overflow > 0 ? -Math.min(overflow, Math.max(rect.left - 16, 0)) : 0)
    }
    setOpen((v) => !v)
  }
  const [field, setField] = useState<Field>('status')
  const [creatorQuery, setCreatorQuery] = useState('')
  const ref = useDismissable(open, useCallback(() => setOpen(false), []))
  const paneRef = useRef<HTMLDivElement>(null)

  // What the button counts: the search box is visible on its own, so not that.
  const activeCount = (statusFilter !== 'ALL' ? 1 : 0) + (dateFilterActive ? 1 : 0)
    + (createdBy ? 1 : 0) + (batchPresence !== 'ANY' ? 1 : 0) + (minSaved ? 1 : 0)
  const lit = open || activeCount > 0
  const sortMeta = SORT_OPTIONS.find((s) => s.key === sortKey) ?? SORT_OPTIONS[0]

  /** The rail: each field with what it is set to. */
  const rail: { key: Field; label: string; icon: React.ReactNode; value: string; active: boolean }[] = [
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

  const pickField = (f: Field) => {
    setField(f)
    // Keep the pane's scroll at the top for the new editor.
    if (paneRef.current) paneRef.current.scrollTop = 0
  }

  const check = <FiCheck className="ml-auto h-3.5 w-3.5 shrink-0 text-[#1f150c]" aria-hidden="true" />

  return (
    <div ref={ref} className="relative">
      <button
        type="button"
        onClick={toggle}
        aria-expanded={open}
        aria-haspopup="dialog"
        className={`inline-flex items-center gap-1.5 rounded-lg border px-2.5 py-1.5 text-[12px] font-semibold transition ${
          lit ? 'border-[#412d15] bg-[#412d15] text-[#f4eede]' : 'border-[#e3d9c4] bg-white text-[#5a4526] hover:border-[#cdbf9f] hover:bg-[#faf7f0]'
        }`}
      >
        <FiFilter className="h-3.5 w-3.5" />
        Filters
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
          style={{ left: shift, width: `min(${POPOVER_W}px, calc(100vw - 2rem))` }}
          className="bulk-pop-in absolute z-30 mt-1.5 overflow-hidden rounded-xl border border-[#e3d9c4] bg-white text-[#1f150c] shadow-[0_18px_44px_rgba(31,21,12,0.16)]"
        >
          <div className="flex">
            {/* ── The rail: what you can filter by, and what each is set to ── */}
            <nav aria-label="Filter by" className="w-[8.25rem] shrink-0 border-r border-[#f2ecdf] bg-[#fcfaf5] p-1.5 sm:w-[11.5rem]">
              {rail.map((r) => {
                const on = field === r.key
                return (
                  <button
                    key={r.key}
                    type="button"
                    onClick={() => pickField(r.key)}
                    aria-current={on ? 'true' : undefined}
                    className={`flex w-full items-center gap-2 rounded-lg px-2 py-1.5 text-left transition ${
                      on ? 'bg-white shadow-sm ring-1 ring-[#e3d9c4]' : 'hover:bg-[#f4eede]/60'
                    }`}
                  >
                    <span className={`inline-flex h-6 w-6 shrink-0 items-center justify-center rounded-md ${r.active ? 'bg-[#1f150c] text-[#f4eede]' : 'bg-[#f4eede] text-[#6b5c42]'}`} aria-hidden="true">
                      {r.icon}
                    </span>
                    <span className="min-w-0 flex-1">
                      <span className="block text-[11.5px] font-semibold leading-tight text-[#1f150c]">{r.label}</span>
                      <span className={`hidden truncate text-[10.5px] leading-tight sm:block ${r.active ? 'font-semibold text-[#412d15]' : 'text-[#a1906d]'}`}>{r.value}</span>
                    </span>
                    <FiChevronRight className={`h-3 w-3 shrink-0 ${on ? 'text-[#412d15]' : 'text-[#dcd4c4]'}`} aria-hidden="true" />
                  </button>
                )
              })}
            </nav>

            {/* ── The editor for the chosen field ── */}
            <div ref={paneRef} className="max-h-[21rem] min-h-[19rem] flex-1 overflow-y-auto p-2.5">
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
                          <span className="rounded-full bg-white px-1.5 py-0.5 text-[10px] font-bold tabular-nums text-[#6b5c42] ring-1 ring-[#e3d9c4]">{n}</span>
                          {on ? check : null}
                        </button>
                      </li>
                    )
                  })}
                </ul>
              ) : null}

              {field === 'created' ? (
                <div className="space-y-3">
                  <div>
                    <p className="mb-1.5 text-[10.5px] font-semibold uppercase tracking-[0.08em] text-[#b6a684]">Quick pick</p>
                    <div className="flex flex-wrap gap-1.5">
                      <button type="button" onClick={() => { setDateFrom(''); setDateTo('') }} className={`${CHIP_BTN} ${!dateFilterActive ? CHIP_ON : CHIP_OFF}`}>Any time</button>
                      {DATE_PRESETS.map((p) => {
                        const on = dateFrom === p.from() && dateTo === p.to()
                        return (
                          <button key={p.label} type="button" onClick={() => { setDateFrom(p.from()); setDateTo(p.to()) }} className={`${CHIP_BTN} ${on ? CHIP_ON : CHIP_OFF}`}>
                            {p.label}
                          </button>
                        )
                      })}
                    </div>
                  </div>
                  <div>
                    <p className="mb-1.5 text-[10.5px] font-semibold uppercase tracking-[0.08em] text-[#b6a684]">Custom range</p>
                    <div className="grid grid-cols-2 gap-2">
                      <label className="block">
                        <span className="mb-1 block text-[11px] text-[#6b5c42]">From</span>
                        <input type="date" value={dateFrom} max={dateTo || undefined} onChange={(e) => setDateFrom(e.target.value)} aria-label="Created from" className={FIELD_INPUT} />
                      </label>
                      <label className="block">
                        <span className="mb-1 block text-[11px] text-[#6b5c42]">To</span>
                        <input type="date" value={dateTo} min={dateFrom || undefined} onChange={(e) => setDateTo(e.target.value)} aria-label="Created to" className={FIELD_INPUT} />
                      </label>
                    </div>
                    <p className="mt-2 text-[11px] text-[#a1906d]">
                      {dateFilterActive ? `Showing imports created ${rangeLabel(dateFrom, dateTo)}.` : 'Leave both empty for any date.'}
                    </p>
                  </div>
                </div>
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
                        {!createdBy ? check : null}
                      </button>
                    </li>
                    {visibleCreators.map((c) => {
                      const on = createdBy === c
                      return (
                        <li key={c}>
                          <button type="button" onClick={() => setCreatedBy(c)} aria-pressed={on} className={`${OPTION} ${on ? OPTION_ON : ''}`}>
                            <span className="inline-flex h-6 w-6 shrink-0 items-center justify-center rounded-full bg-[#412d15] text-[10px] font-bold uppercase text-[#f4eede]" aria-hidden="true">{c.slice(0, 1)}</span>
                            <span className="truncate text-[12.5px] font-semibold">{c}</span>
                            {on ? check : null}
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
                          {on ? check : null}
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
                            {on ? check : null}
                          </button>
                        </li>
                      )
                    })}
                  </ul>
                  <div>
                    <p className="mb-1.5 text-[10.5px] font-semibold uppercase tracking-[0.08em] text-[#b6a684]">Direction</p>
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
            </div>
          </div>

          {/* ── Footer: what it comes to, clear, done ── */}
          <div className="flex items-center justify-between gap-2 border-t border-[#f2ecdf] bg-[#fcfaf5] px-3 py-2 text-[11.5px] text-[#6b5c42]">
            <span aria-live="polite">
              <span className="font-semibold text-[#1f150c]">{filteredCount}</span> of {totalCount} {totalCount === 1 ? 'import' : 'imports'} shown
            </span>
            <span className="flex items-center gap-1.5">
              {anyFilterActive ? (
                <button type="button" onClick={clearFilters} className="inline-flex items-center gap-1 rounded-lg px-2 py-1 text-[11.5px] font-semibold text-[#6b5c42] transition hover:bg-rose-50 hover:text-rose-700">
                  <FiX className="h-3.5 w-3.5" /> Clear all
                </button>
              ) : null}
              <button type="button" onClick={() => setOpen(false)} className="rounded-lg bg-[#1f150c] px-3 py-1 text-[11.5px] font-semibold text-[#f4eede] shadow-sm transition hover:bg-[#412d15]">
                Done
              </button>
            </span>
          </div>
        </div>
      ) : null}
    </div>
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
  const chips: { key: string; label: string; value: string; clear: () => void }[] = []
  if (statusFilter !== 'ALL') chips.push({ key: 'status', label: 'Status', value: BATCH_STATUS[statusFilter].label, clear: () => setStatusFilter('ALL') })
  if (dateFrom || dateTo) chips.push({ key: 'created', label: 'Created', value: rangeLabel(dateFrom, dateTo), clear: () => { setDateFrom(''); setDateTo('') } })
  if (createdBy) chips.push({ key: 'by', label: 'By', value: createdBy, clear: () => setCreatedBy('') })
  if (batchPresence !== 'ANY') chips.push({ key: 'batch', label: 'Batch', value: BATCH_OPTIONS.find((b) => b.key === batchPresence)?.label ?? batchPresence, clear: () => setBatchPresence('ANY') })
  if (minSaved) chips.push({ key: 'rows', label: 'Rows', value: `${minSaved}+`, clear: () => setMinSaved('') })
  if (chips.length === 0) return null
  return (
    <div data-testid="bulk-filter-chips" className="bulk-fade-in mt-2 flex flex-wrap items-center gap-1.5">
      {chips.map((c) => (
        <span key={c.key} className="inline-flex items-center gap-1 rounded-full border border-[#e3d9c4] bg-[#fcfaf5] py-0.5 pl-2.5 pr-1 text-[11.5px] text-[#5a4526]">
          <span className="text-[#a1906d]">{c.label}</span>
          <span className="font-semibold text-[#1f150c]">{c.value}</span>
          <button type="button" onClick={c.clear} aria-label={`Remove ${c.label} filter`} className="ml-0.5 rounded-full p-0.5 text-[#a1906d] transition hover:bg-[#f0e9d8] hover:text-rose-700">
            <FiX className="h-3 w-3" />
          </button>
        </span>
      ))}
      {chips.length > 1 ? (
        <button type="button" onClick={clearFilters} className="text-[11.5px] font-semibold text-[#6b5c42] underline-offset-2 hover:text-rose-700 hover:underline">
          Clear all
        </button>
      ) : null}
    </div>
  )
}
