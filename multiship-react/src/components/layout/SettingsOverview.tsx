import { Link, useLocation } from 'react-router-dom'
import { getSettingsGroupsForRole } from '../../routes/workspaceRoutes'
import { useAppSession } from '../../hooks/useAppSession'
import { normalizeRole } from '../../utils/roles'

/** /settings — every settings page the role can open, by group.
 *  `notFound` is set by the catch-all route so an unknown URL is honest about it. */
export default function SettingsOverview({ notFound }: { notFound?: boolean }) {
  const { role } = useAppSession()
  const location = useLocation()
  const groups = getSettingsGroupsForRole(normalizeRole(role))
  return (
    <div>
      {notFound ? (
        <p className="mb-4 rounded-lg border border-amber-200 bg-amber-50 px-3 py-2 text-[13px] text-amber-800">
          There’s no settings page at <span className="font-mono">{location.pathname}</span>. Here are all settings.
        </p>
      ) : null}
      <h1 className="text-[24px] font-bold tracking-tight text-[#1f150c]">All settings</h1>
      <p className="mt-1 text-[14px] text-[#6b5c42]">Everything that shapes how Multiship ships, grouped by what it controls.</p>
      <div className="mt-5 grid gap-4 [grid-template-columns:repeat(auto-fill,minmax(280px,1fr))]">
        {groups.map((g) => (
          <section key={g.key} className="rounded-2xl border border-[#ebe3d2] bg-white p-5">
            <h2 className="text-[15px] font-bold text-[#1f150c]">{g.label}</h2>
            {g.blurb ? <p className="mt-1 text-[13px] text-[#8a7a5a]">{g.blurb}</p> : null}
            <ul className="mt-3 space-y-0.5">
              {g.pages.map((p) => (
                <li key={p.key}>
                  <Link to={p.to} className="-mx-2 block rounded-lg px-2 py-1.5 text-[13.5px] !text-[#412d15] transition hover:bg-[#f6f0e2] hover:!text-[#1f150c]">
                    {p.label}
                  </Link>
                </li>
              ))}
            </ul>
          </section>
        ))}
      </div>
    </div>
  )
}
