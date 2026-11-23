import { ApiError, authFetch } from './apiClient'

/**
 * Shipmate chat: the events the panel renders, and the call that streams them
 * from POST /ai/chat. The backend sends only `text` today; the other events
 * are what the panel is ready to show once Shipmate gets tools.
 */
export type ShipmateEvent =
  /** What Shipmate is doing right now, e.g. "Getting live rates". Replaced by the next event. */
  | { type: 'status'; text: string }
  /** The next piece of the reply; pieces arrive in order. */
  | { type: 'text'; delta: string }
  /** Something that creates a label or costs money: nothing happens until the user confirms. */
  | {
      type: 'confirm'
      id: string
      title: string
      details: Array<{ label: string; value: string }>
      total?: string
      note?: string
      confirmLabel: string
    }
  /** A result to compare, e.g. rates. `pick` marks the row Shipmate recommends. */
  | { type: 'table'; title: string; columns: string[]; rows: string[][]; pick?: { row: number; label: string } }
  /** A long job. Sent again with the same id each time the count moves. */
  | { type: 'progress'; id: string; label: string; done: number; total: number }

/** unavailable = the AI cannot be reached or failed; limit = the team's per-minute AI budget is spent. */
export type ShipmateFailure = 'unavailable' | 'limit'

export class ShipmateError extends Error {
  readonly kind: ShipmateFailure
  constructor(kind: ShipmateFailure) {
    super(kind)
    this.kind = kind
  }
}

export interface ShipmateTurn {
  role: 'user' | 'assistant'
  text: string
}

export interface ShipmateRequest {
  message: string
  /** The page the user is on, so the reply can be about what they are looking at. */
  page: string
  /** The conversation so far, oldest first. The server keeps none of its own. */
  history: ShipmateTurn[]
  /** Set when the user answers a confirm card. */
  decision?: { id: string; accepted: boolean }
}

export interface ShipmatePrompt {
  icon: 'customs' | 'incoterm' | 'packing' | 'help'
  /** Shown on the card and sent as the message. */
  title: string
  hint: string
}

async function* streamReply(request: ShipmateRequest, signal: AbortSignal): AsyncGenerator<ShipmateEvent> {
  let response: Response
  try {
    response = await authFetch('/ai/chat', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' },
      body: JSON.stringify(request),
      signal,
    })
  } catch (err) {
    if (signal.aborted) throw err
    throw new ShipmateError(err instanceof ApiError && err.status === 429 ? 'limit' : 'unavailable')
  }
  if (!response.body) throw new ShipmateError('unavailable')

  // Server-sent events: blocks separated by a blank line, each carrying one JSON object on `data:`.
  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  for (;;) {
    const { done, value } = await reader.read()
    if (done) return
    buffer += decoder.decode(value, { stream: true })
    for (let end = buffer.indexOf('\n\n'); end >= 0; end = buffer.indexOf('\n\n')) {
      const data = buffer
        .slice(0, end)
        .split('\n')
        .filter((line) => line.startsWith('data:'))
        .map((line) => line.slice(5))
        .join('\n')
      buffer = buffer.slice(end + 2)
      if (!data) continue
      const event = JSON.parse(data) as ShipmateEvent | { type: 'error' }
      if (event.type === 'error') throw new ShipmateError('unavailable')
      yield event
    }
  }
}

const PROMPTS: Record<ShipmatePrompt['icon'], ShipmatePrompt> = {
  help: { icon: 'help', title: 'What can you do here?', hint: 'What Shipmate can and cannot do yet' },
  customs: {
    icon: 'customs',
    title: 'What goes on a commercial invoice?',
    hint: 'The details customs expects for an international parcel',
  },
  incoterm: { icon: 'incoterm', title: 'Should I ship DAP or DDP?', hint: 'Who pays duties and tax under each' },
  packing: { icon: 'packing', title: 'How should I pack a fragile item?', hint: 'Box, padding and labelling that carriers accept' },
}

const LEAD_PROMPT: Record<string, ShipmatePrompt['icon']> = { 'New Shipment': 'customs' }

/** Starter prompts, with the one that fits the current page first. */
export const starterPrompts = (page: string): ShipmatePrompt[] => {
  const lead = LEAD_PROMPT[page] ?? 'help'
  return [PROMPTS[lead], ...Object.values(PROMPTS).filter((p) => p.icon !== lead)]
}

export const shipmateService = {
  /** Streams the reply. Abort the signal to stop it. Throws ShipmateError when the AI cannot answer. */
  send: (request: ShipmateRequest, signal: AbortSignal): AsyncIterable<ShipmateEvent> =>
    streamReply(request, signal),
}
