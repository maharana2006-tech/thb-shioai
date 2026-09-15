import { describe, expect, it } from 'vitest'
import { isServiceAllowedForUsTerritory } from './usTerritoryServices'

/**
 * Territory-lane filter-chain regression (introduced 2026-09-08; PR-lane
 * assertions revised 2026-09-09 after PR #625 empirically narrowed
 * UPS_PR / FEDEX_PR based on real carrier rejects).
 *
 * NewShipmentPage's servicesForCarrier memo is a 4-stage pipeline:
 *
 *   1. carrier + originMatch
 *   2. scopeFits(s.scope)          — accepts BOTH or neededScope
 *   3. allowedServiceIds gate      — client's per-service allowlist
 *   4. isServiceAllowedForUsTerritory  — per-territory carrier map
 *
 * For a US → PR lane, isInternational=true (state=PR crosses the customs
 * boundary) but isTerritoryLane=true forces neededScope='DOMESTIC' so
 * every scope stays visible up to the allowlist. Stage 4 is the sole
 * gate on territory lanes; it decides what survives per validated
 * carrier behaviour:
 *
 *   * PR — UPS accepts domestic Air only (Worldwide 07/08/54/65
 *     rejected with 121100 "service invalid for origin"). FedEx
 *     accepts intl-family only (INTERNATIONAL_PRIORITY / _ECONOMY /
 *     _FIRST / _PRIORITY_EXPRESS); every domestic Express is rejected
 *     "service type not available for destination".
 *   * VI / GU / AS / MP / UM — UPS Worldwide-family only, FedEx
 *     intl-family only (Pacific/Outlying territories on the intl
 *     network).
 *
 * The two carriers are asymmetric on PR: UPS moves it on the DOMESTIC
 * network, FedEx on the INTERNATIONAL network. This suite pins the
 * per-carrier + per-territory contract on the pure filter combinators
 * the memo uses.
 */

// Mirror of the memo's `scopeFits` predicate — same signature, same
// semantics. Kept in sync with NewShipmentPage.tsx.
const scopeFits = (
  scope: string | null | undefined,
  neededScope: 'DOMESTIC' | 'INTERNATIONAL',
): boolean => !scope || scope === 'BOTH' || scope === neededScope

// Minimal fixture — the exact catalog rows the operator ships on for
// US → PR on UPS + FedEx. Codes + scopes match the DB seed.
type Svc = { carrier: 'UPS' | 'FEDEX'; serviceCode: string; scope: 'DOMESTIC' | 'INTERNATIONAL' | 'BOTH' }
const CATALOG: Svc[] = [
  // UPS domestic Air — scope=DOMESTIC in the seed.
  { carrier: 'UPS', serviceCode: '01', scope: 'DOMESTIC' },  // Next Day Air
  { carrier: 'UPS', serviceCode: '02', scope: 'DOMESTIC' },  // 2nd Day Air
  { carrier: 'UPS', serviceCode: '13', scope: 'DOMESTIC' },  // Next Day Air Saver
  // UPS Worldwide — scope=INTERNATIONAL. These are the ones the bug
  // dropped when neededScope=DOMESTIC for territory lanes.
  { carrier: 'UPS', serviceCode: '07', scope: 'INTERNATIONAL' },  // Worldwide Express
  { carrier: 'UPS', serviceCode: '08', scope: 'INTERNATIONAL' },  // Worldwide Expedited
  { carrier: 'UPS', serviceCode: '65', scope: 'INTERNATIONAL' },  // Worldwide Saver
  // UPS Ground — territory allowlist rejects these regardless.
  { carrier: 'UPS', serviceCode: '03', scope: 'DOMESTIC' },
  // FedEx domestic + intl.
  { carrier: 'FEDEX', serviceCode: 'PRIORITY_OVERNIGHT', scope: 'DOMESTIC' },
  { carrier: 'FEDEX', serviceCode: 'FEDEX_2_DAY', scope: 'DOMESTIC' },
  { carrier: 'FEDEX', serviceCode: 'FEDEX_EXPRESS_SAVER', scope: 'DOMESTIC' },
  { carrier: 'FEDEX', serviceCode: 'INTERNATIONAL_PRIORITY', scope: 'INTERNATIONAL' },
  { carrier: 'FEDEX', serviceCode: 'INTERNATIONAL_ECONOMY', scope: 'INTERNATIONAL' },
  { carrier: 'FEDEX', serviceCode: 'FEDEX_GROUND', scope: 'DOMESTIC' },  // ground — allowlist rejects
]

/**
 * Reproduce servicesForCarrier's filter pipeline. Only the two stages
 * that matter for territory-lane behavior — origin match + allowlist
 * gate — are inlined; scopeFits is switched based on isTerritoryLane.
 */
function pickServices(opts: {
  carrier: 'UPS' | 'FEDEX'
  neededScope: 'DOMESTIC' | 'INTERNATIONAL'
  recipientTerritory: string | null
  isTerritoryLane: boolean
}): string[] {
  const { carrier, neededScope, recipientTerritory, isTerritoryLane } = opts
  return CATALOG
    .filter((s) => s.carrier === carrier)
    // Territory-lane fix: skip scopeFits on territory lanes so the
    // per-territory allowlist can pick from both scopes.
    .filter((s) => isTerritoryLane || scopeFits(s.scope, neededScope))
    .filter((s) => isServiceAllowedForUsTerritory(recipientTerritory, carrier, s.serviceCode))
    .map((s) => s.serviceCode)
}

