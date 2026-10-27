import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { formatDateTime } from '../utils/formatDateTime'
import { useOutletContext } from 'react-router-dom'
import {
  FiDownload,
  FiFilter,
  FiUser,
  FiX,
} from 'react-icons/fi'
import { useAppSession } from '../hooks/useAppSession'
import type { SortingState } from '@tanstack/react-table'
import {
  auditLogService,
  type AuditLogEntry,
} from '../api/auditLogService'
import type { SettingsOutletContext } from './layout/SettingsLayout'
import AdvancedDataTable, { type ColumnDef } from './workspace/AdvancedDataTable'
import { summarizeCarrierError } from '../utils/carrierErrorMap'
import Select from './workspace/Select'
import { notify } from '../utils/notify'

/**
 * BUG-110 R7 — the Type / Action columns rendered raw server enums
 * ('CARRIER_ACCOUNT', 'LABEL_GENERATED'). Operators were asking what
 * "LABEL_VOID_REFUSED" was supposed to mean. Humanize the common ones
 * (same codes already listed in the filter dropdowns); unknown codes
 * fall back to the server string so a new category can't disappear
 * from the UI if we forget to update the map.
 */
const ENTITY_TYPE_LABELS: Record<string, string> = {
  CARRIER_ACCOUNT: 'Carrier account',
  LABEL_TEMPLATE: 'Label template',
  ROUTING_RULE: 'Routing rule',
  CODE_MAP: 'Code map',
  CUSTOMS_PROFILE: 'Customs profile',
  CLIENT_POLICY: 'Client policy',
  CLIENT_MARKUP: 'Client markup',
  IMPORT_BATCH: 'Import batch',
  LABEL: 'Label',
  USER: 'User',
  // BUG-157 — six writes that now leave a trail.
  API_KEY: 'API key',
  EMAIL_TEMPLATE: 'Email template',
  STATE_ZONE: 'State zone',
  CARRIER_LIMIT: 'Carrier limit',
  WEBHOOK_SUBSCRIPTION: 'Webhook subscription',
  SCHEDULED_REPORT: 'Scheduled export',
}
const ACTION_LABELS: Record<string, string> = {
  LABEL_GENERATED: 'Label generated',
  LABEL_VOIDED: 'Label voided',
  LABEL_REGENERATED: 'Label regenerated',
  LABEL_VOID_REFUSED: 'Void refused',
  CARRIER_REJECTED: 'Carrier rejected',
  IMPORT_SAVED: 'Import saved',
  IMPORT_GENERATED: 'Import generated',
  LOGIN: 'Login',
  LOGOUT: 'Logout',
  LOGIN_FAILED: 'Login failed',
  CREATE: 'Create',
  UPDATE: 'Update',
  DELETE: 'Delete',
  ATTACH_WAREHOUSE: 'Attach warehouse',
  DETACH_WAREHOUSE: 'Detach warehouse',
}
function humaniseEntityType(v: string | null | undefined): string {
  if (!v) return ''
  return ENTITY_TYPE_LABELS[v] ?? v.replace(/_/g, ' ').toLowerCase().replace(/^\w/, (c) => c.toUpperCase())
}
function humaniseAction(v: string | null | undefined): string {
  if (!v) return ''
  return ACTION_LABELS[v] ?? v.replace(/_/g, ' ').toLowerCase().replace(/^\w/, (c) => c.toUpperCase())
}

/**
 * Read-only feed of settings writes. Filter by actor / entity type /
 * action / entity key / date range. Row-level expand shows the
 * changes JSON pretty-printed.
 */
/** datetime-local value (operator's zone) → ISO-8601 UTC, or undefined when blank. */
const toUtcIso = (local: string): string | undefined => {
  if (!local) return undefined
  const d = new Date(local)
  return Number.isNaN(d.getTime()) ? undefined : d.toISOString()
}

