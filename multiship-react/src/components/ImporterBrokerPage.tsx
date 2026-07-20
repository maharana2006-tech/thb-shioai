import { useEffect, useMemo, useState } from 'react'
import toast from 'react-hot-toast'
import {
  FiBriefcase,
  FiEdit3,
  FiFilter,
  FiGlobe,
  FiGrid,
  FiLayers,
  FiList,
  FiMapPin,
  FiPlus,
  FiRefreshCw,
  FiSearch,
  FiTrash2,
  FiTruck,
  FiUser,
  FiUsers,
  FiX,
} from 'react-icons/fi'
import { clientService, type Client } from '../api/clientService'
import { customsProfileService, type CustomsProfile } from '../api/customsProfileService'
import { countryName, groupByRegion, regionOf, REGIONS, type Region } from '../utils/countries'
import PageSectionHeader from './workspace/PageSectionHeader'
import TablePagination from './workspace/TablePagination'
import Select from './workspace/Select'
import CustomsProfileModal from './modals/CustomsProfileModal'

const filterLabelClass = 'mb-1 flex items-center gap-1.5 text-[10px] font-bold uppercase tracking-[0.14em] text-slate-500'

type ViewMode = 'table' | 'cards' | 'matrix'
type ModalState = { mode: 'new' } | { mode: 'edit'; profile: CustomsProfile } | null

/** Region-grouped country chips. */
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
 * Importer / Broker management — every client's importer + broker identity and
 * the set of destination countries it covers. Three views: table, profile
 * cards, and a client × region coverage matrix. Add opens the editor directly.
 */
