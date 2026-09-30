/**
 * C2 — resolves the tenant's ship-from defaults for the /orders/new
 * sender prefill. Returns null while loading or when the resolve fails
 * (dev without a real tenant, offline, etc.); callers seed the form
 * blank in that case.
 *
 * Refetches whenever the picked clientCode changes (multi-client users
 * can address different tenants inside the same page load).
 */
import { useEffect, useState } from 'react'
import { shipperResolveService, type ResolvedShipper } from '../api/shipperResolveService'

export function useShipperDefault(clientCode?: string | null): ResolvedShipper | null {
  const [value, setValue] = useState<ResolvedShipper | null>(null)

  useEffect(() => {
    let cancelled = false
    setValue(null)
    void shipperResolveService
      .resolve(clientCode ?? undefined)
      .then((r) => { if (!cancelled) setValue(r) })
      .catch(() => { if (!cancelled) setValue(null) })
    return () => { cancelled = true }
  }, [clientCode])

  return value
}
