import { useEffect } from 'react'

/**
 * BUG-005 / BUG-025 — the toast stack sits bottom-right (z-80, above modals).
 * A dialog's controls are its X (top-right) and its footer buttons (bottom
 * edge, left and right), so while one is open the stack docks TOP-LEFT —
 * bottom-left still covered a footer button once two toasts stacked.
 */
const VARS: Record<string, string> = {
  '--toast-top': '1rem',
  '--toast-bottom': 'auto',
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
