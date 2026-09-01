import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useOutletContext } from 'react-router-dom'
import {
  FiChevronDown,
  FiChevronRight,
  FiFilter,
  FiUser,
  FiX,
} from 'react-icons/fi'
import type { ColumnDef, SortingState } from '@tanstack/react-table'
import {
  auditLogService,
  type AuditLogEntry,
} from '../api/auditLogService'
import type { SettingsOutletContext } from './layout/SettingsLayout'
import AdvancedDataTable from './workspace/AdvancedDataTable'
import { summarizeCarrierError } from '../utils/carrierErrorMap'
import Select from './workspace/Select'
import { notify } from '../utils/notify'

/**
 * Read-only feed of settings writes. Filter by actor / entity type /
 * action / entity key / date range. Row-level expand shows the
 * changes JSON pretty-printed.
 */
export default function AuditLogPage() {
  const { registerRefresh } = useOutletContext<SettingsOutletContext>()

  const [rows, setRows] = useState<AuditLogEntry[]>([])
  const [loading, setLoading] = useState(true)
  const [totalElements, setTotalElements] = useState(0)
  const [totalPages, setTotalPages] = useState(1)

  const [actor, setActor] = useState('')
  const [entityType, setEntityType] = useState('')
  const [action, setAction] = useState('')
  const [entityKey, setEntityKey] = useState('')
  /** Logs-page tab: '' = all, else ACTIVITY | SHIPMENT | ERROR. */
  const [category, setCategory] = useState('')
  /** "Everything about order N" filter. */
  const [orderNoFilter, setOrderNoFilter] = useState('')
  /** Audit A1 — 300ms debounce so the AdvancedDataTable search box
   *  doesn't fire /audit-log per keystroke while the user is typing. */
  const [debouncedEntityKey, setDebouncedEntityKey] = useState('')
  /** Audit A2 — date-range filters (backend already supports since/until
   *  but the UI had no inputs). ISO local date; empty = unbounded on
   *  that side. Widened to a full datetime by the browser input picker. */
  const [since, setSince] = useState('')
  const [until, setUntil] = useState('')
  const [showFilters, setShowFilters] = useState(false)
  /** Audit R2 #359 — dropdown-panel ref for outside-click + ESC-to-close.
   *  Pre-fix, the panel stayed open until the operator clicked Filters
   *  again; no ARIA linkage so screen readers didn't know a disclosure
   *  had opened. */
  const filterPanelRef = useRef<HTMLDivElement>(null)
  useEffect(() => {
    if (!showFilters) return
    const onClickOutside = (e: MouseEvent) => {
      if (filterPanelRef.current && !filterPanelRef.current.contains(e.target as Node)) {
        setShowFilters(false)
      }
    }
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setShowFilters(false)
    }
    document.addEventListener('mousedown', onClickOutside)
    document.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('mousedown', onClickOutside)
      document.removeEventListener('keydown', onKey)
    }
  }, [showFilters])
  const [pageIndex, setPageIndex] = useState(0)
  const [pageSize, setPageSize] = useState(25)
  const [reloadToken, setReloadToken] = useState(0)
  const [expanded, setExpanded] = useState<Set<number>>(new Set())

  const [sorting, setSorting] = useState<SortingState>([
    { id: 'createdAt', desc: true },
  ])

  /** Audit A3 — serialise TanStack's sorting state to the backend's
   *  "property,direction" spec so column-header clicks actually reach
   *  the query (pre-fix: state changed, API kept returning createdAt DESC). */
  const sortParam = useMemo(() => {
    const first = sorting[0]
    if (!first) return 'createdAt,DESC'
    return `${first.id},${first.desc ? 'DESC' : 'ASC'}`
  }, [sorting])

  useEffect(() => {
    const t = window.setTimeout(() => setDebouncedEntityKey(entityKey.trim()), 300)
    return () => window.clearTimeout(t)
  }, [entityKey])

  useEffect(() => {
    let cancelled = false
    // eslint-disable-next-line react-hooks/set-state-in-effect -- flip loading spinner before async audit-log fetch
    setLoading(true)
    auditLogService
      .list({
        actor: actor || undefined,
        entityType: entityType || undefined,
        action: action || undefined,
        entityKey: debouncedEntityKey || undefined,
        category: category || undefined,
        orderNo: orderNoFilter.trim() !== '' && !Number.isNaN(Number(orderNoFilter))
          ? Number(orderNoFilter) : undefined,
        since: since || undefined,
        until: until || undefined,
        sort: sortParam,
        page: pageIndex,
        size: pageSize,
      })
      .then((r) => {
        if (cancelled) return
        setRows(r.data?.content ?? [])
        setTotalElements(r.data?.totalElements ?? 0)
        setTotalPages(Math.max(r.data?.totalPages ?? 1, 1))
      })
      .catch((err: unknown) => {
        if (cancelled) return
        notify.apiError(err, 'Failed to load the audit log.')
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [actor, entityType, action, debouncedEntityKey, category, orderNoFilter, since, until, sortParam, pageIndex, pageSize, reloadToken])

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- reset to first page when filters change; must respond to prop-derived filter values, not derivable at render
    setPageIndex(0)
  }, [actor, entityType, action, debouncedEntityKey, category, orderNoFilter, since, until, sortParam, pageSize])

  const refresh = useCallback(() => setReloadToken((t) => t + 1), [])
  useEffect(() => {
    registerRefresh(refresh)
    return () => registerRefresh(null)
  }, [registerRefresh, refresh])

  const toggleExpand = (id: number) => {
    setExpanded((cur) => {
      const next = new Set(cur)
      if (next.has(id)) next.delete(id)
      else next.add(id)
      return next
    })
  }

  const columns = useMemo<ColumnDef<AuditLogEntry, unknown>[]>(
    () => [
      {
        id: 'expand',
        header: '',
        enableSorting: false,
        cell: ({ row }) => {
          const open = expanded.has(row.original.id)
          return (
            <button
              type="button"
              onClick={() => toggleExpand(row.original.id)}
              aria-label={open ? 'Collapse' : 'Expand'}
              className="inline-flex h-6 w-6 items-center justify-center rounded text-slate-500 hover:bg-slate-100"
            >
              {open ? <FiChevronDown className="h-3.5 w-3.5" /> : <FiChevronRight className="h-3.5 w-3.5" />}
            </button>
          )
        },
        meta: { headerLabel: '', hideable: false, exportable: false },
      },
      {
        id: 'createdAt',
        accessorFn: (r) => r.createdAt,
        header: 'When',
        cell: ({ row }) => (
          <span className="text-[11.5px] text-slate-600">
            {formatTime(row.original.createdAt)}
          </span>
        ),
      },
      {
        id: 'actor',
        accessorFn: (r) => r.actor ?? '',
        header: 'Actor',
        cell: ({ row }) => (
          <span className="inline-flex items-center gap-1 text-[12px] text-slate-700">
            <FiUser className="h-3 w-3 text-slate-400" />
            {row.original.actor ?? <span className="italic text-slate-400">system</span>}
          </span>
        ),
      },
      {
        id: 'action',
        accessorFn: (r) => r.action,
        header: 'Action',
        cell: ({ row }) => (
          <span className="inline-flex items-center gap-1.5">
            {/* Severity dot — red for ERROR, amber for WARN, none for INFO. */}
            {row.original.severity === 'ERROR' ? (
              <span title="Error" className="h-1.5 w-1.5 shrink-0 rounded-full bg-rose-500" />
            ) : row.original.severity === 'WARN' ? (
              <span title="Warning" className="h-1.5 w-1.5 shrink-0 rounded-full bg-amber-500" />
            ) : null}
            <ActionBadge action={row.original.action} />
          </span>
        ),
      },
      {
        id: 'orderNo',
        accessorFn: (r) => r.orderNo ?? '',
        header: 'Order',
        enableSorting: false,
        cell: ({ row }) =>
          row.original.orderNo != null ? (
            <a
              href={`/label/${row.original.orderNo}`}
              className="font-mono text-[11.5px] font-semibold text-[#412d15] underline-offset-2 hover:underline"
            >
              #{row.original.orderNo}
            </a>
          ) : (
            <span className="text-slate-300">—</span>
          ),
      },
      {
        id: 'entityType',
        accessorFn: (r) => r.entityType,
        header: 'Type',
        cell: ({ row }) => (
          <span className="inline-flex items-center rounded bg-slate-100 px-1.5 py-0.5 text-[10.5px] font-semibold text-slate-700">
            {row.original.entityType}
          </span>
        ),
      },
      {
        id: 'entityKey',
        accessorFn: (r) => r.entityKey ?? '',
        header: 'Entity',
        cell: ({ row }) => (
          <span className="font-mono text-[11.5px] text-slate-700">
            {row.original.entityKey ?? row.original.entityId ?? '—'}
          </span>
        ),
      },
      {
        id: 'notes',
        accessorFn: (r) => r.notes ?? '',
        header: 'Notes',
        enableSorting: false,
        cell: ({ row }) => {
          const raw = row.original.notes
          if (!raw) return <span className="text-[11.5px] text-slate-600">—</span>
          // Safety net for legacy rows persisted BEFORE the server-side
          // humanizer: a raw carrier payload ("… HTTP 400: {json}") renders
          // as a clean sentence, with the raw text kept in the tooltip.
          const looksRaw = /HTTP\s*\d{3}/.test(raw) || raw.includes('{')
          const shown = looksRaw ? summarizeCarrierError(raw) : raw
          return (
            <span className="text-[11.5px] text-slate-600" title={looksRaw ? raw : undefined}>
              {shown}
            </span>
          )
        },
      },
    ],
    [expanded],
  )

  const filtersActive = !!(actor || entityType || action || entityKey || orderNoFilter || since || until)

  return (
    <div className="space-y-4">
      {/* Category tabs — the client's three logs plus All, one common table. */}
      <div className="flex flex-wrap items-center gap-1.5">
        {([
          { key: '', label: 'All logs' },
          { key: 'ERROR', label: 'Errors' },
          { key: 'SHIPMENT', label: 'Shipments' },
          { key: 'ACTIVITY', label: 'User activity' },
        ] as { key: string; label: string }[]).map((t) => {
          const active = category === t.key
          return (
            <button
              key={t.key || 'all'}
              type="button"
              onClick={() => setCategory(t.key)}
              className={`rounded-xl px-3.5 py-1.5 text-[12.5px] font-semibold transition ${
                active
                  ? 'bg-[#1f150c] text-[#f4eede]'
                  : 'border border-slate-200 bg-white text-slate-700 hover:bg-slate-50'
              } ${t.key === 'ERROR' && !active ? 'text-rose-700' : ''}`}
            >
              {t.label}
            </button>
          )
        })}
        <input
          value={orderNoFilter}
          onChange={(e) => setOrderNoFilter(e.target.value.replace(/[^0-9]/g, ''))}
          placeholder="Order #…"
          inputMode="numeric"
          className="ml-auto w-28 rounded-xl border border-slate-200 bg-white px-3 py-1.5 text-[12px] outline-none focus:border-[#412d15]"
          title="Show every log entry for one order"
        />
      </div>
      <section className="rounded-2xl border border-slate-200 bg-white p-5 shadow-sm">
        {loading && !rows.length ? (
          <p className="py-10 text-center text-sm text-slate-500">Loading audit trail…</p>
        ) : (
          <AdvancedDataTable<AuditLogEntry>
            tableKey="audit-log"
            columns={columns}
            data={rows}
            manualPagination
            manualSorting
            pageIndex={pageIndex}
            pageSize={pageSize}
            pageCount={totalPages}
            sorting={sorting}
            onSortingChange={setSorting}
            onPaginationChange={(next) => {
              setPageIndex(next.pageIndex)
              setPageSize(next.pageSize)
            }}
            search={{
              value: entityKey,
              onChange: setEntityKey,
              placeholder: 'Search entity key (client code, warehouse code, …)',
            }}
            filterToggle={
              /* Audit R2 #359 — outer wrapper is the ref target so a click
                  on either the toggle button or the dropdown counts as
                  "inside" and doesn't close the panel. */
              <div className="relative" ref={filterPanelRef}>
                <button
                  type="button"
                  onClick={() => setShowFilters((v) => !v)}
                  aria-haspopup="true"
                  aria-expanded={showFilters}
                  aria-controls="audit-log-filter-panel"
                  className={`inline-flex items-center gap-1.5 rounded-md border px-3 py-1.5 text-[12.5px] font-semibold transition ${
                    filtersActive
                      ? 'border-[#1f150c] bg-[#1f150c] text-white'
                      : 'border-slate-200 bg-white text-slate-700 hover:bg-slate-50'
                  }`}
                >
                  <FiFilter className="h-3.5 w-3.5" />
                  Filters
                  {filtersActive ? (
                    <span className="rounded-full bg-white/20 px-1.5 text-[10px] font-bold">
                      {[actor, entityType, action, since, until].filter(Boolean).length}
                    </span>
                  ) : null}
                </button>
                {showFilters ? (
                  <div
                    id="audit-log-filter-panel"
                    role="region"
                    aria-label="Audit log filters"
                    className="absolute right-0 z-30 mt-1 w-80 rounded-lg border border-slate-200 bg-white p-3 shadow-lg"
                  >
                    <label className="mb-1 block text-[10px] font-bold uppercase tracking-[0.14em] text-slate-400">
                      Actor (username)
                    </label>
                    <input
                      value={actor}
                      onChange={(e) => setActor(e.target.value)}
                      placeholder="e.g. smoketest"
                      className="mb-3 w-full rounded-md border border-slate-300 px-2 py-1 text-[12.5px]"
                    />
                    <label className="mb-1 block text-[10px] font-bold uppercase tracking-[0.14em] text-slate-400">
                      Entity type
                    </label>
                    <Select value={entityType} onChange={(e) => setEntityType(e.target.value)}>
                      <option value="">All types</option>
                      <option value="CLIENT">Client</option>
                      <option value="WAREHOUSE">Warehouse</option>
                      <option value="CARRIER_ACCOUNT">Carrier account</option>
                      <option value="LABEL_TEMPLATE">Label template</option>
                      <option value="ROUTING_RULE">Routing rule</option>
                      <option value="CODE_MAP">Code map</option>
                      <option value="CUSTOMS_PROFILE">Customs profile</option>
                      <option value="CLIENT_POLICY">Client policy</option>
                      <option value="CLIENT_MARKUP">Client markup</option>
                    </Select>
                    <label className="mb-1 mt-3 block text-[10px] font-bold uppercase tracking-[0.14em] text-slate-400">
                      Action
                    </label>
                    <Select value={action} onChange={(e) => setAction(e.target.value)}>
                      <option value="">All actions</option>
                      <option value="CREATE">CREATE</option>
                      <option value="UPDATE">UPDATE</option>
                      <option value="DELETE">DELETE</option>
                      <option value="TOGGLE_ACTIVE">TOGGLE_ACTIVE</option>
                      <option value="CASCADE_DISABLE">CASCADE_DISABLE</option>
                      <option value="CASCADE_ENABLE">CASCADE_ENABLE</option>
                    </Select>
                    {/* Audit A2 — date-range filters mirror the backend's
                        since/until params (already supported, no UI pre-fix).
                        Empty = unbounded on that side. */}
                    <label className="mb-1 mt-3 block text-[10px] font-bold uppercase tracking-[0.14em] text-slate-400">
                      Since
                    </label>
                    <input
                      type="datetime-local"
                      value={since}
                      onChange={(e) => setSince(e.target.value)}
                      className="mb-3 w-full rounded-md border border-slate-300 px-2 py-1 text-[12.5px]"
                    />
                    <label className="mb-1 block text-[10px] font-bold uppercase tracking-[0.14em] text-slate-400">
                      Until
                    </label>
                    <input
                      type="datetime-local"
                      value={until}
                      onChange={(e) => setUntil(e.target.value)}
                      className="mb-3 w-full rounded-md border border-slate-300 px-2 py-1 text-[12.5px]"
                    />
                    {filtersActive ? (
                      <button
                        type="button"
                        onClick={() => {
                          setActor('')
                          setEntityType('')
                          setAction('')
                          setSince('')
                          setUntil('')
                        }}
                        className="mt-3 inline-flex items-center gap-1 text-[11.5px] font-semibold text-slate-600 hover:text-slate-950"
                      >
                        <FiX className="h-3 w-3" /> Clear filters
                      </button>
                    ) : null}
                  </div>
                ) : null}
              </div>
            }
            emptyState={
              <div className="py-10 text-center text-[12.5px] text-slate-500">
                No audit entries match your filters.
              </div>
            }
            caption={
              <span className="text-[11.5px] text-slate-500">
                {totalElements} entrie{totalElements === 1 ? '' : 's'}
              </span>
            }
          />
        )}
        {rows.length > 0 ? (
          <div className="mt-3 space-y-2">
            {rows.filter((r) => expanded.has(r.id)).map((r) => (
              <details key={r.id} open className="rounded-lg border border-slate-200 bg-slate-50 p-3">
                <summary className="cursor-pointer text-[11.5px] font-semibold text-slate-700">
                  Row #{r.id} · {r.entityType} · {r.action} · {r.entityKey ?? r.entityId}
                </summary>
                <pre className="mt-2 max-h-72 overflow-auto rounded bg-white p-2 font-mono text-[10.5px] text-slate-800">
                  {prettyJson(r.changes)}
                </pre>
              </details>
            ))}
          </div>
        ) : null}
      </section>
    </div>
  )
}

