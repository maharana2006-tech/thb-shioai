import { describe, it, expect } from 'vitest'
import { renderHook, act } from '@testing-library/react'
import { useHistoryFilters } from './useHistoryFilters'

describe('useHistoryFilters', () => {
  it('clearFilters resets every filter but keeps the sort', () => {
    const { result } = renderHook(() => useHistoryFilters())
    expect(result.current.anyFilterActive).toBe(false)

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
    expect(result.current.dateFilterActive).toBe(true)

    act(() => result.current.clearFilters())
    expect(result.current.search).toBe('')
    expect(result.current.statusFilter).toBe('ALL')
    expect(result.current.dateFrom).toBe('')
    expect(result.current.createdBy).toBe('')
    expect(result.current.batchPresence).toBe('ANY')
    expect(result.current.minSaved).toBe('')
    expect(result.current.anyFilterActive).toBe(false)
    expect(result.current.dateFilterActive).toBe(false)
    // Sort is a user preference, not a filter.
    expect(result.current.sortDir).toBe('ASC')
  })
})
