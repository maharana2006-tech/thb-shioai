import { describe, expect, it, vi, afterEach } from 'vitest'
import { render, screen, cleanup, fireEvent, waitFor } from '@testing-library/react'
import AdvancedDataTable, { type ColumnDef } from './AdvancedDataTable'

/**
 * BUG-011 — pre-fix the Export button fired on every click, including
 * rapid double-clicks that kicked off a second parallel export loop
 * before the first finished. These pin the three new behaviours:
 *   · onExport running → button disabled + "Exporting…" label
 *   · double-click → onExport fires exactly once
 *   · when done → button re-enables for the next export
 */

interface Row { id: number; name: string }
const columns: ColumnDef<Row, unknown>[] = [
  { id: 'id', header: 'Id', accessorKey: 'id' },
  { id: 'name', header: 'Name', accessorKey: 'name' },
]
const data: Row[] = [{ id: 1, name: 'Alice' }, { id: 2, name: 'Bob' }]

afterEach(() => cleanup())

describe('AdvancedDataTable · Export busy state (BUG-011)', () => {
  it('disables Export + swaps label while onExport runs; re-enables after', async () => {
    let resolveExport: () => void = () => {}
    const onExport = vi.fn(() => new Promise<void>((r) => { resolveExport = r }))

    render(<AdvancedDataTable tableKey="t-bug011-busy" columns={columns} data={data} onExport={onExport} />)

    const btn = screen.getByRole('button', { name: /Export/i }) as HTMLButtonElement
    expect(btn.disabled).toBe(false)
    fireEvent.click(btn)

    // While the promise is in flight the button is disabled, labelled
    // Exporting…, and aria-busy is set.
    await waitFor(() => expect(btn.disabled).toBe(true))
    expect(btn.textContent).toMatch(/Exporting/)
    expect(btn.getAttribute('aria-busy')).toBe('true')
    expect(onExport).toHaveBeenCalledTimes(1)

    // Finish; button goes back to idle.
    resolveExport()
    await waitFor(() => expect(btn.disabled).toBe(false))
    expect(btn.textContent).toMatch(/^Export$/)
    expect(btn.getAttribute('aria-busy')).toBe(null)
  })

  it('a rapid second click while running is a no-op', async () => {
    let resolveExport: () => void = () => {}
    const onExport = vi.fn(() => new Promise<void>((r) => { resolveExport = r }))

    render(<AdvancedDataTable tableKey="t-bug011-dbl" columns={columns} data={data} onExport={onExport} />)

    const btn = screen.getByRole('button', { name: /Export/i }) as HTMLButtonElement
    fireEvent.click(btn)
    fireEvent.click(btn)
    fireEvent.click(btn)

    await waitFor(() => expect(btn.disabled).toBe(true))
    // Only the first click reached onExport; the rest were ignored
    // (button disabled + runExport() guards with the exporting flag).
    expect(onExport).toHaveBeenCalledTimes(1)

    resolveExport()
    await waitFor(() => expect(btn.disabled).toBe(false))
  })
})
