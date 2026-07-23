import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useOutletContext } from 'react-router-dom'
import { notify } from '../utils/notify'
import {
  FiBriefcase,
  FiEdit3,
  FiFilter,
  FiGlobe,
  FiMapPin,
  FiMoreVertical,
  FiPlus,
  FiTrash2,
  FiTruck,
  FiUser,
  FiUsers,
  FiX,
} from 'react-icons/fi'
import type { ColumnDef, SortingState } from '@tanstack/react-table'
import { clientService, type Client } from '../api/clientService'
import {
  customsProfileService,
  type CustomsProfile,
  type CustomsProfileListParams,
} from '../api/customsProfileService'
import {
  countryName,
  countriesInRegion,
  groupByRegion,
  regionOf,
  REGIONS,
  type Region,
} from '../utils/countries'
import type { SettingsOutletContext } from './layout/SettingsLayout'
import AdvancedDataTable from './workspace/AdvancedDataTable'
import Select from './workspace/Select'
import CustomsProfileModal from './modals/CustomsProfileModal'

const filterLabelClass =
  'mb-1 flex items-center gap-1 text-[10px] font-bold uppercase tracking-[0.14em] text-slate-400'

type ModalState = { mode: 'new' } | { mode: 'edit'; profile: CustomsProfile } | null

/** Region-grouped country chips (used in the Destinations cell). */
function DestinationChips({ countries, max }: { countries: string[]; max?: number }) {
  const groups = groupByRegion(countries)
  const flat = groups.flatMap((g) => g.codes)
  if (!flat.length) return <span className="text-[12px] text-slate-400">—</span>
  const shown = max ? flat.slice(0, max) : flat
  const rest = max ? flat.length - shown.length : 0
  return (
    <div className="flex flex-wrap items-center gap-1">
      {shown.map((c) => (
        <span
          key={c}
          title={countryName(c)}
          className="rounded-md bg-[#412d15]/[0.07] px-1.5 py-0.5 text-[10.5px] font-bold text-[#412d15]"
        >
          {c}
        </span>
      ))}
      {rest > 0 ? <span className="text-[10.5px] font-semibold text-slate-400">+{rest}</span> : null}
    </div>
  )
}

/**
 * Importer / Broker — server-driven master list of every client's importer and
 * broker identity plus the destination countries each profile covers. Search,
 * filters, sort, pagination, and CSV export all happen server-side; the page
 * only renders one page at a time via {@link AdvancedDataTable} in manual mode.
 */
