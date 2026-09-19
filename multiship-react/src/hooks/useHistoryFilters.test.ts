import { describe, it, expect } from 'vitest'
import { renderHook, act } from '@testing-library/react'
import { useHistoryFilters } from './useHistoryFilters'
import type { ImportBatchSummary } from '../api/orderImportService'

/**
 * F5-A — hook coverage for the advanced filter + sort + pagination
 * state extracted from DataHistoryPage. Cases mirror what the
 * pre-extraction toolbar visibly did: status chip filtering, search
 * across four fields, sort direction + key, date range, advanced
 * dropdowns, and the derived {@code creators} / {@code statusCounts} /
 * {@code activeAdvancedCount} / {@code anyFilterActive} values that
 * drive chip badges and clear-all button visibility.
 */

function batch(
  id: number,
  overrides: Partial<ImportBatchSummary> = {},
): ImportBatchSummary {
  return {
    id,
    fileName: `file-${id}.csv`,
    createdBy: `user${id}`,
    createdAt: `2026-09-19T10:${String(id).padStart(2, '0')}:00Z`,
    status: 'COMPLETE',
    totalRows: 10,
    savedRows: 10,
    invalidRows: 0,
    labelBatchId: null,
    source: 'BULK',
    ...overrides,
  } as ImportBatchSummary
}

