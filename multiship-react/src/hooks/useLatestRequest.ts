import { useCallback, useRef } from 'react'

/**
 * Numbers async requests so only the newest one's answer is used — a slower
 * answer for a view the user already left is dropped instead of overwriting
 * the current one. `begin()` starts a request; `isLatest(id)` says whether
 * it's still the one that counts.
 */
export function useLatestRequest() {
  const counter = useRef(0)
  const begin = useCallback(() => ++counter.current, [])
  const isLatest = useCallback((id: number) => id === counter.current, [])
  return { begin, isLatest }
}
