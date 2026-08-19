import { useEffect, type RefObject } from 'react'
import { useFocusTrap } from './useFocusTrap'

/**
 * A11y audit — the standard modal boilerplate in one call: focus trap
 * (Tab stays inside the dialog) + Escape-to-close + focus restoration
 * (delegated to {@link useFocusTrap}).
 *
 * <p>Before this hook every modal wired {@code useFocusTrap} plus its
 * own {@code useEffect} for the Escape key. Consolidated so a new
 * modal only has to opt into keyboard a11y once.
 *
 * <p>Usage:
 * <pre>
 * const dialogRef = useRef&lt;HTMLDivElement&gt;(null);
 * useModalDismiss(true, dialogRef, onClose);
 * return &lt;div ref={dialogRef} role="dialog" aria-modal="true"&gt;...&lt;/div&gt;;
 * </pre>
 */
export function useModalDismiss<T extends HTMLElement>(
  open: boolean,
  containerRef: RefObject<T | null>,
  onClose: () => void,
) {
  useFocusTrap(open, containerRef)

  useEffect(() => {
    if (!open) return
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [open, onClose])
}
