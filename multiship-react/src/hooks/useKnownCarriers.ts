/**
 * C4 — module-scoped cache + hook for the DB-driven carrier registry.
 * First render uses a small hardcoded fallback so the form doesn't
 * flicker; the DB list overrides on the next tick.
 *
 * The cache is shared across every consumer so `/orders/new` doesn't
 * refetch on every remount.
 */
import { useEffect, useState } from 'react'
import { carrierRegistryService, type KnownCarrier } from '../api/carrierRegistryService'

/** Fallback for the very first paint before the fetch resolves. Matches
 *  the four canonical carriers that shipped before C4. */
const FALLBACK: KnownCarrier[] = [
  { code: 'UPS',   label: 'UPS'   },
  { code: 'FEDEX', label: 'FedEx' },
  { code: 'USPS',  label: 'USPS'  },
  { code: 'DHL',   label: 'DHL'   },
]

let cache: KnownCarrier[] | null = null
let inFlight: Promise<KnownCarrier[]> | null = null

export function useKnownCarriers(): KnownCarrier[] {
  const [list, setList] = useState<KnownCarrier[]>(cache ?? FALLBACK)

  useEffect(() => {
    if (cache) {
      setList(cache)
      return
    }
    if (!inFlight) {
      inFlight = carrierRegistryService
        .listKnown()
        .then((v) => {
          cache = v.length > 0 ? v : FALLBACK
          inFlight = null
          return cache
        })
        .catch(() => {
          inFlight = null
          return FALLBACK
        })
    }
    let cancelled = false
    void inFlight.then((v) => {
      if (!cancelled) setList(v)
    })
    return () => { cancelled = true }
  }, [])

  return list
}

/** Convenience: `{UPS:"UPS", FEDEX:"FedEx", ...}` derived from the hook. */
export function toCarrierLabelMap(list: KnownCarrier[]): Record<string, string> {
  const out: Record<string, string> = {}
  for (const c of list) out[c.code] = c.label
  return out
}
