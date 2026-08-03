import { useEffect, useRef, useState } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { FiCheckCircle, FiChevronRight } from 'react-icons/fi'
import { useAppSession } from '../../hooks/useAppSession'
import { resolveBreadcrumb } from '../../routes/workspaceRoutes'
import { normalizeRole } from '../../utils/roles'
import { navIcons } from './navIcons'

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
  const navigate = useNavigate()
  const { username, role, hasConnectedCarrier, connectedCarriers } = useAppSession()
  const [search, setSearch] = useState('')
  const [now, setNow] = useState(() => new Date())
  const searchRef = useRef<HTMLInputElement>(null)

  const normalizedRole = normalizeRole(role)
  const crumb = resolveBreadcrumb(location.pathname)

  useEffect(() => {
    const tick = setInterval(() => setNow(new Date()), 1000)
    return () => clearInterval(tick)
  }, [])

  // "/" focuses the scan bar from anywhere (unless already typing somewhere)
  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      const target = event.target as HTMLElement | null
      if (event.key === '/' && target && !/^(INPUT|TEXTAREA|SELECT)$/.test(target.tagName)) {
        event.preventDefault()
        searchRef.current?.focus()
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [])

  const firstCarrier = connectedCarriers?.[0]
  const carrierLabel = firstCarrier?.name
    ? firstCarrier.name.toUpperCase()
    : hasConnectedCarrier
    ? 'CONNECTED'
    : null

  const initials = (username || 'MS').slice(0, 2).toUpperCase()

  const submitSearch = () => {
    const term = search.trim()
    navigate(term ? `/orders?q=${encodeURIComponent(term)}` : '/orders')
    setSearch('')
  }

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

        {/* scan bar */}
        {normalizedRole !== 'TENANT' ? (
          <label className="hidden w-64 items-center gap-2 rounded-lg bg-slate-100 px-3 py-2 ring-1 ring-transparent transition focus-within:bg-white focus-within:ring-[#412d15] lg:flex">
            <svg viewBox="0 0 20 14" className="h-3.5 w-4 shrink-0 text-slate-400" fill="currentColor" aria-hidden="true">
              <rect x="0" width="1.5" height="14" />
              <rect x="3" width="1" height="14" />
              <rect x="5.5" width="2" height="14" />
              <rect x="9" width="1" height="14" />
              <rect x="11.5" width="1.5" height="14" />
              <rect x="14.5" width="1" height="14" />
              <rect x="17" width="2" height="14" />
            </svg>
            <input
              ref={searchRef}
              value={search}
              onChange={(event) => setSearch(event.target.value)}
              onKeyDown={(event) => {
                if (event.key === 'Enter') submitSearch()
                if (event.key === 'Escape') searchRef.current?.blur()
              }}
              placeholder="Scan / search orders…"
              className="w-full bg-transparent font-mono text-[12px] text-[#1f150c] outline-none placeholder:font-sans placeholder:text-[12.5px] placeholder:text-slate-400"
            />
            <kbd className="rounded border border-slate-300 bg-white px-1.5 font-mono text-[10px] font-semibold text-slate-400">
              /
            </kbd>
          </label>
        ) : null}

        {/* carrier status */}
        {normalizedRole !== 'TENANT' && carrierLabel ? (
          <span className="hidden shrink-0 items-center gap-1.5 rounded-full bg-emerald-50 px-2.5 py-1 text-[11px] font-semibold text-emerald-700 ring-1 ring-emerald-200 sm:inline-flex">
            <FiCheckCircle className="h-3 w-3" />
            {carrierLabel}
          </span>
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
