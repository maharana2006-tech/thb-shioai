import { useEffect, useMemo, useRef, useState } from 'react'
import { hsCodeService, type HsCodeEntry } from '../../api/hsCodeService'
import { checkHsCode, hsCodeHint } from '../../utils/hsCode'

/**
 * Combobox for HS code entry. Wraps a plain text input with an async
 * autocomplete backed by {@code /customs/hs-codes/search}. Two modes of
 * interaction stay possible:
 *
 * <ul>
 *   <li>Type a description — matching codes drop down; pick one to fill the
 *       code AND (via {@link HsCodeComboboxProps.onDescriptionSuggest}) offer
 *       to backfill an empty description with the directory's copy.</li>
 *   <li>Type a code (digits, dots, spaces, hyphens all fine) — the same
 *       shape check the backend runs highlights the input in amber when
 *       malformed. The dropdown shows prefix matches so operators can
 *       verify they picked the right chapter.</li>
 * </ul>
 *
 * <p>Debounced 200ms so a fast typer doesn't fire a request per keystroke.
 * Dropdown closes on blur (with a small delay so clicking an option still
 * registers) or Escape.
 */
export interface HsCodeComboboxProps {
  value: string
  onChange: (value: string) => void
  /** Fires when a directory entry is picked and the caller wants the
   *  description text alongside the code. Ignored if not provided. */
  onDescriptionSuggest?: (description: string) => void
  placeholder?: string
  /** Extra classNames to compose with the built-in input styles. */
  className?: string
  /** BUG-011 — destination ISO alpha-2 for min-digit auto-padding.
   *  Lookup returns 6-digit HS codes (international root); CA / EU want
   *  8-digit, US wants 10-digit. When set, pick() pads the lookup code
   *  with trailing zeros to the destination's minimum so the operator
   *  isn't told "needs 8 digits (you entered 6)" right after the
   *  dropdown handed them 6 digits. */
  destCountry?: string
}

// Destination min-digit requirement — matches the backend
// IntlShipmentValidator / FE shipmentSchema HS_MIN_DIGITS map.
const HS_MIN_DIGITS_BY_DEST: Record<string, number> = {
  US: 10, CA: 8, MX: 8,
  GB: 8, DE: 8, FR: 8, NL: 8, IT: 8, ES: 8, SE: 8, PL: 8,
  IN: 8, JP: 9, AU: 8, BR: 8,
}

