import { describe, expect, it, afterEach } from 'vitest'
import { render, screen, cleanup, fireEvent, within } from '@testing-library/react'
import AdvancedDataTable, { type ColumnDef } from './AdvancedDataTable'

/**
 * BUG-009 — pre-fix Reset layout snapped the Batch column back to
 * hidden on the Orders list. Root cause: Reset wrote `defaultVisibility`
 * which was derived from `initialHiddenColumns`. The Orders page
 * auto-adds `batchId` to that list when the current page has no
 * batchIds, so Reset hid a column the operator had deliberately
 * enabled.
 *
 * Reset now writes `{}` (empty visibility map) so every column comes
 * back; the operator can re-hide via Columns if they want.
 */

interface Row { id: number; name: string; batchId?: number | null }
const columns: ColumnDef<Row, unknown>[] = [
  { id: 'id', header: 'Id', accessorKey: 'id' },
  { id: 'name', header: 'Name', accessorKey: 'name' },
  { id: 'batchId', header: 'Batch', accessorKey: 'batchId' },
]
const data: Row[] = [{ id: 1, name: 'Alice' }]

afterEach(() => {
  cleanup()
  window.localStorage.clear()
})

describe('AdvancedDataTable · Reset layout (BUG-009)', () => {
  it('Reset makes every column visible, even one auto-hidden via initialHiddenColumns', () => {
    render(
      <AdvancedDataTable
        tableKey="t-bug009-reset"
        columns={columns}
        data={data}
        initialHiddenColumns={['batchId']}
      />,
    )

    // Batch column starts hidden (initialHiddenColumns feeds defaults
    // on first mount) — the cell count on the data row proves it.
    const bodyRow = screen.getByText('Alice').closest('tr') as HTMLTableRowElement
    const cellsBefore = within(bodyRow).getAllByRole('cell').length
    expect(cellsBefore).toBe(2) // Id + Name only, Batch hidden

    // Columns menu opens; "Reset layout" button lives there.
    fireEvent.click(screen.getByRole('button', { name: /Columns/i }))
    fireEvent.click(screen.getByRole('button', { name: /Reset layout/i }))

    // Batch comes back after Reset — the audit's canonical regression.
    const cellsAfter = within(bodyRow).getAllByRole('cell').length
    expect(cellsAfter).toBe(3)
  })
})
