import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useOutletContext } from 'react-router-dom'
import type { ColumnDef, SortingState } from '@tanstack/react-table'
import {
  FiActivity,
  FiEdit3,
  FiFilter,
  FiHome,
  FiLink,
  FiMoreVertical,
  FiPlus,
  FiTrash2,
  FiUsers,
  FiX,
} from 'react-icons/fi'
import { notify } from '../utils/notify'
import { warehouseService, type Warehouse } from '../api/warehouseService'
import { countryName } from '../utils/countries'
import AttachClientsModal from './modals/AttachClientsModal'
import WarehouseEditorModal from './modals/WarehouseEditorModal'
import type { SettingsOutletContext } from './layout/SettingsLayout'
import AdvancedDataTable from './workspace/AdvancedDataTable'
import PortalMenu from './workspace/PortalMenu'
import Select from './workspace/Select'

const isAdmin = () => (localStorage.getItem('multiship_role') || '').toUpperCase() === 'ADMIN'

const filterLabelClass =
  'mb-1 flex items-center gap-1 text-[10px] font-bold uppercase tracking-[0.14em] text-slate-400'

export default function WarehousesPage() {
  const admin = isAdmin()

  const [rows, setRows] = useState<Warehouse[]>([])
  const [loading, setLoading] = useState(true)
  const [totalElements, setTotalElements] = useState(0)
  const [totalPages, setTotalPages] = useState(1)

  const [search, setSearch] = useState('')
  const [debouncedSearch, setDebouncedSearch] = useState('')
  const [ownerFilter, setOwnerFilter] = useState('')
  const [activeFilter, setActiveFilter] = useState('')
  const [showFilters, setShowFilters] = useState(false)
  const filtersRef = useRef<HTMLDivElement>(null)

  const [sorting, setSorting] = useState<SortingState>([{ id: 'code', desc: false }])
  const [pageIndex, setPageIndex] = useState(0)
  const [pageSize, setPageSize] = useState(25)
  const [busyId, setBusyId] = useState<number | null>(null)

  const [reloadToken, setReloadToken] = useState(0)
  const [editor, setEditor] = useState<{ warehouse: Warehouse | null } | null>(null)
  const [attachTarget, setAttachTarget] = useState<Warehouse | null>(null)

  useEffect(() => {
    const t = setTimeout(() => setDebouncedSearch(search.trim()), 350)
    return () => clearTimeout(t)
  }, [search])
  useEffect(() => {
    setPageIndex(0)
  }, [debouncedSearch, ownerFilter, activeFilter, sorting, pageSize])

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

  const sortBy = sorting[0]?.id ?? 'code'
  const sortDirection: 'ASC' | 'DESC' = sorting[0]?.desc ? 'DESC' : 'ASC'

  useEffect(() => {
    let cancelled = false
    setLoading(true)
    warehouseService
      .listWarehouses({
        search: debouncedSearch || undefined,
        ownerType: ownerFilter || undefined,
        active: activeFilter || undefined,
        sortBy,
        sortDirection,
        page: pageIndex,
        size: pageSize,
      })
      .then((r) => {
        if (cancelled) return
        setRows(r.data?.content ?? [])
        setTotalElements(r.data?.totalElements ?? 0)
        setTotalPages(Math.max(r.data?.totalPages ?? 1, 1))
      })
      .catch((error) => {
        if (!cancelled) notify.error(error instanceof Error ? error.message : 'Failed to load warehouses.')
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [debouncedSearch, ownerFilter, activeFilter, sortBy, sortDirection, pageIndex, pageSize, reloadToken])

  const refresh = useCallback(() => setReloadToken((t) => t + 1), [])

  const { registerRefresh } = useOutletContext<SettingsOutletContext>()
  useEffect(() => {
    registerRefresh(refresh)
    return () => registerRefresh(null)
  }, [registerRefresh, refresh])

  const activeFilterCount = (ownerFilter ? 1 : 0) + (activeFilter ? 1 : 0)
  const clearFilters = () => {
    setOwnerFilter('')
    setActiveFilter('')
  }

  const handleToggleActive = async (warehouse: Warehouse) => {
    setBusyId(warehouse.id)
    try {
      const r = await warehouseService.toggleActive(warehouse.code)
      notify.success(`${warehouse.code} is now ${r.data.active ? 'active' : 'inactive'}.`)
      refresh()
    } catch (error) {
      notify.error(error instanceof Error ? error.message : 'Failed to update the warehouse.')
    } finally {
      setBusyId(null)
    }
  }

  const handleDelete = async (warehouse: Warehouse) => {
    const attached = warehouse.attachedClientCount ?? 0
    const body = attached > 0
      ? `Delete warehouse ${warehouse.code}? It is currently attached to ${attached} client${attached === 1 ? '' : 's'} — they will be unlinked.`
      : `Delete warehouse ${warehouse.code}?`
    if (!(await notify.confirm(body, {
      title: 'Delete warehouse',
      confirmLabel: 'Delete',
      danger: true,
    }))) return
    try {
      await warehouseService.deleteWarehouse(warehouse.code)
      notify.success(`Warehouse ${warehouse.code} deleted.`)
      refresh()
    } catch (error) {
      notify.error(error instanceof Error ? error.message : 'Failed to delete the warehouse.')
    }
  }

  const columns = useMemo<ColumnDef<Warehouse, unknown>[]>(
    () => [
      {
        id: 'code',
        accessorFn: (w) => w.code,
        header: 'Code',
        cell: ({ row }) => (
          <span className="font-semibold text-slate-950">{row.original.code}</span>
        ),
      },
      {
        id: 'name',
        accessorFn: (w) => w.name,
        header: 'Name',
        cell: ({ row }) => {
          const w = row.original
          return (
            <div>
              <p className="font-medium text-slate-800">{w.name}</p>
              {w.address?.city ? (
                <p className="text-[11.5px] text-slate-500">
                  {w.address.city}
                  {w.address.state ? `, ${w.address.state}` : ''}
                </p>
              ) : null}
            </div>
          )
        },
      },
      {
        id: 'country',
        accessorFn: (w) => w.address?.country ?? '',
        header: 'Country',
        enableSorting: false,
        cell: ({ row }) => {
          const country = row.original.address?.country
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
      },
      {
        id: 'owner',
        accessorFn: (w) => w.ownerType,
        header: 'Owner',
        cell: ({ row }) => {
          const w = row.original
          const platform = (w.ownerType || '').toUpperCase() === 'PLATFORM'
          return (
            <span
              className={`inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-[10.5px] font-semibold ${
                platform
                  ? 'bg-sky-100 text-sky-700'
                  : 'bg-[#412d15]/10 text-[#412d15]'
              }`}
            >
              {platform ? 'Platform' : `Client: ${w.ownerClientCode || '—'}`}
            </span>
          )
        },
      },
      {
        id: 'attached',
        accessorFn: (w) => w.attachedClientCount ?? 0,
        header: 'Attached',
        enableSorting: false,
        cell: ({ row }) => (
          <span className="tabular-nums text-[12px] text-slate-700">
            {row.original.attachedClientCount ?? 0}
          </span>
        ),
      },
      {
        id: 'status',
        accessorFn: (w) => (w.active ? 'ACTIVE' : 'INACTIVE'),
        header: 'Status',
        enableSorting: false,
        cell: ({ row }) => {
          const w = row.original
          return (
            <div className="flex items-center gap-2.5">
              <ActiveToggle
                active={!!w.active}
                busy={busyId !== null}
                disabled={false}
                onToggle={(e) => { e.stopPropagation(); void handleToggleActive(w) }}
              />
              <span
                className={`inline-flex items-center rounded-full px-2 py-0.5 text-[10.5px] font-semibold ${
                  w.active ? 'bg-emerald-100 text-emerald-700' : 'bg-slate-200 text-slate-600'
                }`}
              >
                {w.active ? 'Active' : 'Inactive'}
              </span>
            </div>
          )
        },
      },
      {
        id: 'actions',
        header: '',
        enableSorting: false,
        cell: ({ row }) => {
          const canAttach = (row.original.ownerType || '').toUpperCase() === 'PLATFORM'
          return (
            <div className="text-right">
              <RowActionsMenu
                admin={admin}
                onAttach={canAttach ? () => setAttachTarget(row.original) : null}
                onEdit={() => setEditor({ warehouse: row.original })}
                onDelete={() => void handleDelete(row.original)}
              />
            </div>
          )
        },
        meta: { headerLabel: 'Actions', hideable: false, exportable: false },
      },
    ],
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [admin, busyId],
  )

  return (
    <div className="space-y-4">
      <section className="rounded-2xl border border-slate-200 bg-white p-5 shadow-sm">
        {loading && !rows.length ? (
          <p className="py-10 text-center text-sm text-slate-500">Loading warehouses…</p>
        ) : (
          <AdvancedDataTable<Warehouse>
            tableKey="warehouses"
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
                    aria-label="Filter warehouses"
                    className="absolute left-0 top-full z-30 mt-1.5 w-72 rounded-2xl border border-slate-200 bg-white p-3 shadow-[0_20px_60px_rgba(15,23,42,0.15)]"
                  >
                    <div className="space-y-2.5">
                      <div>
                        <span className={filterLabelClass}><FiUsers className="h-3 w-3 text-sky-600" />Owner</span>
                        <Select value={ownerFilter} onChange={(e) => setOwnerFilter(e.target.value)} aria-label="Filter by owner type">
                          <option value="">Any owner</option>
                          <option value="PLATFORM">Platform-owned</option>
                          <option value="CLIENT">Client-owned</option>
                        </Select>
                      </div>
                      <div>
                        <span className={filterLabelClass}><FiActivity className="h-3 w-3 text-emerald-600" />Status</span>
                        <Select value={activeFilter} onChange={(e) => setActiveFilter(e.target.value)} aria-label="Filter by active status">
                          <option value="">All statuses</option>
                          <option value="YES">Active</option>
                          <option value="NO">Inactive</option>
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
                onClick={() => setEditor({ warehouse: null })}
                className="inline-flex items-center gap-1.5 rounded-xl bg-[#1f150c] px-3 py-1.5 text-[12px] font-semibold text-white transition hover:bg-[#412d15]"
              >
                <FiPlus className="h-3.5 w-3.5" /> Add Warehouse
              </button>
            }
            caption={
              <p className="text-[11.5px] text-slate-400">
                <FiHome className="mr-1 inline h-3 w-3 -translate-y-px" />
                Showing {rows.length} of {totalElements} warehouse{totalElements === 1 ? '' : 's'}
              </p>
            }
            emptyState={
              debouncedSearch || activeFilterCount
                ? 'No warehouses match the current filters.'
                : 'No warehouses registered yet — add your first ship-from location.'
            }
            csvFilename="warehouses"
          />
        )}
      </section>

      {editor ? (
        <WarehouseEditorModal
          warehouse={editor.warehouse}
          onClose={() => setEditor(null)}
          onSaved={() => {
            setEditor(null)
            refresh()
          }}
        />
      ) : null}

      {attachTarget ? (
        <AttachClientsModal
          warehouse={attachTarget}
          onClose={() => {
            setAttachTarget(null)
            refresh()
          }}
        />
      ) : null}
    </div>
  )
}

/** Inline switch used in the Status cell to toggle warehouse.active. */
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
      aria-label={active ? 'Deactivate warehouse' : 'Activate warehouse'}
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
 * Per-row kebab with Attach clients, Edit and (admin) Delete. Uses PortalMenu
 * so the popover escapes AdvancedDataTable's overflow-auto scroll container —
 * previously the absolutely-positioned menu was clipped inside the table
 * viewport.
 */
function RowActionsMenu({
  admin,
  onAttach,
  onEdit,
  onDelete,
}: {
  admin: boolean
  /** null when this warehouse is not attachable (client-owned). */
  onAttach: (() => void) | null
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
        {onAttach ? (
          <button
            type="button"
            role="menuitem"
            onClick={() => { close(); onAttach() }}
            className="flex w-full items-center gap-2 px-3 py-2 text-[12px] font-semibold text-slate-700 transition hover:bg-slate-50"
          >
            <FiLink className="h-3.5 w-3.5 text-[#412d15]" />
            Attach clients
          </button>
        ) : null}
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
