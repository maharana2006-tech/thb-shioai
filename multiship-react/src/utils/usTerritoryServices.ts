// Per-US-territory carrier service allowlist. FedEx + UPS treat every
// US territory (PR/VI/GU/AS/MP/UM) as a separate country for shipping.
// Which services actually deliver differs sharply between them:
//
//   * Puerto Rico — UPS accepts domestic Air codes (01/02/13/14/59) AND
//     Worldwide codes (07/08/54/65); ground family (03/11/12) doesn't
//     serve. FedEx accepts overnight/2-Day domestic codes plus intl.
//
//   * US Virgin Islands — UPS domestic Air services do NOT serve VI
//     (this is what UPS rejects with error 121100 "service invalid for
//     origin"). Only Worldwide-family works. FedEx: intl services only.
//
//   * Guam / American Samoa / Northern Mariana / Minor Outlying Islands
//     — Pacific territories, treated fully international by both carriers.
//     UPS Worldwide-family only; FedEx intl-family only.
//
// Sources:
//   - UPS Rate & Service Guides per territory
//     (https://www.ups.com/assets/resources/webcontent/en_US/rate-service-guide-pr.pdf,
//      https://assets.ups.com/adobe/assets/urn:aaid:aem:8882ad24-7197-4a81-8b9a-93563d1c4e82/original/as/rate-service-guide-vi-us-en.pdf)
//   - FedEx service coverage pages
//     (https://www.fedex.com/en-pr/shipping.html,
//      https://www.fedex.com/en-vi/shipping.html,
//      https://www.fedex.com/en-gu/shipping.html)
//   - Cross-checked against carrier-side rejects: UPS returns 121100
//     "The requested service is invalid for the shipment origin" when
//     an operator picks e.g. 2nd Day Air (02) for VI.
//
// The allowlist runs on top of the FE catalog filter — if a service isn't
// in the carrier catalog at all, it never appears; if it IS in the
// catalog but the territory doesn't allow it, this map hides it.

/** Codes on {@link ShippingService.serviceCode} that carriers accept on
 *  each US-territory lane. `undefined` entry = fall through to the
 *  legacy "hide ground-family" filter for that carrier. */
export type CarrierServiceAllowlist = {
  UPS?: ReadonlySet<string>
  FEDEX?: ReadonlySet<string>
  DHL?: ReadonlySet<string>
  USPS?: ReadonlySet<string>
}

/** ISO 3166-1 alpha-2 codes for the six US territories UsTerritoryNormalizer
 *  covers. Same set as {@code US_TERRITORY_CODES} on the backend. */
export const US_TERRITORY_CODES = ['PR', 'VI', 'GU', 'AS', 'MP', 'UM'] as const
export type UsTerritory = typeof US_TERRITORY_CODES[number]

// UPS service codes (from developer.ups.com Rating API appendix):
//   01 Next Day Air, 02 2nd Day Air, 03 Ground, 07 Worldwide Express,
//   08 Worldwide Expedited, 11 Standard, 12 3 Day Select,
//   13 Next Day Air Saver, 14 Next Day Air Early, 54 Worldwide Express Plus,
//   59 2nd Day Air A.M., 65 Worldwide Saver.

/** PR — domestic Air codes only. Empirically the UPS Rating API
 *  REJECTS the Worldwide family (07/08/54/65) for US → PR with error
 *  121100 "service invalid for the shipment origin" even though older
 *  docs suggested both families are valid. UPS moves PR shipments on
 *  the domestic network; Worldwide services target true cross-border
 *  destinations. Ground family (03/11/12) already excluded via the
 *  earlier catalog filter. Confirmed by operator 2026-09-08:
 *  Worldwide Saver → 121100; 2nd Day Air + Next Day Air → accepted. */
const UPS_PR = new Set([
  '01', '02', '13', '14', '59', // domestic Air only
])

/** VI, GU, AS, MP, UM — Worldwide-family only. Domestic Air codes trip
 *  UPS error 121100 ("service invalid for origin"). This is the bug the
 *  fix addresses. */
const UPS_INTL_ONLY = new Set(['07', '08', '54', '65'])

// FedEx service codes (from FedEx Ship API service-availability doc):
//   FIRST_OVERNIGHT, PRIORITY_OVERNIGHT, STANDARD_OVERNIGHT,
//   FEDEX_2_DAY, FEDEX_2_DAY_AM, FEDEX_EXPRESS_SAVER,
//   FEDEX_GROUND, GROUND_HOME_DELIVERY, SMART_POST,
//   INTERNATIONAL_PRIORITY, INTERNATIONAL_ECONOMY, INTERNATIONAL_FIRST,
//   INTERNATIONAL_PRIORITY_EXPRESS.

