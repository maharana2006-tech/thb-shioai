import { useCallback, useRef, useState, type ReactNode } from 'react'
import { FiCheck, FiChevronRight, FiFilter, FiX } from 'react-icons/fi'
import { useDismissable } from '../../hooks/useDismissable'

/**
 * A table's filters behind one button: a rail of the things you can filter
 * by on the left (each showing what it is set to), the editor for the chosen
 * one on the right, a result count and Clear / Done in the footer. The caller
 * owns the filter state and renders the editors; {@link FilterChips} shows
 * what is applied under the toolbar.
 */

// ── Shared classes for the editors ──
export const OPTION = 'flex w-full items-center gap-2.5 rounded-lg px-2.5 py-2 text-left transition hover:bg-[#faf7f0]'
export const OPTION_ON = 'bg-[#f4eede]/70 hover:bg-[#f4eede]'
export const CHIP_BTN = 'inline-flex items-center gap-1 rounded-full border px-2.5 py-1 text-[11.5px] font-semibold transition'
export const CHIP_OFF = 'border-[#e3d9c4] bg-white text-[#5a4526] hover:border-[#cdbf9f] hover:bg-[#faf7f0]'
export const CHIP_ON = 'border-[#1f150c] bg-[#1f150c] text-[#f4eede]'
export const FIELD_INPUT = 'w-full rounded-lg border border-[#e3d9c4] bg-white px-2.5 py-1.5 text-[12.5px] text-[#1f150c] outline-none transition focus:border-[#412d15] focus:ring-4 focus:ring-[#f0e9d8]'
export const FIELD_LABEL = 'mb-1.5 block text-[10.5px] font-semibold uppercase tracking-[0.08em] text-[#b6a684]'

/** The tick on the chosen option. */
export const Check = () => <FiCheck className="ml-auto h-3.5 w-3.5 shrink-0 text-[#1f150c]" aria-hidden="true" />

/** How many rows an option matches. */
export const CountBadge = ({ n }: { n: number | undefined }) => (
  <span className="rounded-full bg-white px-1.5 py-0.5 text-[10px] font-bold tabular-nums text-[#6b5c42] ring-1 ring-[#e3d9c4]">{n ?? '–'}</span>
)

/** The popover's width when the screen allows it. */
const POPOVER_W = 592

export interface RailItem<F extends string> {
  key: F
  label: string
  icon: ReactNode
  /** What the field is set to, e.g. "Any" or "Last 7 days". */
  value: string
  active: boolean
}

export function FilterPopover<F extends string>({
  rail, initialField, activeCount, shown, total, noun, clearFilters, children,
}: {
  rail: RailItem<F>[]
  initialField: F
  /** Filters set in this popover (the badge on the button). */
  activeCount: number
  /** Rows matching the filters, and rows in all. */
  shown: number
  total: number | undefined
  /** "imports" → "3 of 3 imports shown". */
  noun?: string
  clearFilters: () => void
  /** The editor for the chosen field. */
  children: (field: F) => ReactNode
}) {
  const [open, setOpen] = useState(false)
  const [field, setField] = useState<F>(initialField)
  // Where the popover sits: from the button's left edge, pulled back just enough to stay on screen.
  const [shift, setShift] = useState(0)
  const ref = useDismissable(open, useCallback(() => setOpen(false), []))
  const paneRef = useRef<HTMLDivElement>(null)
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
  const pickField = (f: F) => {
    setField(f)
    // Keep the pane's scroll at the top for the new editor.
    if (paneRef.current) paneRef.current.scrollTop = 0
  }
  const lit = open || activeCount > 0

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
            <div ref={paneRef} className="max-h-[21rem] min-h-[15rem] flex-1 overflow-y-auto p-2.5">
              {children(field)}
            </div>
          </div>

          {/* ── Footer: what it comes to, clear, done ── */}
          <div className="flex items-center justify-between gap-2 border-t border-[#f2ecdf] bg-[#fcfaf5] px-3 py-2 text-[11.5px] text-[#6b5c42]">
            <span aria-live="polite">
              <span className="font-semibold text-[#1f150c]">{shown}</span> of {total ?? '–'} {noun ? `${noun} ` : ''}shown
            </span>
            <span className="flex items-center gap-1.5">
              {activeCount > 0 ? (
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

export interface FilterChip { key: string; label: string; value: string; clear: () => void }

/** The applied filters as chips under the toolbar — each one removable. Nothing when none apply. */
export function FilterChips({ chips, clearFilters, testId }: { chips: FilterChip[]; clearFilters: () => void; testId?: string }) {
  if (chips.length === 0) return null
  return (
    <div data-testid={testId} className="bulk-fade-in mt-2 flex flex-wrap items-center gap-1.5">
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
