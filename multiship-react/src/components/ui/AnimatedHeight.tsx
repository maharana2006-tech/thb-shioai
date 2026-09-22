import { useLayoutEffect, useRef, type ReactNode } from 'react'

/**
 * Grows and shrinks smoothly to fit its content, so what sits below it glides
 * instead of jumping.
 *
 * Swapping content usually passes through in-between states (old content,
 * a skeleton, a "loading" row, the real list), each a different height —
 * following every one makes the page bounce up and down. So when `holdKey`
 * changes (e.g. a tab change) the height is held where it was until `ready`
 * is true, then it glides once to the final height. A safety timer releases
 * the hold if `ready` never comes.
 *
 * Overflow is clipped only while held or animating, so menus inside aren't
 * cut off otherwise. No animation for the first measurement or with
 * prefers-reduced-motion.
 */
export default function AnimatedHeight({
  children,
  duration = 320,
  holdKey,
  ready = true,
  maxHoldMs = 1500,
  className = '',
}: {
  children: ReactNode
  duration?: number
  /** Changing this starts a hold (keep the current height) until `ready`. */
  holdKey?: string
  /** The new content is final — release the hold and glide to its height. */
  ready?: boolean
  maxHoldMs?: number
  className?: string
}) {
  const outerRef = useRef<HTMLDivElement>(null)
  const innerRef = useRef<HTMLDivElement>(null)
  const state = useRef({ first: true, held: false, settle: 0, release: 0, lastKey: holdKey })

  /** Glide to the content's height (or snap on first paint / reduced motion). */
  const follow = () => {
    const outer = outerRef.current
    const inner = innerRef.current
    const st = state.current
    if (!outer || !inner || st.held) return
    const next = inner.offsetHeight
    const reduced = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches ?? false
    if (st.first || reduced) {
      st.first = false
      outer.style.transition = 'none'
      outer.style.height = `${next}px`
      return
    }
    if (Math.abs(outer.offsetHeight - next) < 1) {
      outer.style.overflow = ''
      return
    }
    outer.style.transition = `height ${duration}ms cubic-bezier(0.22, 0.8, 0.24, 1)`
    outer.style.overflow = 'hidden'
    outer.style.height = `${next}px`
    window.clearTimeout(st.settle)
    st.settle = window.setTimeout(() => { if (!state.current.held) outer.style.overflow = '' }, duration + 60)
  }

  // Follow content size changes.
  useLayoutEffect(() => {
    const inner = innerRef.current
    if (!inner || typeof ResizeObserver === 'undefined') return
    follow()
    const ro = new ResizeObserver(() => follow())
    ro.observe(inner)
    const st = state.current
    return () => {
      ro.disconnect()
      window.clearTimeout(st.settle)
      window.clearTimeout(st.release)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps -- follow reads refs only
  }, [duration])

  // A new holdKey: keep the current height until the new content is ready.
  useLayoutEffect(() => {
    const st = state.current
    const outer = outerRef.current
    if (holdKey === st.lastKey || !outer) return
    st.lastKey = holdKey
    if (st.first || ready) { follow(); return }
    st.held = true
    outer.style.overflow = 'hidden'
    window.clearTimeout(st.release)
    st.release = window.setTimeout(() => { st.held = false; follow() }, maxHoldMs)
    // eslint-disable-next-line react-hooks/exhaustive-deps -- follow reads refs only
  }, [holdKey])

  // Ready: release the hold and glide once to the final height.
  useLayoutEffect(() => {
    const st = state.current
    if (!ready || !st.held) return
    st.held = false
    window.clearTimeout(st.release)
    follow()
    // eslint-disable-next-line react-hooks/exhaustive-deps -- follow reads refs only
  }, [ready])

  return (
    <div ref={outerRef} className={`animated-height ${className}`}>
      {/* flow-root keeps children's margins inside, so the measured height is the real one */}
      <div ref={innerRef} style={{ display: 'flow-root' }}>{children}</div>
    </div>
  )
}
