import { useCallback, useEffect, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { FiAlertCircle, FiInfo } from 'react-icons/fi'

export interface IssueItem {
  /** Short uppercase chip: a field name, 'carrier', 'row', 'note'… */
  tag: string
  text: string
  /** 'warn' renders amber; default rose. */
  tone?: 'error' | 'warn'
}

const PANEL_W = 320
const GAP = 8

/**
 * ⓘ badge that opens a popover listing issues, on hover or keyboard focus.
 *
 * Architecture: the panel is rendered through a PORTAL to document.body with
 * position:fixed, coordinates measured from the icon at open time. A plain
 * absolute-positioned tooltip inside these grids gets mangled — the sticky
 * first column and the horizontal scroll container each create stacking /
 * clipping contexts, so the card bled across cells with no background. The
 * portal escapes every ancestor context; scroll/resize close it so the
 * coordinates can never go stale.
 *
 * `side` is the PREFERRED opening direction ('left' icon opens rightward);
 * if the preferred side lacks room the panel flips, and its vertical position
 * clamps to the viewport.
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
  const btnRef = useRef<HTMLButtonElement>(null)
  const closeTimer = useRef<ReturnType<typeof setTimeout> | null>(null)
  const [pos, setPos] = useState<{ top: number; left: number } | null>(null)

  const open = useCallback(() => {
    if (closeTimer.current) { clearTimeout(closeTimer.current); closeTimer.current = null }
    const r = btnRef.current?.getBoundingClientRect()
    if (!r) return
    // Preferred side: 'left' icon opens rightward, 'right' icon leftward.
    // Flip when the preferred side has no room. Viewport dims can read 0 in
    // headless/hidden contexts — skip clamping there rather than clamping
    // everything to negative coordinates.
    const vw = window.innerWidth || document.documentElement.clientWidth
    const vh = window.innerHeight || document.documentElement.clientHeight
    let left = side === 'left' ? r.right + GAP : r.left - GAP - PANEL_W
    if (vw) {
      if (left + PANEL_W > vw - GAP) left = r.left - GAP - PANEL_W
      if (left < GAP) left = Math.min(r.right + GAP, vw - PANEL_W - GAP)
    }
    const mid = r.top + r.height / 2
    const top = vh ? Math.min(Math.max(mid, 80), vh - 80) : mid
    setPos({ top, left })
  }, [side])

  /** Delayed close so the pointer can travel from icon into the panel. */
  const scheduleClose = useCallback(() => {
    if (closeTimer.current) clearTimeout(closeTimer.current)
    closeTimer.current = setTimeout(() => setPos(null), 120)
  }, [])
  const cancelClose = useCallback(() => {
    if (closeTimer.current) { clearTimeout(closeTimer.current); closeTimer.current = null }
  }, [])

  // Stale-coordinate guard: any scroll or resize closes the popover.
  useEffect(() => {
    if (!pos) return
    const close = () => setPos(null)
    window.addEventListener('scroll', close, true)
    window.addEventListener('resize', close)
    return () => {
      window.removeEventListener('scroll', close, true)
      window.removeEventListener('resize', close)
    }
  }, [pos])
  useEffect(() => () => { if (closeTimer.current) clearTimeout(closeTimer.current) }, [])

  if (!items.length) return null
  const hasErrors = items.some((i) => i.tone !== 'warn')
  const errorCount = items.filter((i) => i.tone !== 'warn').length
  const noteCount = items.length - errorCount

  return (
    <>
      <button
        ref={btnRef}
        type="button"
        aria-label={ariaLabel}
        aria-expanded={!!pos}
        onMouseEnter={open}
        onMouseLeave={scheduleClose}
        onFocus={open}
        onBlur={scheduleClose}
        onKeyDown={(e) => { if (e.key === 'Escape') setPos(null) }}
        className={`inline-flex h-[18px] w-[18px] shrink-0 items-center justify-center rounded-full ring-1 transition ${
          hasErrors
            ? 'bg-rose-100 text-rose-700 ring-rose-300 hover:bg-rose-200'
            : 'bg-amber-100 text-amber-700 ring-amber-300 hover:bg-amber-200'
        } ${className}`}
      >
        <FiInfo className="h-3 w-3" />
      </button>

      {pos
        ? createPortal(
            <div
              role="tooltip"
              onMouseEnter={cancelClose}
              onMouseLeave={scheduleClose}
              style={{ position: 'fixed', top: pos.top, left: pos.left, width: PANEL_W, transform: 'translateY(-50%)' }}
              className="z-[120] overflow-hidden rounded-xl border border-[#e3d9c4] bg-white shadow-[0_16px_48px_-12px_rgba(31,21,12,0.35)]"
            >
              {/* Header — what this panel is, at a glance. */}
              <div className={`flex items-center gap-1.5 border-b px-3 py-1.5 ${
                hasErrors ? 'border-rose-100 bg-rose-50' : 'border-amber-100 bg-amber-50'
              }`}>
                <FiAlertCircle className={`h-3 w-3 ${hasErrors ? 'text-rose-600' : 'text-amber-600'}`} />
                <span className={`text-[10px] font-bold uppercase tracking-[0.1em] ${hasErrors ? 'text-rose-800' : 'text-amber-800'}`}>
                  {errorCount > 0 ? `${errorCount} error${errorCount === 1 ? '' : 's'}` : ''}
                  {errorCount > 0 && noteCount > 0 ? ' · ' : ''}
                  {noteCount > 0 ? `${noteCount} note${noteCount === 1 ? '' : 's'}` : ''}
                </span>
              </div>
              <ul className="max-h-64 space-y-1.5 overflow-y-auto px-3 py-2">
                {items.map((it, i) => (
                  <li
                    key={i}
                    className={`flex items-start gap-1.5 text-[10.5px] leading-snug ${
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
                  </li>
                ))}
              </ul>
            </div>,
            document.body,
          )
        : null}
    </>
  )
}