export default function ImporterBrokerPage() {
  const [profiles, setProfiles] = useState<CustomsProfile[]>([])
  const [clients, setClients] = useState<Client[]>([])
  const [loading, setLoading] = useState(true)
  const [view, setView] = useState<ViewMode>('table')
  const [query, setQuery] = useState('')
  const [modal, setModal] = useState<ModalState>(null)
  const [showFilters, setShowFilters] = useState(false)
  const [clientFilter, setClientFilter] = useState('')
  const [regionFilter, setRegionFilter] = useState('')
  const [countryFilter, setCountryFilter] = useState('')
  const [carrierFilter, setCarrierFilter] = useState('')
  const [brokerFilter, setBrokerFilter] = useState('') // YES | NO
  const [sortBy, setSortBy] = useState<'client' | 'destinations' | 'importer' | 'broker'>('client')
  const [sortDir, setSortDir] = useState<'ASC' | 'DESC'>('ASC')
  const [page, setPage] = useState(1)
  const [pageSize, setPageSize] = useState(10)

  const load = async () => {
    setLoading(true)
    try {
      const [list, clientPage] = await Promise.all([
        customsProfileService.listAll(),
        clientService.listClients({ size: 200, sortBy: 'code' }),
      ])
      setProfiles(list)
      setClients(clientPage.data?.content ?? [])
    } catch (e) {
      toast.error(e instanceof Error ? e.message : 'Failed to load profiles.')
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    void load()
  }, [])

  const clientOptions = useMemo(
    () => [...new Set(profiles.map((p) => p.clientCode ?? ''))].filter(Boolean).sort(),
    [profiles]
  )
  const carrierOptions = useMemo(
    () => [...new Set(profiles.map((p) => (p.accountCarrier ?? '').toUpperCase()))].filter(Boolean).sort(),
    [profiles]
  )
  // Destination countries present in the data, narrowed by the region filter.
  const countryOptions = useMemo(() => {
    const all = [...new Set(profiles.flatMap((p) => p.countries ?? []))]
    return all.filter((c) => !regionFilter || regionOf(c) === regionFilter).sort()
  }, [profiles, regionFilter])

  // Switching region invalidates a country pick from another region.
  useEffect(() => {
    if (countryFilter && regionFilter && regionOf(countryFilter) !== regionFilter) setCountryFilter('')
  }, [regionFilter, countryFilter])

  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase()
    return profiles.filter((p) => {
      const countries = p.countries ?? []
      if (clientFilter && (p.clientCode ?? '').toUpperCase() !== clientFilter) return false
      if (regionFilter && !countries.some((c) => regionOf(c) === regionFilter)) return false
      if (countryFilter && !countries.includes(countryFilter)) return false
      if (carrierFilter && (p.accountCarrier ?? '').toUpperCase() !== carrierFilter) return false
      const hasOwnBroker = !!(p.brokerName || p.brokerCompany)
      if (brokerFilter === 'YES' && !hasOwnBroker) return false
      if (brokerFilter === 'NO' && hasOwnBroker) return false
      if (!q) return true
      const hay = `${p.clientCode} ${p.clientName ?? ''} ${p.importerName ?? ''} ${p.brokerName ?? ''} ${countries.join(' ')} ${countries.map(countryName).join(' ')}`
      return hay.toLowerCase().includes(q)
    })
  }, [profiles, query, clientFilter, regionFilter, countryFilter, carrierFilter, brokerFilter])

  const sorted = useMemo(() => {
    const key = (p: CustomsProfile) => {
      switch (sortBy) {
        case 'destinations': return String((p.countries ?? []).length).padStart(3, '0')
        case 'importer': return p.importerName ?? ''
        case 'broker': return p.brokerName ?? ''
        default: return p.clientCode ?? ''
      }
    }
    const dir = sortDir === 'ASC' ? 1 : -1
    return [...filtered].sort((a, b) => key(a).localeCompare(key(b)) * dir)
  }, [filtered, sortBy, sortDir])

  const totalPages = Math.max(1, Math.ceil(sorted.length / pageSize))
  const currentPage = Math.min(page, totalPages)
  const paged = sorted.slice((currentPage - 1) * pageSize, currentPage * pageSize)

  const activeFilterCount =
    (clientFilter ? 1 : 0) + (regionFilter ? 1 : 0) + (countryFilter ? 1 : 0) +
    (carrierFilter ? 1 : 0) + (brokerFilter ? 1 : 0)
  const clearFilters = () => {
    setClientFilter('')
    setRegionFilter('')
    setCountryFilter('')
    setCarrierFilter('')
    setBrokerFilter('')
  }

  useEffect(() => {
    setPage(1)
  }, [query, clientFilter, regionFilter, countryFilter, carrierFilter, brokerFilter, pageSize, sortBy, sortDir])

  const toggleSort = (key: typeof sortBy) => {
    if (sortBy === key) setSortDir((d) => (d === 'ASC' ? 'DESC' : 'ASC'))
    else {
      setSortBy(key)
      setSortDir('ASC')
    }
  }

  const destinationsCovered = useMemo(
    () => new Set(profiles.flatMap((p) => p.countries ?? [])).size,
    [profiles]
  )
  const clientsConfigured = useMemo(() => new Set(profiles.map((p) => p.clientCode)).size, [profiles])

  const handleDelete = async (p: CustomsProfile) => {
    if (!p.clientCode || !p.id) return
    if (!window.confirm(`Delete this ${p.clientCode} importer/broker profile (${(p.countries ?? []).length} destinations)?`)) return
    try {
      await customsProfileService.remove(p.clientCode, p.id)
      toast.success('Profile deleted.')
      void load()
    } catch (e) {
      toast.error(e instanceof Error ? e.message : 'Failed to delete.')
    }
  }

  // client × region coverage matrix
  const matrix = useMemo(() => {
    const byClient = new Map<string, { name: string; regions: Record<string, string[]> }>()
    for (const p of filtered) {
      const code = (p.clientCode ?? '').toUpperCase()
      if (!code) continue
      if (!byClient.has(code)) byClient.set(code, { name: p.clientName ?? '', regions: {} })
      const entry = byClient.get(code)!
      for (const c of p.countries ?? []) {
        const r = regionOf(c)
        if (!r) continue
        ;(entry.regions[r] ??= []).push(c)
      }
    }
    return [...byClient.entries()]
      .map(([code, v]) => ({ code, ...v }))
      .sort((a, b) => a.code.localeCompare(b.code))
  }, [filtered])

  const cards = [
    { label: 'Profiles', value: profiles.length, icon: FiGlobe, chip: 'bg-[#412d15]/10 text-[#412d15]' },
    { label: 'Destinations covered', value: destinationsCovered, icon: FiMapPin, chip: 'bg-sky-50 text-sky-700' },
    { label: 'Clients configured', value: clientsConfigured, icon: FiUsers, chip: 'bg-emerald-50 text-emerald-600' },
  ]

  const viewTabs: Array<{ key: ViewMode; label: string; icon: typeof FiList }> = [
    { key: 'table', label: 'Table', icon: FiList },
    { key: 'cards', label: 'Cards', icon: FiGrid },
    { key: 'matrix', label: 'Coverage', icon: FiLayers },
  ]

  return (
    <div className="space-y-4 pb-16">
      <PageSectionHeader
        title="Importer / Broker"
        description="Each profile is one importer + broker identity applied to a set of destination countries — resolved automatically at shipment."
        actions={
          <div className="flex items-center gap-2">
            <button
              type="button"
              onClick={() => void load()}
              className="inline-flex items-center gap-1.5 rounded-xl border border-slate-200 bg-white px-3 py-2 text-[12.5px] font-semibold text-slate-600 transition hover:bg-slate-50"
            >
              <FiRefreshCw className="h-3.5 w-3.5" /> Refresh
            </button>
            <button
              type="button"
              onClick={() => setModal({ mode: 'new' })}
              className="inline-flex items-center gap-1.5 rounded-xl bg-[#1f150c] px-3.5 py-2 text-[12.5px] font-semibold text-white transition hover:bg-[#412d15]"
            >
              <FiPlus className="h-3.5 w-3.5" /> Add profile
            </button>
          </div>
        }
      />

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
        {/* toolbar */}
        <div className="flex flex-wrap items-center gap-2.5">
          {/* view switch */}
          <div className="mr-auto flex gap-0.5 rounded-lg border border-slate-200/70 bg-slate-100 p-0.5">
            {viewTabs.map((t) => (
              <button
                key={t.key}
                type="button"
                onClick={() => setView(t.key)}
                className={`inline-flex items-center gap-1.5 rounded-md px-2.5 py-1.5 text-[12px] font-semibold transition ${
                  view === t.key ? 'bg-white text-[#1f150c] shadow-sm' : 'text-slate-500 hover:text-slate-800'
                }`}
              >
                <t.icon className="h-3.5 w-3.5" /> {t.label}
              </button>
            ))}
          </div>
          <label className="flex min-w-[240px] items-center gap-2 rounded-xl border border-slate-200 bg-slate-50 px-3 py-2">
            <FiSearch className="h-3.5 w-3.5 shrink-0 text-slate-400" />
            <input
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              placeholder="Search client, country, importer, broker…"
              className="w-full bg-transparent text-[12.5px] text-slate-950 outline-none"
            />
          </label>
          <button
            type="button"
            onClick={() => setShowFilters((v) => !v)}
            aria-pressed={showFilters}
            className={`inline-flex items-center gap-1.5 rounded-xl px-3 py-2 text-[12px] font-semibold transition ${
              showFilters || activeFilterCount ? 'bg-[#1f150c] text-white' : 'border border-slate-200 bg-white text-slate-600 hover:bg-slate-50'
            }`}
          >
            <FiFilter className="h-3.5 w-3.5" /> Filters
            {activeFilterCount ? (
              <span className="rounded-full bg-white/25 px-1.5 py-0.5 text-[10px] tabular-nums">{activeFilterCount}</span>
            ) : null}
          </button>
        </div>

        {/* filter panel */}
        {showFilters ? (
          <div className="mt-3 rounded-2xl border border-[#412d15]/15 bg-gradient-to-br from-[#412d15]/[0.04] to-sky-50/40 p-4">
            <div className="grid grid-cols-2 gap-x-4 gap-y-3 sm:grid-cols-3 lg:grid-cols-5">
              <div>
                <span className={filterLabelClass}><FiUser className="h-3 w-3 text-[#412d15]" />Client</span>
                <Select value={clientFilter} onChange={(e) => setClientFilter(e.target.value)}>
                  <option value="">All clients</option>
                  {clientOptions.map((c) => (<option key={c} value={c}>{c}</option>))}
                </Select>
              </div>
              <div>
                <span className={filterLabelClass}><FiMapPin className="h-3 w-3 text-sky-600" />Region</span>
                <Select value={regionFilter} onChange={(e) => setRegionFilter(e.target.value)}>
                  <option value="">All regions</option>
                  {REGIONS.map((c) => (<option key={c} value={c}>{c}</option>))}
                </Select>
              </div>
              <div>
                <span className={filterLabelClass}><FiGlobe className="h-3 w-3 text-rose-500" />Country</span>
                <Select value={countryFilter} onChange={(e) => setCountryFilter(e.target.value)}>
                  <option value="">All countries</option>
                  {countryOptions.map((c) => (<option key={c} value={c}>{c} — {countryName(c)}</option>))}
                </Select>
              </div>
              <div>
                <span className={filterLabelClass}><FiTruck className="h-3 w-3 text-emerald-600" />Account carrier</span>
                <Select value={carrierFilter} onChange={(e) => setCarrierFilter(e.target.value)}>
                  <option value="">Any carrier</option>
                  {carrierOptions.map((c) => (<option key={c} value={c}>{c}</option>))}
                </Select>
              </div>
              <div>
                <span className={filterLabelClass}><FiBriefcase className="h-3 w-3 text-violet-600" />Broker</span>
                <Select value={brokerFilter} onChange={(e) => setBrokerFilter(e.target.value)}>
                  <option value="">Any</option>
                  <option value="YES">Own broker</option>
                  <option value="NO">Carrier default</option>
                </Select>
              </div>
            </div>
            {activeFilterCount ? (
              <div className="mt-3.5 flex justify-end border-t border-slate-200 pt-3">
                <button
                  type="button"
                  onClick={clearFilters}
                  className="inline-flex items-center gap-1 rounded-xl border border-slate-200 bg-white px-2.5 py-1.5 text-[11.5px] font-semibold text-slate-600 transition hover:bg-slate-50"
                >
                  <FiX className="h-3.5 w-3.5" /> Clear all filters
                </button>
              </div>
            ) : null}
          </div>
        ) : null}

        <p className="mt-3 text-[12.5px] font-semibold text-slate-500">
          Showing {sorted.length} of {profiles.length} profile{profiles.length === 1 ? '' : 's'}
        </p>

        {/* ===== TABLE VIEW ===== */}
        {view === 'table' ? (
          <>
            <div className="mt-2 overflow-x-auto">
              <table className="w-full min-w-[900px] text-[13px] text-slate-700">
                <thead className="border-b border-slate-200 text-left text-[10px] uppercase tracking-[0.16em] text-slate-500">
                  <tr>
                    {([
                      ['client', 'Client'],
                      ['destinations', 'Destinations'],
                      ['importer', 'Importer'],
                      ['broker', 'Broker'],
                    ] as const).map(([key, label]) => (
                      <th key={key} className="px-2.5 py-3">
                        <button
                          type="button"
                          onClick={() => toggleSort(key)}
                          className={`inline-flex items-center gap-1 uppercase tracking-[0.16em] transition hover:text-slate-950 ${sortBy === key ? 'text-slate-950' : ''}`}
                        >
                          {label}
                          <span className="text-slate-300">{sortBy === key ? (sortDir === 'ASC' ? '↑' : '↓') : '↕'}</span>
                        </button>
                      </th>
                    ))}
                    <th className="px-2.5 py-3">Account</th>
                    <th className="px-2.5 py-3 text-right">Actions</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-slate-100">
                  {paged.map((p) => (
                    <tr key={p.id} className="transition hover:bg-slate-50/70">
                      <td className="px-2.5 py-3">
                        <p className="font-semibold text-slate-950">{p.clientCode}</p>
                        <p className="text-[11px] text-slate-400">{p.clientName}</p>
                      </td>
                      <td className="px-2.5 py-3">
                        <DestinationChips countries={p.countries ?? []} max={6} />
                        <p className="mt-0.5 text-[10.5px] text-slate-400">{(p.countries ?? []).length} {(p.countries ?? []).length === 1 ? 'country' : 'countries'}</p>
                      </td>
                      <td className="px-2.5 py-3">
                        {p.importerType === 'RECEIVER' ? (
                          <span className="inline-flex items-center rounded-full bg-sky-50 px-2 py-0.5 text-[11px] font-semibold text-sky-700 ring-1 ring-sky-100">
                            Receiver (DAP)
                          </span>
                        ) : (
                          <>
                            <p className="font-medium text-slate-800">{p.importerName || '—'}</p>
                            {p.importerCity ? <p className="text-[11px] text-slate-400">{p.importerCity}{p.importerState ? `, ${p.importerState}` : ''}</p> : null}
                          </>
                        )}
                      </td>
                      <td className="px-2.5 py-3">
                        {p.brokerName || p.brokerCompany ? (
                          <>
                            <p className="font-medium text-slate-800">{p.brokerName || p.brokerCompany}</p>
                            {p.brokerCity ? <p className="text-[11px] text-slate-400">{p.brokerCity}</p> : null}
                          </>
                        ) : (
                          <span className="text-[11.5px] font-medium text-emerald-700">Carrier clears</span>
                        )}
                      </td>
                      <td className="px-2.5 py-3">
                        {p.accountCarrier || p.accountNo ? (
                          <span className="inline-flex items-center gap-1.5 rounded-lg bg-emerald-50 px-2 py-1 text-[11px] font-semibold text-emerald-700 ring-1 ring-emerald-100">
                            <FiTruck className="h-3 w-3" />
                            {`${p.accountCarrier ?? ''} ${p.accountNo ?? ''}`.trim()}
                          </span>
                        ) : (
                          <span className="text-[12px] text-slate-400">—</span>
                        )}
                      </td>
                      <td className="px-2.5 py-3">
                        <div className="flex justify-end gap-1.5">
                          <button
                            type="button"
                            onClick={() => setModal({ mode: 'edit', profile: p })}
                            className="inline-flex items-center gap-1 rounded-lg border border-slate-200 bg-white px-2.5 py-1.5 text-[11px] font-semibold text-slate-700 transition hover:bg-slate-50"
                          >
                            <FiEdit3 className="h-3 w-3" /> Edit
                          </button>
                          <button
                            type="button"
                            onClick={() => void handleDelete(p)}
                            aria-label={`Delete ${p.clientCode} profile`}
                            className="rounded-lg border border-rose-200 bg-white p-1.5 text-rose-600 transition hover:bg-rose-50"
                          >
                            <FiTrash2 className="h-3.5 w-3.5" />
                          </button>
                        </div>
                      </td>
                    </tr>
                  ))}
                  {!loading && !sorted.length ? (
                    <tr>
                      <td colSpan={6} className="px-2.5 py-12 text-center text-sm text-slate-500">
                        {profiles.length ? 'No profiles match your filters.' : 'No importer/broker profiles yet — click “Add profile”.'}
                      </td>
                    </tr>
                  ) : null}
                </tbody>
              </table>
            </div>
            {sorted.length > 0 ? (
              <TablePagination
                page={currentPage}
                pageSize={pageSize}
                totalPages={totalPages}
                compact
                onPageChange={setPage}
                onPageSizeChange={setPageSize}
              />
            ) : null}
          </>
        ) : null}

        {/* ===== CARDS VIEW ===== */}
        {view === 'cards' ? (
          <div className="mt-3 grid gap-3 md:grid-cols-2 xl:grid-cols-3">
            {sorted.map((p) => (
              <div key={p.id} className="flex flex-col rounded-2xl border border-slate-200 p-4 shadow-sm transition hover:border-[#412d15]/30 hover:shadow-md">
                <div className="flex items-start justify-between gap-2">
                  <div>
                    <p className="font-semibold text-slate-950">{p.clientCode}</p>
                    <p className="text-[11px] text-slate-400">{p.clientName}</p>
                  </div>
                  <span className="rounded-full bg-[#412d15]/10 px-2 py-0.5 text-[11px] font-bold tabular-nums text-[#412d15]">
                    {(p.countries ?? []).length} dest.
                  </span>
                </div>

                <div className="mt-3 space-y-2 text-[12px]">
                  <div className="flex items-start gap-2">
                    <FiUser className="mt-0.5 h-3.5 w-3.5 shrink-0 text-[#412d15]" />
                    <div>
                      {p.importerType === 'RECEIVER' ? (
                        <span className="inline-flex items-center rounded-full bg-sky-50 px-2 py-0.5 text-[11px] font-semibold text-sky-700 ring-1 ring-sky-100">Receiver (DAP)</span>
                      ) : (
                        <><span className="font-semibold text-slate-800">{p.importerName || '—'}</span>{p.importerCity ? <span className="text-slate-400"> · {p.importerCity}</span> : null}</>
                      )}
                    </div>
                  </div>
                  <div className="flex items-start gap-2">
                    <FiBriefcase className="mt-0.5 h-3.5 w-3.5 shrink-0 text-sky-600" />
                    <div className="text-slate-700">
                      {p.brokerName || p.brokerCompany || <span className="font-medium text-emerald-700">Carrier clears</span>}
                    </div>
                  </div>
                  {p.accountCarrier || p.accountNo ? (
                    <div className="flex items-start gap-2">
                      <FiTruck className="mt-0.5 h-3.5 w-3.5 shrink-0 text-emerald-600" />
                      <div className="text-slate-700">{`${p.accountCarrier ?? ''} ${p.accountNo ?? ''}`.trim()}</div>
                    </div>
                  ) : null}
                </div>

                <div className="mt-3 space-y-1.5 border-t border-slate-100 pt-3">
                  {groupByRegion(p.countries ?? []).map((g) => (
                    <div key={g.region}>
                      <p className="text-[9.5px] font-bold uppercase tracking-[0.14em] text-slate-400">{g.region}</p>
                      <div className="mt-0.5"><DestinationChips countries={g.codes} /></div>
                    </div>
                  ))}
                </div>

                <div className="mt-3 flex justify-end gap-1.5">
                  <button
                    type="button"
                    onClick={() => setModal({ mode: 'edit', profile: p })}
                    className="inline-flex items-center gap-1 rounded-lg border border-slate-200 bg-white px-2.5 py-1.5 text-[11px] font-semibold text-slate-700 transition hover:bg-slate-50"
                  >
                    <FiEdit3 className="h-3 w-3" /> Edit
                  </button>
                  <button
                    type="button"
                    onClick={() => void handleDelete(p)}
                    className="rounded-lg border border-rose-200 bg-white p-1.5 text-rose-600 transition hover:bg-rose-50"
                  >
                    <FiTrash2 className="h-3.5 w-3.5" />
                  </button>
                </div>
              </div>
            ))}
            {!loading && !sorted.length ? (
              <div className="col-span-full py-12 text-center text-sm text-slate-500">
                {profiles.length ? 'No profiles match your filters.' : 'No importer/broker profiles yet — click “Add profile”.'}
              </div>
            ) : null}
          </div>
        ) : null}

        {/* ===== COVERAGE MATRIX ===== */}
        {view === 'matrix' ? (
          <div className="mt-3 overflow-x-auto">
            <table className="w-full min-w-[820px] border-separate border-spacing-0 text-[12.5px]">
              <thead>
                <tr>
                  <th className="sticky left-0 z-10 bg-white px-3 py-2.5 text-left text-[10px] font-bold uppercase tracking-[0.14em] text-slate-500">Client</th>
                  {REGIONS.map((r) => (
                    <th key={r} className="px-2 py-2.5 text-center text-[10px] font-bold uppercase tracking-[0.1em] text-slate-500">{r}</th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {matrix.map((row) => (
                  <tr key={row.code} className="transition hover:bg-slate-50/70">
                    <td className="sticky left-0 z-10 border-t border-slate-100 bg-white px-3 py-2.5">
                      <p className="font-semibold text-slate-950">{row.code}</p>
                      <p className="text-[10.5px] text-slate-400">{row.name}</p>
                    </td>
                    {REGIONS.map((r) => {
                      const codes = row.regions[r as Region] ?? []
                      return (
                        <td key={r} className="border-t border-slate-100 px-2 py-2.5 text-center">
                          {codes.length ? (
                            <span
                              title={codes.map(countryName).join(', ')}
                              className="inline-flex min-w-[26px] items-center justify-center rounded-md bg-[#1f150c] px-1.5 py-1 text-[11px] font-bold text-[#f4eede]"
                            >
                              {codes.length}
                            </span>
                          ) : (
                            <span className="text-slate-200">·</span>
                          )}
                        </td>
                      )
                    })}
                  </tr>
                ))}
                {!loading && !matrix.length ? (
                  <tr>
                    <td colSpan={REGIONS.length + 1} className="px-3 py-12 text-center text-sm text-slate-500">
                      No coverage to show.
                    </td>
                  </tr>
                ) : null}
              </tbody>
            </table>
            <p className="mt-2 text-[11px] text-slate-400">Each cell shows how many destination countries that client covers in the region. Hover for the list.</p>
          </div>
        ) : null}
      </section>

      {modal ? (
        <CustomsProfileModal
          clients={clients}
          profile={modal.mode === 'edit' ? modal.profile : null}
          existingProfiles={profiles}
          onClose={() => setModal(null)}
          onSaved={() => void load()}
        />
      ) : null}
    </div>
  )
}
