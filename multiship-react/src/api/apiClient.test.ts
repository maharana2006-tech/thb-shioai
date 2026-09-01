import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, apiClient, authFetch, BASE_URL, isAbortError } from './apiClient'

/**
 * Foundational-layer tests for apiClient.ts. Every FE API call in the app
 * routes through this module, so a regression here (dropped CSRF header,
 * broken auto-logout, silent 401 mishandling) silently breaks every
 * operator flow. Pattern matches outputDestinationService.test.ts —
 * stub globalThis.fetch, inspect the recorded call, and assert on URL /
 * method / headers / body / behaviour.
 *
 * Surface exercised:
 *  - CSRF echo (POST/PUT/PATCH/DELETE only; skipped when cookie absent)
 *  - credentials: 'include' on every request
 *  - JSON body serialisation
 *  - ApiError construction on non-2xx (status + payload + errorCode)
 *  - 204 → empty object
 *  - Malformed JSON body → empty payload (no crash)
 *  - 401 auto-logout: clears localStorage + redirects to /login
 *  - 401 exemptions: /auth/* endpoints and already-on-/login pages
 *  - authFetch: 2xx Response passthrough, non-2xx surfaces server message
 *  - isAbortError: DOMException + plain-object + edge-case handling
 */

interface FetchCall {
  url: string
  init: RequestInit
}

const jsonResponse = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })

const emptyResponse = (status = 204) => new Response(null, { status })

describe('apiClient — request construction', () => {
  const calls: FetchCall[] = []
  const originalFetch = globalThis.fetch

  beforeEach(() => {
    calls.length = 0
    document.cookie = ''
    globalThis.fetch = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      calls.push({ url: String(input), init: init ?? {} })
      return jsonResponse({ ok: true })
    }) as typeof fetch
  })

  afterEach(() => {
    globalThis.fetch = originalFetch
  })

  it('prefixes every URL with BASE_URL', async () => {
    await apiClient.get('/orders')
    expect(calls[0].url).toBe(`${BASE_URL}/orders`)
  })

  it('BASE_URL defaults to /api/v1 when the env var is unset', () => {
    // Tests observe the module-level export directly — reassigning
    // import.meta.env inside a test bag doesn't re-run the top-level
    // OR expression, so we assert the compiled default instead.
    expect(BASE_URL).toBe('/api/v1')
  })

  it('sends credentials: include on every request (cookie-based JWT)', async () => {
    await apiClient.get('/anything')
    expect(calls[0].init.credentials).toBe('include')
  })

  it('sets Content-Type application/json by default', async () => {
    await apiClient.get('/anything')
    const headers = calls[0].init.headers as Record<string, string>
    expect(headers['Content-Type']).toBe('application/json')
  })

  it('serialises the data payload as JSON on POST', async () => {
    await apiClient.post('/orders', { orderNo: 42, note: 'test' })
    expect(calls[0].init.method).toBe('POST')
    expect(JSON.parse(String(calls[0].init.body))).toEqual({ orderNo: 42, note: 'test' })
  })

  it('PUT/PATCH/DELETE go to the right HTTP methods', async () => {
    await apiClient.put('/orders/1', { note: 'p' })
    await apiClient.patch('/orders/1', { note: 'x' })
    await apiClient.delete('/orders/1')
    expect(calls.map((c) => c.init.method)).toEqual(['PUT', 'PATCH', 'DELETE'])
  })
})

describe('apiClient — CSRF echo (double-submit-cookie)', () => {
  const originalFetch = globalThis.fetch
  const calls: FetchCall[] = []

  beforeEach(() => {
    calls.length = 0
    document.cookie = 'XSRF-TOKEN=abc-123-xyz; path=/'
    globalThis.fetch = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      calls.push({ url: String(input), init: init ?? {} })
      return jsonResponse({ ok: true })
    }) as typeof fetch
  })

  afterEach(() => {
    globalThis.fetch = originalFetch
    // Clear cookies between tests so state doesn't leak.
    document.cookie = 'XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/'
  })

  it('echoes X-XSRF-TOKEN from the cookie on POST', async () => {
    await apiClient.post('/orders', {})
    const headers = calls[0].init.headers as Record<string, string>
    expect(headers['X-XSRF-TOKEN']).toBe('abc-123-xyz')
  })

  it('echoes X-XSRF-TOKEN on PUT/PATCH/DELETE (state-changing methods)', async () => {
    await apiClient.put('/orders/1', {})
    await apiClient.patch('/orders/1', {})
    await apiClient.delete('/orders/1')
    for (const call of calls) {
      const headers = call.init.headers as Record<string, string>
      expect(headers['X-XSRF-TOKEN']).toBe('abc-123-xyz')
    }
  })

  it('does NOT echo X-XSRF-TOKEN on GET (safe method — no CSRF risk)', async () => {
    await apiClient.get('/orders')
    const headers = calls[0].init.headers as Record<string, string>
    expect(headers['X-XSRF-TOKEN']).toBeUndefined()
  })

  it('omits X-XSRF-TOKEN header entirely when the cookie is missing', async () => {
    document.cookie = 'XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/'
    await apiClient.post('/orders', {})
    const headers = calls[0].init.headers as Record<string, string>
    // Header must NOT exist — sending an empty token would be worse than
    // sending none (backend may compare-equal against empty cookie).
    expect(headers['X-XSRF-TOKEN']).toBeUndefined()
  })

  it('decodes URL-encoded cookie values before echoing', async () => {
    // Real backend sets the cookie URL-encoded; the header must carry the
    // raw token so Spring's compare-equal check matches.
    document.cookie = 'XSRF-TOKEN=abc%2B123; path=/'
    await apiClient.post('/orders', {})
    const headers = calls[0].init.headers as Record<string, string>
    expect(headers['X-XSRF-TOKEN']).toBe('abc+123')
  })
})

