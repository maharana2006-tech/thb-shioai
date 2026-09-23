/**
 * The Bulk Mailer list's filter, sort and search state. The server applies
 * them (see bulkService.listBatches); this only holds what the toolbar set.
 */
import { useState } from 'react'

export type HistoryStatusKey =
  | 'ALL'
  | 'COMPLETE'
  | 'PARTIAL_COMPLETE'
  | 'IN_PROGRESS'
  | 'INITIATE'
  | 'DRAFT'
  | 'FAILED'

export type HistorySortKey =
  | 'created'
  | 'fileName'
  | 'savedRows'
  | 'status'
  | 'labelBatch'

export type BatchPresenceKey = 'ANY' | 'HAS' | 'NONE'

export function useHistoryFilters() {
  const [search, setSearch] = useState('')
  const [statusFilter, setStatusFilter] = useState<HistoryStatusKey>('ALL')
  const [sortKey, setSortKey] = useState<HistorySortKey>('created')
  const [sortDir, setSortDir] = useState<'ASC' | 'DESC'>('DESC')
  const [dateFrom, setDateFrom] = useState('')
  const [dateTo, setDateTo] = useState('')
  const [createdBy, setCreatedBy] = useState('')
  const [batchPresence, setBatchPresence] = useState<BatchPresenceKey>('ANY')
  const [minSaved, setMinSaved] = useState('')

  const dateFilterActive = dateFrom !== '' || dateTo !== ''
  const anyFilterActive =
    search.trim() !== '' || statusFilter !== 'ALL' || dateFilterActive
    || createdBy !== '' || batchPresence !== 'ANY' || minSaved !== ''

  /** Every filter back to its default; the sort is a preference and stays. */
  const clearFilters = () => {
    setSearch('')
    setStatusFilter('ALL')
    setDateFrom('')
    setDateTo('')
    setCreatedBy('')
    setBatchPresence('ANY')
    setMinSaved('')
  }

  return {
    search, setSearch,
    statusFilter, setStatusFilter,
    sortKey, setSortKey,
    sortDir, setSortDir,
    dateFrom, setDateFrom,
    dateTo, setDateTo,
    createdBy, setCreatedBy,
    batchPresence, setBatchPresence,
    minSaved, setMinSaved,
    dateFilterActive,
    anyFilterActive,
    clearFilters,
  }
}
