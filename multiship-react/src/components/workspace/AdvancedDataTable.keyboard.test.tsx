import { describe, expect, it, afterEach } from 'vitest'
import { render, screen, cleanup, fireEvent } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import AdvancedDataTable, { type ColumnDef } from './AdvancedDataTable'

/** BUG-198 — a sortable heading is a button (Enter sorts); the drag grip is not a tab stop. */

interface Row { id: number; name: string }
const columns: ColumnDef<Row, unknown>[] = [
  { id: 'id', header: 'Id', accessorKey: 'id' },
  { id: 'name', header: 'Name', accessorKey: 'name' },
]
const data: Row[] = [{ id: 1, name: 'Zed' }, { id: 2, name: 'Amy' }]

afterEach(() => { cleanup(); window.localStorage.clear() })

describe('AdvancedDataTable · keyboard sorting (BUG-198)', () => {
  it('Enter on the Name heading sorts the rows and the grip is skipped by Tab', async () => {
    render(<AdvancedDataTable<Row> tableKey="kb-test" columns={columns} data={data} getRowId={(r) => String(r.id)} />)
    // The dnd-kit grip is also role=button, so pick the real <button> in the heading.
    const nameBtn = screen.getAllByRole('button', { name: 'Name' }).find((b) => b.tagName === 'BUTTON') as HTMLElement
    expect(nameBtn).toBeTruthy()
    nameBtn.focus()
    await userEvent.keyboard('{Enter}')
    const cells = screen.getAllByRole('cell').map((c) => c.textContent)
    expect(cells.indexOf('Amy')).toBeLessThan(cells.indexOf('Zed'))
    expect(nameBtn.closest('th')?.getAttribute('aria-sort')).toBe('ascending')

    const grips = document.querySelectorAll('th [aria-roledescription="sortable"]')
    expect(grips.length).toBeGreaterThan(0)
    grips.forEach((g) => expect(g.getAttribute('tabindex')).toBe('-1'))
    fireEvent.keyDown(nameBtn, { key: 'Enter' })
  })
})
