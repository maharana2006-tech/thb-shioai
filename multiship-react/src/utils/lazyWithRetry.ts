import { lazy, type ComponentType } from 'react'

const RELOAD_FLAG = 'ms:chunk-reload'

/**
 * Drop-in replacement for React.lazy that survives a redeploy. A new build
 * renames the page chunks, so a tab left open on the old build 404s when it
 * tries to load a page on demand — the click then does nothing. When the
 * dynamic import fails, reload once to pick up the current bundle instead of
 * leaving the UI stuck. A sessionStorage flag prevents a reload loop when the
 * failure is real (e.g. the chunk is genuinely broken, or offline).
 */
// eslint-disable-next-line @typescript-eslint/no-explicit-any -- lazy accepts any component shape
export function lazyWithRetry<T extends ComponentType<any>>(factory: () => Promise<{ default: T }>) {
  return lazy(async () => {
    try {
      const mod = await factory()
      try { sessionStorage.removeItem(RELOAD_FLAG) } catch { /* storage blocked */ }
      return mod
    } catch (err) {
      try {
        if (!sessionStorage.getItem(RELOAD_FLAG)) {
          sessionStorage.setItem(RELOAD_FLAG, '1')
          window.location.reload()
          // Never resolve — the reload takes over before React renders anything.
          return await new Promise<{ default: T }>(() => {})
        }
      } catch { /* storage blocked — fall through and rethrow for the error boundary */ }
      throw err
    }
  })
}
