import { describe, expect, it } from 'vitest'
import {
  US_TERRITORY_CODES,
  isServiceAllowedForUsTerritory,
  isUsTerritory,
  usTerritoryBannerHint,
} from './usTerritoryServices'

/**
 * Per-territory carrier service allowlist. The FE filters
 * NewShipmentPage's service dropdown against this — sending an operator
 * to UPS 2nd Day Air for VI trips UPS error 121100 ("service invalid
 * for origin"). See utils/usTerritoryServices for the sources.
 */

describe('isUsTerritory', () => {
  it('recognizes the six US territories', () => {
    for (const t of US_TERRITORY_CODES) {
      expect(isUsTerritory(t)).toBe(true)
    }
  })
  it('rejects non-territory codes and null/undefined', () => {
    expect(isUsTerritory('US')).toBe(false)
    expect(isUsTerritory('DE')).toBe(false)  // Germany, not Delaware — but not in our set either
    expect(isUsTerritory(null)).toBe(false)
    expect(isUsTerritory(undefined)).toBe(false)
    expect(isUsTerritory('')).toBe(false)
  })
})

describe('isServiceAllowedForUsTerritory — PR (Puerto Rico)', () => {
  it('accepts UPS domestic Air codes (01, 02, 13, 14, 59)', () => {
    for (const code of ['01', '02', '13', '14', '59']) {
      expect(isServiceAllowedForUsTerritory('PR', 'UPS', code)).toBe(true)
    }
  })
  it('REJECTS UPS Worldwide family — PR is domestic-network only', () => {
    // Operator confirmed 2026-09-08: UPS returns 121100 for
    // Worldwide Saver on US → PR. Docs suggested both families
    // valid but empirical UPS behavior contradicts. Filter must
    // hide 07/08/54/65 for PR.
    for (const code of ['07', '08', '54', '65']) {
      expect(isServiceAllowedForUsTerritory('PR', 'UPS', code)).toBe(false)
    }
  })
  it('rejects UPS Ground family (03, 11, 12)', () => {
    for (const code of ['03', '11', '12']) {
      expect(isServiceAllowedForUsTerritory('PR', 'UPS', code)).toBe(false)
    }
  })
  it('accepts FedEx intl-family only — asymmetric with UPS', () => {
    // FedEx PR = INTL network (Priority/Economy/First/Priority
    // Express). UPS PR = DOMESTIC network. Operator confirmed
    // 2026-09-08: FedEx rejects EVERY domestic service for US → PR.
    for (const code of [
      'INTERNATIONAL_PRIORITY', 'INTERNATIONAL_ECONOMY',
      'INTERNATIONAL_FIRST', 'INTERNATIONAL_PRIORITY_EXPRESS',
    ]) {
      expect(isServiceAllowedForUsTerritory('PR', 'FEDEX', code)).toBe(true)
    }
  })
  it('REJECTS FedEx domestic Express family — PR ships intl on FedEx', () => {
    for (const code of [
      'PRIORITY_OVERNIGHT', 'STANDARD_OVERNIGHT', 'FIRST_OVERNIGHT',
      'FEDEX_2_DAY', 'FEDEX_2_DAY_AM', 'FEDEX_EXPRESS_SAVER',
    ]) {
      expect(isServiceAllowedForUsTerritory('PR', 'FEDEX', code)).toBe(false)
    }
  })
  it('rejects FedEx Ground family', () => {
    for (const code of ['FEDEX_GROUND', 'GROUND_HOME_DELIVERY', 'SMART_POST']) {
      expect(isServiceAllowedForUsTerritory('PR', 'FEDEX', code)).toBe(false)
    }
  })
})

