import { afterEach, describe, expect, it, vi } from 'vitest'
import { ShipmateError, shipmateService, type ShipmateEvent, type ShipmateRequest } from './shipmateService'

const request: ShipmateRequest = { message: 'hi', page: 'Orders', history: [{ role: 'user', text: 'earlier' }] }

/** A reply whose body arrives in the given network chunks. */
const streamOf = (...chunks: string[]) =>
  new Response(
    new ReadableStream<Uint8Array>({
      start(controller) {
        const encoder = new TextEncoder()
        chunks.forEach((chunk) => controller.enqueue(encoder.encode(chunk)))
        controller.close()
      },
    }),
    { status: 200, headers: { 'Content-Type': 'text/event-stream' } },
  )

const collect = async (signal = new AbortController().signal) => {
  const events: ShipmateEvent[] = []
  for await (const event of shipmateService.send(request, signal)) events.push(event)
  return events
}

const failureOf = (run: Promise<unknown>) =>
  run.then(
    () => 'no failure',
    (err: unknown) => (err instanceof ShipmateError ? err.kind : `other: ${String(err)}`),
  )

afterEach(() => vi.unstubAllGlobals())

describe('shipmateService.send', () => {
  it('posts the message, page and history, and reads events even when they are split across chunks', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      streamOf('data:{"type":"text","delta":"Hel', 'lo "}\n\ndata:{"type":"text",', '"delta":"there"}\n\n'),
    )
    vi.stubGlobal('fetch', fetchMock)

    expect(await collect()).toEqual([
      { type: 'text', delta: 'Hello ' },
      { type: 'text', delta: 'there' },
    ])

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toBe('/api/v1/ai/chat')
    expect(init.method).toBe('POST')
    expect(init.credentials).toBe('include')
    expect(JSON.parse(init.body as string)).toEqual(request)
  })

  it('an error event part-way through fails the reply as unavailable, keeping what came before', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(
      streamOf('data:{"type":"text","delta":"Hel"}\n\ndata:{"type":"error"}\n\n'),
    ))
    const seen: ShipmateEvent[] = []

    const failure = await failureOf((async () => {
      for await (const event of shipmateService.send(request, new AbortController().signal)) seen.push(event)
    })())

    expect(seen).toEqual([{ type: 'text', delta: 'Hel' }])
    expect(failure).toBe('unavailable')
  })

  it.each([
    [429, 'limit'],
    [503, 'unavailable'],
    [502, 'unavailable'],
  ])('HTTP %i is reported as %s', async (status, kind) => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ message: 'nope' }), { status, headers: { 'Content-Type': 'application/json' } }),
    ))

    expect(await failureOf(collect())).toBe(kind)
  })

  it('a dropped connection is unavailable, but a stop by the user is passed through as it is', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Failed to fetch')))
    expect(await failureOf(collect())).toBe('unavailable')

    const controller = new AbortController()
    controller.abort()
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new DOMException('Aborted', 'AbortError')))
    expect(await failureOf(collect(controller.signal))).toMatch(/^other: AbortError/)
  })
})
