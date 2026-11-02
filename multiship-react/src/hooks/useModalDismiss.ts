import { useCallback, useEffect, useRef, type RefObject } from 'react'
import { useFocusTrap } from './useFocusTrap'
import { notify } from '../utils/notify'

/**
 * A11y audit — the standard modal boilerplate in one call: focus trap
 * (Tab stays inside the dialog) + Escape-to-close + focus restoration
 * (delegated to {@link useFocusTrap}).
 *
 * <p>BUG-084 — typed values are never dropped silently. The hook watches
 * input/change events inside the dialog; once something was typed, Escape
 * (and any Cancel / backdrop handler wired to the returned
 * {@code requestClose}) asks "Discard what you typed?" first. A dialog with
 * its own dirty check passes {@code confirmDiscard: false}.
 *
 * <p>Usage:
 * <pre>
 * const dialogRef = useRef&lt;HTMLDivElement&gt;(null);
 * const requestClose = useModalDismiss(true, dialogRef, onClose);
 * return &lt;div ref={dialogRef} role="dialog" aria-modal="true"&gt;
 *   …&lt;button onClick={() =&gt; void requestClose()}&gt;Cancel&lt;/button&gt;
 * &lt;/div&gt;;
 * </pre>
 */
export function useModalDismiss<T extends HTMLElement>(
  open: boolean,
  containerRef: RefObject<T | null>,
  onClose: () => void,
  opts: { confirmDiscard?: boolean } = {},
): () => Promise<void> {
  useFocusTrap(open, containerRef)
  const confirmDiscard = opts.confirmDiscard !== false
  const touchedRef = useRef(false)

  useEffect(() => {
    if (!open) return
    touchedRef.current = false
    const el = containerRef.current
    if (!el) return
    const mark = () => { touchedRef.current = true }
    el.addEventListener('input', mark, true)
    el.addEventListener('change', mark, true)
    return () => {
      el.removeEventListener('input', mark, true)
      el.removeEventListener('change', mark, true)
    }
    // containerRef is stable across renders.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open])

  const requestClose = useCallback(async () => {
    if (confirmDiscard && touchedRef.current) {
      const ok = await notify.confirm(
        "Discard what you typed? It hasn't been saved.",
        { title: 'Discard changes', confirmLabel: 'Discard', cancelLabel: 'Keep editing', danger: true },
      )
      if (!ok) return
    }
    onClose()
  }, [confirmDiscard, onClose])

  useEffect(() => {
    if (!open) return
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') void requestClose()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [open, requestClose])

  return requestClose
}
