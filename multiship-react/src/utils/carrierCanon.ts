/** Canonicalise a carrier code (ERP aliases → UPS/FEDEX/USPS).
 *  C4: closed-set 4-carrier line removed — the DB-driven carrier_alias
 *  registry is the source of truth for canonical codes. This function
 *  keeps the ERP shortcuts (P80/F77/L01/STAMPS) and the service-code
 *  prefix heuristics because they're smart-defaults for user input,
 *  not authoritative canonicalisation. */
export const canon = (c?: string | null) => {
  const v = (c || '').trim().toUpperCase()
  if (v === 'P80') return 'UPS'
  if (v === 'F77') return 'FEDEX'
  if (v === 'L01') return 'USPS'
  if (v === 'STAMPS') return 'USPS'
  // Carrier-prefixed SERVICE codes (e.g. INTERNATIONAL_ECONOMY, FEDEX_2_DAY)
  // map back to their carrier — the fix/edit form derives the carrier from the
  // order's ship-via code, which is a service code, not a carrier code.
  if (v.startsWith('FEDEX') || v.startsWith('INTERNATIONAL_') || v.includes('OVERNIGHT')
      || v.includes('GROUND_HOME') || v.includes('SMART_POST')) return 'FEDEX'
  if (v.startsWith('UPS') || v.includes('WORLDWIDE') || v.includes('NEXT_DAY_AIR')) return 'UPS'
  if (v.startsWith('USPS')) return 'USPS'
  if (v.startsWith('DHL')) return 'DHL'
  // UPS's catalog stores numeric service codes (03 Ground, 08 Worldwide
  // Expedited …) — the only carrier that does.
  if (/^\d{2}$/.test(v)) return 'UPS'
  return v
}
