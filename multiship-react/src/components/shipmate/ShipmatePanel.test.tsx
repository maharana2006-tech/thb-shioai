import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import ShipmatePanel from './ShipmatePanel'
import { ShipmateError, shipmateService, type ShipmateEvent, type ShipmateRequest } from '../../api/shipmateService'

/** Each call to send() plays the next script; a thrown value ends that reply as a failure. */
const script = (...replies: Array<ReadonlyArray<ShipmateEvent | Error>>) => {
  const queue = [...replies]
  return vi.spyOn(shipmateService, 'send').mockImplementation(async function* () {
    for (const step of queue.shift() ?? []) {
      if (step instanceof Error) throw step
      yield step
    }
  })
}

const renderPanel = (onClose = vi.fn()) => {
  render(
    <MemoryRouter initialEntries={['/orders/new']}>
      <ShipmatePanel open onClose={onClose} />
    </MemoryRouter>,
  )
  return { onClose, box: screen.getByRole('textbox', { name: 'Message Shipmate' }) }
}

// jsdom has no pointer capture, which the resize handle uses on a real pointer.
Element.prototype.setPointerCapture = () => {}
Element.prototype.hasPointerCapture = () => false

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
})

describe('ShipmatePanel', () => {
  it('sends the message with the current page and joins the streamed pieces into one reply', async () => {
    const send = script([{ type: 'text', delta: 'Hello ' }, { type: 'text', delta: 'there' }])
    const { box } = renderPanel()

    await userEvent.type(box, 'hi{Enter}')

    expect(await screen.findByText('Hello there')).toBeInTheDocument()
    expect(send.mock.calls[0][0]).toEqual({ message: 'hi', page: 'New Shipment', history: [] })
    expect(box).toHaveValue('')
  })

  it('each new message carries the conversation so far', async () => {
    const send = script([{ type: 'text', delta: 'I cannot see rates yet.' }], [{ type: 'text', delta: 'Same answer.' }])
    const { box } = renderPanel()

    await userEvent.type(box, 'cheapest to Leeds?{Enter}')
    await screen.findByText('I cannot see rates yet.')
    await userEvent.type(box, 'and to Berlin?{Enter}')
    await screen.findByText('Same answer.')

    expect(send.mock.calls[1][0]).toEqual({
      message: 'and to Berlin?',
      page: 'New Shipment',
      history: [
        { role: 'user', text: 'cheapest to Leeds?' },
        { role: 'assistant', text: 'I cannot see rates yet.' },
      ],
    })
  })

  it('Stop ends the reply without showing a failure', async () => {
    vi.spyOn(shipmateService, 'send').mockImplementation(async function* (_request: ShipmateRequest, signal: AbortSignal) {
      yield { type: 'text', delta: 'Working' }
      await new Promise((_resolve, reject) => signal.addEventListener('abort', () => reject(signal.reason)))
    })
    const { box } = renderPanel()

    await userEvent.type(box, 'long job{Enter}')
    await userEvent.click(await screen.findByRole('button', { name: 'Stop' }))

    expect(await screen.findByRole('button', { name: 'Send' })).toBeInTheDocument()
    expect(screen.getByText('Working')).toBeInTheDocument()
    expect(screen.getByText('Stopped')).toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('an unreachable AI offers Try again, which resends the same message', async () => {
    const send = script([new ShipmateError('unavailable')], [{ type: 'text', delta: 'Back online' }])
    const { box } = renderPanel()

    await userEvent.type(box, 'rates?{Enter}')
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('still works by hand')
    await userEvent.click(within(alert).getByRole('button', { name: 'Try again' }))

    expect(await screen.findByText('Back online')).toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    expect(send.mock.calls[1][0]).toEqual(send.mock.calls[0][0])
  })

  it('hitting the per-minute limit is explained and can be retried', async () => {
    script([new ShipmateError('limit')], [{ type: 'text', delta: 'Here now' }])
    const { box } = renderPanel()

    await userEvent.type(box, 'rates?{Enter}')
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('per-minute limit')
    await userEvent.click(within(alert).getByRole('button', { name: 'Try again' }))

    expect(await screen.findByText('Here now')).toBeInTheDocument()
  })

  it('a confirm card does nothing until answered, then sends the decision', async () => {
    const send = script(
      [{
        type: 'confirm',
        id: 'c1',
        title: 'Create 8 labels',
        details: [{ label: 'Royal Mail Tracked 24', value: '7 × £4.85' }],
        total: '£41.30',
        confirmLabel: 'Create 8 labels',
      }],
      [{ type: 'text', delta: 'Done' }],
    )
    const { box } = renderPanel()

    await userEvent.type(box, 'label them{Enter}')
    const card = await screen.findByRole('region', { name: 'Create 8 labels' })
    expect(within(card).getByText('Needs your OK')).toBeInTheDocument()
    expect(within(card).getByText('£41.30')).toBeInTheDocument()
    expect(send).toHaveBeenCalledTimes(1)
    await userEvent.click(within(card).getByRole('button', { name: 'Create 8 labels' }))

    expect(await screen.findByText('Done')).toBeInTheDocument()
    expect(send.mock.calls[1][0]).toMatchObject({ decision: { id: 'c1', accepted: true } })
    expect(within(card).getByText('Confirmed')).toBeInTheDocument()
    expect(within(card).queryByRole('button')).not.toBeInTheDocument()
  })

  it('progress updates one bar in place', async () => {
    script([
      { type: 'progress', id: 'p1', label: 'orders.csv', done: 40, total: 240 },
      { type: 'progress', id: 'p1', label: 'orders.csv', done: 240, total: 240 },
    ])
    const { box } = renderPanel()

    await userEvent.type(box, 'check file{Enter}')

    expect(await screen.findByText('240 of 240')).toBeInTheDocument()
    expect(screen.getAllByRole('progressbar')).toHaveLength(1)
  })

  it('shows what Shipmate is doing until the answer arrives, then the result with its pick', async () => {
    let release = () => {}
    vi.spyOn(shipmateService, 'send').mockImplementation(async function* () {
      yield { type: 'status', text: 'Getting live rates' }
      await new Promise<void>((resolve) => { release = resolve })
      yield {
        type: 'table',
        title: 'Rates to Leeds',
        columns: ['Service', 'Price'],
        rows: [['Royal Mail', '£4.85'], ['DPD', '£7.35']],
        pick: { row: 0, label: 'Cheapest' },
      }
    })
    const { box } = renderPanel()

    await userEvent.type(box, 'rates{Enter}')
    expect(await screen.findByRole('status')).toHaveTextContent('Getting live rates')
    release()

    const table = within(await screen.findByRole('region', { name: 'Rates to Leeds' }))
    expect(table.getByRole('row', { name: /Royal Mail.*Cheapest.*£4\.85/ })).toBeInTheDocument()
    expect(table.getAllByText('Cheapest')).toHaveLength(1)
    expect(screen.queryByRole('status')).not.toBeInTheDocument()
  })

  it('the welcome screen leads with the prompt for the current page and sends it on click', async () => {
    const send = script([{ type: 'text', delta: 'ok' }])
    renderPanel()

    const cards = screen.getAllByRole('button', { name: /invoice|DAP or DDP|fragile|can you do/i })
    expect(cards).toHaveLength(4)
    expect(cards[0]).toHaveTextContent('commercial invoice')
    await userEvent.click(cards[0])

    expect(await screen.findByText('ok')).toBeInTheDocument()
    expect(send.mock.calls[0][0].message).toBe('What goes on a commercial invoice?')
  })

  it('New chat clears the conversation back to the welcome screen', async () => {
    script([{ type: 'text', delta: 'Hello' }])
    const { box } = renderPanel()
    expect(screen.getByRole('button', { name: 'New chat' })).toBeDisabled()

    await userEvent.type(box, 'hi{Enter}')
    await screen.findByText('Hello')
    await userEvent.click(screen.getByRole('button', { name: 'New chat' }))

    expect(screen.queryByText('Hello')).not.toBeInTheDocument()
    expect(screen.getByText(/You are on New Shipment/)).toBeInTheDocument()
  })

  it('Copy puts the reply on the clipboard', async () => {
    const user = userEvent.setup()
    script([{ type: 'text', delta: 'Tracked 24 is cheapest' }])
    const { box } = renderPanel()

    await user.type(box, 'hi{Enter}')
    await user.click(await screen.findByRole('button', { name: 'Copy' }))

    expect(await screen.findByRole('button', { name: 'Copied' })).toBeInTheDocument()
    expect(await navigator.clipboard.readText()).toBe('Tracked 24 is cheapest')
  })

  it('the docked panel resizes from the keyboard within its limits and resets on double-click', async () => {
    const onWidthChange = vi.fn()
    render(
      <MemoryRouter>
        <ShipmatePanel open onClose={vi.fn()} width={400} onWidthChange={onWidthChange} />
      </MemoryRouter>,
    )
    const handle = screen.getByRole('separator', { name: 'Resize Shipmate' })

    handle.focus()
    await userEvent.keyboard('{ArrowLeft}')
    expect(onWidthChange).toHaveBeenLastCalledWith(424)
    await userEvent.keyboard('{ArrowRight}')
    expect(onWidthChange).toHaveBeenLastCalledWith(376)
    await userEvent.dblClick(handle)
    expect(onWidthChange).toHaveBeenLastCalledWith(380)

    cleanup()
    render(
      <MemoryRouter>
        <ShipmatePanel open onClose={vi.fn()} width={320} onWidthChange={onWidthChange} />
      </MemoryRouter>,
    )
    screen.getByRole('separator').focus()
    await userEvent.keyboard('{ArrowRight}')
    expect(onWidthChange).toHaveBeenLastCalledWith(320)

    await userEvent.click(screen.getByRole('button', { name: 'Full screen' }))
    expect(screen.queryByRole('separator')).not.toBeInTheDocument()
  })

  it('Escape leaves full screen first, and only then hides the panel', async () => {
    const { box, onClose } = renderPanel()
    await userEvent.click(screen.getByRole('button', { name: 'Full screen' }))
    expect(screen.getByRole('button', { name: 'Exit full screen' })).toHaveAttribute('aria-pressed', 'true')

    await userEvent.type(box, '{Escape}')
    expect(screen.getByRole('button', { name: 'Full screen' })).toBeInTheDocument()
    expect(onClose).not.toHaveBeenCalled()

    await userEvent.type(box, '{Escape}')
    expect(onClose).toHaveBeenCalled()
  })
})