export default function AuditLogPage() {
  const { registerRefresh } = useOutletContext<SettingsOutletContext>()
  // Audit B6 (#357) — Export CSV button only renders for ADMIN; endpoint
  // 403s otherwise + hiding the button prevents a confusing failure.
  const session = useAppSession()
  const isAdmin = session.role?.toUpperCase() === 'ADMIN'

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
  // BUG-159 — keep what was typed; only a whole order number reaches the
  // server, anything else is explained under the box (same as Alerts History).
  const orderNoError = orderNoFilter.trim() && !/^\d{1,18}$/.test(orderNoFilter.trim())
    ? 'Order number must be a whole number.'
    : null
  const orderNoParam = orderNoFilter.trim() && !orderNoError ? Number(orderNoFilter.trim()) : undefined
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
    if (orderNoError) return
    // eslint-disable-next-line react-hooks/set-state-in-effect -- flip loading spinner before async audit-log fetch
    setLoading(true)
    auditLogService
      .list({
        actor: actor || undefined,
        entityType: entityType || undefined,
        action: action || undefined,
        entityKey: debouncedEntityKey || undefined,
        category: category || undefined,
        orderNo: orderNoParam,
        // BUG-156 — the boxes hold the operator's local time; send it with its
        // zone so the server compares instants instead of wall-clock strings.
        since: toUtcIso(since),
        until: toUtcIso(until),
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
  }, [actor, entityType, action, debouncedEntityKey, category, orderNoParam, orderNoError, since, until, sortParam, pageIndex, pageSize, reloadToken])

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- reset to first page when filters change; must respond to prop-derived filter values, not derivable at render
    setPageIndex(0)
  }, [actor, entityType, action, debouncedEntityKey, category, orderNoParam, since, until, sortParam, pageSize])

  const refresh = useCallback(() => setReloadToken((t) => t + 1), [])
  useEffect(() => {
    registerRefresh(refresh)
    return () => registerRefresh(null)
  }, [registerRefresh, refresh])

  const columns = useMemo<ColumnDef<AuditLogEntry, unknown>[]>(
    () => [
      {
        id: 'createdAt',
        accessorFn: (r) => r.createdAt,
        header: 'When',
        // BUG-158 — exports carry the UTC stamp, same column as the server CSV.
        meta: { headerLabel: 'When (UTC)', exportValue: (r: AuditLogEntry) => new Date(r.createdAt).toISOString() },
        cell: ({ row }) => (
          <span className="text-[11.5px] text-slate-600">
            {formatDateTime(row.original.createdAt)}
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
          <span
            className="inline-flex items-center rounded bg-slate-100 px-1.5 py-0.5 text-[10.5px] font-semibold text-slate-700"
            title={row.original.entityType}
          >
            {humaniseEntityType(row.original.entityType)}
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
      // BUG-158 — hidden on screen, always in the CSV, so "Export" and
      // "Export all" produce the same 13 columns (see AuditLogController).
      ...([
        ['id', 'ID', (r: AuditLogEntry) => r.id],
        ['entityId', 'Entity ID', (r: AuditLogEntry) => r.entityId ?? ''],
        ['clientCode', 'Client', (r: AuditLogEntry) => r.clientCode ?? ''],
        ['category', 'Category', (r: AuditLogEntry) => r.category],
        ['severity', 'Severity', (r: AuditLogEntry) => r.severity],
        ['changes', 'Changes', (r: AuditLogEntry) => r.changes ?? ''],
      ] as const).map(([id, header, value]) => ({
        id,
        accessorFn: value,
        header,
        enableSorting: false,
        meta: { exportAlways: true },
        cell: ({ row }: { row: { original: AuditLogEntry } }) => <span className="text-[11.5px] text-slate-600">{String(value(row.original) ?? '')}</span>,
      })),
    ],
    [],
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
        <label className="ml-auto flex flex-col">
          <input
            value={orderNoFilter}
            onChange={(e) => setOrderNoFilter(e.target.value)}
            placeholder="Order #…"
            inputMode="numeric"
            aria-invalid={orderNoError ? true : undefined}
            className={`w-28 rounded-xl border bg-white px-3 py-1.5 text-[12px] outline-none focus:border-[#412d15] ${orderNoError ? 'border-rose-400' : 'border-slate-200'}`}
            title="Show every log entry for one order"
          />
          {orderNoError ? <span className="mt-1 text-[11px] text-rose-700">{orderNoError}</span> : null}
        </label>
      </div>
      <section className="rounded-2xl border border-slate-200 bg-white p-5 shadow-sm">
        {loading && !rows.length ? (
          <p className="py-10 text-center text-sm text-slate-500">Loading audit trail…</p>
        ) : (
          <AdvancedDataTable<AuditLogEntry>
            tableKey="audit-log"
            columns={columns}
            initialHiddenColumns={['id', 'entityId', 'clientCode', 'category', 'severity', 'changes']}
            renderExpanded={(r) => <ChangeDetails row={r} />}
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
              <div className="relative flex items-center gap-2" ref={filterPanelRef}>
                {isAdmin ? (
                  /* Audit B6 (#357) — CSV export with the live filter set.
                     <a href> lets the browser handle the download dialog
                     + Content-Disposition filename without a Blob roundtrip. */
                  <a
                    href={auditLogService.exportUrl({
                      actor, entityType, action, entityKey: debouncedEntityKey, category,
                      orderNo: orderNoParam,
                      since, until,
                    })}
                    title={`Export EVERY filtered row as CSV (ADMIN only; capped at 100k rows). The 'Export' button beside this one saves only the current page.`}
                    className="inline-flex items-center gap-1.5 rounded-md border border-slate-200 bg-white px-3 py-1.5 text-[12.5px] font-semibold text-slate-700 transition hover:bg-slate-50"
                  >
                    <FiDownload className="h-3.5 w-3.5" />
                    {/* BUG-127 — Export CSV = full filtered set; the
                        AdvancedDataTable Export button beside it saves
                        the visible page. Rename so operators can tell
                        which does which. */}
                    Export all (CSV)
                  </a>
                ) : null}
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
                    className="absolute right-0 top-full z-30 mt-1 w-80 rounded-lg border border-slate-200 bg-white p-3 shadow-lg"
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
                    {/* BUG-129 — Entity type + Action lists must match the
                        actual rows the log stores. Added: AUTH, ORDER (which
                        dominate the Shipments tab), USER. Action list adds
                        LOGIN, LOGOUT, LABEL_GENERATED, LABEL_VOIDED. The
                        6 pre-fix 'offered but never occur' entries stay in
                        case rare rows do appear. */}
                    <Select value={entityType} onChange={(e) => setEntityType(e.target.value)}>
                      <option value="">All types</option>
                      <option value="AUTH">Auth</option>
                      <option value="ORDER">Order</option>
                      <option value="USER">User</option>
                      <option value="CLIENT">Client</option>
                      <option value="WAREHOUSE">Warehouse</option>
                      <option value="CARRIER_ACCOUNT">Carrier account</option>
                      <option value="LABEL_TEMPLATE">Label template</option>
                      <option value="ROUTING_RULE">Routing rule</option>
                      <option value="CODE_MAP">Code map</option>
                      <option value="CUSTOMS_PROFILE">Customs profile</option>
                      <option value="CLIENT_POLICY">Client policy</option>
                      <option value="CLIENT_MARKUP">Client markup</option>
                      {/* BUG-129 R6+1: AuditService also writes under
                          IMPORT_BATCH — add it so the filter matches
                          every entity type the backend produces. */}
                      <option value="IMPORT_BATCH">Import batch</option>
                      <option value="API_KEY">API key</option>
                      <option value="EMAIL_TEMPLATE">Email template</option>
                      <option value="STATE_ZONE">State zone</option>
                      <option value="CARRIER_LIMIT">Carrier limit</option>
                      <option value="WEBHOOK_SUBSCRIPTION">Webhook subscription</option>
                      <option value="SCHEDULED_REPORT">Scheduled export</option>
                    </Select>
                    <label className="mb-1 mt-3 block text-[10px] font-bold uppercase tracking-[0.14em] text-slate-400">
                      Action
                    </label>
                    <Select value={action} onChange={(e) => setAction(e.target.value)}>
                      <option value="">All actions</option>
                      <option value="CREATE">CREATE</option>
                      <option value="UPDATE">UPDATE</option>
                      <option value="DELETE">DELETE</option>
                      <option value="LOGIN">LOGIN</option>
                      <option value="LOGOUT">LOGOUT</option>
                      <option value="LABEL_GENERATED">LABEL_GENERATED</option>
                      <option value="LABEL_VOIDED">LABEL_VOIDED</option>
                      <option value="LABEL_REGENERATED">LABEL_REGENERATED</option>
                      <option value="LABEL_VOID_REFUSED">LABEL_VOID_REFUSED</option>
                      <option value="CARRIER_REJECTED">CARRIER_REJECTED</option>
                      <option value="IMPORT_SAVED">IMPORT_SAVED</option>
                      <option value="IMPORT_GENERATED">IMPORT_GENERATED</option>
                      <option value="LOGIN_FAILED">LOGIN_FAILED</option>
                      <option value="TOGGLE_ACTIVE">TOGGLE_ACTIVE</option>
                      <option value="CASCADE_DISABLE">CASCADE_DISABLE</option>
                      <option value="CASCADE_ENABLE">CASCADE_ENABLE</option>
                      {/* BUG-129 R7: ClientWarehouseServiceImpl writes
                          ATTACH_WAREHOUSE / DETACH_WAREHOUSE rows that
                          the picker didn't surface. */}
                      <option value="ATTACH_WAREHOUSE">ATTACH_WAREHOUSE</option>
                      <option value="DETACH_WAREHOUSE">DETACH_WAREHOUSE</option>
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
      </section>
    </div>
  )
}

/** BUG-160 — the row's changes, readable: a before/after table when the
 *  payload has one, else key/value rows; ISO timestamps become local time. */
function ChangeDetails({ row }: { row: AuditLogEntry }) {
  const parsed = parseChanges(row.changes)
  if (parsed == null) {
    return <p className="px-3 py-2 text-[11.5px] text-slate-500">{row.changes ?? 'No details were recorded for this row.'}</p>
  }
  const before = pick(parsed, ['before', 'old', 'previous'])
  const after = pick(parsed, ['after', 'new', 'current'])
  const twoColumn = before != null || after != null
  const keys = twoColumn
    ? Array.from(new Set([...Object.keys(before ?? {}), ...Object.keys(after ?? {})]))
    : Object.keys(parsed)
  return (
    <div className="px-3 py-2">
      <table className="w-full text-[11.5px]">
        <thead className="text-[10.5px] uppercase tracking-wide text-slate-500">
          <tr><th className="py-1 pr-3 text-left">Field</th>{twoColumn ? <th className="py-1 pr-3 text-left">Before</th> : null}<th className="py-1 text-left">{twoColumn ? 'After' : 'Value'}</th></tr>
        </thead>
        <tbody>
          {keys.map((k) => {
            const a = twoColumn ? after?.[k] : parsed[k]
            const b = twoColumn ? before?.[k] : undefined
            const changed = twoColumn && JSON.stringify(a) !== JSON.stringify(b)
            return (
              <tr key={k} className={changed ? 'font-semibold text-slate-900' : 'text-slate-700'}>
                <td className="py-0.5 pr-3 font-mono">{k}</td>
                {twoColumn ? <td className="py-0.5 pr-3">{display(b)}</td> : null}
                <td className="py-0.5">{display(a)}</td>
              </tr>
            )
          })}
        </tbody>
      </table>
    </div>
  )
}

function parseChanges(raw: string | null): Record<string, unknown> | null {
  if (!raw) return null
  try {
    const v: unknown = JSON.parse(raw)
    return v && typeof v === 'object' && !Array.isArray(v) ? (v as Record<string, unknown>) : null
  } catch {
    return null
  }
}

function pick(o: Record<string, unknown>, names: string[]): Record<string, unknown> | null {
  for (const n of names) {
    const v = o[n]
    if (v && typeof v === 'object' && !Array.isArray(v)) return v as Record<string, unknown>
  }
  return null
}

const ISO = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}/

function display(v: unknown): string {
  if (v == null || v === '') return '—'
  if (typeof v === 'string') return ISO.test(v) ? formatDateTime(v) : v
  if (typeof v === 'object') return JSON.stringify(v)
  return String(v)
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
    <span
      className={`inline-flex items-center rounded px-1.5 py-0.5 text-[10.5px] font-semibold ${cls}`}
      title={action}
    >
      {humaniseAction(action)}
    </span>
  )
}