export default function ImporterBrokerPage() {
  const [profiles, setProfiles] = useState<CustomsProfile[]>([])
  const [clients, setClients] = useState<Client[]>([])
  const [stats, setStats] = useState({ profiles: 0, destinationsCovered: 0, clientsConfigured: 0 })
  const [loading, setLoading] = useState(true)
  const [totalElements, setTotalElements] = useState(0)
  const [totalPages, setTotalPages] = useState(1)

  const [search, setSearch] = useState('')
  const [debouncedSearch, setDebouncedSearch] = useState('')
  const [clientFilter, setClientFilter] = useState('')
  const [regionFilter, setRegionFilter] = useState<'' | Region>('')
  const [countryFilter, setCountryFilter] = useState('')
  const [carrierFilter, setCarrierFilter] = useState('')
  const [brokerFilter, setBrokerFilter] = useState('') // YES | NO
  const [showFilters, setShowFilters] = useState(false)
  const filtersRef = useRef<HTMLDivElement>(null)

  const [sorting, setSorting] = useState<SortingState>([{ id: 'client', desc: false }])
  const [pageIndex, setPageIndex] = useState(0)
  const [pageSize, setPageSize] = useState(25)
  const [reloadToken, setReloadToken] = useState(0)
  const [modal, setModal] = useState<ModalState>(null)

  useEffect(() => {
    const t = setTimeout(() => setDebouncedSearch(search.trim()), 350)
    return () => clearTimeout(t)
  }, [search])

  // Switching region invalidates a country pick from another region.
  useEffect(() => {
    if (countryFilter && regionFilter && regionOf(countryFilter) !== regionFilter) setCountryFilter('')
  }, [regionFilter, countryFilter])

  // Any filter or sort change snaps back to page 1.
  useEffect(() => {
    setPageIndex(0)
  }, [debouncedSearch, clientFilter, regionFilter, countryFilter, carrierFilter, brokerFilter, sorting, pageSize])

  // Popover close on outside click / Escape.
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

  // Fetch clients once for the Client filter dropdown.
  useEffect(() => {
    let cancelled = false
    clientService
      .listClients({ size: 500, sortBy: 'code' })
      .then((r) => {
        if (!cancelled) setClients(r.data?.content ?? [])
      })
      .catch(() => {})
    return () => {
      cancelled = true
    }
  }, [])

  const sortBy = sorting[0]?.id ?? 'client'
  const sortDirection: 'ASC' | 'DESC' = sorting[0]?.desc ? 'DESC' : 'ASC'

  /**
   * Region + Country → the countries list the server filters by. Country wins
   * when both are set (country is by definition a subset of its region).
   */
  const countryFilterParam = useMemo<string[] | undefined>(() => {
    if (countryFilter) return [countryFilter]
    if (regionFilter) return countriesInRegion(regionFilter).map((c) => c.code)
    return undefined
  }, [regionFilter, countryFilter])

  const listParams = useMemo<CustomsProfileListParams>(
    () => ({
      search: debouncedSearch || undefined,
      clientCode: clientFilter || undefined,
      carrier: carrierFilter || undefined,
      broker: brokerFilter || undefined,
      countries: countryFilterParam,
      sortBy: ['client', 'destinations', 'importer', 'broker'].includes(sortBy) ? sortBy : 'client',
      sortDirection,
    }),
    [debouncedSearch, clientFilter, carrierFilter, brokerFilter, countryFilterParam, sortBy, sortDirection],
  )

  useEffect(() => {
    let cancelled = false
    setLoading(true)
    customsProfileService
      .listProfiles({ ...listParams, page: pageIndex, size: pageSize })
      .then((r) => {
        if (cancelled) return
        setProfiles(r.data?.content ?? [])
        setTotalElements(r.data?.totalElements ?? 0)
        setTotalPages(Math.max(r.data?.totalPages ?? 1, 1))
      })
      .catch((error) => {
        if (!cancelled) notify.error(error instanceof Error ? error.message : 'Failed to load profiles.')
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [listParams, pageIndex, pageSize, reloadToken])

  // Health-strip totals are cross-cutting, not per-page. Fetch on mount and
  // after any mutation (add/edit/delete).
  useEffect(() => {
    let cancelled = false
    customsProfileService
      .stats()
      .then((s) => {
        if (!cancelled) setStats(s)
      })
      .catch(() => {})
    return () => {
      cancelled = true
    }
  }, [reloadToken])

  const refresh = useCallback(() => setReloadToken((t) => t + 1), [])

  const { registerRefresh } = useOutletContext<SettingsOutletContext>()
  useEffect(() => {
    registerRefresh(refresh)
    return () => registerRefresh(null)
  }, [registerRefresh, refresh])

  // Country options in the popover narrow by region when one is selected.
  const countryOptions = useMemo(() => {
    if (regionFilter) return countriesInRegion(regionFilter).map((c) => c.code)
    // Union across all regions (deduped, sorted alphabetically).
    return [...new Set(REGIONS.flatMap((r) => countriesInRegion(r).map((c) => c.code)))].sort()
  }, [regionFilter])

  const activeFilterCount =
    (clientFilter ? 1 : 0) +
    (regionFilter ? 1 : 0) +
    (countryFilter ? 1 : 0) +
    (carrierFilter ? 1 : 0) +
    (brokerFilter ? 1 : 0)
  const clearFilters = () => {
    setClientFilter('')
    setRegionFilter('')
    setCountryFilter('')
    setCarrierFilter('')
    setBrokerFilter('')
  }

  const handleDelete = async (p: CustomsProfile) => {
    if (!p.clientCode || !p.id) return
    if (
      !(await notify.confirm(
        `Delete this ${p.clientCode} importer/broker profile (${(p.countries ?? []).length} destinations)?`,
        { title: 'Delete profile', confirmLabel: 'Delete', danger: true },
      ))
    ) {
      return
    }
    try {
      await customsProfileService.remove(p.clientCode, p.id)
      notify.success('Profile deleted.')
      refresh()
    } catch (e) {
      notify.error(e instanceof Error ? e.message : 'Failed to delete.')
    }
  }

  const handleExport = async () => {
    try {
      await customsProfileService.exportProfilesCsv(listParams)
    } catch (error) {
      notify.error(error instanceof Error ? error.message : 'Failed to export profiles.')
    }
  }

  const columns = useMemo<ColumnDef<CustomsProfile, unknown>[]>(
    () => [
      {
        id: 'client',
        accessorFn: (p) => p.clientCode ?? '',
        header: 'Client',
        cell: ({ row }) => (
          <div>
            <p className="font-semibold text-slate-950">{row.original.clientCode}</p>
            <p className="text-[11px] text-slate-400">{row.original.clientName}</p>
          </div>
        ),
        meta: {
          headerLabel: 'Client',
          exportValue: (p: CustomsProfile) => p.clientCode ?? '',
        },
      },
      {
        id: 'destinations',
        header: 'Destinations',
        accessorFn: (p) => (p.countries ?? []).length,
        cell: ({ row }) => {
          const countries = row.original.countries ?? []
          return (
            <div>
              <DestinationChips countries={countries} max={6} />
              <p className="mt-0.5 text-[10.5px] text-slate-400">
                {countries.length} {countries.length === 1 ? 'country' : 'countries'}
              </p>
            </div>
          )
        },
        meta: {
          headerLabel: 'Destinations',
          exportValue: (p: CustomsProfile) => (p.countries ?? []).join(' '),
        },
      },
      {
        id: 'importer',
        header: 'Importer',
        accessorFn: (p) => p.importerName ?? '',
        cell: ({ row }) => {
          const p = row.original
          if (p.importerType === 'RECEIVER') {
            return (
              <span className="inline-flex items-center rounded-full bg-sky-50 px-2 py-0.5 text-[11px] font-semibold text-sky-700 ring-1 ring-sky-100">
                Receiver (DAP)
              </span>
            )
          }
          return (
            <>
              <p className="font-medium text-slate-800">{p.importerName || '—'}</p>
              {p.importerCity ? (
                <p className="text-[11px] text-slate-400">
                  {p.importerCity}
                  {p.importerState ? `, ${p.importerState}` : ''}
                </p>
              ) : null}
            </>
          )
        },
        meta: {
          headerLabel: 'Importer',
          exportValue: (p: CustomsProfile) =>
            p.importerType === 'RECEIVER' ? 'Receiver (DAP)' : p.importerName ?? '',
        },
      },
      {
        id: 'broker',
        header: 'Broker',
        accessorFn: (p) => p.brokerName ?? p.brokerCompany ?? '',
        cell: ({ row }) => {
          const p = row.original
          if (p.brokerName || p.brokerCompany) {
            return (
              <>
                <p className="font-medium text-slate-800">{p.brokerName || p.brokerCompany}</p>
                {p.brokerCity ? <p className="text-[11px] text-slate-400">{p.brokerCity}</p> : null}
              </>
            )
          }
          return <span className="text-[11.5px] font-medium text-emerald-700">Carrier clears</span>
        },
        meta: {
          headerLabel: 'Broker',
          exportValue: (p: CustomsProfile) => p.brokerName ?? p.brokerCompany ?? 'Carrier clears',
        },
      },
      {
        id: 'account',
        header: 'Account',
        enableSorting: false,
        cell: ({ row }) => {
          const p = row.original
          if (!(p.accountCarrier || p.accountNo)) {
            return <span className="text-[12px] text-slate-400">—</span>
          }
          return (
            <span className="inline-flex items-center gap-1.5 rounded-lg bg-emerald-50 px-2 py-1 text-[11px] font-semibold text-emerald-700 ring-1 ring-emerald-100">
              <FiTruck className="h-3 w-3" />
              {`${p.accountCarrier ?? ''} ${p.accountNo ?? ''}`.trim()}
            </span>
          )
        },
        meta: {
          headerLabel: 'Account',
          exportValue: (p: CustomsProfile) => `${p.accountCarrier ?? ''} ${p.accountNo ?? ''}`.trim(),
        },
      },
      {
        id: 'actions',
        header: '',
        enableSorting: false,
        cell: ({ row }) => (
          <div className="text-right">
            <RowActionsMenu
              onEdit={() => setModal({ mode: 'edit', profile: row.original })}
              onDelete={() => void handleDelete(row.original)}
            />
          </div>
        ),
        meta: { headerLabel: 'Actions', hideable: false, exportable: false },
      },
    ],
    // Handlers close over the latest state through their function bodies; they
    // aren't referentially stable, so we intentionally omit them to keep the
    // column model from rebuilding every render.
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [],
  )

  const cards = [
    { label: 'Profiles', value: stats.profiles, icon: FiGlobe, chip: 'bg-[#412d15]/10 text-[#412d15]' },
    { label: 'Destinations covered', value: stats.destinationsCovered, icon: FiMapPin, chip: 'bg-sky-50 text-sky-700' },
    { label: 'Clients configured', value: stats.clientsConfigured, icon: FiUsers, chip: 'bg-emerald-50 text-emerald-600' },
  ]

  return (
    <div className="space-y-4 pb-16">
      {/* health strip */}
      <section className="grid grid-cols-3 gap-3">
        {cards.map((c) => (
          <div key={c.label} className="flex items-start justify-between rounded-2xl border border-slate-200 bg-white p-4 shadow-sm">
            <div>
              <p className="text-[11px] font-semibold uppercase tracking-[0.12em] text-slate-500">{c.label}</p>
              <p className="mt-1.5 text-2xl font-semibold tabular-nums text-slate-950">{c.value}</p>
            </div>
            <span className={`inline-flex h-9 w-9 shrink-0 items-center justify-center rounded-xl ${c.chip}`}>
              <c.icon className="h-4 w-4" />
            </span>
          </div>
        ))}
      </section>

      <section className="rounded-2xl border border-slate-200 bg-white p-5 shadow-sm">
        {loading && !profiles.length ? (
          <p className="py-10 text-center text-sm text-slate-500">Loading profiles…</p>
        ) : (
          <AdvancedDataTable<CustomsProfile>
            tableKey="importer-broker"
            columns={columns}
            data={profiles}
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
              placeholder: 'Search client, country, importer, broker…',
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
                    aria-label="Filter profiles"
                    className="absolute left-0 top-full z-30 mt-1.5 w-72 rounded-2xl border border-slate-200 bg-white p-3 shadow-[0_20px_60px_rgba(15,23,42,0.15)]"
                  >
                    <div className="space-y-2.5">
                      <div>
                        <span className={filterLabelClass}><FiUser className="h-3 w-3 text-[#412d15]" />Client</span>
                        <Select value={clientFilter} onChange={(e) => setClientFilter(e.target.value)} aria-label="Filter by client">
                          <option value="">All clients</option>
                          {clients.map((c) => (
                            <option key={c.clientCode} value={c.clientCode}>
                              {c.clientCode}
                            </option>
                          ))}
                        </Select>
                      </div>
                      <div>
                        <span className={filterLabelClass}><FiMapPin className="h-3 w-3 text-sky-600" />Region</span>
                        <Select value={regionFilter} onChange={(e) => setRegionFilter(e.target.value as '' | Region)} aria-label="Filter by region">
                          <option value="">All regions</option>
                          {REGIONS.map((r) => (
                            <option key={r} value={r}>
                              {r}
                            </option>
                          ))}
                        </Select>
                      </div>
                      <div>
                        <span className={filterLabelClass}><FiGlobe className="h-3 w-3 text-rose-500" />Country</span>
                        <Select value={countryFilter} onChange={(e) => setCountryFilter(e.target.value)} aria-label="Filter by country">
                          <option value="">All countries</option>
                          {countryOptions.map((c) => (
                            <option key={c} value={c}>
                              {c} — {countryName(c)}
                            </option>
                          ))}
                        </Select>
                      </div>
                      <div>
                        <span className={filterLabelClass}><FiTruck className="h-3 w-3 text-emerald-600" />Account carrier</span>
                        <Select value={carrierFilter} onChange={(e) => setCarrierFilter(e.target.value)} aria-label="Filter by carrier">
                          <option value="">Any carrier</option>
                          <option value="UPS">UPS</option>
                          <option value="FEDEX">FedEx</option>
                          <option value="USPS">USPS</option>
                        </Select>
                      </div>
                      <div>
                        <span className={filterLabelClass}><FiBriefcase className="h-3 w-3 text-violet-600" />Broker</span>
                        <Select value={brokerFilter} onChange={(e) => setBrokerFilter(e.target.value)} aria-label="Filter by broker">
                          <option value="">Any</option>
                          <option value="YES">Own broker</option>
                          <option value="NO">Carrier default</option>
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
                onClick={() => setModal({ mode: 'new' })}
                className="inline-flex items-center gap-1.5 rounded-xl bg-[#1f150c] px-3 py-1.5 text-[12px] font-semibold text-white transition hover:bg-[#412d15]"
              >
                <FiPlus className="h-3.5 w-3.5" /> Add profile
              </button>
            }
            caption={
              <p className="text-[11.5px] text-slate-400">
                Showing {profiles.length} of {totalElements} profile{totalElements === 1 ? '' : 's'}
              </p>
            }
            emptyState={
              debouncedSearch || activeFilterCount
                ? 'No profiles match your filters.'
                : 'No importer/broker profiles yet — click Add profile.'
            }
            csvFilename="customs-profiles"
          />
        )}
      </section>

      {modal ? (
        <CustomsProfileModal
          clients={clients}
          profile={modal.mode === 'edit' ? modal.profile : null}
          existingProfiles={profiles}
          onClose={() => setModal(null)}
          onSaved={() => refresh()}
        />
      ) : null}
    </div>
  )
}

/** Per-row kebab (Edit + Delete). */
function RowActionsMenu({
  onEdit,
  onDelete,
}: {
  onEdit: () => void
  onDelete: () => void
}) {
  const [open, setOpen] = useState(false)
  const ref = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (!open) return
    const onDocClick = (e: MouseEvent) => {
      if (!ref.current?.contains(e.target as Node)) setOpen(false)
    }
    document.addEventListener('mousedown', onDocClick)
    return () => document.removeEventListener('mousedown', onDocClick)
  }, [open])

  return (
    <div ref={ref} className="relative inline-block text-left">
      <button
        type="button"
        onClick={(e) => { e.stopPropagation(); setOpen((c) => !c) }}
        aria-haspopup="menu"
        aria-expanded={open}
        aria-label="Row actions"
        className="inline-flex h-7 w-7 items-center justify-center rounded-lg border border-slate-200 bg-white text-slate-500 transition hover:bg-slate-50"
      >
        <FiMoreVertical className="h-3.5 w-3.5" />
      </button>
      {open ? (
        <div
          role="menu"
          className="absolute right-0 z-20 mt-1 w-40 overflow-hidden rounded-xl border border-slate-200 bg-white text-left shadow-lg"
        >
          <button
            type="button"
            role="menuitem"
            onClick={() => { setOpen(false); onEdit() }}
            className="flex w-full items-center gap-2 px-3 py-2 text-[12px] font-semibold text-slate-700 transition hover:bg-slate-50"
          >
            <FiEdit3 className="h-3.5 w-3.5 text-slate-500" />
            Edit
          </button>
          <div className="my-1 border-t border-slate-100" />
          <button
            type="button"
            role="menuitem"
            onClick={() => { setOpen(false); onDelete() }}
            className="flex w-full items-center gap-2 px-3 py-2 text-[12px] font-semibold text-rose-600 transition hover:bg-rose-50"
          >
            <FiTrash2 className="h-3.5 w-3.5" />
            Delete
          </button>
        </div>
      ) : null}
    </div>
  )
}
