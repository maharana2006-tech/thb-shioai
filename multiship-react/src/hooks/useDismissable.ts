import { useEffect, useRef } from 'react'

/**
 * Closes a popover on a click outside it or on Escape. Put the returned ref on
 * the element that owns the popover (the trigger and the panel together).
 */
export function useDismissable(open: boolean, onClose: () => void) {
  const ref = useRef<HTMLDivElement | null>(null)
  useEffect(() => {
    if (!open) return
    const clickAway = (e: MouseEvent) => {
      if (!ref.current) return
      if (!ref.current.contains(e.target as Node)) onClose()
    }
    const escape = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose()
    }
    document.addEventListener('mousedown', clickAway)
    document.addEventListener('keydown', escape)
    return () => {
      document.removeEventListener('mousedown', clickAway)
      document.removeEventListener('keydown', escape)
    }
  }, [open, onClose])
  return ref
}
