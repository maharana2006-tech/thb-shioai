import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { outputDestinationService } from './outputDestinationService'

/**
 * Sprint 52 — surface tests for outputDestinationService URL / payload
 * shape. Mocks fetch to catch a bad URL template (e.g. missing
 * clientCode encoding) without spinning up the real backend.
 */

interface FetchCall {
  url: string
  init: RequestInit
}

describe('outputDestinationService', () => {
  const calls: FetchCall[] = []
  const originalFetch = globalThis.fetch

  beforeEach(() => {
    calls.length = 0
    globalThis.fetch = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      calls.push({ url: String(input), init: init ?? {} })
      return new Response(
        JSON.stringify({ status: 'SUCCESS', code: 200, data: null, message: 'ok' }),
        {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        },
      )
    }) as typeof fetch
  })

  afterEach(() => {
    globalThis.fetch = originalFetch
  })

  it('list without filter hits the base URL', async () => {
    await outputDestinationService.list()
    expect(calls[0].url).toMatch(/\/admin\/output-destinations$/)
    expect(calls[0].init.method).toBe('GET')
  })

  it('list encodes the clientCode filter', async () => {
    await outputDestinationService.list('AC ME')
    expect(calls[0].url).toMatch(/\/admin\/output-destinations\?clientCode=AC%20ME$/)
  })

  it('create POSTs the payload as JSON', async () => {
    await outputDestinationService.create({
      clientCode: 'ACME',
      docType: 'LABEL',
      destinationType: 'LOCAL_FS',
      config: '{"path":"/tmp"}',
      active: true,
    })
    expect(calls[0].init.method).toBe('POST')
    expect(calls[0].url).toMatch(/\/admin\/output-destinations$/)
    const body = JSON.parse(String(calls[0].init.body))
    expect(body.clientCode).toBe('ACME')
    expect(body.destinationType).toBe('LOCAL_FS')
  })

  it('update uses PUT with the id path segment', async () => {
    await outputDestinationService.update(42, {
      clientCode: 'ACME',
      docType: 'LABEL',
      destinationType: 'LOCAL_FS',
      config: '{"path":"/tmp"}',
      active: true,
    })
    expect(calls[0].init.method).toBe('PUT')
    expect(calls[0].url).toMatch(/\/admin\/output-destinations\/42$/)
  })

  it('delete uses DELETE with the id path segment', async () => {
    await outputDestinationService.delete(99)
    expect(calls[0].init.method).toBe('DELETE')
    expect(calls[0].url).toMatch(/\/admin\/output-destinations\/99$/)
  })

  it('test posts to /{id}/test', async () => {
    await outputDestinationService.test(7)
    expect(calls[0].init.method).toBe('POST')
    expect(calls[0].url).toMatch(/\/admin\/output-destinations\/7\/test$/)
  })
})