describe('apiClient — non-2xx response handling', () => {
  const originalFetch = globalThis.fetch

  beforeEach(() => {
    document.cookie = ''
  })

  afterEach(() => {
    globalThis.fetch = originalFetch
  })

  it('throws ApiError with the server-supplied message on 400', async () => {
    globalThis.fetch = vi.fn(async () =>
      jsonResponse({ message: 'Invalid recipient', code: 400 }, 400),
    ) as typeof fetch

    await expect(apiClient.get('/foo')).rejects.toThrow('Invalid recipient')
  })

  it('ApiError carries the HTTP status and full payload', async () => {
    globalThis.fetch = vi.fn(async () =>
      jsonResponse({ message: 'Conflict', errorCode: 'LABEL_ALREADY_GENERATED', extra: 42 }, 409),
    ) as typeof fetch

    try {
      await apiClient.get('/foo')
      expect.fail('expected apiClient.get to throw')
    } catch (err) {
      expect(err).toBeInstanceOf(ApiError)
      const apiErr = err as ApiError
      expect(apiErr.status).toBe(409)
      expect(apiErr.errorCode).toBe('LABEL_ALREADY_GENERATED')
      // Full payload preserved — callers branch on structured fields (e.g. prefill data).
      expect(apiErr.payload.extra).toBe(42)
    }
  })

  it('falls back to a generic message when the server returns no message field', async () => {
    globalThis.fetch = vi.fn(async () => jsonResponse({}, 500)) as typeof fetch
    await expect(apiClient.get('/foo')).rejects.toThrow(/status: 500/)
  })

  it('errorCode is null when the payload has no errorCode field', async () => {
    globalThis.fetch = vi.fn(async () =>
      jsonResponse({ message: 'Bad' }, 400),
    ) as typeof fetch

    try {
      await apiClient.get('/foo')
      expect.fail('should throw')
    } catch (err) {
      expect((err as ApiError).errorCode).toBeNull()
    }
  })
})

describe('apiClient — 204 and malformed JSON', () => {
  const originalFetch = globalThis.fetch

  afterEach(() => {
    globalThis.fetch = originalFetch
  })

  it('returns an empty object for a 204 No Content response', async () => {
    globalThis.fetch = vi.fn(async () => emptyResponse(204)) as typeof fetch
    const result = await apiClient.delete<Record<string, never>>('/orders/1')
    expect(result).toEqual({})
  })

  it('treats unparseable JSON in error responses as an empty payload (no crash)', async () => {
    // Server returned status 500 with an HTML error page — .json() throws.
    // The service must still emit an ApiError with a sane message.
    globalThis.fetch = vi.fn(
      async () =>
        new Response('<html>Bad Gateway</html>', {
          status: 502,
          headers: { 'Content-Type': 'text/html' },
        }),
    ) as typeof fetch

    await expect(apiClient.get('/foo')).rejects.toThrow(/status: 502/)
  })
})

