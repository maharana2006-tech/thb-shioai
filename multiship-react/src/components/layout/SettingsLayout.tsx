import { useCallback, useMemo, useState } from 'react'
import { NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom'
import type { IconType } from 'react-icons'
import {
  FiActivity, FiAlertCircle, FiAlertTriangle, FiBell, FiBookOpen, FiBriefcase, FiCalendar, FiCircle,
  FiCornerUpLeft, FiCreditCard, FiDollarSign, FiEdit3, FiFileText, FiGlobe, FiGrid, FiHome, FiInbox,
  FiLayout, FiLink, FiList, FiMail, FiMap, FiMessageSquare, FiPackage, FiPrinter, FiRefreshCw, FiRepeat,
  FiSearch, FiSend, FiSettings, FiShield, FiShuffle, FiSliders, FiTerminal, FiTruck, FiUsers,
} from 'react-icons/fi'
import { getSettingsGroupsForRole, settingsNavItems, workspacePaths } from '../../routes/workspaceRoutes'
import { useAppSession } from '../../hooks/useAppSession'
import { normalizeRole } from '../../utils/roles'

/** One icon per settings page, by settingsNavItems key. */
const PAGE_ICONS: Record<string, IconType> = {
  clients: FiBriefcase, warehouses: FiHome, 'address-book': FiBookOpen, users: FiUsers, roles: FiShield,
  carriers: FiCreditCard, 'carriers-platform': FiTruck, 'shipping-catalog': FiPackage, 'code-maps': FiShuffle,
  'carrier-limits': FiSliders, 'carrier-dropdowns': FiList, cutoffs: FiCalendar,
  'importer-broker': FiGlobe, countries: FiMap, 'reasons-for-export': FiFileText, currencies: FiDollarSign,
  templates: FiLayout, 'custom-fields': FiEdit3, printers: FiPrinter, 'output-destinations': FiSend,
  mail: FiMail, 'notification-templates': FiMessageSquare, 'my-subscriptions': FiBell,
  'external-systems': FiLink, system: FiSettings, 'carrier-error-messages': FiAlertTriangle,
  'audit-log': FiActivity, 'carrier-api-log': FiTerminal, 'notification-delivery-log': FiInbox,
  'writeback-journal': FiRepeat, 'alerts-history': FiAlertCircle, 'returns-analytics': FiCornerUpLeft,
}

/** Handler shape a Settings page registers so the global Refresh icon has something to call. */
type RefreshHandler = () => void | Promise<void>

/** Outlet context settings pages read to plug into the shared toolbar. */
export interface SettingsOutletContext {
  registerRefresh: (handler: RefreshHandler | null) => void
}

/**
 * The Settings hub: a grouped left menu with search, and one shared page
 * header (title, description, Refresh) above whichever page is open. Pages
 * register their load() via outlet context so the header's Refresh can call it.
 * Below lg the menu collapses into a select so the page keeps the width.
 */
export default function SettingsLayout() {
  const { role } = useAppSession()
  const location = useLocation()
  const navigate = useNavigate()
  const groups = getSettingsGroupsForRole(normalizeRole(role))
  const [query, setQuery] = useState('')

  const [refresh, setRefresh] = useState<RefreshHandler | null>(null)
  const [refreshing, setRefreshing] = useState(false)
  // Wrap in a thunk so setState doesn't interpret the handler as a functional updater.
  const registerRefresh = useCallback((handler: RefreshHandler | null) => {
    setRefresh(() => handler)
  }, [])

  // Nested routes (e.g. /settings/templates/{id}) keep their parent page lit.
  const isActive = (to: string) => location.pathname === to || location.pathname.startsWith(to + '/')
  const activeItem = useMemo(
    () => settingsNavItems.find((item) => isActive(item.to)) || null,
    // eslint-disable-next-line react-hooks/exhaustive-deps -- isActive only reads location.pathname
    [location.pathname],
  )
  const onOverview = location.pathname === workspacePaths.settings || location.pathname === workspacePaths.settings + '/'

  const q = query.trim().toLowerCase()
  const shown = q
    ? groups
        .map((g) => ({
          ...g,
          pages: g.pages.filter((p) => `${p.label} ${p.description} ${g.label}`.toLowerCase().includes(q)),
        }))
        .filter((g) => g.pages.length)
    : groups

  const runRefresh = async () => {
    if (!refresh || refreshing) return
    setRefreshing(true)
    try {
      await refresh()
    } finally {
      setRefreshing(false)
    }
  }

  const itemClass = (active: boolean) =>
    `group/item flex w-full items-center gap-2.5 rounded-lg px-2.5 py-[6px] text-[13px] leading-snug transition ${
      active ? 'bg-[#1f150c] font-semibold !text-[#f4eede]' : 'font-medium !text-[#5a4526] hover:bg-[#efe7d4] hover:!text-[#1f150c]'
    }`
  const iconClass = (active: boolean) =>
    `h-[15px] w-[15px] shrink-0 ${active ? 'text-[#e1dcc9]' : 'text-[#a1906d] group-hover/item:text-[#412d15]'}`

  return (
    // lg+: the menu is a full-height panel attached to the app sidebar — the
    // negative margins cancel <main>'s padding so it sits flush left and top.
    <div className="flex flex-col lg:-mx-8 lg:-my-5 lg:flex-row">
      <aside
        aria-label="Settings"
        className="hidden w-[232px] shrink-0 border-r border-[#e6dcc6] bg-[#f8f3e8] lg:sticky lg:top-14 lg:block lg:h-[calc(100vh-3.5rem)] lg:self-start lg:overflow-y-auto [scrollbar-color:#d9cfbb_transparent] [scrollbar-width:thin]"
      >
        <div className="px-3 pb-6 pt-4">
          <p className="mb-3 flex items-center gap-2 px-2.5 text-[15px] font-bold tracking-tight text-[#1f150c]">
            <FiSettings className="h-4 w-4 text-[#412d15]" />
            Settings
          </p>
          <label className="relative mb-3 block">
            <span className="sr-only">Search settings</span>
            <FiSearch className="pointer-events-none absolute left-2.5 top-1/2 h-3.5 w-3.5 -translate-y-1/2 text-[#a1906d]" />
            <input
              type="search"
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              placeholder="Search settings"
              className="h-8 w-full rounded-lg border border-[#e3d9c4] bg-white pl-8 pr-2 text-[12.5px] text-[#1f150c] outline-none transition placeholder:text-[#a1906d] focus:border-[#412d15] focus:ring-4 focus:ring-[#efe5cf]"
            />
          </label>

          {!q ? (
            <NavLink to={workspacePaths.settings} end aria-current={onOverview ? 'page' : undefined} className={itemClass(onOverview)}>
              <FiGrid className={iconClass(onOverview)} aria-hidden="true" />
              All settings
            </NavLink>
          ) : null}

          {shown.map((g) => (
            <div key={g.key} className="mt-4 space-y-px">
              <p className="mb-1 px-2.5 font-mono text-[9.5px] font-semibold uppercase tracking-[0.14em] text-[#a1906d]">{g.label}</p>
              {g.pages.map((p) => {
                const active = isActive(p.to)
                const Icon = PAGE_ICONS[p.key] ?? FiCircle
                return (
                  <NavLink key={p.key} to={p.to} aria-current={active ? 'page' : undefined} className={itemClass(active)}>
                    <Icon className={iconClass(active)} aria-hidden="true" />
                    <span className="truncate">{p.label}</span>
                  </NavLink>
                )
              })}
            </div>
          ))}
          {q && !shown.length ? (
            <p className="px-2.5 py-2 text-[12.5px] text-[#8a7a5a]">No setting matches “{query.trim()}”.</p>
          ) : null}
        </div>
      </aside>

      <div className="min-w-0 flex-1 lg:px-7 lg:py-5">
        {/* Below lg the menu is a select, so the page keeps its width. */}
        <label className="mb-3 block lg:hidden">
          <span className="sr-only">Settings page</span>
          <select
            value={onOverview ? workspacePaths.settings : activeItem?.to ?? ''}
            onChange={(e) => navigate(e.target.value)}
            className="h-10 w-full rounded-xl border border-[#e3d9c4] bg-white px-3 text-[13.5px] font-semibold text-[#1f150c]"
          >
            <option value={workspacePaths.settings}>All settings</option>
            {groups.map((g) => (
              <optgroup key={g.key} label={g.label}>
                {g.pages.map((p) => <option key={p.key} value={p.to}>{p.label}</option>)}
              </optgroup>
            ))}
          </select>
        </label>

        {!onOverview ? (
          <div className="mb-4 flex items-start justify-between gap-4 border-b border-[#ebe3d2] pb-4">
            <div className="min-w-0">
              <h1 className="text-[22px] font-bold tracking-tight text-[#1f150c]">{activeItem?.label ?? 'Settings'}</h1>
              {activeItem?.description ? (
                <p className="mt-1 max-w-3xl text-[13.5px] leading-relaxed text-[#6b5c42]">{activeItem.description}</p>
              ) : null}
            </div>
            <button
              type="button"
              onClick={() => void runRefresh()}
              disabled={!refresh || refreshing}
              aria-label="Refresh"
              title={refresh ? 'Refresh this page' : 'Nothing to refresh on this page'}
              className="inline-flex h-9 w-9 shrink-0 items-center justify-center rounded-lg border border-[#e3d9c4] bg-white text-[#5a4526] transition hover:bg-[#faf6ec] disabled:cursor-not-allowed disabled:opacity-40"
            >
              <FiRefreshCw className={`h-4 w-4 ${refreshing ? 'animate-spin' : ''}`} />
            </button>
          </div>
        ) : null}

        <Outlet context={{ registerRefresh } satisfies SettingsOutletContext} />
      </div>
    </div>
  )
}