describe('useHistoryFilters', () => {
  it('returns every batch when no filters are set', () => {
    const rows = [batch(1), batch(2), batch(3)]
    const { result } = renderHook(() => useHistoryFilters(rows))
    expect(result.current.filtered).toHaveLength(3)
    expect(result.current.anyFilterActive).toBe(false)
  })

  it('narrows by status chip', () => {
    const rows = [
      batch(1, { status: 'COMPLETE' }),
      batch(2, { status: 'FAILED' }),
      batch(3, { status: 'FAILED' }),
    ]
    const { result } = renderHook(() => useHistoryFilters(rows))
    act(() => result.current.setStatusFilter('FAILED'))
    expect(result.current.filtered.map((b) => b.id)).toEqual([3, 2])
    expect(result.current.anyFilterActive).toBe(true)
  })

  it('searches across fileName + createdBy + id + labelBatchId', () => {
    const rows = [
      batch(1, { fileName: 'orders-q3.csv' }),
      batch(2, { fileName: 'orders-q4.csv', labelBatchId: 909 }),
      batch(3, { fileName: 'refunds.csv', createdBy: 'alice' }),
    ]
    const { result } = renderHook(() => useHistoryFilters(rows))

    act(() => result.current.setSearch('q3'))
    expect(result.current.filtered.map((b) => b.id)).toEqual([1])

    act(() => result.current.setSearch('909'))
    expect(result.current.filtered.map((b) => b.id)).toEqual([2])

    act(() => result.current.setSearch('alice'))
    expect(result.current.filtered.map((b) => b.id)).toEqual([3])
  })

  it('applies DRAFT/IN_PROGRESS-first status sort tiebreaker', () => {
    const rows = [
      batch(1, { status: 'COMPLETE' }),
      batch(2, { status: 'IN_PROGRESS' }),
      batch(3, { status: 'FAILED' }),
      batch(4, { status: 'DRAFT' }),
    ]
    const { result } = renderHook(() => useHistoryFilters(rows))
    act(() => result.current.setSortKey('status'))
    act(() => result.current.setSortDir('ASC'))
    // DRAFT(0)+IN_PROGRESS(0) tie, broken by id ascending — id 2 (IN_PROGRESS)
    // comes before id 4 (DRAFT); then FAILED(3) then COMPLETE(4).
    expect(result.current.filtered.map((b) => b.status)).toEqual([
      'IN_PROGRESS',
      'DRAFT',
      'FAILED',
      'COMPLETE',
    ])
  })

  it('flips sort direction via functional setSortDir', () => {
    const rows = [batch(1), batch(2), batch(3)]
    const { result } = renderHook(() => useHistoryFilters(rows))
    // Default: DESC. IDs 3, 2, 1 by created (batch-id in fixture drives timestamp).
    expect(result.current.filtered.map((b) => b.id)).toEqual([3, 2, 1])
    act(() => result.current.setSortDir((d) => (d === 'DESC' ? 'ASC' : 'DESC')))
    expect(result.current.filtered.map((b) => b.id)).toEqual([1, 2, 3])
  })

  it('narrows by date range (inclusive)', () => {
    const rows = [
      batch(1, { createdAt: '2026-09-01T00:00:00Z' }),
      batch(2, { createdAt: '2026-09-10T12:00:00Z' }),
      batch(3, { createdAt: '2026-09-20T23:00:00Z' }),
    ]
    const { result } = renderHook(() => useHistoryFilters(rows))
    act(() => result.current.setDateFrom('2026-09-05'))
    act(() => result.current.setDateTo('2026-09-15'))
    expect(result.current.filtered.map((b) => b.id)).toEqual([2])
    expect(result.current.dateFilterActive).toBe(true)
  })

  it('narrows by advanced createdBy + label-batch presence + min saved', () => {
    const rows = [
      batch(1, { createdBy: 'alice', labelBatchId: 100, savedRows: 5 }),
      batch(2, { createdBy: 'alice', labelBatchId: null, savedRows: 50 }),
      batch(3, { createdBy: 'bob', labelBatchId: 101, savedRows: 5 }),
    ]
    const { result } = renderHook(() => useHistoryFilters(rows))

    act(() => result.current.setCreatedBy('alice'))
    expect(result.current.filtered.map((b) => b.id)).toEqual([2, 1])
    expect(result.current.activeAdvancedCount).toBe(1)

    act(() => result.current.setBatchPresence('HAS'))
    expect(result.current.filtered.map((b) => b.id)).toEqual([1])
    expect(result.current.activeAdvancedCount).toBe(2)

    act(() => result.current.setMinSaved('40'))
    expect(result.current.filtered.map((b) => b.id)).toEqual([])
    expect(result.current.activeAdvancedCount).toBe(3)
  })

  it('exposes distinct sorted creators and per-status counts', () => {
    const rows = [
      batch(1, { createdBy: 'zed', status: 'COMPLETE' }),
      batch(2, { createdBy: 'alice', status: 'FAILED' }),
      batch(3, { createdBy: 'alice', status: 'COMPLETE' }),
      batch(4, { createdBy: null, status: 'DRAFT' }),
    ]
    const { result } = renderHook(() => useHistoryFilters(rows))
    expect(result.current.creators).toEqual(['alice', 'zed'])
    expect(result.current.statusCounts.ALL).toBe(4)
    expect(result.current.statusCounts.COMPLETE).toBe(2)
    expect(result.current.statusCounts.FAILED).toBe(1)
    expect(result.current.statusCounts.DRAFT).toBe(1)
  })

  it('clearFilters resets every user-visible filter but keeps sort', () => {
    const rows = [batch(1), batch(2), batch(3)]
    const { result } = renderHook(() => useHistoryFilters(rows))
    act(() => {
      result.current.setSearch('foo')
      result.current.setStatusFilter('FAILED')
      result.current.setDateFrom('2026-09-01')
      result.current.setCreatedBy('alice')
      result.current.setBatchPresence('HAS')
      result.current.setMinSaved('10')
      result.current.setSortDir('ASC')
    })
    expect(result.current.anyFilterActive).toBe(true)

    act(() => result.current.clearFilters())
    expect(result.current.search).toBe('')
    expect(result.current.statusFilter).toBe('ALL')
    expect(result.current.dateFrom).toBe('')
    expect(result.current.createdBy).toBe('')
    expect(result.current.batchPresence).toBe('ANY')
    expect(result.current.minSaved).toBe('')
    expect(result.current.anyFilterActive).toBe(false)
    // Sort is a user preference, not a "filter" — preserved on clear.
    expect(result.current.sortDir).toBe('ASC')
  })
})
