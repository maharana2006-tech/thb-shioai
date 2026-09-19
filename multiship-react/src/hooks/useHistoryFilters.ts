/**
 * F5-A — advanced filter + sort + pagination state for the imports view
 * of DataHistoryPage, extracted from the monolithic component so the
 * search-and-filter surface is testable in isolation and no longer takes
 * up ~120 lines in the middle of a 2K-LoC file.
 *
 * <p>Pure client-side: the history list is already fully loaded, so
 * every filter recomputes on the current in-memory batches array.
 * Returned {@code filtered} matches the pre-extraction ordering
 * exactly, including the DRAFT/IN_PROGRESS-first status tiebreaker.
 */
import { useEffect, useMemo, useState } from 'react'
import type { ImportBatchSummary } from '../api/orderImportService'

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

export interface UseHistoryFiltersResult {
  search: string
  setSearch: (v: string) => void
  statusFilter: HistoryStatusKey
  setStatusFilter: (v: HistoryStatusKey) => void
  sortKey: HistorySortKey
  setSortKey: (v: HistorySortKey) => void
  sortDir: 'ASC' | 'DESC'
  setSortDir: (v: 'ASC' | 'DESC' | ((prev: 'ASC' | 'DESC') => 'ASC' | 'DESC')) => void
  showAdvanced: boolean
  setShowAdvanced: (v: boolean | ((prev: boolean) => boolean)) => void
  dateFrom: string
  setDateFrom: (v: string) => void
  dateTo: string
  setDateTo: (v: string) => void
  createdBy: string
  setCreatedBy: (v: string) => void
  batchPresence: BatchPresenceKey
  setBatchPresence: (v: BatchPresenceKey) => void
  minSaved: string
  setMinSaved: (v: string) => void

  creators: string[]
  statusCounts: Record<string, number>
  activeAdvancedCount: number
  dateFilterActive: boolean
  anyFilterActive: boolean
  filtered: ImportBatchSummary[]
  clearFilters: () => void
}

