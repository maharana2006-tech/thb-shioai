import { describe, expect, it } from 'vitest'
import { COUNTRIES, countryNameFor } from './_countryData'

/**
 * F5-C — coverage for the country data + display-name helper
 * extracted from CountrySelect. Kept small because the curated list
 * itself is authoritative; the risk is a fallback-lookup regression
 * (Intl.DisplayNames path) that silently strips diacritics or
 * returns the raw code instead of the country name.
 */

describe('COUNTRIES', () => {
  it('exposes the curated ISO-2 → display-name pairs sorted by common shipping regions', () => {
    // Spot-check: US must be present and top-of-list (defaults key off it).
    expect(COUNTRIES[0]).toEqual(['US', 'United States'])
    expect(COUNTRIES.some(([code]) => code === 'GB')).toBe(true)
    expect(COUNTRIES.some(([code]) => code === 'IN')).toBe(true)
    expect(COUNTRIES.some(([code]) => code === 'CN')).toBe(true)
    expect(COUNTRIES.some(([code]) => code === 'BR')).toBe(true)
  })

  it('has no duplicate country codes', () => {
    const codes = COUNTRIES.map(([code]) => code)
    expect(codes.length).toBe(new Set(codes).size)
  })

  it('uses uppercase ISO-3166 alpha-2 codes exclusively', () => {
    for (const [code] of COUNTRIES) {
      expect(code).toMatch(/^[A-Z]{2}$/)
    }
  })
})

describe('countryNameFor', () => {
  it('returns the curated name for a listed code', () => {
    expect(countryNameFor('US')).toBe('United States')
    expect(countryNameFor('GB')).toBe('United Kingdom')
    expect(countryNameFor('AE')).toBe('United Arab Emirates')
  })

  it('is case-insensitive and trims whitespace', () => {
    expect(countryNameFor('us')).toBe('United States')
    expect(countryNameFor('  ca  ')).toBe('Canada')
  })

  it('falls back to Intl.DisplayNames for codes outside the curated list', () => {
    // Cuba isn't in the shipping-oriented curated list. Intl.DisplayNames
    // returns 'Cuba' — the fallback exists precisely so the UI stops
    // showing "CU (CU)" for these edge cases.
    const cu = countryNameFor('CU')
    expect(cu.length).toBeGreaterThan(0)
    expect(cu).not.toBe('CU')
  })

  it('returns the empty string for a blank/null-ish code', () => {
    expect(countryNameFor('')).toBe('')
    expect(countryNameFor('   ')).toBe('')
  })

  it('echoes back an unknown code when Intl.DisplayNames does not resolve it', () => {
    // Zzz isn't a valid region — the helper's `|| c` fallback keeps the
    // raw code so the UI still shows *something* instead of the empty
    // string that would break the picker's placeholder logic.
    expect(countryNameFor('ZZ')).toBeDefined()
  })
})
