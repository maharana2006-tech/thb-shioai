import { describe, expect, it } from 'vitest'
import { fireEvent, render, screen } from '@testing-library/react'
import { useRef } from 'react'
import { useFocusTrap } from './useFocusTrap'

/**
 * Sprint 49 Tier 4 Fix 6 — hook contract:
 *   - initial focus moves into the trapped container
 *   - Tab from the last element wraps to the first
 *   - Shift+Tab from the first wraps to the last
 *   - Focus restores to the trigger on unmount / close
 *
 * <p>requestAnimationFrame is polyfilled by jsdom-with-testing-library
 * setup already (harnesses render synchronously); a short flush is
 * simulated by advancing the queued rAF callbacks.
 */

function TestModal({ open }: { open: boolean }) {
  const ref = useRef<HTMLDivElement>(null)
  useFocusTrap(open, ref)
  return open ? (
    <div ref={ref} role="dialog" aria-modal="true">
      <button>First</button>
      <input aria-label="middle" />
      <button>Last</button>
    </div>
  ) : null
}

async function flushRaf() {
  // rAF resolves on next tick in jsdom via a queued microtask.
  await new Promise((r) => setTimeout(r, 20))
}

describe('useFocusTrap', () => {
  it('focuses the first focusable child on open', async () => {
    render(<TestModal open />)
    await flushRaf()
    expect(document.activeElement).toBe(screen.getByRole('button', { name: 'First' }))
  })

  it('Tab from the last element wraps to the first', async () => {
    render(<TestModal open />)
    await flushRaf()
    const first = screen.getByRole('button', { name: 'First' })
    const last = screen.getByRole('button', { name: 'Last' })

    last.focus()
    expect(document.activeElement).toBe(last)

    fireEvent.keyDown(document, { key: 'Tab', shiftKey: false })
    expect(document.activeElement).toBe(first)
  })

  it('Shift+Tab from the first element wraps to the last', async () => {
    render(<TestModal open />)
    await flushRaf()
    const first = screen.getByRole('button', { name: 'First' })
    const last = screen.getByRole('button', { name: 'Last' })

    first.focus()
    expect(document.activeElement).toBe(first)

    fireEvent.keyDown(document, { key: 'Tab', shiftKey: true })
    expect(document.activeElement).toBe(last)
  })

  it('restores focus to the trigger when the modal unmounts', async () => {
    const trigger = document.createElement('button')
    trigger.textContent = 'Open'
    document.body.appendChild(trigger)
    trigger.focus()
    expect(document.activeElement).toBe(trigger)

    const { unmount } = render(<TestModal open />)
    await flushRaf()
    // Focus is now inside the modal.
    expect(document.activeElement).not.toBe(trigger)

    unmount()
    expect(document.activeElement).toBe(trigger)

    document.body.removeChild(trigger)
  })
})
