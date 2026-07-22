import { useCallback, useMemo, useState } from 'react'
import { NavLink, Outlet, useLocation } from 'react-router-dom'
import { FiRefreshCw } from 'react-icons/fi'
import { getSettingsNavForRole, settingsNavItems } from '../../routes/workspaceRoutes'
import { useAppSession } from '../../hooks/useAppSession'
import { normalizeRole } from '../../utils/roles'
import { navIcons } from './navIcons'

/** Handler shape a Settings page registers so the global Refresh icon has something to call. */
type RefreshHandler = () => void | Promise<void>

/** Outlet context settings pages read to plug into the shared toolbar. */
export interface SettingsOutletContext {
  registerRefresh: (handler: RefreshHandler | null) => void
}

/**
 * The Settings hub: submenus row + a tight one-line description of the active
 * tab. The old "Settings" title block is gone — the submenus row is the top,
 * to maximize workspace. Individual pages register their load() via outlet
 * context so the shared Refresh icon (right-aligned in the submenus row) can
 * refresh whichever page is active.
 */
export default function SettingsLayout() {
  const { role } = useAppSession()
  const location = useLocation()
  const items = getSettingsNavForRole(normalizeRole(role))

  const [refresh, setRefresh] = useState<RefreshHandler | null>(null)
  const [refreshing, setRefreshing] = useState(false)
  // Wrap in a thunk so setState doesn't interpret the handler as a functional updater.
  const registerRefresh = useCallback((handler: RefreshHandler | null) => {
    setRefresh(() => handler)
  }, [])

  const activeItem = useMemo(
    () => settingsNavItems.find((item) => item.to === location.pathname) || null,
    [location.pathname],
  )

  const runRefresh = async () => {
    if (!refresh || refreshing) return
    setRefreshing(true)
    try {
      await refresh()
    } finally {
      setRefreshing(false)
    }
  }

  return (
    <div className="space-y-3">
      {/* Submenus row — replaces the old "Settings" header. Refresh icon
          right-aligned; a page registers its handler via outlet context. */}
      <div className="flex flex-wrap items-center gap-2 rounded-xl border border-slate-200 bg-white p-1 shadow-sm">
        <div className="flex flex-wrap gap-0.5" role="tablist">
          {items.map((item) => (
            <NavLink
              key={item.key}
              to={item.to}
              className={({ isActive }) =>
                `inline-flex items-center gap-2 rounded-lg px-3.5 py-2 text-[13px] font-semibold transition ${
                  isActive ? 'bg-[#1f150c] !text-white' : '!text-slate-600 hover:bg-slate-100'
                }`
              }
            >
              <span className="[&>svg]:h-4 [&>svg]:w-4">{navIcons[item.iconKey]}</span>
              {item.label}
            </NavLink>
          ))}
        </div>

        <button
          type="button"
          onClick={() => void runRefresh()}
          disabled={!refresh || refreshing}
          aria-label="Refresh"
          title={refresh ? 'Refresh this page' : 'Nothing to refresh on this page'}
          className="ml-auto inline-flex h-9 w-9 items-center justify-center rounded-lg border border-slate-200 bg-white text-slate-600 transition hover:bg-slate-50 disabled:cursor-not-allowed disabled:opacity-40"
        >
          <FiRefreshCw className={`h-4 w-4 ${refreshing ? 'animate-spin' : ''}`} />
        </button>
      </div>

      {activeItem?.description ? (
        <p className="truncate px-1 text-[12px] text-slate-500" title={activeItem.description}>
          {activeItem.description}
        </p>
      ) : null}

      <Outlet context={{ registerRefresh } satisfies SettingsOutletContext} />
    </div>
  )
}
