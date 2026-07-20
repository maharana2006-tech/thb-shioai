import { NavLink, Outlet } from 'react-router-dom'
import { getSettingsNavForRole } from '../../routes/workspaceRoutes'
import { useAppSession } from '../../hooks/useAppSession'
import { normalizeRole } from '../../utils/roles'
import { navIcons } from './navIcons'

/**
 * The Settings hub: a segmented sub-nav (Clients · Carriers · Broker/Importer)
 * over the master-data pages. Tabs are role-gated; the active page renders in
 * the Outlet. Backend resources are unchanged — this is grouping, not a rewrite.
 */
export default function SettingsLayout() {
  const { role } = useAppSession()
  const items = getSettingsNavForRole(normalizeRole(role))

  return (
    <div className="space-y-4">
      <div>
        <h1 className="px-1 text-xl font-semibold tracking-tight text-[#1f150c]">Settings</h1>
        <p className="mt-0.5 px-1 text-[13px] text-slate-500">
          Master data used across the app — clients, carrier accounts, and customs identities.
        </p>
      </div>

      {/* segmented sub-nav */}
      <div className="flex flex-wrap gap-1 rounded-xl border border-slate-200 bg-white p-1 shadow-sm">
        {items.map((item) => (
          <NavLink
            key={item.key}
            to={item.to}
            className={({ isActive }) =>
              // `!text-*` beats the global `a { color: inherit }` (unlayered CSS
              // outranks layered Tailwind utilities in v4), so the active tab's
              // label + icon render white instead of dark-on-dark.
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

      <Outlet />
    </div>
  )
}
