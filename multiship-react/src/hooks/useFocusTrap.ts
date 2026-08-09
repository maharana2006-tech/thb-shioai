import { useEffect, useRef, type RefObject } from 'react'

/**
 * Sprint 49 Tier 4 Fix 6 — modal focus trap + focus restoration.
 *
 * <p>Prior modals used {@code role="dialog" aria-modal="true"} but had
 * no focus management. Tab could escape into the underlying page (the
 * sidebar / topbar caught the focus); Esc close didn't return focus
 * to the button that opened the modal. Keyboard-only operators lost
 * context on every open/close cycle.
 *
 * <p>Usage:
 * <pre>
 * const containerRef = useRef&lt;HTMLDivElement&gt;(null);
 * useFocusTrap(open, containerRef);
 * return open ? &lt;div ref={containerRef} role="dialog"&gt;...&lt;/div&gt; : null;
 * </pre>
 *
 * <p>Restores focus to whatever had it when the modal opened, so
 * closing snaps back to the trigger button naturally.
 */
export function useFocusTrap<T extends HTMLElement>(
  open: boolean,
  containerRef: RefObject<T | null>,
) {
  const triggerRef = useRef<HTMLElement | null>(null)

  useEffect(() => {
    if (!open) return

    // Capture what had focus BEFORE the modal opened so we can restore.
    triggerRef.current = (document.activeElement as HTMLElement | null) ?? null

    // Move focus INTO the modal on open (first focusable, else the
    // container itself if it's tabbable).
    const container = containerRef.current
    if (container) {
      const focusables = focusableChildren(container)
      const target = focusables[0] ?? container
      // requestAnimationFrame lets the DOM paint the dialog before we
      // measure / focus — avoids "cannot focus a hidden element" on
      // portals that reveal via transition.
      requestAnimationFrame(() => target.focus?.())
    }

    const handleKeydown = (e: KeyboardEvent) => {
      if (e.key !== 'Tab') return
      const container = containerRef.current
      if (!container) return
      const focusables = focusableChildren(container)
      if (focusables.length === 0) {
        e.preventDefault()
        return
      }
      const first = focusables[0]
      const last = focusables[focusables.length - 1]
      const active = document.activeElement as HTMLElement | null

      if (e.shiftKey && active === first) {
        e.preventDefault()
        last.focus()
      } else if (!e.shiftKey && active === last) {
        e.preventDefault()
        first.focus()
      }
    }

    document.addEventListener('keydown', handleKeydown)
    return () => {
      document.removeEventListener('keydown', handleKeydown)
      // Restore focus to whatever was focused before the modal opened.
      // Guard for the trigger having been unmounted while the modal was open.
      const trigger = triggerRef.current
      if (trigger && typeof trigger.focus === 'function' && document.body.contains(trigger)) {
        trigger.focus()
      }
    }
    // containerRef is stable across renders — no need to re-run on ref change.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open])
}

const FOCUSABLE_SELECTOR = [
  'a[href]',
  'area[href]',
  'button:not([disabled])',
  'input:not([disabled]):not([type="hidden"])',
  'select:not([disabled])',
  'textarea:not([disabled])',
  '[tabindex]:not([tabindex="-1"])',
].join(',')

function focusableChildren(container: HTMLElement): HTMLElement[] {
  // Deliberately skip the offsetParent visibility check: jsdom doesn't
  // compute layout so it always returns null, and real browsers rarely
  // hide focusables inside an open modal. `inert` + `hidden` cover the
  // legitimate cases without depending on the layout engine.
  return Array.from(container.querySelectorAll<HTMLElement>(FOCUSABLE_SELECTOR))
    .filter((el) => !el.hasAttribute('inert') && !el.hasAttribute('hidden'))
}
