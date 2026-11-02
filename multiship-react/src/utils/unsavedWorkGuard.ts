/**
 * BUG-030 — a dirty NewShipmentPage used to lose work when the operator
 * clicked a sidebar / topbar item: react-router {@code navigate()} fires
 * synchronously and the in-page {@code leaveForm} prompt only runs on
 * the page's own Cancel button. {@code beforeunload} covers hard-reload
 * but not in-app routing.
 *
 * BrowserRouter (not a data router) rules out {@code useBlocker}, so this
 * is a tiny module-level pact instead: a page that owns unsaved work
 * registers an async guard; code that wants to leave calls
 * {@code canLeaveUnsavedWork()} first and respects the boolean. One
 * guard at a time — no stacking needed since the only page using this
 * is the New shipment form.
 */

type Guard = () => Promise<boolean>

let activeGuard: Guard | null = null

export function registerUnsavedWorkGuard(guard: Guard | null): void {
  activeGuard = guard
}

/** Returns true if it's safe to leave (no guard, or the operator confirmed). */
export async function canLeaveUnsavedWork(): Promise<boolean> {
  if (!activeGuard) return true
  try {
    return await activeGuard()
  } catch {
    // A broken guard mustn't trap the user in the page.
    return true
  }
}
