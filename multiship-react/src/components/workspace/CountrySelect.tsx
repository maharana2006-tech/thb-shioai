import { useMemo, useState } from 'react'
import { FiSearch } from 'react-icons/fi'
import { COUNTRIES, countryName } from '../../utils/countries'

/**
 * Searchable single-country dropdown. Displays "Name (CODE)", stores the ISO
 * alpha-2 code. Backed by the shared COUNTRIES list so every form stays in sync.
 */
export default function CountrySelect({
  value,
  onChange,
  inputClassName = 'w-full rounded-lg border border-slate-200 bg-white px-3 py-2 text-[13px] text-slate-900 outline-none transition focus:border-slate-400',
  placeholder = 'Search country…',
}: {
  value?: string | null
  onChange: (code: string) => void
  inputClassName?: string
  placeholder?: string
}) {
  const [open, setOpen] = useState(false)
  const [query, setQuery] = useState('')

  const code = (value || '').toUpperCase()
  const selectedLabel = code ? `${countryName(code)} (${code})` : ''
  const q = query.trim().toLowerCase()
  const matches = useMemo(
    () =>
      q
        ? COUNTRIES.filter((c) => c.name.toLowerCase().includes(q) || c.code.toLowerCase().includes(q))
        : COUNTRIES,
    [q],
  )

  return (
    <div className="relative">
      <FiSearch className="pointer-events-none absolute left-2.5 top-1/2 h-3.5 w-3.5 -translate-y-1/2 text-slate-300" />
      <input
        className={`${inputClassName} pl-8`}
        value={open ? query : selectedLabel}
        placeholder={placeholder}
        onFocus={() => {
          setOpen(true)
          setQuery('')
        }}
        onChange={(e) => {
          setOpen(true)
          setQuery(e.target.value)
        }}
        onBlur={() => setTimeout(() => setOpen(false), 120)}
        autoComplete="off"
      />
      {open ? (
        <ul className="absolute z-[60] mt-1 max-h-56 w-full overflow-auto rounded-xl border border-slate-200 bg-white py-1 shadow-lg">
          {matches.length === 0 ? (
            <li className="px-3 py-2 text-[12px] text-slate-400">No match</li>
          ) : (
            matches.map((c) => (
              <li key={c.code}>
                <button
                  type="button"
                  onMouseDown={(e) => {
                    e.preventDefault()
                    onChange(c.code)
                    setOpen(false)
                    setQuery('')
                  }}
                  className={`flex w-full items-center justify-between gap-2 px-3 py-1.5 text-left text-[12.5px] transition hover:bg-slate-50 ${
                    c.code === code ? 'font-semibold text-slate-900' : 'text-slate-700'
                  }`}
                >
                  <span className="truncate">{c.name}</span>
                  <span className="shrink-0 font-mono text-[11px] text-slate-400">{c.code}</span>
                </button>
              </li>
            ))
          )}
        </ul>
      ) : null}
    </div>
  )
}
