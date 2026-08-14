import { useEffect, useState } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { notify } from '../../utils/notify'
import { FiChevronsLeft, FiChevronsRight, FiLogOut } from 'react-icons/fi'
import { authService } from '../../api/authService'
import { clearAuthSession, useAppSession } from '../../hooks/useAppSession'
import { getNavItemsForRole, resolveWorkspaceRouteKey } from '../../routes/workspaceRoutes'
import { normalizeRole } from '../../utils/roles'
import { navIcons } from './navIcons'
import { useAppDispatch } from '../../store/hooks'
import { logout as logoutAction } from '../../store/store'

interface SidebarProps {
  pinned: boolean
  onTogglePin: () => void
  /**
   * Sprint 51 FE-M5 — mobile drawer open state. When true, the sidebar
   * renders full-width from the left as an overlay on <md screens. Above
   * md it's ignored; the sidebar is a static rail as before.
   */
  mobileOpen?: boolean
  /** Sprint 51 FE-M5 — close callback for the mobile drawer. */
  onMobileClose?: () => void
}

// decorative ladder-barcode bar heights (stub echo of the auth waybill)
const BARCODE = [3, 1, 2, 4, 1, 2, 1, 3, 1, 2, 4, 1, 1, 3, 2, 1]

/**
 * Sidebar v3 — "the delivery route". The espresso rail reads as the
 * tear-off stub of the workspace document:
 *  · nav items are STOPS on a vertical dashed route line; the brand cube
 *    at the top is the depot, the active page is a filled cream stop with
 *    a pulsing halo (pairs with the header's tracking-route breadcrumb)
 *  · an inner perforation line runs along the right edge
 *  · a ladder barcode + document number sit above the operator card
 * Behavior is unchanged: 64px rail ↔ 224px pinned, hover-peek, pin state
 * persisted by the parent, print-hidden.
 */