describe('territory-lane filter chain — US → PR', () => {
  it('UPS PR surfaces domestic Air only — Worldwide rejected by 121100', () => {
    const visible = pickServices({
      carrier: 'UPS',
      neededScope: 'DOMESTIC',  // territory-lane rule keeps DOMESTIC
      recipientTerritory: 'PR',
      isTerritoryLane: true,
    })
    // Domestic Air family — the only codes UPS accepts for US → PR.
    expect(visible).toContain('01')
    expect(visible).toContain('02')
    expect(visible).toContain('13')
    // Worldwide family — the UPS Rating API REJECTS these for US → PR
    // with 121100 "service invalid for origin" (operator confirmed
    // 2026-09-08; PR #625 narrowed UPS_PR to Air-only).
    expect(visible).not.toContain('07')
    expect(visible).not.toContain('08')
    expect(visible).not.toContain('65')
    // Ground — hidden by allowlist
    expect(visible).not.toContain('03')
  })

  it('FedEx PR surfaces intl-family only — domestic Express rejected', () => {
    const visible = pickServices({
      carrier: 'FEDEX',
      neededScope: 'DOMESTIC',
      recipientTerritory: 'PR',
      isTerritoryLane: true,
    })
    // Intl family — the only codes FedEx accepts for US → PR. FedEx
    // treats PR as INTERNATIONAL destination on its network.
    expect(visible).toContain('INTERNATIONAL_PRIORITY')
    expect(visible).toContain('INTERNATIONAL_ECONOMY')
    // Domestic Express — FedEx rejects EVERY domestic service for
    // US → PR with "service type not available for destination"
    // (operator confirmed 2026-09-08; PR #625 narrowed FEDEX_PR to
    // intl-only).
    expect(visible).not.toContain('PRIORITY_OVERNIGHT')
    expect(visible).not.toContain('FEDEX_2_DAY')
    expect(visible).not.toContain('FEDEX_EXPRESS_SAVER')
    // Ground — hidden by allowlist
    expect(visible).not.toContain('FEDEX_GROUND')
  })

  it('asymmetric-per-carrier PR contract: UPS Air, FedEx intl (never overlap)', () => {
    // Documents the deliberate asymmetry PR #625 enshrined. Same PR
    // lane, two carriers, disjoint valid service sets.
    const upsVisible = pickServices({
      carrier: 'UPS',
      neededScope: 'DOMESTIC',
      recipientTerritory: 'PR',
      isTerritoryLane: true,
    })
    const fedexVisible = pickServices({
      carrier: 'FEDEX',
      neededScope: 'DOMESTIC',
      recipientTerritory: 'PR',
      isTerritoryLane: true,
    })
    // No shared codes — the sets are disjoint by carrier network design.
    const overlap = upsVisible.filter((c) => fedexVisible.includes(c))
    expect(overlap).toEqual([])
  })
})

describe('territory-lane filter chain — US → VI (intl-only territory)', () => {
  it('UPS VI surfaces Worldwide only (allowlist gate does the work)', () => {
    const visible = pickServices({
      carrier: 'UPS',
      neededScope: 'DOMESTIC',
      recipientTerritory: 'VI',
      isTerritoryLane: true,
    })
    // VI allowlist drops domestic Air.
    expect(visible).not.toContain('01')
    expect(visible).not.toContain('02')
    // Worldwide survives — this is exactly the case the fix rescues.
    expect(visible).toContain('07')
    expect(visible).toContain('08')
    expect(visible).toContain('65')
  })

  it('FedEx VI surfaces intl-family only', () => {
    const visible = pickServices({
      carrier: 'FEDEX',
      neededScope: 'DOMESTIC',
      recipientTerritory: 'VI',
      isTerritoryLane: true,
    })
    expect(visible).not.toContain('PRIORITY_OVERNIGHT')
    expect(visible).not.toContain('FEDEX_2_DAY')
    expect(visible).toContain('INTERNATIONAL_PRIORITY')
    expect(visible).toContain('INTERNATIONAL_ECONOMY')
  })
})

describe('non-territory lanes — scopeFits stays authoritative', () => {
  it('domestic US → US drops INTERNATIONAL-scoped services', () => {
    const visible = pickServices({
      carrier: 'UPS',
      neededScope: 'DOMESTIC',
      recipientTerritory: null,
      isTerritoryLane: false,
    })
    // Domestic services show.
    expect(visible).toContain('01')
    expect(visible).toContain('03')
    // Worldwide (INTERNATIONAL scope) must NOT leak into domestic pickers.
    expect(visible).not.toContain('07')
    expect(visible).not.toContain('65')
  })

  it('intl US → DE drops DOMESTIC-scoped services', () => {
    const visible = pickServices({
      carrier: 'UPS',
      neededScope: 'INTERNATIONAL',
      recipientTerritory: null,
      isTerritoryLane: false,
    })
    expect(visible).not.toContain('01')
    expect(visible).not.toContain('02')
    expect(visible).toContain('07')
    expect(visible).toContain('65')
  })
})
