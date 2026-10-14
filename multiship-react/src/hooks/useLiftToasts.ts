import { useEffect } from 'react'

/**
 * BUG-005 / BUG-025 — the toast stack sits bottom-right (z-80, above modals).
 * The dialogs' action buttons are at the top-right (X) and bottom-right (footer),
 * so while a dialog is open we dock toasts to the BOTTOM-LEFT via the shared vars
 * NotifyHost reads, clear of every button, then restore on close.
 */
const VARS: Record<string, string> = {
  '--toast-left': '1rem',
  '--toast-right': 'auto',
  '--toast-align': 'flex-start',
}

export function useLiftToasts(active = true): void {
  useEffect(() => {
    if (!active) return
    const el = document.documentElement
    const prev = Object.fromEntries(
      Object.keys(VARS).map((k) => [k, el.style.getPropertyValue(k)]),
    )
    for (const [k, v] of Object.entries(VARS)) el.style.setProperty(k, v)
    return () => {
      for (const k of Object.keys(VARS)) {
        if (prev[k]) el.style.setProperty(k, prev[k])
        else el.style.removeProperty(k)
      }
    }
  }, [active])
}
