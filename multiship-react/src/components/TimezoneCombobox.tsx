import { useEffect, useMemo, useRef, useState } from 'react'
import { FiCrosshair } from 'react-icons/fi'

/**
 * Searchable timezone picker backed by `Intl.supportedValuesOf('timeZone')`.
 * ~420 canonical IANA zones (America/New_York, Europe/London, etc.),
 * sorted alphabetically, each with its current UTC offset shown next to
 * the zone name. Includes a "Detect from browser" quick-fill button that
 * reads the operator's local zone via
 * `Intl.DateTimeFormat().resolvedOptions().timeZone`.
 *
 * The component preserves whatever's currently in `value` even if that
 * zone isn't in the runtime list (legacy / deprecated tz names): the
 * input shows the value when the dropdown is closed, so a save doesn't
 * silently wipe an unrecognised zone.
 */
export interface TimezoneComboboxProps {
  value: string
  onChange: (value: string) => void
  className?: string
  placeholder?: string
  id?: string
  ariaInvalid?: boolean
}

interface TimezoneOption {
  id: string
  offset: string
}

// Called once at module load. `Intl.supportedValuesOf` is an ES2022
// API supported in every evergreen browser since ~2022; the try/catch
// fallback keeps the component safe on older engines (input still
// works as a plain text field with the Detect button as the only picker).
const RAW_ZONES: string[] = (() => {
  try {
    // eslint-disable-next-line @typescript-eslint/no-explicit-any -- Intl.supportedValuesOf isn't in older TS lib defs.
    const anyIntl = Intl as any
    if (typeof anyIntl.supportedValuesOf === 'function') {
      return anyIntl.supportedValuesOf('timeZone') as string[]
    }
  } catch { /* fall through */ }
  return []
})()

/** Normalise `Intl.DateTimeFormat`'s "GMT+5:30" / "GMT-5" / "GMT" style
 *  short-offset output to "UTC+05:30" / "UTC-05:00" / "UTC±00:00". Uses
 *  the current date so DST is reflected. */
function computeOffset(tz: string, now: Date): string {
  try {
    const fmt = new Intl.DateTimeFormat('en-US', { timeZone: tz, timeZoneName: 'shortOffset' })
    const parts = fmt.formatToParts(now)
    const raw = parts.find((p) => p.type === 'timeZoneName')?.value ?? ''
    if (raw === 'GMT') return 'UTC±00:00'
    const match = raw.match(/GMT([+-])(\d{1,2})(?::?(\d{2}))?/)
    if (!match) return raw
    const sign = match[1] === '+' ? 1 : -1
    const hh = parseInt(match[2], 10)
    const mm = match[3] ? parseInt(match[3], 10) : 0
    const total = sign * (hh * 60 + mm)
    const absHH = Math.floor(Math.abs(total) / 60).toString().padStart(2, '0')
    const absMM = (Math.abs(total) % 60).toString().padStart(2, '0')
    return `UTC${sign > 0 ? '+' : '-'}${absHH}:${absMM}`
  } catch {
    return ''
  }
}

const DEFAULT_INPUT_CLASS =
  'w-full rounded-xl border border-slate-200 bg-slate-50 px-2.5 py-1.5 text-[12.5px] text-slate-950 outline-none transition focus:border-sky-600 focus:bg-white focus:ring-2 focus:ring-sky-100'

/** Render cap on the dropdown so the popover stays lightweight even
 *  with an unfiltered ~420-entry list. If the filtered set is larger,
 *  a "narrow your search" hint sits at the bottom. */
const MAX_RENDERED = 200