describe('isServiceAllowedForUsTerritory — VI (US Virgin Islands)', () => {
  it('rejects UPS domestic Air (bug case — UPS 121100)', () => {
    // The reported bug: operator picks 2nd Day Air for VI, UPS rejects
    // with 121100 "service invalid for origin". Filter must hide 02.
    for (const code of ['01', '02', '13', '14', '59']) {
      expect(isServiceAllowedForUsTerritory('VI', 'UPS', code)).toBe(false)
    }
  })
  it('accepts UPS Worldwide family only (07, 08, 54, 65)', () => {
    for (const code of ['07', '08', '54', '65']) {
      expect(isServiceAllowedForUsTerritory('VI', 'UPS', code)).toBe(true)
    }
  })
  it('rejects FedEx domestic services', () => {
    for (const code of [
      'PRIORITY_OVERNIGHT', 'STANDARD_OVERNIGHT', 'FEDEX_2_DAY',
      'FEDEX_GROUND', 'GROUND_HOME_DELIVERY',
    ]) {
      expect(isServiceAllowedForUsTerritory('VI', 'FEDEX', code)).toBe(false)
    }
  })
  it('accepts FedEx intl-family only', () => {
    for (const code of ['INTERNATIONAL_PRIORITY', 'INTERNATIONAL_ECONOMY', 'INTERNATIONAL_FIRST']) {
      expect(isServiceAllowedForUsTerritory('VI', 'FEDEX', code)).toBe(true)
    }
  })
})

describe('isServiceAllowedForUsTerritory — GU / AS / MP / UM (Pacific + Outlying)', () => {
  for (const territory of ['GU', 'AS', 'MP', 'UM'] as const) {
    it(`${territory}: UPS Worldwide family only`, () => {
      expect(isServiceAllowedForUsTerritory(territory, 'UPS', '02')).toBe(false)
      expect(isServiceAllowedForUsTerritory(territory, 'UPS', '07')).toBe(true)
      expect(isServiceAllowedForUsTerritory(territory, 'UPS', '65')).toBe(true)
    })
    it(`${territory}: FedEx intl-family only`, () => {
      expect(isServiceAllowedForUsTerritory(territory, 'FEDEX', 'FEDEX_2_DAY')).toBe(false)
      expect(isServiceAllowedForUsTerritory(territory, 'FEDEX', 'INTERNATIONAL_PRIORITY')).toBe(true)
    })
  }
})

describe('isServiceAllowedForUsTerritory — no territory (pass-through)', () => {
  it('null / undefined / non-territory country lets every service through', () => {
    expect(isServiceAllowedForUsTerritory(null, 'UPS', '03')).toBe(true)
    expect(isServiceAllowedForUsTerritory(undefined, 'UPS', 'anything')).toBe(true)
    expect(isServiceAllowedForUsTerritory('US', 'UPS', '03')).toBe(true)
    expect(isServiceAllowedForUsTerritory('CA', 'FEDEX', 'FEDEX_GROUND')).toBe(true)
  })
})

describe('isServiceAllowedForUsTerritory — carriers without a validated allowlist', () => {
  it('DHL / USPS fall back to the legacy ground-family denylist', () => {
    // Ground family: still denied under fallback.
    expect(isServiceAllowedForUsTerritory('PR', 'DHL', '03')).toBe(false)
    expect(isServiceAllowedForUsTerritory('VI', 'USPS', 'FEDEX_GROUND')).toBe(false)
    // Non-ground code: allowed under fallback.
    expect(isServiceAllowedForUsTerritory('PR', 'DHL', 'EXPRESS_WORLDWIDE')).toBe(true)
    expect(isServiceAllowedForUsTerritory('VI', 'USPS', 'PRIORITY_MAIL')).toBe(true)
  })
})

describe('usTerritoryBannerHint', () => {
  it('PR mentions the ground-family carve-out', () => {
    const hint = usTerritoryBannerHint('PR')
    expect(hint).toContain('Ground')
    expect(hint).toContain('SmartPost')
  })
  it('VI / GU / AS / MP / UM mention Worldwide-only', () => {
    for (const t of ['VI', 'GU', 'AS', 'MP', 'UM']) {
      const hint = usTerritoryBannerHint(t)
      expect(hint).toMatch(/Worldwide|International/i)
    }
  })
  it('non-territory returns empty string', () => {
    expect(usTerritoryBannerHint(null)).toBe('')
    expect(usTerritoryBannerHint('US')).toBe('')
  })
})
