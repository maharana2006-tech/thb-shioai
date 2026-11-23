import { useEffect, useState, type CSSProperties } from 'react'
import { Outlet, useLocation } from 'react-router-dom'
import Sidebar from './Sidebar'
import WorkspaceHeader from './WorkspaceHeader'
import BrandBackdrop from './BrandBackdrop'
import RouteErrorBoundary from '../errors/RouteErrorBoundary'
import ShipmatePanel, { SHIPMATE_DEFAULT_WIDTH } from '../shipmate/ShipmatePanel'
import { useAppSession } from '../../hooks/useAppSession'
import { normalizeRole } from '../../utils/roles'

const PIN_KEY = 'multiship_nav_pinned'
const SHIPMATE_WIDTH_KEY = 'multiship_shipmate_width'

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

  // The AI endpoints are ADMIN/USER only, so client (TENANT) logins get no Shipmate.
  const canUseShipmate = normalizeRole(useAppSession().role) !== 'TENANT'
  const [shipmateOpen, setShipmateOpen] = useState(false)
  const [shipmateWidth, setShipmateWidth] = useState(
    () => Number(localStorage.getItem(SHIPMATE_WIDTH_KEY)) || SHIPMATE_DEFAULT_WIDTH,
  )
  useEffect(() => {
    localStorage.setItem(SHIPMATE_WIDTH_KEY, String(shipmateWidth))
  }, [shipmateWidth])
  useEffect(() => {
    if (!canUseShipmate) return
    const onKey = (event: KeyboardEvent) => {
      if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 'j') {
        event.preventDefault()
        setShipmateOpen((cur) => !cur)
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [canUseShipmate])

  // Settings brings its own menu beside the sidebar, so there the sidebar
  // starts as the icon rail (hover still peeks it open). Pinning it there
  // lasts for the visit and leaves the operator's saved choice alone.
  const onSettings = useLocation().pathname.startsWith('/settings')
  const [settingsPinned, setSettingsPinned] = useState(false)
  const railPinned = onSettings ? settingsPinned : pinned
  const toggleRail = onSettings ? () => setSettingsPinned((cur) => !cur) : togglePin

  return (
    <div
      style={{ '--shipmate-w': `${shipmateWidth}px` } as CSSProperties}
      className="relative min-h-screen overflow-x-clip bg-[var(--color-background)] text-[var(--color-text)]"
    >
      {/* whisper-subtle signature texture behind every page (hidden in print) */}
      <div className="fixed inset-0 print:hidden">
        <BrandBackdrop variant="light" />
      </div>
      <Sidebar
        pinned={railPinned}
        onTogglePin={toggleRail}
        mobileOpen={mobileNavOpen}
        onMobileClose={() => setMobileNavOpen(false)}
      />
      {/* Sprint 51 FE-M5 — content column starts full-bleed on <md (drawer
          is an overlay, not a permanent rail) and re-inherits the 56/64
          margin on md+. */}
      {/* Only the sidebar side animates: the right margin has to follow a resize drag without lag.
          The page gives way to Shipmate until it would drop under ~760px; past that the panel lies over it. */}
      <div className={`relative transition-[margin-left] duration-200 ease-out print:ml-0 print:mr-0 ml-0 ${railPinned ? 'md:ml-56' : 'md:ml-16'} ${shipmateOpen ? 'min-[1440px]:mr-[min(var(--shipmate-w),calc(100vw-984px))]' : ''}`}>
        <WorkspaceHeader
          onOpenMobileNav={() => setMobileNavOpen(true)}
          shipmateOpen={shipmateOpen}
          onToggleShipmate={canUseShipmate ? () => setShipmateOpen((cur) => !cur) : undefined}
        />
        <main className="px-4 py-5 sm:px-6 lg:px-8">
          {/* Sprint 49 Tier 4 Fix 1 — route-scoped boundary so a broken
              /orders (or any route) doesn't kill the sidebar + topbar
              around it. Keyed by pathname so retrying navigates to a
              fresh mount cleanly on the same route. */}
          <RouteScopedBoundary />
        </main>
      </div>
      {/* Docks beside the page from 1440px; on narrower screens it lies over the right edge. */}
      {canUseShipmate ? (
        <ShipmatePanel
          open={shipmateOpen}
          onClose={() => setShipmateOpen(false)}
          width={shipmateWidth}
          onWidthChange={setShipmateWidth}
        />
      ) : null}
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
