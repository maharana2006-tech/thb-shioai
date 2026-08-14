import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { useNavigate } from 'react-router-dom'
import {
  FiArrowRight,
  FiBox,
  FiCornerDownLeft,
  FiHome,
  FiLoader,
  FiSearch,
  FiSettings,
  FiUsers,
} from 'react-icons/fi'
import { orderService, type Order } from '../../api/orderService'
import { clientService, type Client } from '../../api/clientService'
import { warehouseService, type Warehouse } from '../../api/warehouseService'
import { settingsNavItems, workspacePaths } from '../../routes/workspaceRoutes'
import { normalizeRole, type UserRole } from '../../utils/roles'

/**
 * Universal search — one box that reaches every corner of the workspace
 * instead of only forwarding to /orders.
 *
 * Sources, queried in parallel and grouped in the dropdown:
 *  · Orders     — order #, tracking number, recipient, city (server search)
 *  · Clients    — code + name (server search)
 *  · Warehouses — code + name (server search)
 *  · Pages      — nav + settings destinations, matched locally so "carrier"
 *                 or "template" jumps straight to that screen
 *
 * Scan behaviour is preserved: a barcode gun types fast and ends with
 * Enter, which fires the top hit (an order when one matched) — so
 * scanning still lands on the order without touching the mouse.
 */

type ResultKind = 'order' | 'client' | 'warehouse' | 'page'

interface SearchHit {
  id: string
  kind: ResultKind
  title: string
  subtitle?: string
  to: string
}

const GROUP_META: Record<ResultKind, { label: string; icon: ReactNode }> = {
  order: { label: 'Orders', icon: <FiBox className="h-3 w-3" /> },
  client: { label: 'Clients', icon: <FiUsers className="h-3 w-3" /> },
  warehouse: { label: 'Warehouses', icon: <FiHome className="h-3 w-3" /> },
  page: { label: 'Go to', icon: <FiSettings className="h-3 w-3" /> },
}

/** Order in which groups render — most-specific data first, pages last. */
const GROUP_ORDER: ResultKind[] = ['order', 'client', 'warehouse', 'page']

/** Static destinations, matched locally (no network). */
function pageTargets(role: UserRole | null): SearchHit[] {
  const base: SearchHit[] = [
    { id: 'page-dashboard', kind: 'page', title: 'Dashboard', subtitle: 'Overview', to: workspacePaths.dashboard },
    { id: 'page-orders', kind: 'page', title: 'Orders', subtitle: 'Shipment & label workspace', to: workspacePaths.orders },
  ]
  for (const item of settingsNavItems) {
    if (role && !item.roles.includes(role)) continue
    base.push({
      id: `page-${item.key}`,
      kind: 'page',
      title: item.label,
      subtitle: 'Settings',
      to: item.to,
    })
  }
  return base
}

