import { describe, expect, it } from 'vitest'
import { phoneErrorFor } from './countryFormats'

describe('phoneErrorFor — country-aware phone length', () => {
  it('blank phone is allowed (handled by required elsewhere)', () => {
    expect(phoneErrorFor('US', '')).toBeNull()
    expect(phoneErrorFor('US', null)).toBeNull()
  })

  it('valid US 10-digit number passes (with separators)', () => {
    expect(phoneErrorFor('US', '6505550123')).toBeNull()
    expect(phoneErrorFor('US', '(650) 555-0123')).toBeNull()
  })

  it('US 9-digit number fails with a clear message', () => {
    const e = phoneErrorFor('US', '650555012')
    expect(e).toMatch(/US phone numbers are 10 digits/i)
    expect(e).toMatch(/entered 9/i)
  })

  it('strips an inline US dial code so 1+10 digits is accepted', () => {
    expect(phoneErrorFor('US', '16505550123')).toBeNull()
    expect(phoneErrorFor('US', '+1 650 555 0123')).toBeNull()
  })

  it('valid India 10-digit and invalid 9-digit', () => {
    expect(phoneErrorFor('IN', '9876543210')).toBeNull()
    expect(phoneErrorFor('IN', '987654321')).toMatch(/IN phone numbers are 10 digits/i)
  })

  it('GB accepts a 9–10 digit range', () => {
    expect(phoneErrorFor('GB', '2079460958')).toBeNull() // 10
    expect(phoneErrorFor('GB', '207946095')).toBeNull() // 9
    expect(phoneErrorFor('GB', '20794')).toMatch(/GB phone numbers are 9–10 digits/i)
  })

  it('unknown country falls back to the generic 7–15 rule', () => {
    expect(phoneErrorFor('ZZ', '1234567')).toBeNull()
    expect(phoneErrorFor('ZZ', '123')).toMatch(/7–15 digits/i)
  })
})