export function TimezoneCombobox({
  value,
  onChange,
  className,
  placeholder,
  id,
  ariaInvalid,
}: TimezoneComboboxProps) {
  const [query, setQuery] = useState('')
  const [open, setOpen] = useState(false)
  const [activeIndex, setActiveIndex] = useState(-1)
  const containerRef = useRef<HTMLDivElement>(null)
  const inputRef = useRef<HTMLInputElement>(null)
  const listRef = useRef<HTMLUListElement>(null)

  const options = useMemo<TimezoneOption[]>(() => {
    const now = new Date()
    return RAW_ZONES
      .map((z) => ({ id: z, offset: computeOffset(z, now) }))
      .sort((a, b) => a.id.localeCompare(b.id))
  }, [])

  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase()
    if (!q) return options
    return options.filter((o) => o.id.toLowerCase().includes(q))
  }, [options, query])

  useEffect(() => {
    if (!open) return
    const onDocMouseDown = (e: MouseEvent) => {
      if (!containerRef.current?.contains(e.target as Node)) setOpen(false)
    }
    document.addEventListener('mousedown', onDocMouseDown)
    return () => document.removeEventListener('mousedown', onDocMouseDown)
  }, [open])

  // Keep the active option in view as arrow navigation moves it.
  useEffect(() => {
    if (activeIndex < 0 || !listRef.current) return
    const el = listRef.current.querySelector<HTMLLIElement>(`[data-idx="${activeIndex}"]`)
    el?.scrollIntoView({ block: 'nearest' })
  }, [activeIndex])

  // Reset active when the filter changes so the top result is always primed.
  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- one-shot on filter change; deriving at render needs extra state to avoid a flicker on the first result.
    setActiveIndex(filtered.length ? 0 : -1)
  }, [filtered])

  const commit = (zone: string) => {
    onChange(zone)
    setQuery('')
    setOpen(false)
    inputRef.current?.blur()
  }

  const detectFromBrowser = () => {
    try {
      const tz = Intl.DateTimeFormat().resolvedOptions().timeZone
      if (tz) commit(tz)
    } catch { /* ignore */ }
  }

  const onKey = (e: React.KeyboardEvent<HTMLInputElement>) => {
    if (e.key === 'Escape') {
      setOpen(false)
      return
    }
    if (e.key === 'ArrowDown') {
      e.preventDefault()
      setOpen(true)
      setActiveIndex((i) => Math.min(i + 1, filtered.length - 1))
      return
    }
    if (e.key === 'ArrowUp') {
      e.preventDefault()
      setActiveIndex((i) => Math.max(i - 1, 0))
      return
    }
    if (e.key === 'Enter') {
      e.preventDefault()
      const opt = filtered[activeIndex]
      if (opt) commit(opt.id)
    }
  }

  // When the dropdown is open we show the query the user is typing; when
  // closed we show the currently-committed value (which may be a legacy
  // zone not in RAW_ZONES — preserved so the save doesn't silently drop it).
  const displayValue = open ? query : value

  const listboxId = id ? `${id}-listbox` : 'tz-listbox'

  return (
    <div ref={containerRef} className="relative">
      <div className="flex items-center gap-1">
        <input
          ref={inputRef}
          id={id}
          role="combobox"
          aria-expanded={open}
          aria-controls={listboxId}
          aria-autocomplete="list"
          aria-invalid={ariaInvalid ? true : undefined}
          value={displayValue}
          onChange={(e) => {
            setQuery(e.target.value)
            setOpen(true)
          }}
          onFocus={() => setOpen(true)}
          onKeyDown={onKey}
          placeholder={placeholder ?? 'Search e.g. New_York'}
          autoComplete="off"
          spellCheck={false}
          className={className ?? DEFAULT_INPUT_CLASS}
        />
        <button
          type="button"
          onClick={detectFromBrowser}
          title="Detect timezone from browser"
          aria-label="Detect timezone from browser"
          className="shrink-0 rounded-xl border border-slate-200 bg-white p-1.5 text-slate-600 transition hover:border-sky-400 hover:bg-sky-50 hover:text-sky-700"
        >
          <FiCrosshair className="h-3.5 w-3.5" />
        </button>
      </div>
      {open && filtered.length > 0 ? (
        <ul
          id={listboxId}
          ref={listRef}
          role="listbox"
          className="absolute z-50 mt-1 max-h-64 w-full overflow-auto rounded-xl border border-slate-200 bg-white shadow-lg"
        >
          {filtered.slice(0, MAX_RENDERED).map((opt, i) => (
            <li
              key={opt.id}
              data-idx={i}
              role="option"
              aria-selected={i === activeIndex}
              onMouseDown={(e) => {
                e.preventDefault()
                commit(opt.id)
              }}
              onMouseEnter={() => setActiveIndex(i)}
              className={`flex cursor-pointer items-center justify-between gap-3 px-2.5 py-1.5 text-[12px] ${
                i === activeIndex ? 'bg-sky-50 text-slate-900' : 'text-slate-700'
              } ${opt.id === value ? 'font-semibold' : ''}`}
            >
              <span className="truncate">{opt.id}</span>
              <span className="shrink-0 font-mono text-[10px] text-slate-500">{opt.offset}</span>
            </li>
          ))}
          {filtered.length > MAX_RENDERED ? (
            <li className="px-2.5 py-1 text-[10px] italic text-slate-500">
              +{filtered.length - MAX_RENDERED} more — narrow your search
            </li>
          ) : null}
        </ul>
      ) : null}
      {open && filtered.length === 0 ? (
        <div className="absolute z-50 mt-1 w-full rounded-xl border border-slate-200 bg-white p-2 text-[11px] text-slate-500 shadow-lg">
          No timezone matches “{query}”.
        </div>
      ) : null}
    </div>
  )
}
