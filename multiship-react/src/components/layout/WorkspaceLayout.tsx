import { useEffect, useState } from 'react'
import { Outlet, useLocation } from 'react-router-dom'
import Sidebar from './Sidebar'
import WorkspaceHeader from './WorkspaceHeader'
import BrandBackdrop from './BrandBackdrop'
import RouteErrorBoundary from '../errors/RouteErrorBoundary'

const PIN_KEY = 'multiship_nav_pinned'

/**
 * App frame: full-height navy sidebar on the left (collapsible, pin state
 * persisted), and a content column beside it holding the light topbar and
 * the page. Print hides all chrome and resets the offset so 4x6 labels
 * stay full-bleed.
 */
export default function WorkspaceLayout() {
  // Default to open: an operator who has never touched the pin gets the
  // full labelled rail, not the icon-only stub. Only an explicit '0'
  // (they pressed Collapse) keeps it collapsed across reloads.
  const [pinned, setPinned] = useState(() => localStorage.getItem(PIN_KEY) !== '0')
  // Sprint 51 FE-M5 — mobile drawer open state. Not persisted; the drawer
  // is a one-off overlay per navigation, not a preference.
  const [mobileNavOpen, setMobileNavOpen] = useState(false)

  // Persist in an effect, not inside the updater. A setState updater must
  // be pure — StrictMode invokes it twice in dev and React may replay it,
  // so a localStorage write in there fires more than once per click and
  // can persist a value the state never settled on.
  useEffect(() => {
    localStorage.setItem(PIN_KEY, pinned ? '1' : '0')
  }, [pinned])

  const togglePin = () => setPinned((cur) => !cur)

  return (
    <div className="relative min-h-screen bg-[var(--color-background)] text-[var(--color-text)]">
      {/* whisper-subtle signature texture behind every page (hidden in print) */}
      <div className="fixed inset-0 print:hidden">
        <BrandBackdrop variant="light" />
      </div>
      <Sidebar
        pinned={pinned}
        onTogglePin={togglePin}
        mobileOpen={mobileNavOpen}
        onMobileClose={() => setMobileNavOpen(false)}
      />
      {/* Sprint 51 FE-M5 — content column starts full-bleed on <md (drawer
          is an overlay, not a permanent rail) and re-inherits the 56/64
          margin on md+. */}
      <div className={`relative transition-[margin] duration-200 ease-out print:ml-0 ml-0 ${pinned ? 'md:ml-56' : 'md:ml-16'}`}>
        <WorkspaceHeader onOpenMobileNav={() => setMobileNavOpen(true)} />
        <main className="px-4 py-5 sm:px-6 lg:px-8">
          {/* Sprint 49 Tier 4 Fix 1 — route-scoped boundary so a broken
              /orders (or any route) doesn't kill the sidebar + topbar
              around it. Keyed by pathname so retrying navigates to a
              fresh mount cleanly on the same route. */}
          <RouteScopedBoundary />
        </main>
      </div>
    </div>
  )
}

/**
 * Sprint 49 Tier 4 Fix 1 — path-keyed boundary so navigating away
 * from a crashed page resets the boundary state; otherwise the error
 * fallback would persist even after the operator picks a different
 * sidebar item.
 */
function RouteScopedBoundary() {
  const location = useLocation()
  return (
    <RouteErrorBoundary key={location.pathname} routeLabel={location.pathname}>
      <Outlet />
    </RouteErrorBoundary>
  )
}
