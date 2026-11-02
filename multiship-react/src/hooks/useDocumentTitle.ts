import { useEffect } from 'react'

/**
 * BUG-007 — the browser tab stayed on the Vite boilerplate
 * "multiship-react" title because no page set it. This hook sets
 * document.title to {@code title + " · " + suffix} while the component
 * is mounted and restores the previous title on unmount, so switching
 * pages doesn't leave a stale title.
 *
 * Pass {@code null} / {@code undefined} to skip (useful while data is
 * still loading).
 */
export function useDocumentTitle(title: string | null | undefined, suffix = 'Multiship'): void {
  useEffect(() => {
    if (!title) return
    const prev = document.title
    document.title = `${title} · ${suffix}`
    return () => { document.title = prev }
  }, [title, suffix])
}
