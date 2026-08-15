import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { Provider } from 'react-redux'
import { combineReducers, configureStore } from '@reduxjs/toolkit'
import carrierReducer from '../../store/carrierSlice'
import orderReducer from '../../store/orderSlice'
import Sidebar from './Sidebar'

/**
 * Sprint 52 verification hardening — behaviour audit for the FE-M5
 * responsive drawer (Sprint 51 PR #167). The drawer had no automated
 * coverage; this file locks in the pieces that ARE wired:
 *   · drawer slides in / out driven by the `mobileOpen` prop
 *   · a backdrop is rendered when open, and clicking it fires
 *     `onMobileClose`
 *   · the drawer exposes a `data-mobile-open` hook for E2E tests
 *
 * <p>Sprint 52 follow-up (this PR) — closes the four a11y gaps Agent I
 * flagged:
 *   · role="dialog" + aria-modal + aria-labelledby only when open
 *   · Escape closes the drawer
 *   · body scroll is locked while open, restored on close
 *   · focus trap: Tab from the last focusable wraps to the first
 * Pattern lifted from Sprint 49 Tier 4 Fix 6 (useFocusTrap) and
 * Sprint 51 PR #157 modal a11y hardening (BulkLabelModal et al.).
 */

vi.mock('../../api/authService', () => ({
  authService: {
    logout: vi.fn().mockResolvedValue(undefined),
  },
}))

// useAppSession pulls from a global subscription. Stub the module so
// the sidebar renders with a stable operator identity in tests.
vi.mock('../../hooks/useAppSession', () => ({
  useAppSession: () => ({ username: 'ops.tester', role: 'ADMIN' }),
  clearAuthSession: vi.fn(),
}))

vi.mock('../../utils/notify', () => ({
  notify: {
    success: vi.fn(),
    error: vi.fn(),
    info: vi.fn(),
  },
}))

function renderSidebar(overrides: Partial<Parameters<typeof Sidebar>[0]> = {}) {
  const rootReducer = combineReducers({
    carriers: carrierReducer,
    orders: orderReducer,
  })
  const store = configureStore({
    reducer: rootReducer,
    middleware: (getDefaultMiddleware) =>
      getDefaultMiddleware({ serializableCheck: false }),
  })
  const onTogglePin = vi.fn()
  const onMobileClose = vi.fn()
  const utils = render(
    <Provider store={store}>
      <MemoryRouter initialEntries={['/orders']}>
        <Sidebar
          pinned={false}
          onTogglePin={onTogglePin}
          mobileOpen={false}
          onMobileClose={onMobileClose}
          {...overrides}
        />
      </MemoryRouter>
    </Provider>,
  )
  return { ...utils, onTogglePin, onMobileClose }
}

describe('Sidebar — FE-M5 mobile drawer', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  afterEach(() => {
    // Sprint 52 a11y — the scroll-lock effect sets `body.style.overflow`.
    // Tests that open the drawer and unmount without closing it leave the
    // cleanup pending; force-reset here so state doesn't leak.
    document.body.style.overflow = ''
  })

  it('renders the nav with data-mobile-open="false" when the drawer is closed', () => {
    renderSidebar({ mobileOpen: false })
    const nav = screen.getByRole('navigation', { name: /primary/i })
    expect(nav).toHaveAttribute('data-mobile-open', 'false')
    // -translate-x-full keeps the drawer off-screen on <md when closed.
    expect(nav.className).toMatch(/-translate-x-full/)
    // Sprint 52 a11y — the desktop rail must NOT announce as a modal.
    expect(nav).not.toHaveAttribute('role', 'dialog')
    expect(nav).not.toHaveAttribute('aria-modal')
  })

  it('flips data-mobile-open + translate-x-0 when the drawer opens', () => {
    renderSidebar({ mobileOpen: true })
    // When open the nav takes on role="dialog" — query by that instead.
    const dialog = screen.getByRole('dialog', { name: /navigation/i })
    expect(dialog).toHaveAttribute('data-mobile-open', 'true')
    // translate-x-0 slides the drawer into view on <md.
    expect(dialog.className).toMatch(/translate-x-0/)
  })

  it('renders the click-to-close backdrop only when the drawer is open', () => {
    const { rerender, onMobileClose } = renderSidebar({ mobileOpen: false })
    // No backdrop when closed.
    expect(document.querySelector('div[aria-hidden="true"].fixed.inset-0'))
      .toBeNull()

    rerender(
      <Provider store={configureStore({
        reducer: combineReducers({
          carriers: carrierReducer,
          orders: orderReducer,
        }),
      })}>
        <MemoryRouter initialEntries={['/orders']}>
          <Sidebar
            pinned={false}
            onTogglePin={vi.fn()}
            mobileOpen
            onMobileClose={onMobileClose}
          />
        </MemoryRouter>
      </Provider>,
    )

    const backdrop = document.querySelector('div[aria-hidden="true"].fixed.inset-0') as HTMLElement
    expect(backdrop).not.toBeNull()
    // Backdrop must be hidden on md+ so it can't intercept desktop clicks.
    expect(backdrop.className).toMatch(/md:hidden/)
  })

  it('invokes onMobileClose when the backdrop is clicked', () => {
    const { onMobileClose } = renderSidebar({ mobileOpen: true })
    const backdrop = document.querySelector('div[aria-hidden="true"].fixed.inset-0') as HTMLElement
    expect(backdrop).not.toBeNull()

    // Sidebar has a mount-time useEffect that auto-closes the drawer on
    // route change (activePath dep). With mobileOpen=true, that effect
    // fires on the initial render — take the current count as the
    // baseline and assert the backdrop click drives ONE additional call.
    const baseline = onMobileClose.mock.calls.length
    fireEvent.click(backdrop)
    expect(onMobileClose).toHaveBeenCalledTimes(baseline + 1)
  })

  it('fires onMobileClose on mount when opened (auto-close on route change effect)', () => {
    // The Sidebar has a useEffect keyed on activePath that calls
    // onMobileClose whenever mobileOpen is true — so tapping a nav item
    // (which drives a route change) auto-closes the drawer. Locks the
    // effect wiring in place so a refactor doesn't accidentally drop it.
    const { onMobileClose } = renderSidebar({ mobileOpen: true })
    // Initial mount is treated as a route change → effect fires once.
    expect(onMobileClose).toHaveBeenCalled()
  })

  it('shows nav labels when the mobile drawer is open regardless of pinned state', () => {
    // Rail collapsed on desktop (`pinned=false`) would normally hide labels;
    // opening the mobile drawer must force them visible.
    renderSidebar({ mobileOpen: true, pinned: false })
    // Any nav item's aria-label / title carries the item.label; use a well-
    // known ADMIN nav item — the "Orders" route — as a probe.
    const ordersButton = screen.getByRole('button', { name: /orders/i })
    expect(ordersButton).toBeInTheDocument()
  })
})

