import { useCallback, useEffect, useLayoutEffect, useRef, useState, type ReactNode } from 'react'
import { createPortal } from 'react-dom'

/**
 * Popover portaled to `document.body` so it escapes any `overflow: auto`
 * ancestor (AdvancedDataTable's scroll wrapper clips absolutely-positioned
 * menus rendered inside a row cell — that's the whole reason this exists).
 *
 * Positioning is anchor-based: right-aligned to the anchor, opened downward
 * unless there isn't room, in which case it flips upward.
 *
 * Scroll behaviour:
 *  - Scrolls that originate INSIDE the menu (e.g. wheel-scrolling its own
 *    max-height overflow list) are ignored — closing the menu on every wheel
 *    tick was making internal lists unusable.
 *  - Scrolls anywhere else re-position the menu so it tracks the anchor
 *    (page scroll, sideways scroll of a table wrapper, etc.) instead of
 *    dismissing. That way the menu stays glued to its anchor.
 *
 * Closes on click-outside, Escape, or window resize.
 */
export default function PortalMenu({
  open,
  anchorRef,
  onClose,
  children,
  width = 176,
}: {
  open: boolean
  anchorRef: React.RefObject<HTMLElement | null>
  onClose: () => void
  children: ReactNode
  /** Menu pixel width; used both for layout and to keep it on-screen. */
  width?: number
}) {
  const menuRef = useRef<HTMLDivElement>(null)
  const [pos, setPos] = useState<{ top: number; left: number } | null>(null)

  // Reposition — anchor rect + menu height decide whether we open down or up,
  // and horizontal clamp keeps the menu inside the viewport. Called both on
  // open (initial mount) and on outside-scroll (anchor tracking).
  const compute = useCallback(() => {
    const anchor = anchorRef.current
    if (!anchor) return
    const rect = anchor.getBoundingClientRect()
    const menuHeight = menuRef.current?.offsetHeight ?? 0
    const spaceBelow = window.innerHeight - rect.bottom
    const openUp = menuHeight > 0 && spaceBelow < menuHeight + 8
    const top = openUp ? rect.top - menuHeight - 4 : rect.bottom + 4
    const rawLeft = rect.right - width
    const left = Math.max(8, Math.min(rawLeft, window.innerWidth - width - 8))
    setPos({ top, left })
  }, [anchorRef, width])

  useLayoutEffect(() => {
    if (!open) {
      setPos(null)
      return
    }
    compute()
    const raf = requestAnimationFrame(compute)
    return () => cancelAnimationFrame(raf)
  }, [open, compute])

  useEffect(() => {
    if (!open) return
    const onDocPointer = (e: MouseEvent) => {
      const target = e.target as Node
      if (menuRef.current?.contains(target)) return
      if (anchorRef.current?.contains(target)) return
      onClose()
    }
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose()
    }
    const onScroll = (e: Event) => {
      const target = e.target as Node | null
      // Scrolling inside the menu itself is normal list interaction — don't
      // close, don't reposition (the menu doesn't move relative to the anchor).
      if (target && menuRef.current?.contains(target)) return
      // Outside scroll (page, table wrapper, ancestor with overflow): follow
      // the anchor rather than dismiss.
      compute()
    }
    const onResize = () => onClose()
    document.addEventListener('mousedown', onDocPointer)
    document.addEventListener('keydown', onKey)
    // Capture phase catches scrolls on any ancestor overflow container without
    // needing to walk the DOM to attach listeners individually.
    window.addEventListener('scroll', onScroll, true)
    window.addEventListener('resize', onResize)
    return () => {
      document.removeEventListener('mousedown', onDocPointer)
      document.removeEventListener('keydown', onKey)
      window.removeEventListener('scroll', onScroll, true)
      window.removeEventListener('resize', onResize)
    }
  }, [open, anchorRef, onClose, compute])

  if (!open) return null

  return createPortal(
    <div
      ref={menuRef}
      role="menu"
      style={{
        position: 'fixed',
        top: pos?.top ?? -9999,
        left: pos?.left ?? -9999,
        width,
        visibility: pos ? 'visible' : 'hidden',
        // Bumped from 60 to keep the menu above table borders / rounded-corner
        // wrappers that were painting a subtle overlap on top of the popover
        // in dense settings pages.
        zIndex: 100,
      }}
      className="overflow-hidden rounded-xl border border-slate-200 bg-white text-left shadow-lg"
    >
      {children}
    </div>,
    document.body,
  )
}