function formatTime(iso: string): string {
  if (!iso) return '—'
  try {
    // Explicit en-US — the bare toLocaleString() rendered 01/09/2026 (DD/MM)
    // on some machines while the rest of the app prints "Sep 1, 2026".
    return new Date(iso).toLocaleString('en-US', {
      month: 'short', day: 'numeric', year: 'numeric', hour: 'numeric', minute: '2-digit',
    })
  } catch {
    return iso
  }
}

function prettyJson(raw: string | null): string {
  if (!raw) return '(no payload)'
  try {
    return JSON.stringify(JSON.parse(raw), null, 2)
  } catch {
    return raw
  }
}

function ActionBadge({ action }: { action: string }) {
  const tone: Record<string, string> = {
    CREATE: 'bg-emerald-100 text-emerald-800',
    UPDATE: 'bg-sky-100 text-sky-800',
    DELETE: 'bg-rose-100 text-rose-800',
    TOGGLE_ACTIVE: 'bg-slate-100 text-slate-700',
    CASCADE_DISABLE: 'bg-amber-100 text-amber-800',
    CASCADE_ENABLE: 'bg-emerald-100 text-emerald-800',
  }
  const cls = tone[action] ?? 'bg-slate-100 text-slate-700'
  return (
    <span className={`inline-flex items-center rounded px-1.5 py-0.5 text-[10.5px] font-semibold ${cls}`}>
      {action}
    </span>
  )
}
