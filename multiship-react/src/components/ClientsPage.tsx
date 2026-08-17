import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useNavigate, useOutletContext } from 'react-router-dom'
import { notify } from '../utils/notify'
import {
  FiActivity,
  FiEdit3,
  FiFilter,
  FiGlobe,
  FiHash,
  FiMoreVertical,
  FiPlus,
  FiTag,
  FiTrash2,
  FiTruck,
  FiUsers,
  FiX,
} from 'react-icons/fi'
import type { ColumnDef, SortingState } from '@tanstack/react-table'
import { clientService, type Client } from '../api/clientService'
import { formatCarrierName } from '../utils/carrierUtils'
import { countryName } from '../utils/countries'
import PortalMenu from './workspace/PortalMenu'
import CustomsProfileModal from './modals/CustomsProfileModal'
import type { SettingsOutletContext } from './layout/SettingsLayout'
import AdvancedDataTable from './workspace/AdvancedDataTable'
import Select from './workspace/Select'
import { useAppSession } from '../hooks/useAppSession'
import { normalizeRole } from '../utils/roles'

const filterLabelClass =
  'mb-1 flex items-center gap-1 text-[10px] font-bold uppercase tracking-[0.14em] text-slate-400'

export default function ClientsPage() {
  // Sprint 51 FE-L1 — read from the session store instead of localStorage
  // on every render; the store is cached + change-notified.
  const { role } = useAppSession()
  const admin = normalizeRole(role) === 'ADMIN'
  const navigate = useNavigate()

  const [clients, setClients] = useState<Client[]>([])
  const [loading, setLoading] = useState(true)
  const [totalElements, setTotalElements] = useState(0)
  const [totalPages, setTotalPages] = useState(1)

  const [search, setSearch] = useState('')
  const [debouncedSearch, setDebouncedSearch] = useState('')
  const [statusFilter, setStatusFilter] = useState('')
  const [carrierFilter, setCarrierFilter] = useState('')
  const [ordersFilter, setOrdersFilter] = useState('')
  const emptyCols = { code: '', name: '', city: '' }
  const [colFilters, setColFilters] = useState(emptyCols)
  const [debouncedCols, setDebouncedCols] = useState(emptyCols)
  const [showFilters, setShowFilters] = useState(false)
  const filtersRef = useRef<HTMLDivElement>(null)

  // Sort + pagination — controlled here and passed to the server on each fetch.
  const [sorting, setSorting] = useState<SortingState>([{ id: 'code', desc: false }])
  const [pageIndex, setPageIndex] = useState(0)
  const [pageSize, setPageSize] = useState(25)
  const [busyId, setBusyId] = useState<number | null>(null)

  const [reloadToken, setReloadToken] = useState(0)
  const [customsClient, setCustomsClient] = useState<Client | null>(null)

  useEffect(() => {
    const t = setTimeout(() => setDebouncedSearch(search.trim()), 350)
    return () => clearTimeout(t)
  }, [search])
  useEffect(() => {
    const t = setTimeout(() => setDebouncedCols(colFilters), 350)
    return () => clearTimeout(t)
  }, [colFilters])
  // Snap back to first page whenever the effective filter/sort set changes.
  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- reset paging when filters/sort change; must respond to prop-derived filter values, not derivable at render
    setPageIndex(0)
  }, [debouncedSearch, statusFilter, carrierFilter, ordersFilter, debouncedCols, sorting, pageSize])

  // Close the Filters popover on outside click / Escape.
  useEffect(() => {
    if (!showFilters) return
    const onDocClick = (e: MouseEvent) => {
      if (!filtersRef.current?.contains(e.target as Node)) setShowFilters(false)
    }
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setShowFilters(false)
    }
    document.addEventListener('mousedown', onDocClick)
    document.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('mousedown', onDocClick)
      document.removeEventListener('keydown', onKey)
    }
  }, [showFilters])

  // Server params derived from state: sort → sortBy + sortDirection.
  const sortBy = sorting[0]?.id ?? 'code'
  const sortDirection: 'ASC' | 'DESC' = sorting[0]?.desc ? 'DESC' : 'ASC'

  useEffect(() => {
    let cancelled = false
    // eslint-disable-next-line react-hooks/set-state-in-effect -- flip loading spinner before async paginated client-list fetch
    setLoading(true)
    clientService
      .listClients({
        search: debouncedSearch || undefined,
        status: statusFilter || undefined,
        carrier: carrierFilter || undefined,
        hasOrders: ordersFilter || undefined,
        code: debouncedCols.code || undefined,
        name: debouncedCols.name || undefined,
        city: debouncedCols.city || undefined,
        sortBy,
        sortDirection,
        page: pageIndex,
        size: pageSize,
      })
      .then((r) => {
        if (cancelled) return
        setClients(r.data?.content ?? [])
        setTotalElements(r.data?.totalElements ?? 0)
        setTotalPages(Math.max(r.data?.totalPages ?? 1, 1))
      })
      .catch((error) => {
        if (!cancelled) notify.apiError(error, 'Failed to load clients.')
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [debouncedSearch, statusFilter, carrierFilter, ordersFilter, debouncedCols, sortBy, sortDirection, pageIndex, pageSize, reloadToken])

  const refresh = useCallback(() => setReloadToken((t) => t + 1), [])

  const { registerRefresh } = useOutletContext<SettingsOutletContext>()
  useEffect(() => {
    registerRefresh(refresh)
    return () => registerRefresh(null)
  }, [registerRefresh, refresh])

  const activeFilterCount =
    (statusFilter ? 1 : 0) + (carrierFilter ? 1 : 0) + (ordersFilter ? 1 : 0) + Object.values(colFilters).filter(Boolean).length
  const clearFilters = () => {
    setStatusFilter('')
    setCarrierFilter('')
    setOrdersFilter('')
    setColFilters(emptyCols)
  }

  const handleToggleActive = async (client: Client) => {
    // Disable path goes through cascade-preview first: hard block on
    // pending orders, otherwise confirm with the caller so they know
    // how many carrier accounts / warehouses will be deactivated too.
    // Enable path is trivial — no cascade blockers.
    const goingInactive = client.status === 'ACTIVE'
    if (goingInactive) {
      try {
        const preview = (await clientService.cascadePreview(client.clientCode)).data
        if (!preview) throw new Error('Preview returned no data.')
        if (preview.pendingOrderCount > 0) {
          notify.error(
            `${client.clientCode} has ${preview.pendingOrderCount} pending order(s). ` +
              `Complete or void them before deactivating.`,
          )
          return
        }
        const summary =
          `Deactivating ${client.clientCode} will also disable:\n` +
          `• ${preview.activeCarrierAccountCount} carrier account(s)\n` +
          `• ${preview.clientOwnedWarehouseCount} client-owned warehouse(s)\n` +
          `• ${preview.clientWarehouseLinkCount} warehouse attachment(s)\n\n` +
          `Re-enabling later restores exactly these rows.`
        const ok = await notify.confirm(summary, {
          title: `Deactivate ${client.clientCode}?`,
          confirmLabel: 'Deactivate',
          danger: true,
        })
        if (!ok) return
      } catch (error) {
        notify.apiError(error, 'Failed to preview the cascade.')
        return
      }
    }

    setBusyId(client.id)
    try {
      const r = await clientService.toggleActive(client.clientCode)
      notify.success(`${client.clientCode} is now ${r.data.status}.`)
      refresh()
    } catch (error) {
      // CLIENT_HAS_ORDERS (race: someone created a pending order between
      // preview and toggle) is handled by the friendly-message map.
      notify.apiError(error, 'Failed to update the client status.')
    } finally {
      setBusyId(null)
    }
  }
  const handleDelete = async (client: Client) => {
    // Delete is PERMANENT — mirror the deactivate cascade-preview flow so the
    // operator sees exactly what will be removed (carriers, warehouses)
    // before the destructive step. Deactivate had this preview; delete
    // silently skipped it, letting operators nuke rows without warning.
    try {
      const preview = (await clientService.cascadePreview(client.clientCode)).data
      if (!preview) throw new Error('Preview returned no data.')
      if (preview.pendingOrderCount > 0) {
        notify.error(
          `${client.clientCode} has ${preview.pendingOrderCount} pending order(s). ` +
            `Complete or void them before deleting.`,
        )
        return
      }
      const summary =
        `PERMANENTLY delete ${client.clientCode}? This will also delete:\n` +
        `• ${preview.activeCarrierAccountCount} carrier account(s)\n` +
        `• ${preview.clientOwnedWarehouseCount} client-owned warehouse(s)\n` +
        `• ${preview.clientWarehouseLinkCount} warehouse attachment(s)\n\n` +
        `This cannot be undone. Use Deactivate instead if you want to keep the record.`
      const ok = await notify.confirm(summary, {
        title: `Delete ${client.clientCode}?`,
        confirmLabel: 'Delete permanently',
        danger: true,
      })
      if (!ok) return
    } catch (error) {
      notify.apiError(error, 'Failed to preview the cascade.')
      return
    }
    try {
      await clientService.deleteClient(client.clientCode)
      notify.success(`Client ${client.clientCode} deleted.`)
      // If the delete emptied the current page (not page 0), snap back
      // to the previous page so the operator doesn't land on an empty
      // paginated view.
      if (pageIndex > 0 && clients.length === 1) {
        setPageIndex((p) => Math.max(0, p - 1))
      }
      refresh()
    } catch (error) {
      // CLIENT_HAS_ORDERS is handled by the friendly-message map.
      notify.apiError(error, 'Failed to delete the client.')
    }
  }

  const handleExport = async () => {
    try {
      await clientService.exportClientsCsv({
        search: debouncedSearch || undefined,
        status: statusFilter || undefined,
        carrier: carrierFilter || undefined,
        hasOrders: ordersFilter || undefined,
        code: debouncedCols.code || undefined,
        name: debouncedCols.name || undefined,
        city: debouncedCols.city || undefined,
        sortBy,
        sortDirection,
      })
    } catch (error) {
      notify.apiError(error, 'Failed to export clients.')
    }
  }

  const columns = useMemo<ColumnDef<Client, unknown>[]>(
    () => [
      {
        id: 'code',
        accessorFn: (c) => c.clientCode,
        header: 'Code',
        cell: ({ row }) => (
          <span className="font-semibold text-slate-950">{row.original.clientCode}</span>
        ),
        meta: {
          headerLabel: 'Code',
          exportValue: (c: Client) => c.clientCode,
        },
      },
      {
        id: 'name',
        accessorFn: (c) => c.name,
        header: 'Name',
        cell: ({ row }) => {
          const client = row.original
          return (
            <div>
              <p className="font-medium text-slate-800">{client.name}</p>
              {client.shipFrom?.city ? (
                <p className="text-[11.5px] text-slate-500">
                  {client.shipFrom.city}
                  {client.shipFrom.state ? `, ${client.shipFrom.state}` : ''}
                </p>
              ) : null}
            </div>
          )
        },
        meta: {
          headerLabel: 'Name',
          exportValue: (c: Client) => c.name,
        },
      },
      {
        id: 'contact',
        accessorFn: (c) => c.email || c.phone || '',
        header: 'Contact',
        enableSorting: false,
        cell: ({ row }) => (
          <span className="text-[12px] text-slate-600">
            {row.original.email || row.original.phone || '—'}
          </span>
        ),
        meta: {
          headerLabel: 'Contact',
          exportValue: (c: Client) => c.email || c.phone || '',
        },
      },
      {
        id: 'country',
        accessorFn: (c) => c.shipFrom?.country ?? '',
        header: 'Country',
        enableSorting: false,
        cell: ({ row }) => {
          const country = row.original.shipFrom?.country
          return country ? (
            <span
              title={countryName(country)}
              className="inline-flex items-center rounded-full bg-slate-100 px-2 py-0.5 text-[11px] font-semibold text-slate-700"
            >
              {country.toUpperCase()}
            </span>
          ) : (
            <span className="text-slate-400">—</span>
          )
        },
        meta: {
          headerLabel: 'Country',
          exportValue: (c: Client) => c.shipFrom?.country ?? '',
        },
      },
      {
        id: 'accounts',
        header: 'Carrier accounts',
        enableSorting: false,
        cell: ({ row }) => {
          const list = row.original.carrierAccounts
          if (!list?.length) return <span className="text-[11.5px] text-slate-400">—</span>
          return (
            <span className="flex flex-wrap gap-1">
              {list.map((a) => (
                <span
                  key={a.id}
                  className={`inline-flex items-center rounded-full px-2 py-0.5 text-[10.5px] font-semibold ${
                    a.clientDefault ? 'bg-[#412d15]/10 text-[#412d15]' : 'bg-slate-100 text-slate-600'
                  }`}
                >
                  {formatCarrierName(a.carrierCode)}
                  {a.clientDefault ? ' ★' : ''}
                </span>
              ))}
            </span>
          )
        },
        meta: {
          headerLabel: 'Carrier accounts',
          exportValue: (c: Client) =>
            (c.carrierAccounts ?? [])
              .map((a) => `${formatCarrierName(a.carrierCode)}${a.clientDefault ? '*' : ''}`)
              .join('; '),
        },
      },
      {
        id: 'orderCount',
        accessorFn: (c) => c.orderCount,
        header: 'Orders',
        cell: ({ row }) => <span className="tabular-nums">{row.original.orderCount}</span>,
        meta: {
          headerLabel: 'Orders',
          exportValue: (c: Client) => c.orderCount,
        },
      },
      {
        id: 'status',
        accessorFn: (c) => c.status,
        header: 'Status',
        enableSorting: false,
        cell: ({ row }) => {
          const client = row.original
          const active = client.status === 'ACTIVE'
          return (
            <div className="flex items-center gap-2.5">
              <ActiveToggle
                active={active}
                busy={busyId !== null}
                disabled={!admin}
                onToggle={(e) => { e.stopPropagation(); void handleToggleActive(client) }}
              />
              <span
                className={`inline-flex items-center rounded-full px-2 py-0.5 text-[10.5px] font-semibold ${
                  active ? 'bg-emerald-100 text-emerald-700' : 'bg-slate-200 text-slate-600'
                }`}
              >
                {active ? 'Active' : 'Inactive'}
              </span>
            </div>
          )
        },
        meta: {
          headerLabel: 'Status',
          exportValue: (c: Client) => c.status,
        },
      },
      {
        id: 'actions',
        header: '',
        enableSorting: false,
        cell: ({ row }) => (
          <div className="text-right">
            <RowActionsMenu
              admin={admin}
              onImporter={() => setCustomsClient(row.original)}
              onEdit={() => navigate(`/settings/clients/${encodeURIComponent(row.original.clientCode)}`)}
              onDelete={() => void handleDelete(row.original)}
            />
          </div>
        ),
        meta: { headerLabel: 'Actions', hideable: false, exportable: false },
      },
    ],
    // Handlers close over admin + refresh + toggle/delete which are stable
    // per-render for our purposes; only busyId and admin actually affect cells.
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [admin, busyId],
  )

  return (
    <div className="space-y-4">
      <section className="rounded-2xl border border-slate-200 bg-white p-5 shadow-sm">
        {loading && !clients.length ? (
          <p className="py-10 text-center text-sm text-slate-500">Loading clients…</p>
        ) : (
          <AdvancedDataTable<Client>
            tableKey="clients"
            columns={columns}
            data={clients}
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
            onExport={handleExport}
            search={{
              value: search,
              onChange: setSearch,
              placeholder: 'Search code, name, city…',
            }}
            filterToggle={
              <div ref={filtersRef} className="relative">
                <button
                  type="button"
                  onClick={() => setShowFilters((cur) => !cur)}
                  aria-pressed={showFilters}
                  aria-expanded={showFilters}
                  aria-label="Filters"
                  title="Filters"
                  className={`inline-flex items-center gap-1.5 rounded-xl px-2.5 py-1.5 text-[12px] font-semibold transition md:px-3 ${
                    showFilters || activeFilterCount
                      ? 'bg-[#1f150c] text-white'
                      : 'border border-slate-200 bg-white text-slate-600 hover:bg-slate-50'
                  }`}
                >
                  <FiFilter className="h-3.5 w-3.5" />
                  <span className="hidden md:inline">Filters</span>
                  {activeFilterCount ? (
                    <span className="rounded-full bg-white/25 px-1.5 py-0.5 text-[10px] tabular-nums">
                      {activeFilterCount}
                    </span>
                  ) : null}
                </button>
                {showFilters ? (
                  <div
                    role="dialog"
                    aria-label="Filter clients"
                    className="absolute left-0 top-full z-30 mt-1.5 w-72 rounded-2xl border border-slate-200 bg-white p-3 shadow-[0_20px_60px_rgba(15,23,42,0.15)]"
                  >
                    <div className="space-y-2.5">
                      <div>
                        <span className={filterLabelClass}><FiHash className="h-3 w-3 text-sky-600" />Code contains</span>
                        <input
                          value={colFilters.code}
                          onChange={(e) => setColFilters((c) => ({ ...c, code: e.target.value }))}
                          placeholder="e.g. ARHDEV"
                          className="w-full rounded-xl border border-slate-200 bg-white px-3 py-2 text-[12.5px] font-semibold text-slate-950 outline-none transition focus:border-[#412d15]"
                        />
                      </div>
                      <div>
                        <span className={filterLabelClass}><FiTag className="h-3 w-3 text-[#412d15]" />Name contains</span>
                        <input
                          value={colFilters.name}
                          onChange={(e) => setColFilters((c) => ({ ...c, name: e.target.value }))}
                          placeholder="e.g. Modern Art"
                          className="w-full rounded-xl border border-slate-200 bg-white px-3 py-2 text-[12.5px] font-semibold text-slate-950 outline-none transition focus:border-[#412d15]"
                        />
                      </div>
                      <div>
                        <span className={filterLabelClass}><FiGlobe className="h-3 w-3 text-emerald-600" />City contains</span>
                        <input
                          value={colFilters.city}
                          onChange={(e) => setColFilters((c) => ({ ...c, city: e.target.value }))}
                          placeholder="e.g. Chicago"
                          className="w-full rounded-xl border border-slate-200 bg-white px-3 py-2 text-[12.5px] font-semibold text-slate-950 outline-none transition focus:border-[#412d15]"
                        />
                      </div>
                      <div>
                        <span className={filterLabelClass}><FiActivity className="h-3 w-3 text-sky-600" />Status</span>
                        <Select value={statusFilter} onChange={(e) => setStatusFilter(e.target.value)} aria-label="Filter by status">
                          <option value="">All statuses</option>
                          <option value="ACTIVE">Active</option>
                          <option value="INACTIVE">Inactive</option>
                        </Select>
                      </div>
                      <div>
                        <span className={filterLabelClass}><FiTruck className="h-3 w-3 text-violet-600" />Carrier</span>
                        <Select value={carrierFilter} onChange={(e) => setCarrierFilter(e.target.value)} aria-label="Filter by carrier">
                          <option value="">Any carrier</option>
                          <option value="UPS">Has UPS</option>
                          <option value="FEDEX">Has FedEx</option>
                          <option value="USPS">Has USPS</option>
                        </Select>
                      </div>
                      <div>
                        <span className={filterLabelClass}><FiUsers className="h-3 w-3 text-[#412d15]" />Orders</span>
                        <Select value={ordersFilter} onChange={(e) => setOrdersFilter(e.target.value)} aria-label="Filter by orders">
                          <option value="">All clients</option>
                          <option value="YES">With orders</option>
                          <option value="NO">No orders</option>
                        </Select>
                      </div>
                    </div>

                    <div className="mt-3 flex items-center justify-end gap-2 border-t border-slate-100 pt-2.5">
                      {activeFilterCount ? (
                        <button
                          type="button"
                          onClick={clearFilters}
                          className="inline-flex items-center gap-1 rounded-xl border border-slate-200 bg-white px-2.5 py-1.5 text-[11.5px] font-semibold text-slate-600 transition hover:bg-slate-50"
                        >
                          <FiX className="h-3.5 w-3.5" />
                          Clear
                        </button>
                      ) : null}
                      <button
                        type="button"
                        onClick={() => setShowFilters(false)}
                        className="inline-flex items-center gap-1 rounded-xl bg-[#1f150c] px-3 py-1.5 text-[11.5px] font-semibold text-white transition hover:bg-[#412d15]"
                      >
                        Done
                      </button>
                    </div>
                  </div>
                ) : null}
              </div>
            }
            toolbarActions={
              <button
                type="button"
                onClick={() => navigate('/settings/clients/new')}
                className="inline-flex items-center gap-1.5 rounded-xl bg-[#1f150c] px-3 py-1.5 text-[12px] font-semibold text-white transition hover:bg-[#412d15]"
              >
                <FiPlus className="h-3.5 w-3.5" /> Add Client
              </button>
            }
            caption={
              <p className="text-[11.5px] text-slate-400">
                Showing {clients.length} of {totalElements} client{totalElements === 1 ? '' : 's'}
              </p>
            }
            emptyState={
              debouncedSearch || activeFilterCount
                ? 'No clients match the current filters.'
                : 'No clients registered yet — add your first client.'
            }
            csvFilename="clients"
          />
        )}
      </section>


      {customsClient ? (
        <CustomsProfileModal
          clients={clients}
          lockedClientCode={customsClient.clientCode}
          onClose={() => setCustomsClient(null)}
        />
      ) : null}
    </div>
  )
}

/** Inline switch used in the Status cell to toggle client.active. */
function ActiveToggle({
  active,
  busy,
  disabled,
  onToggle,
}: {
  active: boolean
  busy: boolean
  disabled: boolean
  onToggle: (event: React.MouseEvent<HTMLButtonElement>) => void
}) {
  return (
    <button
      type="button"
      role="switch"
      aria-checked={active}
      aria-label={active ? 'Deactivate client' : 'Activate client'}
      onClick={onToggle}
      disabled={busy || disabled}
      title={disabled ? 'Admin only' : active ? 'Deactivate' : 'Activate'}
      className={`relative inline-flex h-4 w-7 shrink-0 items-center rounded-full transition disabled:opacity-50 ${
        active ? 'bg-emerald-500' : 'bg-slate-300'
      }`}
    >
      <span
        className={`inline-block h-3 w-3 transform rounded-full bg-white shadow transition ${
          active ? 'translate-x-3.5' : 'translate-x-0.5'
        }`}
      />
    </button>
  )
}

/**
 * Per-row kebab with Importer/Broker + Edit, and (admin) Delete.
 *
 * Uses PortalMenu so the popover renders into document.body and escapes
 * AdvancedDataTable's overflow-auto scroll container, which used to clip
 * the menu inside the table viewport.
 */
function RowActionsMenu({
  admin,
  onImporter,
  onEdit,
  onDelete,
}: {
  admin: boolean
  onImporter: () => void
  onEdit: () => void
  onDelete: () => void
}) {
  const [open, setOpen] = useState(false)
  const buttonRef = useRef<HTMLButtonElement>(null)
  const close = useCallback(() => setOpen(false), [])

  return (
    <>
      <button
        ref={buttonRef}
        type="button"
        onClick={(e) => { e.stopPropagation(); setOpen((c) => !c) }}
        aria-haspopup="menu"
        aria-expanded={open}
        aria-label="Row actions"
        className="inline-flex h-7 w-7 items-center justify-center rounded-lg border border-slate-200 bg-white text-slate-500 transition hover:bg-slate-50"
      >
        <FiMoreVertical className="h-3.5 w-3.5" />
      </button>
      <PortalMenu open={open} anchorRef={buttonRef} onClose={close} width={192}>
        <button
          type="button"
          role="menuitem"
          onClick={() => { close(); onImporter() }}
          className="flex w-full items-center gap-2 px-3 py-2 text-[12px] font-semibold text-slate-700 transition hover:bg-slate-50"
        >
          <FiGlobe className="h-3.5 w-3.5 text-[#412d15]" />
          Importer / Broker
        </button>
        <button
          type="button"
          role="menuitem"
          onClick={() => { close(); onEdit() }}
          className="flex w-full items-center gap-2 px-3 py-2 text-[12px] font-semibold text-slate-700 transition hover:bg-slate-50"
        >
          <FiEdit3 className="h-3.5 w-3.5 text-slate-500" />
          Edit
        </button>
        {admin ? (
          <button
            type="button"
            role="menuitem"
            onClick={() => { close(); onDelete() }}
            className="flex w-full items-center gap-2 border-t border-slate-100 px-3 py-2 text-[12px] font-semibold text-rose-600 transition hover:bg-rose-50"
          >
            <FiTrash2 className="h-3.5 w-3.5" />
            Delete
          </button>
        ) : null}
      </PortalMenu>
    </>
  )
}