export default function Sidebar({ pinned, onTogglePin, mobileOpen = false, onMobileClose }: SidebarProps) {
  const location = useLocation()
  const navigate = useNavigate()

  // Sprint 51 FE-M5 — auto-close the mobile drawer on route change so
  // tapping a nav item doesn't leave the overlay covering the page.
  const activePath = location.pathname
  useEffect(() => {
    if (mobileOpen) onMobileClose?.()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [activePath])
  const dispatch = useAppDispatch()
  const { username, role } = useAppSession()
  const [loggingOut, setLoggingOut] = useState(false)

  const normalizedRole = normalizeRole(role)
  const navItems = getNavItemsForRole(normalizedRole)
  const activeKey = resolveWorkspaceRouteKey(location.pathname)

  const initials = (username ?? 'MS')
    .split(/[\s._-]+/)
    .filter(Boolean)
    .slice(0, 2)
    .map((part) => part[0]?.toUpperCase() ?? '')
    .join('')

  const handleLogout = async () => {
    if (loggingOut) return
    setLoggingOut(true)
    try {
      await authService.logout()
    } catch (error) {
      console.debug('Logout request failed', error)
    }

    // Sprint 49 Tier 4 Fix 2 — reset ALL Redux user-data slices BEFORE
    // navigating so the next signed-in user never briefly sees the
    // previous operator's orders / carrier status / stats.
    dispatch(logoutAction())
    clearAuthSession()
    notify.success('Logged out')
    navigate('/login')
  }

  // Labels are visible when pinned OR when the mobile drawer is open
  // (the drawer is always the full-width variant on <md), or on
  // hover-peek when collapsed on desktop.
  const labelClass = pinned || mobileOpen
    ? 'opacity-100'
    : 'opacity-0 transition-opacity duration-150 group-hover:opacity-100'

  // Sprint 51 FE-M5 — on <md the sidebar behaves as an off-canvas
  // drawer: hidden by default, slides in from the left when mobileOpen,
  // full 224px wide (the base `w-56` below). On md+ it reverts to the
  // desktop 64/224 rail.
  //
  // These MUST stay `md:`-prefixed. Unprefixed `w-16` and the base
  // `w-56` have identical specificity, so the winner is decided by
  // stylesheet order, not by class order in the attribute — `w-56`
  // always won and the rail stayed 224px wide while the content column
  // moved to a 64px margin, i.e. the sidebar covered the page and the
  // pin toggle looked dead. The `md:` variant sits in a media query
  // that comes after the base utilities, so it wins cleanly; the hover
  // peek beats it in turn on pseudo-class specificity.
  const desktopWidth = pinned
    ? 'md:w-56'
    : 'md:w-16 md:hover:w-56 md:hover:shadow-[12px_0_40px_rgba(10,22,40,0.25)]'
  const mobileTranslate = mobileOpen ? 'translate-x-0' : '-translate-x-full'

  return (
    <>
      {/* Sprint 51 FE-M5 — backdrop for the mobile drawer. Click to close.
          Hidden on md+ where the sidebar is a permanent rail. */}
      {mobileOpen ? (
        <div
          aria-hidden="true"
          onClick={onMobileClose}
          className="fixed inset-0 z-30 bg-slate-900/40 backdrop-blur-sm md:hidden print:hidden"
        />
      ) : null}
      <nav
      aria-label="Primary"
      className={`group fixed inset-y-0 left-0 z-40 flex w-56 flex-col overflow-hidden bg-[#1f150c] transition-transform duration-200 ease-out print:hidden ${mobileTranslate} md:transition-[width] md:duration-200 ${desktopWidth} md:translate-x-0`}
      data-mobile-open={mobileOpen ? 'true' : 'false'}
    >
      {/* inner perforation along the right edge — the stub's tear line */}
      <span
        aria-hidden="true"
        className="pointer-events-none absolute inset-y-3 right-[5px] border-l border-dashed border-[#e1dcc9]/15"
      />

      {/* brand — the depot at the top of the route */}
      <button
        type="button"
        onClick={() => navigate(normalizedRole === 'TENANT' ? '/orders' : '/dashboard')}
        className="flex h-16 shrink-0 items-center gap-3 pl-3.5 pr-3"
      >
        <span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-xl bg-[#e1dcc9] shadow-[0_8px_20px_rgba(0,0,0,0.35)]">
          <svg viewBox="0 0 24 24" fill="none" className="h-5 w-5 text-[#1f150c]" aria-hidden="true">
            <path
              d="M5.5 7.25 12 3.5l6.5 3.75M5.5 7.25V16.75L12 20.5m-6.5-13.25L12 11m6.5-3.75V16.75L12 20.5m6.5-13.25L12 11m0 0v9.5"
              stroke="currentColor"
              strokeWidth="1.6"
              strokeLinecap="round"
              strokeLinejoin="round"
            />
          </svg>
        </span>
        <span className={`min-w-0 text-left ${labelClass}`}>
          <span className="block truncate text-[14px] font-bold tracking-tight text-white">Multiship</span>
          <span className="block text-[9px] uppercase tracking-[0.2em] text-slate-400">Shipping Ops</span>
        </span>
      </button>

      {/* main nav — stops on the route */}
      <div className="relative mt-1 flex-1 px-2.5">
        {/* the route line, threading through the stop nodes */}
        <span
          aria-hidden="true"
          className="pointer-events-none absolute bottom-4 left-[31px] top-8 border-l-2 border-dashed border-[#e1dcc9]/15"
        />

        <p
          className={`px-3 pb-1 pt-1.5 font-mono text-[9px] font-semibold uppercase tracking-[0.24em] text-slate-400 ${labelClass}`}
        >
          Route
        </p>

        <div className="space-y-1">
          {navItems.map((item) => {
            const active = activeKey === item.key

            return (
              <button
                key={item.key}
                type="button"
                onClick={() => navigate(item.to)}
                aria-current={active ? 'page' : undefined}
                title={item.label}
                className="relative flex w-full items-center gap-3 rounded-lg px-1 py-1 text-left transition hover:bg-white/[0.05]"
              >
                {/* stop node — solid bg masks the route line behind it */}
                <span
                  className={`relative flex h-9 w-9 shrink-0 items-center justify-center rounded-full border transition ${
                    active
                      ? 'border-[#e1dcc9] bg-[#e1dcc9] text-[#1f150c] shadow-[0_6px_18px_rgba(0,0,0,0.4)]'
                      : 'border-[#e1dcc9]/20 bg-[#1f150c] text-[#c2b494] group-hover:border-[#e1dcc9]/30'
                  }`}
                >
                  {active ? (
                    <span
                      aria-hidden="true"
                      className="absolute -inset-1 animate-ping rounded-full bg-[#e1dcc9]/25 [animation-duration:2.6s]"
                    />
                  ) : null}
                  <span className="relative">{navIcons[item.key]}</span>
                </span>
                <span
                  className={`whitespace-nowrap text-[13px] font-medium ${
                    active ? 'font-semibold text-white' : 'text-[#c2b494]'
                  } ${labelClass}`}
                >
                  {item.label}
                </span>
                {active ? (
                  <span
                    aria-hidden="true"
                    className={`ml-auto mr-2 font-mono text-[8px] font-bold uppercase tracking-[0.18em] text-[#e1dcc9]/60 ${labelClass}`}
                  >
                    Here
                  </span>
                ) : null}
              </button>
            )
          })}
        </div>
      </div>

      {/* pin toggle */}
      <div className="px-2.5 pb-1">
        <button
          type="button"
          onClick={onTogglePin}
          title={pinned ? 'Collapse sidebar' : 'Pin sidebar open'}
          className="flex w-full items-center gap-3 rounded-lg px-3 py-2 text-slate-400 transition hover:bg-white/[0.06] hover:text-slate-200"
        >
          {pinned ? (
            <FiChevronsLeft className="h-[18px] w-[18px] shrink-0" />
          ) : (
            <FiChevronsRight className="h-[18px] w-[18px] shrink-0" />
          )}
          <span className={`whitespace-nowrap text-[12px] font-medium ${labelClass}`}>
            {pinned ? 'Collapse' : 'Pin open'}
          </span>
        </button>
      </div>

      {/* stub footer: ladder barcode + document number, then the operator card */}
      <div className="border-t border-dashed border-[#e1dcc9]/15 px-2.5 py-3">
        <div className="flex items-center gap-3 px-1.5 pb-2.5">
          <span aria-hidden="true" className="flex w-8 shrink-0 flex-col items-stretch gap-[2px]">
            {BARCODE.map((h, i) => (
              <span key={i} className="bg-[#e1dcc9]/30" style={{ height: `${Math.ceil(h / 2)}px` }} />
            ))}
          </span>
          <span className={`min-w-0 ${labelClass}`}>
            <span className="block whitespace-nowrap font-mono text-[8.5px] uppercase tracking-[0.2em] text-slate-500">
              MS-2481
            </span>
            <span className="block whitespace-nowrap font-mono text-[8.5px] uppercase tracking-[0.2em] text-slate-500">
              Operator copy
            </span>
          </span>
        </div>

        <div className="flex items-center gap-3 rounded-lg px-1.5 py-1.5">
          <span className="flex h-8 w-8 shrink-0 items-center justify-center rounded-full bg-[#e1dcc9] text-[11px] font-bold text-[#1f150c]">
            {initials}
          </span>
          <span className={`min-w-0 flex-1 ${labelClass}`}>
            <span className="block truncate text-[12.5px] font-semibold text-white">{username || 'Signed in'}</span>
            <span className="block truncate whitespace-nowrap font-mono text-[9px] uppercase tracking-[0.16em] text-slate-400">
              {normalizedRole}
            </span>
          </span>
          <button
            type="button"
            onClick={() => {
              void handleLogout()
            }}
            disabled={loggingOut}
            aria-label="Log out"
            title="Log out"
            className={`shrink-0 rounded-lg p-2 transition hover:bg-rose-600 hover:text-white disabled:opacity-50 ${
              loggingOut ? 'bg-rose-600 text-white' : 'text-slate-400'
            } ${labelClass}`}
          >
            <FiLogOut className="h-4 w-4" />
          </button>
        </div>
      </div>
    </nav>
    </>
  )
}