describe('apiClient — 401 auto-logout', () => {
  const originalFetch = globalThis.fetch
  const originalAssign = window.location.assign

  beforeEach(() => {
    localStorage.setItem('multiship_user', 'alice')
    localStorage.setItem('multiship_role', 'ADMIN')
    // Neutral pathname so the redirect branch actually fires.
    Object.defineProperty(window, 'location', {
      writable: true,
      value: { ...window.location, pathname: '/dashboard', assign: vi.fn() },
    })
  })

  afterEach(() => {
    globalThis.fetch = originalFetch
    localStorage.clear()
    // Restore the original assign to keep the jsdom global intact for
    // sibling describe blocks.
    Object.defineProperty(window.location, 'assign', {
      writable: true,
      value: originalAssign,
    })
  })

  it('401 on a non-/auth endpoint clears local auth state', async () => {
    globalThis.fetch = vi.fn(async () =>
      jsonResponse({ message: 'expired' }, 401),
    ) as typeof fetch

    await expect(apiClient.get('/orders')).rejects.toThrow()

    expect(localStorage.getItem('multiship_user')).toBeNull()
    expect(localStorage.getItem('multiship_role')).toBeNull()
  })

  it('401 on a non-/auth endpoint redirects to /login', async () => {
    globalThis.fetch = vi.fn(async () => jsonResponse({}, 401)) as typeof fetch

    await expect(apiClient.get('/orders')).rejects.toThrow()

    expect(window.location.assign).toHaveBeenCalledWith('/login')
  })

  it('401 on /auth/* does NOT clear localStorage (wrong-password stays on login page)', async () => {
    globalThis.fetch = vi.fn(async () =>
      jsonResponse({ message: 'bad credentials' }, 401),
    ) as typeof fetch

    await expect(apiClient.post('/auth/login', {})).rejects.toThrow()

    // The endpoint's own 401 (wrong password) must NOT auto-logout,
    // otherwise the login form clears its own future re-attempts.
    expect(localStorage.getItem('multiship_user')).toBe('alice')
    expect(window.location.assign).not.toHaveBeenCalled()
  })

  it('401 when already on /login does NOT redirect (no redirect loop)', async () => {
    Object.defineProperty(window, 'location', {
      writable: true,
      value: { ...window.location, pathname: '/login', assign: vi.fn() },
    })
    globalThis.fetch = vi.fn(async () => jsonResponse({}, 401)) as typeof fetch

    await expect(apiClient.get('/orders')).rejects.toThrow()

    expect(window.location.assign).not.toHaveBeenCalled()
  })
})

describe('authFetch — non-JSON endpoints', () => {
  const originalFetch = globalThis.fetch

  beforeEach(() => {
    document.cookie = ''
  })

  afterEach(() => {
    globalThis.fetch = originalFetch
  })

  it('returns the raw Response on 2xx so callers can consume blob/text/etc.', async () => {
    // jsdom's Blob → Response.stream() has a bug with binary bodies; use a
    // Uint8Array directly (also what LabelDocumentPage sees over the wire).
    const pdfBytes = new Uint8Array([0x25, 0x50, 0x44, 0x46])
    globalThis.fetch = vi.fn(async () =>
      new Response(pdfBytes, {
        status: 200,
        headers: { 'Content-Type': 'application/pdf' },
      }),
    ) as typeof fetch

    const res = await authFetch('/label/1.pdf')
    expect(res.status).toBe(200)
    expect(res.headers.get('Content-Type')).toBe('application/pdf')
    // The caller (LabelDocumentPage) does the .blob() — verify body reads.
    const bodyBytes = new Uint8Array(await res.arrayBuffer())
    expect(bodyBytes[0]).toBe(0x25) // '%' — first byte of the PDF magic number
  })

  it('throws ApiError with the server message on non-2xx JSON body', async () => {
    globalThis.fetch = vi.fn(async () =>
      new Response(JSON.stringify({ message: 'not found' }), {
        status: 404,
        headers: { 'Content-Type': 'application/json' },
      }),
    ) as typeof fetch

    await expect(authFetch('/label/999.pdf')).rejects.toThrow(/^not found$/)
  })

  it('surfaces short non-JSON error bodies verbatim in the thrown message', async () => {
    globalThis.fetch = vi.fn(
      async () => new Response('Rate limited', { status: 429 }),
    ) as typeof fetch

    await expect(authFetch('/anything')).rejects.toThrow(/^Rate limited$/)
  })

  it('echoes X-XSRF-TOKEN on state-changing methods', async () => {
    document.cookie = 'XSRF-TOKEN=csrf-a; path=/'
    let captured: Headers | null = null
    globalThis.fetch = vi.fn(async (_input, init) => {
      captured = new Headers(init?.headers)
      return new Response(null, { status: 200 })
    }) as typeof fetch

    await authFetch('/anything', { method: 'POST' })

    expect(captured!.get('X-XSRF-TOKEN')).toBe('csrf-a')
  })
})

describe('isAbortError', () => {
  it('true for DOMException named AbortError', () => {
    const err = new DOMException('cancelled', 'AbortError')
    expect(isAbortError(err)).toBe(true)
  })

  it('true for a plain object with name === "AbortError" (edge: cross-realm errors)', () => {
    expect(isAbortError({ name: 'AbortError' })).toBe(true)
  })

  it('false for a generic Error', () => {
    expect(isAbortError(new Error('boom'))).toBe(false)
  })

  it('false for null / undefined / non-object primitives', () => {
    expect(isAbortError(null)).toBe(false)
    expect(isAbortError(undefined)).toBe(false)
    expect(isAbortError('AbortError')).toBe(false)
    expect(isAbortError(42)).toBe(false)
  })
})
