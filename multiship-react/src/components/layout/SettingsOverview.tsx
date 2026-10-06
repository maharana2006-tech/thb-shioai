import { Link } from 'react-router-dom'
import { getSettingsGroupsForRole } from '../../routes/workspaceRoutes'
import { useAppSession } from '../../hooks/useAppSession'
import { normalizeRole } from '../../utils/roles'

/** /settings — every settings page the role can open, by group. */
export default function SettingsOverview() {
  const { role } = useAppSession()
  const groups = getSettingsGroupsForRole(normalizeRole(role))
  return (
    <div>
      <h1 className="text-[24px] font-bold tracking-tight text-[var(--e-1f150c)]">All settings</h1>
      <p className="mt-1 text-[14px] text-[var(--e-6b5c42)]">Everything that shapes how Multiship ships, grouped by what it controls.</p>
      <div className="mt-5 grid gap-4 [grid-template-columns:repeat(auto-fill,minmax(280px,1fr))]">
        {groups.map((g) => (
          <section key={g.key} className="rounded-2xl border border-[var(--e-ebe3d2)] bg-white p-5">
            <h2 className="text-[15px] font-bold text-[var(--e-1f150c)]">{g.label}</h2>
            {g.blurb ? <p className="mt-1 text-[13px] text-[var(--e-8a7a5a)]">{g.blurb}</p> : null}
            <ul className="mt-3 space-y-0.5">
              {g.pages.map((p) => (
                <li key={p.key}>
                  <Link to={p.to} className="-mx-2 block rounded-lg px-2 py-1.5 text-[13.5px] !text-[var(--e-412d15)] transition hover:bg-[var(--e-f6f0e2)] hover:!text-[var(--e-1f150c)]">
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
