import { describe, expect, it } from 'vitest'
import { errorMessages, getFriendlyError } from './errorMessages'

describe('errorMessages', () => {
  it('exposes a friendly entry for every Sprint 50 backend errorCode', () => {
    // Guard rail — if a new backend errorCode ships without a UI entry,
    // this test surfaces it during PR review. Add the code to the list
    // when you patch errorMessages.ts (and vice versa).
    const sprint50Codes = [
      'API_KEY_RATE_LIMITED',
      'TENANT_RATE_LIMITED',
      'CARRIER_RATE_LIMITED',
      'EMAIL_NOT_VERIFIED',
      'ACCOUNT_DEACTIVATED',
      'CROSS_TENANT_ACCESS_DENIED',
      'IDEMPOTENCY_IN_PROGRESS',
      'IDEMPOTENCY_UNAVAILABLE',
      'MARKUP_REQUIRED_FOR_CLIENT',
      'UNIT_REQUIRED',
      'CURRENCY_REQUIRED',
      'CLIENT_CARRIER_AUTH_FAILED',
      'API_KEY_EXPIRED',
      'INSUFFICIENT_SCOPE',
      'INVITE_EXPIRED',
      'INVITE_ALREADY_USED',
      'ADMIN_TARGET_USER_NOT_FOUND',
      'ADMIN_TARGET_CLIENT_NOT_FOUND',
    ]
    for (const code of sprint50Codes) {
      expect(errorMessages[code], `missing entry for ${code}`).toBeDefined()
      expect(errorMessages[code].message.length).toBeGreaterThan(10)
    }
  })

  it('exposes a friendly entry for pre-Sprint-50 codes lifted out of inline branches', () => {
    // These codes existed before Sprint 50 but had bespoke messages inlined in
    // components. During the PR J migration we moved them into the map so
    // notify.apiError picks them up uniformly.
    const migratedCodes = [
      'CLIENT_HAS_ORDERS',
      'ALLOWLIST_ALREADY_EXISTS',
      'POLICY_FIXED_SERVICE_REQUIRED',
    ]
    for (const code of migratedCodes) {
      expect(errorMessages[code], `missing entry for ${code}`).toBeDefined()
      expect(errorMessages[code].message.length).toBeGreaterThan(10)
    }
  })

  it('every entry has a non-empty message', () => {
    for (const [code, entry] of Object.entries(errorMessages)) {
      expect(entry.message.trim(), `empty message for ${code}`).not.toEqual('')
    }
  })
})

describe('getFriendlyError', () => {
  it('returns the mapped entry for a known code', () => {
    const friendly = getFriendlyError('CROSS_TENANT_ACCESS_DENIED', 'raw msg')
    expect(friendly.title).toBe('Not permitted')
    expect(friendly.message).toContain('client outside your scope')
  })

  it('falls back to server message when code is unknown', () => {
    const friendly = getFriendlyError('WHO_KNOWS', 'server said this')
    expect(friendly.title).toBeUndefined()
    expect(friendly.message).toBe('server said this')
  })

  it('falls back to generic when code + message are both missing', () => {
    const friendly = getFriendlyError(null, null)
    expect(friendly.message).toContain('Something went wrong')
  })

  it('falls back to caller-supplied fallback when both server hints missing', () => {
    const friendly = getFriendlyError(undefined, undefined, 'Custom fallback.')
    expect(friendly.message).toBe('Custom fallback.')
  })

  it('prefers the mapped entry over both server message and fallback', () => {
    const friendly = getFriendlyError(
      'API_KEY_EXPIRED',
      'server text ignored',
      'fallback ignored',
    )
    expect(friendly.title).toBe('API key expired')
    expect(friendly.message).toMatch(/rotate/i)
  })

  it('treats blank server message as absent', () => {
    const friendly = getFriendlyError(null, '   ', 'fallback wins')
    expect(friendly.message).toBe('fallback wins')
  })
})
