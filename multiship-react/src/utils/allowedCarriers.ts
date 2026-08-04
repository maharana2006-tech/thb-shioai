import type { CarrierAccountRef } from '../api/accountRefService'

/**
 * Which carrier codes the operator is allowed to pick when creating a
 * shipping-service mapping — used to narrow the "Carrier Ship Via"
 * dropdown so it only lists services the client can actually ship on.
 *
 * Two sources contribute:
 *   1. The client's own active + complete carrier accounts (edit mode)
 *      OR the currently-drafted carrier accounts (create mode, before the
 *      client is persisted).
 *   2. An OPTIONAL platform carrier account the operator explicitly picked
 *      to extend or seed the filter — its `carrierCode` is added to the
 *      allowed set.
 *
 * The result set is intersected against the platform's service catalog
 * downstream; empty here means "nothing to offer, prompt the operator to
 * pick a platform account or add a carrier account to this client".
 */

export interface ComputeAllowedCarriersInput {
  /** ISO client code (upper-case). Empty / null = Any-client mapping. */
  clientCode?: string | null
  /**
   * Live carrier accounts fetched from accountRefService.listAccounts().
   * Both client-owned (customerNo set) and platform (customerNo blank) rows
   * are needed — the helper filters internally.
   */
  accounts: CarrierAccountRef[]
  /**
   * Draft carrier codes staged in memory (Client-editor create mode, before
   * accounts are POSTed). Ignored in edit mode where `accounts` is the
   * source of truth.
   */
  clientCarrierDrafts?: ReadonlyArray<{ carrierCode: string }>
  /**
   * Id of the platform carrier account the operator picked to seed / extend
   * the filter. Null = no override.
   */
  platformAccountId?: number | null
}

export interface AllowedCarriersResult {
  /** Union of carrier codes (upper-case) the Ship Via dropdown should offer. */
  carriers: Set<string>
  /** True when the client has at least one active + complete own account. */
  hasClientCarriers: boolean
  /** True when the operator picked a platform account to extend the filter. */
  hasPlatformOverride: boolean
  /** Convenience: `carriers.size === 0` — the dropdown should render empty
   *  with a "pick a platform account first" hint. */
  isEmpty: boolean
}

function isActiveComplete(a: CarrierAccountRef): boolean {
  return !!a.active && !!a.complete && !!a.carrierCode
}

function isClientOwned(a: CarrierAccountRef, upperClientCode: string): boolean {
  return !!a.customerNo && a.customerNo.toUpperCase() === upperClientCode
}

function isPlatformOwned(a: CarrierAccountRef): boolean {
  return a.customerNo == null || a.customerNo.trim() === ''
}

export function computeAllowedCarriers(input: ComputeAllowedCarriersInput): AllowedCarriersResult {
  const carriers = new Set<string>()
  const upperClientCode = (input.clientCode ?? '').trim().toUpperCase()
  // 1) client's own carriers (persisted accounts win over drafts)
  let hasClientCarriers = false
  if (upperClientCode) {
    for (const a of input.accounts) {
      if (!isActiveComplete(a)) continue
      if (!isClientOwned(a, upperClientCode)) continue
      carriers.add(a.carrierCode.toUpperCase())
      hasClientCarriers = true
    }
  }
  // 2) staged drafts (create-mode fallback — nothing in accounts[] yet)
  if (!hasClientCarriers && input.clientCarrierDrafts) {
    for (const d of input.clientCarrierDrafts) {
      if (!d.carrierCode) continue
      carriers.add(d.carrierCode.toUpperCase())
      hasClientCarriers = true
    }
  }
  // 3) platform-account override (extend or seed)
  let hasPlatformOverride = false
  if (input.platformAccountId != null) {
    const picked = input.accounts.find((a) => a.id === input.platformAccountId)
    if (picked && isActiveComplete(picked) && isPlatformOwned(picked)) {
      carriers.add(picked.carrierCode.toUpperCase())
      hasPlatformOverride = true
    }
  }
  return {
    carriers,
    hasClientCarriers,
    hasPlatformOverride,
    isEmpty: carriers.size === 0,
  }
}

/** Every active + complete platform account — surfaced in the platform-account
 *  picker dropdowns. Sorted by carrier then account number for stable order. */
export function platformAccounts(accounts: CarrierAccountRef[]): CarrierAccountRef[] {
  return accounts
    .filter((a) => isActiveComplete(a) && isPlatformOwned(a))
    .slice()
    .sort((a, b) => {
      const c = (a.carrierCode || '').localeCompare(b.carrierCode || '')
      if (c !== 0) return c
      return (a.accountNumber || '').localeCompare(b.accountNumber || '')
    })
}
