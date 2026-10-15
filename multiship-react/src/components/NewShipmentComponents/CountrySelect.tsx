/**
 * F5-C — searchable country dropdown used across the new-shipment form
 * (shipper, recipient, notify, sold-to blocks). Extracted from
 * NewShipmentPage. Country data + display-name helper live in
 * {@link ./_countryData.ts} so this file only exports the component
 * (react-refresh rule).
 */
import { useState } from 'react'
import { FiSearch } from 'react-icons/fi'
import { inputCls } from './_shared'
import { COUNTRIES, countryNameFor } from './_countryData'

/** Searchable country dropdown — type to filter, click to select. */
export function CountrySelect({
  value,
  onChange,
}: {
  value: string
  onChange: (code: string) => void
}) {
  const [open, setOpen] = useState(false)
  const [query, setQuery] = useState('')
  // BUG-123: keyboard navigation — ArrowDown/Up moves the highlight,
  // Enter commits, Escape closes. Same shape as HsCodeCombobox.
  const [activeIndex, setActiveIndex] = useState(-1)
  const selectedName = countryNameFor(value)
  const q = query.trim().toLowerCase()
  const matches = q
    ? COUNTRIES.filter(([code, name]) => name.toLowerCase().includes(q) || code.toLowerCase().includes(q))
    : COUNTRIES

  return (
    <div className="relative">
      <div className="relative">
        <FiSearch className="pointer-events-none absolute left-2.5 top-1/2 h-3.5 w-3.5 -translate-y-1/2 text-[#b6a684]" />
        <input
          className={`${inputCls} pl-8`}
          value={open ? query : selectedName ? `${selectedName} (${value})` : ''}
          placeholder="Search country…"
          onFocus={() => {
            setOpen(true)
            setQuery('')
          }}
          onChange={(e) => {
            setOpen(true)
            setQuery(e.target.value)
            setActiveIndex(-1)
          }}
          onKeyDown={(e) => {
            if (e.key === 'Escape') { setOpen(false); return }
            if (!open && (e.key === 'ArrowDown' || e.key === 'Enter')) {
              e.preventDefault(); setOpen(true); return
            }
            if (e.key === 'ArrowDown') {
              e.preventDefault()
              setActiveIndex((i) => (matches.length === 0 ? -1 : (i + 1) % matches.length))
            } else if (e.key === 'ArrowUp') {
              e.preventDefault()
              setActiveIndex((i) => (matches.length === 0 ? -1 : (i <= 0 ? matches.length - 1 : i - 1)))
            } else if (e.key === 'Enter' && activeIndex >= 0 && activeIndex < matches.length) {
              e.preventDefault()
              const [code] = matches[activeIndex]
              onChange(code); setOpen(false); setQuery(''); setActiveIndex(-1)
            }
          }}
          onBlur={() => setTimeout(() => setOpen(false), 120)}
          autoComplete="off"
          role="combobox"
          aria-expanded={open}
          aria-activedescendant={activeIndex >= 0 ? `country-opt-${activeIndex}` : undefined}
        />
      </div>
      {open ? (
        <ul className="absolute z-40 mt-1 max-h-56 w-full overflow-auto rounded-xl border border-[#e3d9c4] bg-white py-1 shadow-lg">
          {matches.length === 0 ? (
            <li className="px-3 py-2 text-[12px] text-[#b6a684]">No match</li>
          ) : (
            matches.map(([code, name], i) => (
              <li key={code} id={`country-opt-${i}`} role="option" aria-selected={i === activeIndex}>
                <button
                  type="button"
                  onMouseDown={(e) => {
                    e.preventDefault()
                    onChange(code)
                    setOpen(false)
                    setQuery('')
                  }}
                  // Also commit on click: a pointer that lands after the
                  // 120 ms blur-close missed mousedown and the field snapped
                  // back to the previous country with no feedback.
                  onClick={() => {
                    onChange(code)
                    setOpen(false)
                    setQuery('')
                  }}
                  className={`flex w-full items-center justify-between px-3 py-1.5 text-left text-[12.5px] hover:bg-[#faf7f0] ${
                    i === activeIndex ? 'bg-[#faf7f0]' : ''
                  } ${code === value ? 'font-semibold text-[#1f150c]' : 'text-[#5a4526]'}`}
                >
                  <span>{name}</span>
                  <span className="font-mono text-[10px] text-[#b6a684]">{code}</span>
                </button>
              </li>
            ))
          )}
        </ul>
      ) : null}
    </div>
  )
}
