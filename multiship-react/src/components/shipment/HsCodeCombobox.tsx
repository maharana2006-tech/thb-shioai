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
}

export default function HsCodeCombobox({
  value,
  onChange,
  onDescriptionSuggest,
  placeholder = '6109.10',
  className = '',
}: HsCodeComboboxProps) {
  const [query, setQuery] = useState(value)
  const [matches, setMatches] = useState<HsCodeEntry[]>([])
  const [open, setOpen] = useState(false)
  const [loading, setLoading] = useState(false)
  const wrapperRef = useRef<HTMLDivElement>(null)

  // Keep local query in sync when the parent replaces value externally
  // (e.g. wizard state reset).
  useEffect(() => {
    setQuery(value)
  }, [value])

  const hsStatus = useMemo(() => checkHsCode(value), [value])
  const hsWarn = hsCodeHint(hsStatus)
  const borderClass = hsStatus === 'malformed' ? 'border-amber-400' : 'border-slate-200'

  // Debounced fetch — fires only after 200ms of no keystrokes.
  useEffect(() => {
    if (!open || !query || query.trim().length < 2) {
      setMatches([])
      return
    }
    setLoading(true)
    const timer = window.setTimeout(async () => {
      try {
        const results = await hsCodeService.search(query)
        setMatches(results)
      } catch {
        setMatches([])
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
    onChange(entry.code)
    setQuery(entry.code)
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
          if (e.key === 'Escape') setOpen(false)
        }}
        autoComplete="off"
      />
      {open && (matches.length > 0 || loading) ? (
        <div className="absolute left-0 right-0 top-full z-30 mt-1 max-h-64 overflow-y-auto rounded-xl border border-slate-200 bg-white shadow-[0_16px_40px_rgba(15,23,42,0.15)]">
          {loading && matches.length === 0 ? (
            <div className="px-3 py-2 text-[11px] text-slate-400">Searching…</div>
          ) : (
            matches.map((entry) => (
              <button
                key={entry.code}
                type="button"
                onClick={() => pick(entry)}
                onMouseDown={(e) => e.preventDefault()} // keep focus on input
                className="flex w-full items-start gap-2 border-b border-slate-100 px-3 py-2 text-left text-[11.5px] transition last:border-b-0 hover:bg-slate-50"
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
      {hsWarn ? (
        <p className="mt-1 text-[10.5px] leading-4 text-amber-700">
          <span className="font-semibold">HS code · </span>
          {hsWarn}
        </p>
      ) : null}
    </div>
  )
}