export default function UniversalSearch() {
  const navigate = useNavigate()
  const [term, setTerm] = useState('')
  const [open, setOpen] = useState(false)
  const [loading, setLoading] = useState(false)
  const [hits, setHits] = useState<SearchHit[]>([])
  const [active, setActive] = useState(0)
  const inputRef = useRef<HTMLInputElement>(null)
  const boxRef = useRef<HTMLDivElement>(null)
  /** Drops responses that arrive after a newer keystroke. */
  const seq = useRef(0)

  const role = normalizeRole(
    typeof window !== 'undefined' ? window.localStorage.getItem('multiship_role') : null,
  )
  const pages = useMemo(() => pageTargets(role), [role])

  // "/" focuses from anywhere; Escape closes.
  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      const target = event.target as HTMLElement | null
      if (event.key === '/' && target && !/^(INPUT|TEXTAREA|SELECT)$/.test(target.tagName)) {
        event.preventDefault()
        inputRef.current?.focus()
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [])

  // Click outside closes the dropdown.
  useEffect(() => {
    const onClick = (event: MouseEvent) => {
      if (boxRef.current && !boxRef.current.contains(event.target as Node)) setOpen(false)
    }
    document.addEventListener('mousedown', onClick)
    return () => document.removeEventListener('mousedown', onClick)
  }, [])

  const runSearch = useCallback(
    async (raw: string) => {
      const q = raw.trim()
      if (q.length < 2) {
        setHits([])
        setLoading(false)
        return
      }
      const mySeq = ++seq.current
      setLoading(true)

      const lower = q.toLowerCase()
      const pageHits = pages
        .filter((p) => p.title.toLowerCase().includes(lower))
        .slice(0, 4)

      // Every source is optional — one failing endpoint must not blank the
      // whole dropdown, so each promise resolves to [] on error.
      const [orders, clients, warehouses] = await Promise.all([
        orderService
          .listOrders({ page: 0, size: 5, search: q })
          .then((res) => (Array.isArray(res?.data?.content) ? res.data.content : []) as Order[])
          .catch((): Order[] => []),
        clientService
          .listClients({ page: 0, size: 4, search: q })
          .then((res) => (Array.isArray(res?.data?.content) ? res.data.content : []) as Client[])
          .catch((): Client[] => []),
        warehouseService
          .listWarehouses({ page: 0, size: 4, search: q })
          .then((res) => (Array.isArray(res?.data?.content) ? res.data.content : []) as Warehouse[])
          .catch((): Warehouse[] => []),
      ])

      if (mySeq !== seq.current) return // superseded by a newer keystroke

      const next: SearchHit[] = []
      for (const o of orders) {
        // The list DTO is nested: orderDetails / shippingDetails / labelDetails.
        const orderNo = o.orderDetails?.orderNo
        if (orderNo == null) continue
        const tracking = o.labelDetails?.trackingNumber || null
        const client = o.orderDetails?.customerCode
        next.push({
          id: `order-${orderNo}`,
          kind: 'order',
          title: `#${orderNo}${client ? ` · ${client}` : ''}`,
          subtitle:
            [tracking, o.shippingDetails?.city, o.orderDetails?.status].filter(Boolean).join(' · ') ||
            undefined,
          to: `${workspacePaths.orders}?q=${encodeURIComponent(String(tracking ?? orderNo))}`,
        })
      }
      for (const c of clients) {
        const code = c.clientCode
        if (!code) continue
        next.push({
          id: `client-${code}`,
          kind: 'client',
          title: `${code}${c.name ? ` — ${c.name}` : ''}`,
          subtitle: c.status || undefined,
          to: `/settings/clients?q=${encodeURIComponent(code)}`,
        })
      }
      for (const w of warehouses) {
        const code = w.code
        next.push({
          id: `warehouse-${code}`,
          kind: 'warehouse',
          title: `${code}${w.name ? ` — ${w.name}` : ''}`,
          subtitle:
            [w.address?.city, w.address?.country].filter(Boolean).join(', ') || undefined,
          to: `/settings/warehouses?q=${encodeURIComponent(code)}`,
        })
      }
      next.push(...pageHits)

      setHits(next)
      setActive(0)
      setLoading(false)
    },
    [pages],
  )

  // Debounced query — 250ms feels instant while a scan gun's burst of
  // keystrokes still collapses into one request.
  useEffect(() => {
    const t = setTimeout(() => void runSearch(term), 250)
    return () => clearTimeout(t)
  }, [term, runSearch])

  const go = (hit: SearchHit) => {
    navigate(hit.to)
    setTerm('')
    setHits([])
    setOpen(false)
    inputRef.current?.blur()
  }

  const onKeyDown = (event: React.KeyboardEvent<HTMLInputElement>) => {
    if (event.key === 'ArrowDown') {
      event.preventDefault()
      setActive((i) => Math.min(i + 1, hits.length - 1))
      setOpen(true)
    } else if (event.key === 'ArrowUp') {
      event.preventDefault()
      setActive((i) => Math.max(i - 1, 0))
    } else if (event.key === 'Enter') {
      event.preventDefault()
      if (hits[active]) {
        go(hits[active])
      } else if (term.trim()) {
        // No hit yet (fast scan, results still in flight) — fall back to the
        // orders list filtered by the raw scan so nothing is lost.
        navigate(`${workspacePaths.orders}?q=${encodeURIComponent(term.trim())}`)
        setTerm('')
        setOpen(false)
      }
    } else if (event.key === 'Escape') {
      setOpen(false)
      inputRef.current?.blur()
    }
  }

  // Group hits for rendering while keeping the flat index for keyboard nav.
  const grouped = useMemo(() => {
    const map = new Map<ResultKind, Array<{ hit: SearchHit; index: number }>>()
    hits.forEach((hit, index) => {
      const list = map.get(hit.kind) ?? []
      list.push({ hit, index })
      map.set(hit.kind, list)
    })
    return GROUP_ORDER.filter((k) => map.has(k)).map((k) => ({ kind: k, rows: map.get(k)! }))
  }, [hits])

  const showDropdown = open && term.trim().length >= 2

  return (
    <div ref={boxRef} className="relative hidden lg:block">
      <label className="flex w-72 items-center gap-2 rounded-lg bg-slate-100 px-3 py-2 ring-1 ring-transparent transition focus-within:bg-white focus-within:ring-[#412d15] xl:w-80">
        <FiSearch className="h-3.5 w-3.5 shrink-0 text-slate-400" aria-hidden="true" />
        <input
          ref={inputRef}
          value={term}
          onChange={(event) => {
            setTerm(event.target.value)
            setOpen(true)
          }}
          onFocus={() => setOpen(true)}
          onKeyDown={onKeyDown}
          placeholder="Search orders, clients, warehouses…"
          aria-label="Universal search"
          className="w-full bg-transparent text-[12.5px] text-[#1f150c] outline-none placeholder:text-slate-400"
        />
        {loading ? (
          <FiLoader className="h-3 w-3 shrink-0 animate-spin text-slate-400" aria-hidden="true" />
        ) : (
          <kbd className="rounded border border-slate-300 bg-white px-1.5 font-mono text-[10px] font-semibold text-slate-400">
            /
          </kbd>
        )}
      </label>

      {showDropdown ? (
        <div className="absolute right-0 top-[calc(100%+6px)] z-50 max-h-[420px] w-[26rem] overflow-y-auto rounded-xl border border-[#e3d9c4] bg-white p-1.5 shadow-[0_20px_50px_rgba(31,21,12,0.22)]">
          {hits.length === 0 ? (
            <p className="px-3 py-4 text-center text-[11.5px] text-[#8a7959]">
              {loading ? 'Searching…' : `No matches for “${term.trim()}”`}
            </p>
          ) : (
            grouped.map(({ kind, rows }) => (
              <div key={kind} className="mb-1 last:mb-0">
                <p className="flex items-center gap-1.5 px-2 py-1 text-[9.5px] font-bold uppercase tracking-[0.14em] text-[#b6a684]">
                  {GROUP_META[kind].icon} {GROUP_META[kind].label}
                </p>
                {rows.map(({ hit, index }) => (
                  <button
                    key={hit.id}
                    type="button"
                    onMouseEnter={() => setActive(index)}
                    onClick={() => go(hit)}
                    className={`flex w-full items-center justify-between gap-2 rounded-lg px-2.5 py-1.5 text-left transition ${
                      index === active ? 'bg-[#faf7f0]' : 'hover:bg-[#faf7f0]/70'
                    }`}
                  >
                    <span className="min-w-0">
                      <span className="block truncate text-[12.5px] font-semibold text-[#1f150c]">{hit.title}</span>
                      {hit.subtitle ? (
                        <span className="block truncate text-[10.5px] text-[#8a7959]">{hit.subtitle}</span>
                      ) : null}
                    </span>
                    {index === active ? (
                      <FiCornerDownLeft className="h-3 w-3 shrink-0 text-[#b6a684]" aria-hidden="true" />
                    ) : (
                      <FiArrowRight className="h-3 w-3 shrink-0 text-[#e3d9c4]" aria-hidden="true" />
                    )}
                  </button>
                ))}
              </div>
            ))
          )}
        </div>
      ) : null}
    </div>
  )
}
