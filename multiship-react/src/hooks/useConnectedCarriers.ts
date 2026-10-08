import { useEffect, useState } from 'react'
import { accountRefService } from '../api/accountRefService'
import { isAbortError } from '../api/apiClient'

/** Map a stored account carrier code to the canonical pickup/close carrier. */
function canonical(code: string): string {
  const c = code.trim().toUpperCase()
  if (c === 'STAMPS_COM' || c === 'USPS_DIRECT') return 'USPS'
  return c
}

/**
 * V135 — the carriers actually connected on this application: those with at
 * least one active carrier account (CarrierAccountRef). Used to show only
 * connected carriers in the pickup / close-out pickers.
 *
 * Returns `null` while loading or if the lookup fails — callers treat null as
 * "unknown" and fall back to showing every carrier, so the picker is never
 * empty or broken when the accounts can't be read.
 */
export function useConnectedCarriers(): Set<string> | null {
  const [carriers, setCarriers] = useState<Set<string> | null>(null)
  useEffect(() => {
    let cancelled = false
    accountRefService.listAccounts()
      .then((accounts) => {
        if (cancelled) return
        setCarriers(new Set(
          accounts
            .filter((a) => a.active !== false && a.carrierCode)
            .map((a) => canonical(a.carrierCode)),
        ))
      })
      .catch((e) => { if (!isAbortError(e)) console.debug('[load] connected carriers', e) })
    return () => { cancelled = true }
  }, [])
  return carriers
}
