import { afterEach, describe, expect, it, vi } from 'vitest'
import { render, cleanup, fireEvent, screen } from '@testing-library/react'
import { useRef } from 'react'
import { useModalDismiss } from './useModalDismiss'

const confirmMock = vi.fn()
vi.mock('../utils/notify', () => ({ notify: { confirm: (...a: unknown[]) => confirmMock(...a) } }))

function Dialog({ onClose, confirmDiscard }: { onClose: () => void; confirmDiscard?: boolean }) {
  const ref = useRef<HTMLDivElement>(null)
  const requestClose = useModalDismiss(true, ref, onClose, { confirmDiscard })
  return (
    <div ref={ref} role="dialog">
      <input aria-label="Name" />
      <button type="button" onClick={() => void requestClose()}>Cancel</button>
    </div>
  )
}

afterEach(() => { cleanup(); confirmMock.mockReset() })
const tick = () => new Promise((r) => setTimeout(r, 0))

describe('useModalDismiss (BUG-084)', () => {
  it('untouched dialog closes on Escape without asking', async () => {
    const onClose = vi.fn()
    render(<Dialog onClose={onClose} />)
    fireEvent.keyDown(window, { key: 'Escape' })
    await tick()
    expect(confirmMock).not.toHaveBeenCalled()
    expect(onClose).toHaveBeenCalledTimes(1)
  })

  it('typed value: Escape asks, "Keep editing" keeps the dialog, Cancel asks too', async () => {
    const onClose = vi.fn()
    confirmMock.mockResolvedValue(false)
    render(<Dialog onClose={onClose} />)
    fireEvent.input(screen.getByLabelText('Name'), { target: { value: 'ZZ' } })
    fireEvent.keyDown(window, { key: 'Escape' })
    await tick()
    expect(confirmMock).toHaveBeenCalledTimes(1)
    expect(onClose).not.toHaveBeenCalled()

    confirmMock.mockResolvedValue(true)
    fireEvent.click(screen.getByText('Cancel'))
    await tick()
    expect(onClose).toHaveBeenCalledTimes(1)
  })

  it('confirmDiscard: false leaves the dirty check to the dialog', async () => {
    const onClose = vi.fn()
    render(<Dialog onClose={onClose} confirmDiscard={false} />)
    fireEvent.input(screen.getByLabelText('Name'), { target: { value: 'ZZ' } })
    fireEvent.keyDown(window, { key: 'Escape' })
    await tick()
    expect(confirmMock).not.toHaveBeenCalled()
    expect(onClose).toHaveBeenCalledTimes(1)
  })
})
