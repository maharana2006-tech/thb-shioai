import { afterEach, describe, expect, it, vi } from 'vitest'
import { render, screen, cleanup, fireEvent } from '@testing-library/react'
import { DateRangeField } from './FilterPopover'

/**
 * BUG-015 / BUG-055 — the shared date-range filter is used by Orders
 * and Import history. Pre-fix the inputs had no outer min/max, so a
 * 5-digit year flowed through to the backend as a 400, and a
 * manually-inverted range (from > to) produced an empty page with
 * no explanation.
 *
 * These pin: outer bounds attached, inverted-range alert visible.
 */

afterEach(() => cleanup())

describe('DateRangeField · outer bounds + inverted-range (BUG-015 / BUG-055)', () => {
  it('both inputs carry the 2000..2099 outer bounds', () => {
    render(<DateRangeField from="" to="" onChange={vi.fn()} label="Created" />)
    const fromEl = screen.getByLabelText(/Created from/i) as HTMLInputElement
    const toEl = screen.getByLabelText(/Created to/i) as HTMLInputElement
    expect(fromEl.min).toBe('2000-01-01')
    expect(toEl.max).toBe('2099-12-31')
  })

  it('from < to tightens the opposing bound (existing behaviour)', () => {
    render(<DateRangeField from="2026-02-01" to="2026-02-28" onChange={vi.fn()} label="Created" />)
    const fromEl = screen.getByLabelText(/Created from/i) as HTMLInputElement
    const toEl = screen.getByLabelText(/Created to/i) as HTMLInputElement
    expect(fromEl.max).toBe('2026-02-28')
    expect(toEl.min).toBe('2026-02-01')
  })

  it('shows the inverted-range alert when from > to', () => {
    // Browser clamp would normally stop this; the alert covers manual
    // paste / assistive-tech bypasses.
    render(<DateRangeField from="2026-03-10" to="2026-02-01" onChange={vi.fn()} label="Created" />)
    const alert = screen.getByRole('alert')
    expect(alert.textContent).toMatch(/From is after To/)
  })

  it('no alert when from <= to', () => {
    render(<DateRangeField from="2026-02-01" to="2026-02-28" onChange={vi.fn()} label="Created" />)
    expect(screen.queryByRole('alert')).toBeNull()
  })

  it('fires onChange with the typed from value', () => {
    const onChange = vi.fn()
    render(<DateRangeField from="" to="" onChange={onChange} label="Created" />)
    fireEvent.change(screen.getByLabelText(/Created from/i), { target: { value: '2026-02-01' } })
    expect(onChange).toHaveBeenCalledWith('2026-02-01', '')
  })
})
