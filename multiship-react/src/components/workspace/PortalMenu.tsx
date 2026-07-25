import { useEffect, useLayoutEffect, useRef, useState, type ReactNode } from 'react'
import { createPortal } from 'react-dom'

/**
 * Popover portaled to `document.body` so it escapes any `overflow: auto`
 * ancestor (AdvancedDataTable's scroll wrapper clips absolutely-positioned
 * menus rendered inside a row cell — that's the whole reason this exists).
 *
 * Positioning is anchor-based: right-aligned to the anchor, opened downward
 * unless there isn't room, in which case it flips upward. Closes on
 * click-outside, Escape, or when the anchor scrolls away.
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

  useLayoutEffect(() => {
    if (!open) {
      setPos(null)
      return
    }
    const compute = () => {
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
    }
    compute()
    const raf = requestAnimationFrame(compute)
    return () => cancelAnimationFrame(raf)
  }, [open, anchorRef, width])

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
    const onScrollOrResize = () => onClose()
    document.addEventListener('mousedown', onDocPointer)
    document.addEventListener('keydown', onKey)
    window.addEventListener('scroll', onScrollOrResize, true)
    window.addEventListener('resize', onScrollOrResize)
    return () => {
      document.removeEventListener('mousedown', onDocPointer)
      document.removeEventListener('keydown', onKey)
      window.removeEventListener('scroll', onScrollOrResize, true)
      window.removeEventListener('resize', onScrollOrResize)
    }
  }, [open, anchorRef, onClose])

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
        zIndex: 60,
      }}
      className="overflow-hidden rounded-xl border border-slate-200 bg-white text-left shadow-lg"
    >
      {children}
    </div>,
    document.body,
  )
}
