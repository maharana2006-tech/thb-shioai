import { useCallback, useEffect, useState } from 'react'
import { useOutletContext } from 'react-router-dom'
import toast from 'react-hot-toast'
import {
  FiArrowDown,
  FiArrowUp,
  FiEdit3,
  FiFilter,
  FiGlobe,
  FiPlus,
  FiSearch,
  FiTrash2,
} from 'react-icons/fi'
import { ApiError } from '../api/apiClient'
import { clientService, type Client } from '../api/clientService'
import { formatCarrierName } from '../utils/carrierUtils'
import { countryName } from '../utils/countries'
import ClientEditorModal from './modals/ClientEditorModal'
import CustomsProfileModal from './modals/CustomsProfileModal'
import type { SettingsOutletContext } from './layout/SettingsLayout'
import Select from './workspace/Select'
import TablePagination from './workspace/TablePagination'

const isAdmin = () => (localStorage.getItem('multiship_role') || '').toUpperCase() === 'ADMIN'

export default function ClientsPage() {
  const admin = isAdmin()

  const [clients, setClients] = useState<Client[]>([])
  const [loading, setLoading] = useState(true)
  const [totalElements, setTotalElements] = useState(0)
  const [totalPages, setTotalPages] = useState(1)

  const [search, setSearch] = useState('')
  const [debouncedSearch, setDebouncedSearch] = useState('')
  const [statusFilter, setStatusFilter] = useState('')
  const [carrierFilter, setCarrierFilter] = useState('')
  const [ordersFilter, setOrdersFilter] = useState('')
  const [showFilters, setShowFilters] = useState(false)
  const emptyCols = { code: '', name: '', city: '' }
  const [colFilters, setColFilters] = useState(emptyCols)
  const [debouncedCols, setDebouncedCols] = useState(emptyCols)

  const [sortBy, setSortBy] = useState('code')
  const [sortDirection, setSortDirection] = useState<'ASC' | 'DESC'>('ASC')
  const [page, setPage] = useState(1)
  const [pageSize, setPageSize] = useState(10)
  const [reloadToken, setReloadToken] = useState(0)
  const [editor, setEditor] = useState<{ client: Client | null } | null>(null)
  const [customsClient, setCustomsClient] = useState<Client | null>(null)

  useEffect(() => {
    const t = setTimeout(() => setDebouncedSearch(search.trim()), 350)
    return () => clearTimeout(t)
  }, [search])
  useEffect(() => {
    const t = setTimeout(() => setDebouncedCols(colFilters), 350)
    return () => clearTimeout(t)
  }, [colFilters])
  useEffect(() => {
    setPage(1)
  }, [debouncedSearch, statusFilter, carrierFilter, ordersFilter, debouncedCols, sortBy, sortDirection, pageSize])

  useEffect(() => {
    let cancelled = false
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
        page: page - 1,
        size: pageSize,
      })
      .then((r) => {
        if (cancelled) return
        setClients(r.data?.content ?? [])
        setTotalElements(r.data?.totalElements ?? 0)
        setTotalPages(Math.max(r.data?.totalPages ?? 1, 1))
      })
      .catch((error) => {
        if (!cancelled) toast.error(error instanceof Error ? error.message : 'Failed to load clients.')
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [debouncedSearch, statusFilter, carrierFilter, ordersFilter, debouncedCols, sortBy, sortDirection, page, pageSize, reloadToken])

  const refresh = useCallback(() => setReloadToken((t) => t + 1), [])

  const { registerRefresh } = useOutletContext<SettingsOutletContext>()
  useEffect(() => {
    registerRefresh(refresh)
    return () => registerRefresh(null)
  }, [registerRefresh, refresh])

  const handleSort = (key: string) => {
    if (sortBy === key) {
      setSortDirection((c) => (c === 'ASC' ? 'DESC' : 'ASC'))
      return
    }
    setSortBy(key)
    setSortDirection('ASC')
  }

  const activeFilterCount =
    (statusFilter ? 1 : 0) + (carrierFilter ? 1 : 0) + (ordersFilter ? 1 : 0) + Object.values(colFilters).filter(Boolean).length
  const clearFilters = () => {
    setStatusFilter('')
    setCarrierFilter('')
    setOrdersFilter('')
    setColFilters(emptyCols)
  }

  const handleToggleActive = async (client: Client) => {
    try {
      const r = await clientService.toggleActive(client.clientCode)
      toast.success(`${client.clientCode} is now ${r.data.status}.`)
      refresh()
    } catch (error) {
      toast.error(error instanceof Error ? error.message : 'Failed to update the client status.')
    }
  }
  const handleDelete = async (client: Client) => {
    try {
      await clientService.deleteClient(client.clientCode)
      toast.success(`Client ${client.clientCode} deleted.`)
      refresh()
    } catch (error) {
      if (error instanceof ApiError && error.errorCode === 'CLIENT_HAS_ORDERS') {
        toast.error(`${client.clientCode} has orders and cannot be deleted — deactivate it instead.`)
      } else {
        toast.error(error instanceof Error ? error.message : 'Failed to delete the client.')
      }
    }
  }

  const sortableHeader = (label: string, key: string) => (
    <th className="px-2.5 py-3">
      <button
        type="button"
        onClick={() => handleSort(key)}
        className={`inline-flex items-center gap-1 uppercase tracking-[0.16em] transition hover:text-slate-950 ${sortBy === key ? 'text-slate-950' : ''}`}
      >
        {label}
        {sortBy === key ? (
          sortDirection === 'ASC' ? <FiArrowUp className="h-3 w-3" /> : <FiArrowDown className="h-3 w-3" />
        ) : (
          <span className="text-slate-300">↕</span>
        )}
      </button>
    </th>
  )
  const filterCell = (key: 'code' | 'name' | 'city', placeholder: string) => (
    <th className="px-2 pb-2.5">
      <input
        value={colFilters[key]}
        onChange={(e) => setColFilters((c) => ({ ...c, [key]: e.target.value }))}
        placeholder={placeholder}
        className="w-full rounded-lg border border-slate-200 bg-white px-2 py-1.5 text-[11.5px] font-medium normal-case tracking-normal text-slate-950 outline-none transition placeholder:text-slate-300 focus:border-[#412d15]"
      />
    </th>
  )

  return (
    <div className="space-y-4">
      {/* Page-specific actions bar — Refresh is global (submenus row). */}
      <div className="flex flex-wrap items-center gap-2">
        <button
          type="button"
          onClick={() => setEditor({ client: null })}
          className="ml-auto inline-flex items-center gap-1.5 rounded-xl bg-[#1f150c] px-3.5 py-2 text-[12.5px] font-semibold text-white transition hover:bg-[#412d15]"
        >
          <FiPlus className="h-3.5 w-3.5" />
          Add Client
        </button>
      </div>

      <section className="rounded-2xl border border-slate-200 bg-white p-5 shadow-sm">
        {/* toolbar */}
        <div className="flex flex-wrap items-center gap-2">
          <label className="flex min-w-[210px] flex-1 items-center gap-2 rounded-xl border border-slate-200 bg-slate-50 px-3 py-2 sm:max-w-xs">
            <FiSearch className="h-3.5 w-3.5 shrink-0 text-slate-400" />
            <input
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              placeholder="Search code, name, city…"
              className="w-full bg-transparent text-[12.5px] text-slate-950 outline-none"
            />
          </label>

          <div className="min-w-[130px]">
            <Select value={statusFilter} onChange={(e) => setStatusFilter(e.target.value)} aria-label="Filter by status">
              <option value="">All statuses</option>
              <option value="ACTIVE">Active</option>
              <option value="INACTIVE">Inactive</option>
            </Select>
          </div>
          <div className="min-w-[130px]">
            <Select value={carrierFilter} onChange={(e) => setCarrierFilter(e.target.value)} aria-label="Filter by carrier">
              <option value="">Any carrier</option>
              <option value="UPS">Has UPS</option>
              <option value="FEDEX">Has FedEx</option>
              <option value="USPS">Has USPS</option>
            </Select>
          </div>
          <div className="min-w-[130px]">
            <Select value={ordersFilter} onChange={(e) => setOrdersFilter(e.target.value)} aria-label="Filter by orders">
              <option value="">All clients</option>
              <option value="YES">With orders</option>
              <option value="NO">No orders</option>
            </Select>
          </div>

          <button
            type="button"
            onClick={() => setShowFilters((c) => !c)}
            aria-pressed={showFilters}
            className={`inline-flex items-center gap-1.5 rounded-xl px-2.5 py-2 text-[12px] font-semibold transition ${
              showFilters || activeFilterCount ? 'bg-[#1f150c] text-white' : 'border border-slate-200 bg-white text-slate-600 hover:bg-slate-50'
            }`}
          >
            <FiFilter className="h-3.5 w-3.5" />
            Filters
            {activeFilterCount ? (
              <span className="rounded-full bg-white/25 px-1.5 py-0.5 text-[10px] tabular-nums">{activeFilterCount}</span>
            ) : null}
          </button>
          {activeFilterCount ? (
            <button
              type="button"
              onClick={clearFilters}
              className="rounded-xl border border-slate-200 bg-white px-2.5 py-2 text-[12px] font-semibold text-slate-600 transition hover:bg-slate-50"
            >
              Clear
            </button>
          ) : null}
        </div>

        {/* table */}
        <div className="mt-3.5 overflow-x-auto">
          <table className="w-full min-w-[900px] text-[13px] text-slate-700">
            <thead className="border-b border-slate-200 text-left text-[10px] uppercase tracking-[0.16em] text-slate-500">
              <tr>
                {sortableHeader('Code', 'code')}
                {sortableHeader('Name', 'name')}
                <th className="px-2.5 py-3">Contact</th>
                <th className="px-2.5 py-3">Country</th>
                <th className="px-2.5 py-3">Carrier accounts</th>
                {sortableHeader('Orders', 'orderCount')}
                <th className="px-2.5 py-3">Status</th>
                <th className="px-2.5 py-3 text-right">Actions</th>
              </tr>
              {showFilters ? (
                <tr className="border-b border-slate-100 bg-slate-50/60">
                  {filterCell('code', 'e.g. ARHDEV')}
                  {filterCell('name', 'name contains')}
                  <th className="px-2 pb-2.5">
                    <input
                      value={colFilters.city}
                      onChange={(e) => setColFilters((c) => ({ ...c, city: e.target.value }))}
                      placeholder="city"
                      className="w-full rounded-lg border border-slate-200 bg-white px-2 py-1.5 text-[11.5px] font-medium normal-case tracking-normal text-slate-950 outline-none focus:border-[#412d15]"
                    />
                  </th>
                  <th className="px-2 pb-2.5" />
                  <th className="px-2 pb-2.5" />
                  <th className="px-2 pb-2.5" />
                  <th className="px-2 pb-2.5" />
                  <th className="px-2 pb-2.5" />
                </tr>
              ) : null}
            </thead>
            <tbody className="divide-y divide-slate-100">
              {clients.map((client) => {
                const active = client.status === 'ACTIVE'
                return (
                  <tr key={client.id} className="transition hover:bg-slate-50/70">
                    <td className="px-2.5 py-3 font-semibold text-slate-950">{client.clientCode}</td>
                    <td className="px-2.5 py-3">
                      <p className="font-medium text-slate-800">{client.name}</p>
                      {client.shipFrom?.city ? (
                        <p className="text-[11.5px] text-slate-500">
                          {client.shipFrom.city}
                          {client.shipFrom.state ? `, ${client.shipFrom.state}` : ''}
                        </p>
                      ) : null}
                    </td>
                    <td className="px-2.5 py-3 text-[12px] text-slate-600">{client.email || client.phone || '—'}</td>
                    <td className="px-2.5 py-3">
                      {client.shipFrom?.country ? (
                        <span
                          title={countryName(client.shipFrom.country)}
                          className="inline-flex items-center rounded-full bg-slate-100 px-2 py-0.5 text-[11px] font-semibold text-slate-700"
                        >
                          {client.shipFrom.country.toUpperCase()}
                        </span>
                      ) : (
                        <span className="text-slate-400">—</span>
                      )}
                    </td>
                    <td className="px-2.5 py-3">
                      {client.carrierAccounts.length ? (
                        <span className="flex flex-wrap gap-1">
                          {client.carrierAccounts.map((a) => (
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
                      ) : (
                        <span className="text-[11.5px] text-slate-400">—</span>
                      )}
                    </td>
                    <td className="px-2.5 py-3 tabular-nums">{client.orderCount}</td>
                    <td className="px-2.5 py-3">
                      <span
                        className={`inline-flex items-center rounded-full px-2.5 py-1 text-[11px] font-semibold ${
                          active ? 'bg-emerald-100 text-emerald-700' : 'bg-slate-200 text-slate-600'
                        }`}
                      >
                        {active ? 'Active' : 'Inactive'}
                      </span>
                    </td>
                    <td className="px-2.5 py-3 text-right">
                      <span className="inline-flex items-center gap-1.5">
                        <button
                          type="button"
                          onClick={() => setCustomsClient(client)}
                          title="Importer / Broker (per destination country)"
                          className="inline-flex items-center gap-1.5 rounded-lg border border-slate-200 bg-white px-2.5 py-1.5 text-[11px] font-semibold text-[#412d15] transition hover:bg-slate-50"
                        >
                          <FiGlobe className="h-3 w-3" />
                          Importer/Broker
                        </button>
                        <button
                          type="button"
                          onClick={() => setEditor({ client })}
                          className="inline-flex items-center gap-1.5 rounded-lg border border-slate-200 bg-white px-2.5 py-1.5 text-[11px] font-semibold text-slate-700 transition hover:bg-slate-50"
                        >
                          <FiEdit3 className="h-3 w-3" />
                          Edit
                        </button>
                        {admin ? (
                          <>
                            <button
                              type="button"
                              onClick={() => void handleToggleActive(client)}
                              className="rounded-lg border border-slate-200 bg-white px-2.5 py-1.5 text-[11px] font-semibold text-slate-700 transition hover:bg-slate-50"
                            >
                              {active ? 'Deactivate' : 'Activate'}
                            </button>
                            <button
                              type="button"
                              onClick={() => void handleDelete(client)}
                              aria-label={`Delete ${client.clientCode}`}
                              className="rounded-lg border border-rose-200 bg-white p-1.5 text-rose-600 transition hover:bg-rose-50"
                            >
                              <FiTrash2 className="h-3.5 w-3.5" />
                            </button>
                          </>
                        ) : null}
                      </span>
                    </td>
                  </tr>
                )
              })}
              {!clients.length ? (
                <tr>
                  <td colSpan={8} className="px-2.5 py-12 text-center text-sm text-slate-500">
                    {loading
                      ? 'Loading clients…'
                      : debouncedSearch || activeFilterCount
                      ? 'No clients match the current filters.'
                      : 'No clients registered yet — add your first client.'}
                  </td>
                </tr>
              ) : null}
            </tbody>
          </table>
        </div>

        {totalElements > 0 ? (
          <TablePagination
            page={page}
            pageSize={pageSize}
            totalPages={totalPages}
            compact
            onPageChange={setPage}
            onPageSizeChange={setPageSize}
          />
        ) : null}
      </section>

      {editor ? (
        <ClientEditorModal
          client={editor.client}
          onClose={() => setEditor(null)}
          onSaved={() => {
            setEditor(null)
            refresh()
          }}
        />
      ) : null}

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
