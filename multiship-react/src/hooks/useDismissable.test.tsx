import { describe, expect, it, afterEach, vi } from 'vitest'
import { render, cleanup, fireEvent, screen } from '@testing-library/react'
import { useDismissable } from './useDismissable'

/** BUG-200 — Escape closes the popover and hands focus back to its button. */
function Pop({ onClose }: { onClose: () => void }) {
  const ref = useDismissable(true, onClose)
  return (
    <div ref={ref}>
      <button type="button" aria-expanded="true">Filters</button>
      <div role="dialog"><input aria-label="Carrier" /></div>
    </div>
  )
}

afterEach(cleanup)

describe('useDismissable', () => {
  it('Escape closes and focuses the trigger', () => {
    const onClose = vi.fn()
    render(<Pop onClose={onClose} />)
    screen.getByLabelText('Carrier').focus()
    fireEvent.keyDown(document, { key: 'Escape' })
    expect(onClose).toHaveBeenCalledTimes(1)
    expect(document.activeElement).toBe(screen.getByRole('button', { name: 'Filters' }))
  })
})
