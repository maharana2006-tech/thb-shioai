import { FiInfo } from 'react-icons/fi'

export interface IssueItem {
  /** Short uppercase chip: a field name, 'carrier', 'row', 'note'… */
  tag: string
  text: string
  /** 'warn' renders amber; default rose. */
  tone?: 'error' | 'warn'
}

/**
 * ⓘ badge that reveals a list of issues in a styled tooltip on hover or
 * keyboard focus. Shared by the WMS batch grid and the Orders list so a
 * problem reads the same everywhere. `side` flips which way the tooltip
 * opens so it never leaves the viewport: side="left" means the icon sits
 * at the LEFT edge and opens rightward; side="right" the opposite.
 */
export default function IssuesInfoIcon({
  side,
  ariaLabel,
  items,
  className = '',
}: {
  side: 'left' | 'right'
  ariaLabel: string
  items: IssueItem[]
  className?: string
}) {
  if (!items.length) return null
  const hasErrors = items.some((i) => i.tone !== 'warn')
  return (
    <span className={`group relative inline-flex align-middle ${className}`}>
      <button
        type="button"
        tabIndex={0}
        aria-label={ariaLabel}
        className={`inline-flex h-[18px] w-[18px] items-center justify-center rounded-full ring-1 transition ${
          hasErrors
            ? 'bg-rose-100 text-rose-700 ring-rose-300 hover:bg-rose-200'
            : 'bg-amber-100 text-amber-700 ring-amber-300 hover:bg-amber-200'
        }`}
      >
        <FiInfo className="h-3 w-3" />
      </button>
      <span
        className={`pointer-events-none absolute top-1/2 z-30 hidden w-80 -translate-y-1/2 rounded-xl border border-[#e3d9c4] bg-white p-2.5 shadow-xl group-hover:block group-focus-within:block ${
          side === 'left' ? 'left-full ml-2' : 'right-full mr-2'
        }`}
      >
        <span className="block space-y-1">
          {items.map((it, i) => (
            <span
              key={i}
              className={`flex items-start gap-1.5 text-left text-[10px] leading-snug ${
                it.tone === 'warn' ? 'text-amber-700' : 'text-rose-700'
              }`}
            >
              <span
                className={`mt-[1px] shrink-0 rounded px-1 font-mono text-[8.5px] font-bold uppercase tracking-wide ring-1 ${
                  it.tone === 'warn'
                    ? 'bg-amber-100 text-amber-800 ring-amber-200'
                    : 'bg-rose-100 text-rose-800 ring-rose-200'
                }`}
              >
                {it.tag}
              </span>
              <span className="min-w-0 break-words">{it.text}</span>
            </span>
          ))}
        </span>
      </span>
    </span>
  )
}