/** PR — FedEx INTERNATIONAL family only. The carrier rule is asymmetric
 *  with UPS: UPS moves US → PR on its DOMESTIC network (Air codes
 *  only); FedEx treats PR as an INTERNATIONAL destination (Priority /
 *  Economy / First / PriorityExpress). Operator confirmed 2026-09-08:
 *  FedEx rejects EVERY domestic service (Express Saver / 2Day /
 *  Overnight variants) for US → PR with "This service type is not
 *  available for the destination." Both carriers still require the
 *  customs declaration — that's what makes it a US-territory lane —
 *  but the service catalogue is per-carrier network. */
const FEDEX_PR = new Set([
  'INTERNATIONAL_PRIORITY', 'INTERNATIONAL_ECONOMY',
  'INTERNATIONAL_FIRST', 'INTERNATIONAL_PRIORITY_EXPRESS',
])

/** VI, GU, AS, MP, UM — FedEx intl-family only. FedEx routes these via
 *  International Priority / Economy, no domestic Overnight/2-Day
 *  service serves them from the US mainland. */
const FEDEX_INTL_ONLY = new Set([
  'INTERNATIONAL_PRIORITY', 'INTERNATIONAL_ECONOMY', 'INTERNATIONAL_FIRST',
  'INTERNATIONAL_PRIORITY_EXPRESS',
])

/** Per-territory carrier→allowed-service-code map. If a territory has no
 *  entry for a carrier, the caller falls back to the coarse "hide ground
 *  family" filter (backwards-compat for carriers we haven't validated).
 *
 *  DHL + USPS: no per-territory data validated yet — DHL Express treats
 *  all territories as intl (routing via DHL Express International),
 *  and USPS handles territories at domestic rates via Priority Mail /
 *  Priority Mail Express (both bill-domestic AND land on-territory).
 *  Leaving both carriers unfiltered here — the ground-hide legacy
 *  filter is the fallback until we validate them. */
export const US_TERRITORY_SERVICE_ALLOWLIST: Record<UsTerritory, CarrierServiceAllowlist> = {
  PR: { UPS: UPS_PR,        FEDEX: FEDEX_PR },
  VI: { UPS: UPS_INTL_ONLY, FEDEX: FEDEX_INTL_ONLY },
  GU: { UPS: UPS_INTL_ONLY, FEDEX: FEDEX_INTL_ONLY },
  AS: { UPS: UPS_INTL_ONLY, FEDEX: FEDEX_INTL_ONLY },
  MP: { UPS: UPS_INTL_ONLY, FEDEX: FEDEX_INTL_ONLY },
  UM: { UPS: UPS_INTL_ONLY, FEDEX: FEDEX_INTL_ONLY },
}

/** Legacy ground-family denylist. Applied when the territory has no
 *  per-carrier allowlist entry (currently DHL + USPS). Same set as the
 *  original PR #610 filter. */
export const US_TERRITORY_GROUND_DENYLIST: ReadonlySet<string> = new Set([
  'FEDEX_GROUND', 'GROUND_HOME_DELIVERY', 'SMART_POST',
  '03', // UPS Ground
  '11', // UPS Standard (ground-hybrid)
  '12', // UPS 3 Day Select (ground-hybrid)
])

/** Is this a US-territory code we know about? Guard for the map. */
export function isUsTerritory(code: string | null | undefined): code is UsTerritory {
  if (!code) return false
  return (US_TERRITORY_CODES as readonly string[]).includes(code)
}

/**
 * Decide whether {@code serviceCode} is deliverable to {@code territory}
 * on {@code carrier}. When the territory has a per-carrier allowlist,
 * the code must be in it. When it doesn't, the code just needs to NOT be
 * in the ground-family denylist (preserves the pre-fix behavior for
 * carriers we haven't validated yet).
 *
 * Return {@code true} when {@code territory} is null — no territory, no
 * filter (pass-through).
 */
export function isServiceAllowedForUsTerritory(
  territory: string | null | undefined,
  carrier: string,
  serviceCode: string,
): boolean {
  if (!isUsTerritory(territory)) return true
  const carrierKey = carrier.toUpperCase() as keyof CarrierServiceAllowlist
  const allow = US_TERRITORY_SERVICE_ALLOWLIST[territory][carrierKey]
  if (allow) return allow.has(serviceCode)
  // No validated allowlist for this carrier — fall back to the legacy
  // ground-family denylist.
  return !US_TERRITORY_GROUND_DENYLIST.has(serviceCode)
}

/**
 * Short human phrase for the inline banner explaining what got hidden.
 * Baked here so the banner copy stays consistent with the allowlist —
 * changing PR's UPS set only in one place also updates the message.
 */
export function usTerritoryBannerHint(territory: string | null | undefined): string {
  if (!isUsTerritory(territory)) return ''
  switch (territory) {
    case 'PR':
      return 'Ground / Home Delivery / SmartPost / 3 Day Select don’t serve it and have been hidden'
    case 'VI':
    case 'GU':
    case 'AS':
    case 'MP':
    case 'UM':
      return 'only Worldwide / International-family services deliver — every domestic Ground and Air service has been hidden'
    default:
      return ''
  }
}