export function useHistoryFilters(
  batches: ImportBatchSummary[],
): UseHistoryFiltersResult {
  const [search, setSearch] = useState('')
  const [statusFilter, setStatusFilter] = useState<HistoryStatusKey>('ALL')
  const [sortKey, setSortKey] = useState<HistorySortKey>('created')
  const [sortDir, setSortDir] = useState<'ASC' | 'DESC'>('DESC')
  const [showAdvanced, setShowAdvanced] = useState(false)
  const [dateFrom, setDateFrom] = useState('')
  const [dateTo, setDateTo] = useState('')
  const [createdBy, setCreatedBy] = useState('')
  const [batchPresence, setBatchPresence] = useState<BatchPresenceKey>('ANY')
  const [minSaved, setMinSaved] = useState('')

  // Pagination scaffolding — kept for the pager-UI PR that hasn't landed
  // yet. Preserving the reset-on-filter-change behavior from the
  // pre-extraction component means the effect below still fires; the
  // page value is not surfaced because no pager consumes it yet.
  const [, setPage] = useState(1)
  const [pageSize] = useState(10)

  const creators = useMemo(
    () =>
      Array.from(
        new Set(batches.map((b) => b.createdBy).filter((v): v is string => !!v)),
      ).sort(),
    [batches],
  )

  const statusCounts = useMemo(() => {
    const c: Record<string, number> = { ALL: batches.length }
    for (const b of batches) {
      const k = (b.status || '').toUpperCase()
      c[k] = (c[k] ?? 0) + 1
    }
    return c
  }, [batches])

  const activeAdvancedCount =
    (createdBy ? 1 : 0) + (batchPresence !== 'ANY' ? 1 : 0) + (minSaved ? 1 : 0)
  const dateFilterActive = dateFrom !== '' || dateTo !== ''
  const anyFilterActive =
    search.trim() !== '' ||
    statusFilter !== 'ALL' ||
    dateFilterActive ||
    activeAdvancedCount > 0

  const clearFilters = () => {
    setSearch('')
    setStatusFilter('ALL')
    setDateFrom('')
    setDateTo('')
    setCreatedBy('')
    setBatchPresence('ANY')
    setMinSaved('')
  }

  const filtered = useMemo(() => {
    const q = search.trim().toLowerCase()
    const from = dateFrom ? new Date(dateFrom + 'T00:00:00').getTime() : null
    const to = dateTo ? new Date(dateTo + 'T23:59:59').getTime() : null
    const min = minSaved ? Number(minSaved) : null

    const rows = batches.filter((b) => {
      if (statusFilter !== 'ALL' && (b.status || '').toUpperCase() !== statusFilter) return false
      if (q) {
        const hay = `${b.fileName ?? ''} ${b.createdBy ?? ''} #${b.id} batch ${b.labelBatchId ?? ''}`.toLowerCase()
        if (!hay.includes(q)) return false
      }
      if (createdBy && b.createdBy !== createdBy) return false
      if (batchPresence === 'HAS' && b.labelBatchId == null) return false
      if (batchPresence === 'NONE' && b.labelBatchId != null) return false
      if (min != null && b.savedRows < min) return false
      if (from != null || to != null) {
        const t = b.createdAt ? new Date(b.createdAt).getTime() : NaN
        if (Number.isNaN(t)) return false
        if (from != null && t < from) return false
        if (to != null && t > to) return false
      }
      return true
    })

    // DRAFT/IN_PROGRESS first so the fresh-work rows don't get buried
    // under COMPLETE ones. Same order as the pre-extraction component.
    const statusOrder: Record<string, number> = {
      DRAFT: 0,
      IN_PROGRESS: 0,
      INITIATE: 1,
      PARTIAL_COMPLETE: 2,
      FAILED: 3,
      COMPLETE: 4,
    }
    const dir = sortDir === 'ASC' ? 1 : -1
    rows.sort((a, b) => {
      let cmp: number
      switch (sortKey) {
        case 'fileName':
          cmp = (a.fileName || '').localeCompare(b.fileName || '')
          break
        case 'savedRows':
          cmp = a.totalRows - b.totalRows
          break
        case 'labelBatch':
          cmp = (a.labelBatchId ?? -1) - (b.labelBatchId ?? -1)
          break
        case 'status':
          cmp =
            (statusOrder[(a.status || '').toUpperCase()] ?? 9) -
            (statusOrder[(b.status || '').toUpperCase()] ?? 9)
          break
        default: {
          const ta = a.createdAt ? new Date(a.createdAt).getTime() : 0
          const tb = b.createdAt ? new Date(b.createdAt).getTime() : 0
          cmp = ta - tb
        }
      }
      if (cmp === 0) cmp = a.id - b.id
      return cmp * dir
    })
    return rows
  }, [
    batches,
    search,
    statusFilter,
    sortKey,
    sortDir,
    dateFrom,
    dateTo,
    createdBy,
    batchPresence,
    minSaved,
  ])

  // Snap paging back to 1 whenever the filter/sort set changes so the
  // user never lands on an out-of-range page after narrowing results.
  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- user-input-driven, not derivable at render
    setPage(1)
  }, [
    search,
    statusFilter,
    sortKey,
    sortDir,
    dateFrom,
    dateTo,
    createdBy,
    batchPresence,
    minSaved,
    pageSize,
  ])

  return {
    search,
    setSearch,
    statusFilter,
    setStatusFilter,
    sortKey,
    setSortKey,
    sortDir,
    setSortDir,
    showAdvanced,
    setShowAdvanced,
    dateFrom,
    setDateFrom,
    dateTo,
    setDateTo,
    createdBy,
    setCreatedBy,
    batchPresence,
    setBatchPresence,
    minSaved,
    setMinSaved,

    creators,
    statusCounts,
    activeAdvancedCount,
    dateFilterActive,
    anyFilterActive,
    filtered,
    clearFilters,
  }
}
