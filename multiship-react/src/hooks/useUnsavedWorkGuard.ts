import { useEffect } from 'react'
import { notify } from '../utils/notify'
import { registerUnsavedWorkGuard } from '../utils/unsavedWorkGuard'

const SENTINEL = { unsavedWorkSentinel: true }

/**
 * One guard for every page that owns unsaved work (BUG-063 / 073 / 120 / 140).
 *
 * While {@code dirty}: reload and tab-close get the browser prompt, in-app
 * navigation (sidebar, settings nav, global search) asks through
 * {@link registerUnsavedWorkGuard}, and the browser Back button asks too.
 *
 * BrowserRouter has no {@code useBlocker}, so Back is handled with a
 * sentinel history entry on the same URL: Back pops the sentinel (the page
 * stays mounted) and we get to ask before going back for real.
 * ponytail: after a confirmed in-app leave the sentinel stays below the new
 * entry, so Back then takes one extra press; swap for useBlocker if the app
 * ever moves to a data router.
 */
export function useUnsavedWorkGuard(dirty: boolean, message: string, title: string) {
  useEffect(() => {
    if (!dirty) return
    const warn = (e: BeforeUnloadEvent) => { e.preventDefault(); e.returnValue = '' }
    window.addEventListener('beforeunload', warn)

    const confirmLeave = () => notify.confirm(message, { title, confirmLabel: 'Leave', cancelLabel: 'Stay' })
    registerUnsavedWorkGuard(confirmLeave)

    const url = window.location.href
    window.history.pushState(SENTINEL, '', url)
    let leaving = false
    const onPop = async () => {
      if (leaving) return
      if (await confirmLeave()) {
        leaving = true
        window.history.back()
      } else {
        window.history.pushState(SENTINEL, '', url)
      }
    }
    window.addEventListener('popstate', onPop)

    return () => {
      window.removeEventListener('beforeunload', warn)
      window.removeEventListener('popstate', onPop)
      registerUnsavedWorkGuard(null)
      // Clean after a save while still on the page: drop the sentinel so
      // Back goes where it did before the form became dirty.
      if (!leaving && (window.history.state as typeof SENTINEL | null)?.unsavedWorkSentinel) {
        window.history.back()
      }
    }
  }, [dirty, message, title])
}
