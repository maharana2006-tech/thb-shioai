import { describe, it, expect } from 'vitest'
import { compatiblePresetIds } from './servicePackageCompatibility'
import type { ServicePackageLink } from '../api/shippingConfigService'

const link = (serviceId: number, presetId: number): ServicePackageLink => ({
  serviceId,
  presetId,
})

describe('compatiblePresetIds', () => {
  it('returns null when serviceId is empty string (no service picked yet)', () => {
    expect(compatiblePresetIds([link(1, 100)], '')).toBeNull()
  })

  it('returns null when serviceId is null or undefined', () => {
    expect(compatiblePresetIds([link(1, 100)], null)).toBeNull()
    expect(compatiblePresetIds([link(1, 100)], undefined)).toBeNull()
  })

  it('returns null when links is null or undefined (survives partial API response)', () => {
    expect(compatiblePresetIds(null, 1)).toBeNull()
    expect(compatiblePresetIds(undefined, 1)).toBeNull()
  })

  it('returns null when the picked service has ZERO linked presets', () => {
    // Deliberate null-not-empty-set — the caller uses null as "don't filter"
    // so the FE dropdown shows the pre-Sprint-52 pool. The BE guard will
    // return SERVICE_HAS_NO_LINKED_PACKAGES at submit time with the fix
    // path to /settings/shipping-catalog.
    expect(
      compatiblePresetIds(
        [link(2, 100), link(3, 101)], // no rows for serviceId=1
        1,
      ),
    ).toBeNull()
  })

  it('returns a Set of preset IDs linked to the picked service only', () => {
    const links = [
      link(1, 100), // ← picked
      link(1, 101), // ← picked
      link(2, 100), // different service — excluded
      link(1, 102), // ← picked
    ]
    const result = compatiblePresetIds(links, 1)
    expect(result).toBeInstanceOf(Set)
    expect(result?.size).toBe(3)
    expect(result?.has(100)).toBe(true)
    expect(result?.has(101)).toBe(true)
    expect(result?.has(102)).toBe(true)
    expect(result?.has(200)).toBe(false)
  })

  it('regression pin — FEDEX_2_DAY (id=42) linked to FEDEX_ENVELOPE (id=7) allows it, denies FEDEX_TUBE (id=9)', () => {
    // Mirrors what V29 seeds server-side: FEDEX_2_DAY links to
    // FEDEX_ENVELOPE + FEDEX_PAK. FEDEX_TUBE not linked in this fixture
    // (contrived — real V29 does link tube to Express services); pins
    // the filter's inclusion/exclusion contract regardless of seed
    // content.
    const links = [
      link(42, 7), // FEDEX_2_DAY → FEDEX_ENVELOPE
      link(42, 8), // FEDEX_2_DAY → FEDEX_PAK
    ]
    const result = compatiblePresetIds(links, 42)
    expect(result?.has(7)).toBe(true)
    expect(result?.has(8)).toBe(true)
    expect(result?.has(9)).toBe(false) // FEDEX_TUBE not linked
  })

  it('regression pin — FEDEX_GROUND (id=1) with no CARRIER links returns null', () => {
    // V29 deliberately does NOT seed FEDEX_GROUND × any FEDEX_* preset
    // (Ground accepts only YOUR_PACKAGING, which is handled by the
    // CUSTOM short-circuit in the guard). The FE filter falls through
    // to null so the "Your boxes" section still renders as the only
    // option — matching the order-900003 fix contract.
    const links = [
      link(42, 7), // some Express row
      link(43, 8), // another Express row
    ]
    expect(compatiblePresetIds(links, 1)).toBeNull()
  })
})
