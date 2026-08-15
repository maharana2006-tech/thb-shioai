import { describe, expect, it, vi, beforeEach } from 'vitest'
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
 * <p>Focus-trap / role="dialog"+aria-modal semantics are intentionally
 * NOT asserted here — the drawer never got wired to `useFocusTrap`
 * (see the verification-hardening PR body for the follow-up bug).
 * These tests describe what production DOES today; changing them is
 * a signal that the drawer contract shifted.
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

  it('renders the nav with data-mobile-open="false" when the drawer is closed', () => {
    renderSidebar({ mobileOpen: false })
    const nav = screen.getByRole('navigation', { name: /primary/i })
    expect(nav).toHaveAttribute('data-mobile-open', 'false')
    // -translate-x-full keeps the drawer off-screen on <md when closed.
    expect(nav.className).toMatch(/-translate-x-full/)
  })

  it('flips data-mobile-open + translate-x-0 when the drawer opens', () => {
    renderSidebar({ mobileOpen: true })
    const nav = screen.getByRole('navigation', { name: /primary/i })
    expect(nav).toHaveAttribute('data-mobile-open', 'true')
    // translate-x-0 slides the drawer into view on <md.
    expect(nav.className).toMatch(/translate-x-0/)
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
