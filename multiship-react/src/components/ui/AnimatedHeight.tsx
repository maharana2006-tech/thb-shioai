import { useLayoutEffect, useRef, type ReactNode } from 'react'

/**
 * Grows and shrinks smoothly to fit its content, so whatever sits below it
 * glides instead of jumping when the content is swapped (a tab change, a
 * skeleton replaced by the real list, a table that finishes loading).
 *
 * The content is measured with a ResizeObserver; the wrapper's height follows
 * it with a CSS transition. Overflow is clipped only while a change is
 * animating, so menus and popovers inside aren't cut off the rest of the time.
 * No animation for the first measurement or with prefers-reduced-motion.
 */
export default function AnimatedHeight({
  children,
  duration = 300,
  className = '',
}: {
  children: ReactNode
  duration?: number
  className?: string
}) {
  const outerRef = useRef<HTMLDivElement>(null)
  const innerRef = useRef<HTMLDivElement>(null)

  useLayoutEffect(() => {
    const outer = outerRef.current
    const inner = innerRef.current
    if (!outer || !inner || typeof ResizeObserver === 'undefined') return
    const reduced = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches ?? false
    let first = true
    let settle: number | undefined
    const follow = () => {
      const next = inner.offsetHeight
      if (first || reduced) {
        first = false
        outer.style.height = `${next}px`
        return
      }
      if (Math.abs(outer.offsetHeight - next) < 1) return
      outer.style.overflow = 'hidden'
      outer.style.height = `${next}px`
      window.clearTimeout(settle)
      settle = window.setTimeout(() => { outer.style.overflow = '' }, duration + 50)
    }
    follow()
    const ro = new ResizeObserver(follow)
    ro.observe(inner)
    return () => {
      ro.disconnect()
      window.clearTimeout(settle)
      outer.style.height = ''
      outer.style.overflow = ''
    }
  }, [duration])

  return (
    <div
      ref={outerRef}
      className={`animated-height ${className}`}
      style={{ transition: `height ${duration}ms cubic-bezier(0.2, 0.8, 0.2, 1)` }}
    >
      {/* flow-root keeps children's margins inside, so the measured height is the real one */}
      <div ref={innerRef} style={{ display: 'flow-root' }}>{children}</div>
    </div>
  )
}