export default function HsCodeCombobox({
  value,
  onChange,
  onDescriptionSuggest,
  placeholder = '6109.10',
  className = '',
  destCountry,
}: HsCodeComboboxProps) {
  const [query, setQuery] = useState(value)
  const [matches, setMatches] = useState<HsCodeEntry[]>([])
  const [open, setOpen] = useState(false)
  const [loading, setLoading] = useState(false)
  // BUG-123 — keyboard navigation. ArrowUp/Down cycles the highlighted
  // match; Enter commits.
  const [activeIndex, setActiveIndex] = useState(-1)
  const wrapperRef = useRef<HTMLDivElement>(null)

  // Keep local query in sync when the parent replaces value externally
  // (e.g. wizard state reset).
  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- sync local input to parent-controlled value on external reset; user typing between renders means we can't just render `value` directly (would clobber unsent keystrokes)
    setQuery(value)
  }, [value])

  const hsStatus = useMemo(() => checkHsCode(value), [value])
  const hsWarn = hsCodeHint(hsStatus)
  const borderClass = hsStatus === 'malformed' ? 'border-amber-400' : 'border-slate-200'

  // Debounced fetch — fires only after 200ms of no keystrokes.
  useEffect(() => {
    if (!open || !query || query.trim().length < 2) {
      // eslint-disable-next-line react-hooks/set-state-in-effect -- clear cached matches when the query gets too short or the dropdown closes; results are async, so no render-time derivation possible
      setMatches([])
      return
    }
    setLoading(true)
    const timer = window.setTimeout(async () => {
      try {
        const results = await hsCodeService.search(query)
        setMatches(results)
        setActiveIndex(results.length ? 0 : -1)
      } catch {
        setMatches([])
        setActiveIndex(-1)
      } finally {
        setLoading(false)
      }
    }, 200)
    return () => window.clearTimeout(timer)
  }, [query, open])

  // Close dropdown on outside click.
  useEffect(() => {
    if (!open) return
    const onDoc = (e: MouseEvent) => {
      if (!wrapperRef.current?.contains(e.target as Node)) setOpen(false)
    }
    document.addEventListener('mousedown', onDoc)
    return () => document.removeEventListener('mousedown', onDoc)
  }, [open])

  const pick = (entry: HsCodeEntry) => {
    // BUG-011 — pad lookup code to destination's minimum digit count.
    const destMin = destCountry ? HS_MIN_DIGITS_BY_DEST[destCountry.toUpperCase()] : undefined
    const digits = entry.code.replace(/\D/g, '')
    const padded = destMin && digits.length < destMin
      ? digits + '0'.repeat(destMin - digits.length)
      : entry.code
    onChange(padded)
    setQuery(padded)
    setOpen(false)
    if (onDescriptionSuggest) onDescriptionSuggest(entry.description)
  }

  return (
    <div ref={wrapperRef} className="relative">
      <input
        className={`w-full rounded-lg border bg-slate-50 px-2.5 py-1.5 text-[12px] ${borderClass} ${className}`}
        placeholder={placeholder}
        value={query}
        onChange={(e) => {
          const next = e.target.value
          setQuery(next)
          onChange(next)
          if (!open) setOpen(true)
        }}
        onFocus={() => setOpen(true)}
        onKeyDown={(e) => {
          if (e.key === 'Escape') { setOpen(false); return }
          // BUG-123 — ArrowDown / ArrowUp cycles matches; Enter commits
          // the highlighted entry so the lookup is keyboard-reachable.
          if (!open || matches.length === 0) return
          if (e.key === 'ArrowDown') {
            e.preventDefault()
            setActiveIndex((i) => Math.min(matches.length - 1, i + 1))
          } else if (e.key === 'ArrowUp') {
            e.preventDefault()
            setActiveIndex((i) => Math.max(0, i - 1))
          } else if (e.key === 'Enter' && activeIndex >= 0) {
            e.preventDefault()
            pick(matches[activeIndex])
          }
        }}
        role="combobox"
        aria-expanded={open}
        aria-autocomplete="list"
        aria-controls="hs-code-listbox"
        aria-activedescendant={activeIndex >= 0 ? `hs-code-opt-${activeIndex}` : undefined}
        autoComplete="off"
      />
      {/* BUG-071 — dropdown used to inherit the input's 123 px cell
          width, wrapping every description to a few letters per line.
          Fixed width (560px, capped at 90vw) so descriptions stay on
          one line regardless of the input's cell. */}
      {open && (matches.length > 0 || loading) ? (
        <div id="hs-code-listbox" role="listbox" className="absolute left-0 top-full z-30 mt-1 max-h-64 w-[560px] max-w-[90vw] overflow-y-auto rounded-xl border border-slate-200 bg-white shadow-[0_16px_40px_rgba(15,23,42,0.15)]">
          {loading && matches.length === 0 ? (
            <div className="px-3 py-2 text-[11px] text-slate-400">Searching…</div>
          ) : (
            matches.map((entry, i) => (
              <button
                key={entry.code}
                id={`hs-code-opt-${i}`}
                role="option"
                aria-selected={i === activeIndex}
                type="button"
                onClick={() => pick(entry)}
                onMouseEnter={() => setActiveIndex(i)}
                onMouseDown={(e) => e.preventDefault()} // keep focus on input
                className={`flex w-full items-start gap-2 border-b border-slate-100 px-3 py-2 text-left text-[11.5px] transition last:border-b-0 ${
                  i === activeIndex ? 'bg-sky-50' : 'hover:bg-slate-50'
                }`}
              >
                <span className="min-w-[70px] font-mono font-semibold text-slate-950">{entry.code}</span>
                <span className="flex-1 text-slate-700">{entry.description}</span>
                {entry.category ? (
                  <span className="shrink-0 rounded-full bg-slate-100 px-2 py-0.5 text-[9.5px] font-semibold uppercase tracking-[0.1em] text-slate-500">
                    {entry.category}
                  </span>
                ) : null}
              </button>
            ))
          )}
        </div>
      ) : null}
      {/* BUG-144 — pre-fix the 'letters not allowed' warning showed
          while the operator was typing a word to search the lookup, so
          the message and the matching list contradicted each other.
          Suppress the warning when the operator is actively using the
          lookup (dropdown open with results or loading). */}
      {hsWarn && !(open && (loading || matches.length > 0)) ? (
        <p className="mt-1 text-[10.5px] leading-4 text-amber-700">
          <span className="font-semibold">HS code · </span>
          {hsWarn}
        </p>
      ) : null}
    </div>
  )
}