describe('Sidebar — FE-M5 mobile drawer a11y (Sprint 52 follow-up)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  afterEach(() => {
    document.body.style.overflow = ''
  })

  it('sets role="dialog", aria-modal, and aria-labelledby to a real heading when open', () => {
    renderSidebar({ mobileOpen: true })
    const dialog = screen.getByRole('dialog', { name: /navigation/i })
    expect(dialog).toHaveAttribute('aria-modal', 'true')
    const labelledBy = dialog.getAttribute('aria-labelledby')
    expect(labelledBy).toBeTruthy()
    // The referenced id MUST resolve to a real node so the accessible
    // name computation succeeds.
    const heading = document.getElementById(labelledBy as string)
    expect(heading).not.toBeNull()
    expect(heading?.textContent).toMatch(/navigation/i)
  })

  it('does NOT expose dialog semantics when the drawer is closed (desktop rail)', () => {
    renderSidebar({ mobileOpen: false })
    expect(screen.queryByRole('dialog')).toBeNull()
  })

  it('moves focus into the drawer when it opens', async () => {
    renderSidebar({ mobileOpen: true })
    // useFocusTrap uses requestAnimationFrame to focus the first
    // focusable; wait one frame.
    await new Promise((r) => requestAnimationFrame(() => r(null)))
    const dialog = screen.getByRole('dialog', { name: /navigation/i })
    // Focus should have landed on some element inside the drawer.
    expect(dialog.contains(document.activeElement)).toBe(true)
    // And that focused element should be one of the drawer's own
    // focusables (a button/link), not the container itself.
    expect(document.activeElement?.tagName).toBe('BUTTON')
  })

  it('closes the drawer when Escape is pressed', () => {
    const { onMobileClose } = renderSidebar({ mobileOpen: true })
    const baseline = onMobileClose.mock.calls.length
    fireEvent.keyDown(document, { key: 'Escape' })
    expect(onMobileClose).toHaveBeenCalledTimes(baseline + 1)
  })

  it('does NOT bind an Escape listener when the drawer is closed', () => {
    const { onMobileClose } = renderSidebar({ mobileOpen: false })
    const baseline = onMobileClose.mock.calls.length
    fireEvent.keyDown(document, { key: 'Escape' })
    expect(onMobileClose).toHaveBeenCalledTimes(baseline)
  })

  it('locks body scroll while the drawer is open and restores on close', () => {
    document.body.style.overflow = 'auto'
    const { rerender, onMobileClose } = renderSidebar({ mobileOpen: true })
    expect(document.body.style.overflow).toBe('hidden')

    // Close the drawer via rerender; cleanup should restore the prior
    // inline overflow value the app had set.
    rerender(
      <Provider store={configureStore({
        reducer: combineReducers({
          carriers: carrierReducer,
          orders: orderReducer,
        }),
      })}>
        <MemoryRouter initialEntries={['/orders']}>
          <Sidebar
            pinned={false}
            onTogglePin={vi.fn()}
            mobileOpen={false}
            onMobileClose={onMobileClose}
          />
        </MemoryRouter>
      </Provider>,
    )
    expect(document.body.style.overflow).toBe('auto')
  })

  it('traps Tab focus: Tab from the last focusable wraps to the first', async () => {
    renderSidebar({ mobileOpen: true })
    const dialog = screen.getByRole('dialog', { name: /navigation/i })

    const FOCUSABLE = 'a[href], button:not([disabled]), input:not([disabled]):not([type="hidden"]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])'
    const focusables = Array.from(
      dialog.querySelectorAll<HTMLElement>(FOCUSABLE),
    ).filter((el) => !el.hasAttribute('inert') && !el.hasAttribute('hidden'))
    expect(focusables.length).toBeGreaterThan(1)

    const first = focusables[0]
    const last = focusables[focusables.length - 1]

    // Simulate the user Tabbing from the last focusable — the trap
    // should preventDefault and cycle focus back to the first.
    last.focus()
    expect(document.activeElement).toBe(last)
    fireEvent.keyDown(document, { key: 'Tab' })
    expect(document.activeElement).toBe(first)
  })
})
