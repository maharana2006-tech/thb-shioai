import { useEffect, useState } from 'react'
import { useLocation } from 'react-router-dom'
import { FiAlertCircle, FiCheckCircle, FiChevronRight } from 'react-icons/fi'
import { useAppSession } from '../../hooks/useAppSession'
import { accountRefService } from '../../api/accountRefService'
import { resolveBreadcrumb, settingsPaths } from '../../routes/workspaceRoutes'
import { normalizeRole } from '../../utils/roles'
import { navIcons } from './navIcons'
import UniversalSearch from './UniversalSearch'

/**
 * Header v3 — the "manifest strip". The topbar speaks the same shipping-
 * document language as the auth waybill:
 *  · breadcrumb = a mini tracking route (origin node ─ dashed line ▸
 *    destination with a pulsing "you are here" dot)
 *  · search = a scan bar (barcode glyph, "/" to focus)
 *  · a live ops clock ticking in mono
 *  · user = an ID badge (espresso initials tile + clearance line)
 *  · bottom edge = perforated tear line, not a solid border
 */
export default function WorkspaceHeader() {
  const location = useLocation()
  const { username, role } = useAppSession()
  const [now, setNow] = useState(() => new Date())
  /** Live carrier roster from the account book. The old chip read a
   *  localStorage cache that only ever held ONE carrier (whichever synced
   *  last), so it showed the same name forever regardless of what was
   *  actually connected. */
  const [carriers, setCarriers] = useState<string[] | null>(null)

  const normalizedRole = normalizeRole(role)
  const crumb = resolveBreadcrumb(location.pathname)

  useEffect(() => {
    const tick = setInterval(() => setNow(new Date()), 1000)
    return () => clearInterval(tick)
  }, [])

  // Pull the distinct carriers that have a verified, active account.
  useEffect(() => {
    if (normalizedRole === 'TENANT') return
    let cancelled = false
    accountRefService
      .listAccounts()
      .then((accounts) => {
        if (cancelled) return
        const live = accounts
          .filter((a) => a.active && a.verified !== false && a.carrierCode)
          .map((a) => a.carrierCode.toUpperCase())
        setCarriers([...new Set(live)].sort())
      })
      .catch(() => {
        if (!cancelled) setCarriers([])
      })
    return () => {
      cancelled = true
    }
  }, [normalizedRole, location.pathname])

  const initials = (username || 'MS').slice(0, 2).toUpperCase()

  return (
    <header className="sticky top-0 z-30 border-b border-dashed border-slate-300 bg-white/85 backdrop-blur-xl print:hidden">
      <div className="flex h-14 items-center gap-4 px-5">
        {/* breadcrumb as a tracking route: origin ─ ─ ▸ current location */}
        <div className="flex min-w-0 items-center gap-2.5">
          {crumb ? (
            <>
              <span className="hidden items-center gap-1.5 sm:flex">
                <span className="h-2 w-2 shrink-0 rounded-full border-[1.5px] border-slate-400" />
                <span className="whitespace-nowrap text-[11px] font-bold uppercase tracking-[0.18em] text-slate-400">
                  {crumb.section}
                </span>
              </span>
              <span className="hidden items-center sm:flex" aria-hidden="true">
                <span className="w-9 border-t-2 border-dashed border-slate-300" />
                <FiChevronRight className="-ml-1 h-3 w-3 text-slate-300" />
              </span>
              <span className="flex min-w-0 items-center gap-2">
                <span className="relative flex h-2.5 w-2.5 shrink-0" aria-hidden="true">
                  <span className="absolute inline-flex h-full w-full animate-ping rounded-full bg-[#412d15]/40 [animation-duration:2.6s]" />
                  <span className="relative inline-flex h-2.5 w-2.5 rounded-full bg-[#412d15]" />
                </span>
                <span className="shrink-0 text-[#412d15]">{navIcons[crumb.iconKey] ?? null}</span>
                <span className="truncate text-[16px] font-bold text-[#1f150c]">{crumb.label}</span>
              </span>
            </>
          ) : (
            <span className="text-[13.5px] font-bold text-[#1f150c]">Multiship</span>
          )}
        </div>

        <span className="ml-auto" />

        {/* live ops clock */}
        <span className="hidden shrink-0 items-baseline gap-1.5 xl:flex">
          <span className="font-mono text-[11.5px] font-semibold tabular-nums text-slate-600">
            {now.toLocaleTimeString('en-GB', { hour12: false })}
          </span>
          <span className="text-[8.5px] font-bold uppercase tracking-[0.2em] text-slate-400">local</span>
        </span>

        {/* universal search — orders, clients, warehouses, and pages */}
        {normalizedRole !== 'TENANT' ? <UniversalSearch /> : null}

        {/* carrier status — every connected carrier, from live account data */}
        {normalizedRole !== 'TENANT' && carriers !== null ? (
          carriers.length > 0 ? (
            <a
              href={settingsPaths.carriers}
              title={`Connected carriers: ${carriers.join(', ')}`}
              className="hidden shrink-0 items-center gap-1.5 rounded-full bg-emerald-50 px-2.5 py-1 text-[11px] font-semibold text-emerald-700 ring-1 ring-emerald-200 transition hover:bg-emerald-100 sm:inline-flex"
            >
              <FiCheckCircle className="h-3 w-3" />
              {carriers.slice(0, 3).join(' · ')}
              {carriers.length > 3 ? ` +${carriers.length - 3}` : ''}
            </a>
          ) : (
            <a
              href={settingsPaths.carriers}
              title="No verified carrier account yet — connect one to generate labels."
              className="hidden shrink-0 items-center gap-1.5 rounded-full bg-amber-50 px-2.5 py-1 text-[11px] font-semibold text-amber-700 ring-1 ring-amber-200 transition hover:bg-amber-100 sm:inline-flex"
            >
              <FiAlertCircle className="h-3 w-3" />
              No carrier
            </a>
          )
        ) : null}

        {/* user as ID badge */}
        <span className="hidden shrink-0 items-center gap-2 rounded-lg border border-slate-200 bg-white/70 py-1 pl-1 pr-2.5 sm:flex">
          <span className="flex h-7 w-7 items-center justify-center rounded-md bg-[#1f150c] font-mono text-[10px] font-black text-[#e1dcc9]">
            {initials}
          </span>
          <span className="text-left leading-tight">
            <span className="block text-[12px] font-semibold text-[#1f150c]">{username || 'Signed in'}</span>
            <span className="block font-mono text-[8.5px] uppercase tracking-[0.16em] text-slate-400">
              {normalizedRole} · on shift
            </span>
          </span>
        </span>
      </div>
    </header>
  )
}
