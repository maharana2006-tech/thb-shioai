/**
 * Sprint 48 — Vitest global setup.
 *
 * Registers jest-dom's DOM matchers (toBeInTheDocument, toHaveTextContent,
 * etc.) so Testing Library assertions read naturally in tests.
 *
 * <p>jsdom polyfills: jsdom does not implement layout, so
 * {@code getBoundingClientRect()} returns zeros and
 * {@code ResizeObserver} is missing. Both are load-bearing for
 * {@code @tanstack/react-virtual}, which computes visible-row count from
 * the scroll container's height. We stub a fixed viewport (800×600) so
 * virtualization tests can assert against realistic mount counts.
 */
import '@testing-library/jest-dom/vitest'

if (typeof window !== 'undefined') {
  // ResizeObserver — react-virtual observes the scroll container.
  if (!(window as unknown as { ResizeObserver?: unknown }).ResizeObserver) {
    ;(window as unknown as { ResizeObserver: unknown }).ResizeObserver = class {
      observe() {}
      unobserve() {}
      disconnect() {}
    }
  }

  // IntersectionObserver — used by some libs; harmless stub.
  if (!(window as unknown as { IntersectionObserver?: unknown }).IntersectionObserver) {
    ;(window as unknown as { IntersectionObserver: unknown }).IntersectionObserver = class {
      observe() {}
      unobserve() {}
      disconnect() {}
      takeRecords() { return [] }
    }
  }

  // Give the scroll container a viewport so react-virtual computes a
  // non-zero visible range.
  Object.defineProperty(HTMLElement.prototype, 'offsetHeight', {
    configurable: true,
    get() { return 600 },
  })
  Object.defineProperty(HTMLElement.prototype, 'offsetWidth', {
    configurable: true,
    get() { return 800 },
  })
  Object.defineProperty(HTMLElement.prototype, 'clientHeight', {
    configurable: true,
    get() { return 600 },
  })
  Object.defineProperty(HTMLElement.prototype, 'clientWidth', {
    configurable: true,
    get() { return 800 },
  })

  const originalGetBoundingClientRect = HTMLElement.prototype.getBoundingClientRect
  HTMLElement.prototype.getBoundingClientRect = function () {
    const rect = originalGetBoundingClientRect.call(this)
    return {
      ...rect,
      width: (rect.width || 800),
      height: (rect.height || 72),  // estimateSize default for CommodityRow
      top: rect.top || 0,
      left: rect.left || 0,
      bottom: rect.bottom || 72,
      right: rect.right || 800,
      x: rect.x || 0,
      y: rect.y || 0,
      toJSON: () => ({}),
    } as DOMRect
  }
}
