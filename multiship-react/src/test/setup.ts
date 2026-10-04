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
import { configure } from '@testing-library/react'
import { afterEach } from 'vitest'

// Default waitFor / findBy* timeout is 1000ms, which is tight once the
// suite warms up — a single state update + mock-call waitFor racing a
// mount effect under load easily blows past that. 5000ms is comfortable
// and still fails loud if an assertion is truly stuck. Testing Library's
// asyncWrapper also uses this for findBy* queries so the lift is global.
configure({ asyncUtilTimeout: 5000 })

// Pollution guard: clear localStorage + sessionStorage + reset window.history
// after every test regardless of file. Several files write to these and don't
// uniformly reset — e.g. SystemChannelSection.test.tsx has beforeEach-clear
// but no afterEach, leaving its last-run tenant in localStorage for whoever
// runs next. Centralising the teardown here means per-file beforeEach-clears
// still work but no file can leak either way.
if (typeof window !== 'undefined') {
  afterEach(() => {
    try { window.localStorage.clear() } catch { /* private mode */ }
    try { window.sessionStorage.clear() } catch { /* private mode */ }
    // Reset the URL to '/' so BrowserRouter-based renderWithProviders
    // doesn't pick up a prior test's pushState. Only one file currently
    // uses pushState (NewShipmentPage.defaults) and it does reset in a
    // finally, but belt-and-braces is cheap.
    if (window.location.pathname !== '/') {
      window.history.pushState({}, '', '/')
    }
  })
}

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
