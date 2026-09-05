import { apiClient } from './apiClient'
import type { ApiResponse } from './orderService'

/** One entry in the carrier account reference book (credentials never leave the backend). */
export interface CarrierAccountRef {
  id: number
  accountNumber: string
  carrierCode: string
  accountName: string | null
  customerNo: string | null
  environment: string | null
  isDefault: boolean
  /** Default account for the linked client (customerNo). */
  clientDefault?: boolean
  active: boolean
  complete: boolean
  clientIdPreview: string | null
  /** Last live credential check: true/false, or null when never checked. */
  verified: boolean | null
  lastVerifiedAt: string | null
  /** Usage: labels generated with this account and when it last shipped. */
  labelsGenerated: number | null
  lastUsedAt: string | null
  /** International-shipment defaults per account. Wire values match
   *  {@link ../utils/customsOptions}. Nullable — carriers apply their own
   *  defaults when unset. */
  shippingPurpose?: string | null
  clearanceOption?: string | null
  /** F6-B2 — per-account billing currency (ISO 4217). NULL means "use
   *  carrier home currency" (USPS/UPS/FedEx → USD, DHL → EUR). Non-null
   *  overrides both carrier default AND client currency. */
  currency?: string | null
  /** FDX-H1 — per-account default pickupType (FedEx only; UPS/DHL/USPS
   *  ignore). NULL means USE_SCHEDULED_PICKUP (pre-FDX-H1 hardcode).
   *  Values: REGULAR_PICKUP | REQUEST_COURIER | DROP_BOX |
   *  BUSINESS_SERVICE_CENTER | STATION | USE_SCHEDULED_PICKUP. */
  pickupType?: string | null
  /** UPS-4a — per-account UPS LabelImageFormat (UPS only; other carriers
   *  ignore). NULL means GIF (pre-UPS-4a hardcode). Values: GIF | PDF |
   *  PNG | ZPL | EPL. */
  labelImageFormat?: string | null
  /** FDX-H3 — per-account FedEx labelSpecification.imageType (FedEx only;
   *  other carriers ignore). NULL means PDF (pre-FDX-H3 hardcode). Values:
   *  PDF | PNG | ZPLII | EPL2 | DPL. */
  labelImageType?: string | null
  /** FDX-H3 — per-account FedEx labelSpecification.labelStockType (FedEx
   *  only; other carriers ignore). NULL means PAPER_4X6 (pre-FDX-H3
   *  hardcode). */
  labelStockType?: string | null
  /** Per-account label stock size in inches. UPS emits verbatim; FedEx /
   *  DHL / USPS map to their per-carrier enums at connector time. Nulls
   *  fall back to 4×6 (Height=6, Width=4) at label time. Standard picks:
   *  4x6, 4x8, 4x9, 6x4, 8x4. */
  labelStockHeight?: number | null
  labelStockWidth?: number | null
  /** Third-party billing default — only meaningful when clearanceOption is
   *  THIRD_PARTY. Nullable individually; per-shipment overrides live on the
   *  Shipment row (follow-up). */
  thirdPartyAccount?: string | null
  thirdPartyName?: string | null
  thirdPartyAddress1?: string | null
  thirdPartyCity?: string | null
  thirdPartyState?: string | null
  thirdPartyPostcode?: string | null
  thirdPartyCountry?: string | null
  createdAt: string | null
  updatedAt: string | null
}

export interface CredentialCheck {
  verified: boolean
  message: string
  checkedAt: string | null
}

/**
 * Lightweight projection of a carrier account for the
 * `/settings/shipping-catalog` sync menu. Carries only fields the picker
 * needs — no credentials, no third-party billing, no usage stats.
 * Backend derives `isPlatform` from customerNo so the FE can group
 * accounts without reimplementing the null-customerNo convention.
 */
export interface SyncEligibleAccount {
  id: number
  carrierCode: string
  accountNumber: string
  accountName: string | null
  environment: string
  isPlatform: boolean
  customerNo: string | null
}

export interface AccountRefUpsertPayload {
  accountNumber: string
  carrierCode: string
  accountName?: string
  /** Blank/omitted on update = keep the persisted value. Required on create. */
  clientId?: string
  /** Blank/omitted on update = keep the persisted value. Required on create. */
  clientSecret?: string
  environment?: string
  customerNo?: string
  /** Make this the linked client's default account. */
  clientDefault?: boolean
  /** International-shipment defaults; both optional. Values validated by the
   *  frontend against the enum in ../utils/customsOptions. */
  shippingPurpose?: string | null
  clearanceOption?: string | null
  /** F6-B2 — per-account billing currency (ISO 4217, e.g. USD / EUR). Null
   *  in the payload clears the persisted value; omitted keeps it. */
  currency?: string | null
  /** FDX-H1 — per-account default pickupType (FedEx only). Null clears the
   *  persisted value; omitted keeps it. Value must be a FedEx pickupType
   *  enum member; the backend rejects anything else with a Bean-Validation
   *  400 (pattern-constrained). */
  pickupType?: string | null
  /** UPS-4a — per-account UPS LabelImageFormat (UPS only). Null clears the
   *  persisted value; omitted keeps it. Value must be one of
   *  GIF / PDF / PNG / ZPL / EPL (pattern-constrained backend-side). */
  labelImageFormat?: string | null
  /** FDX-H3 — per-account FedEx labelSpecification.imageType (FedEx only).
   *  Null clears the persisted value; omitted keeps it. Value must be one
   *  of PDF / PNG / ZPLII / EPL2 / DPL (pattern-constrained backend-side). */
  labelImageType?: string | null
  /** FDX-H3 — per-account FedEx labelSpecification.labelStockType (FedEx
   *  only). Null clears the persisted value; omitted keeps it. */
  labelStockType?: string | null
  /** Per-account label stock size in inches (both required or both null).
   *  Backend validates each in [3.0, 8.0] range per UPS Ship API spec. */
  labelStockHeight?: number | null
  labelStockWidth?: number | null
  /** Third-party billing default (only sent when clearance = THIRD_PARTY).
   *  Null on any field = clear the persisted value; omitting the field from
   *  the payload entirely = keep the persisted value. */
  thirdPartyAccount?: string | null
  thirdPartyName?: string | null
  thirdPartyAddress1?: string | null
  thirdPartyCity?: string | null
  thirdPartyState?: string | null
  thirdPartyPostcode?: string | null
  thirdPartyCountry?: string | null
}

/**
 * Which account an order will ship with:
 * ORDER - full details on the order itself
 * REFERENCE - order's account number matched a complete saved account
 * NEEDS_DETAILS - partial details; the fill-up form is required
 * DEFAULT - no details; the admin's global default applies
 * NO_DEFAULT - no details and no global default configured
 */
export type ResolutionScenario =
  | 'ORDER'
  | 'REFERENCE'
  | 'GENERATED'
  | 'NEEDS_DETAILS'
  | 'CLIENT_DEFAULT'
  | 'MANUAL'
  | 'CHOOSE_ACCOUNT'
  | 'DEFAULT'
  | 'CLIENT_MISSING'
  | 'CLIENT_INACTIVE'
  | 'NO_DEFAULT'
  // Dev's f047c80 ("nine defects from Orders-section E2E reports") added
  // FAILED + VOIDED cases in AccountScenarioBadge.tsx without extending
  // this union — the TS build broke at the switch statement. Adding
  // them here to close the loop.
  | 'FAILED'
  | 'VOIDED'

export interface OrderAccountResolution {
  orderNo: number
  scenario: ResolutionScenario
  carrierCode: string | null
  accountNumber: string | null
  accountName: string | null
  environment: string | null
  missingFields: string[] | null
  prefillClientId: string | null
}

export const accountRefService = {
  listAccounts: async (): Promise<CarrierAccountRef[]> => {
    const response = await apiClient.get<ApiResponse<CarrierAccountRef[]>>('/carrier-accounts')
    return Array.isArray(response.data) ? response.data : []
  },

  /** Create or complete an account; scenario-2 fill-ups land here permanently. */
  upsertAccount: (payload: AccountRefUpsertPayload) => {
    return apiClient.post<ApiResponse<CarrierAccountRef>>('/carrier-accounts', payload)
  },

  setClientDefault: (accountId: number) => {
    return apiClient.post<ApiResponse<CarrierAccountRef>>(`/carrier-accounts/${accountId}/client-default`)
  },


  toggleActive: (accountId: number) => {
    return apiClient.post<ApiResponse<CarrierAccountRef>>(`/carrier-accounts/${accountId}/toggle-active`)
  },

  /** ADMIN-only hard delete; backend refuses (409) if any labels have been generated on the account. */
  deleteAccount: (accountId: number) => {
    return apiClient.delete<ApiResponse<void>>(`/carrier-accounts/${accountId}`)
  },

  /** Live OAuth check for a saved account; stamps verified + lastVerifiedAt on it. */
  verifyAccount: (accountId: number) => {
    return apiClient.post<ApiResponse<CarrierAccountRef>>(`/carrier-accounts/${accountId}/verify`)
  },

  /** Stateless credential check used by the add-account drawer before saving.
   *  `accountNumber` is optional but recommended for UPS (used as x-merchant-id
   *  on the OAuth request; ignored by carriers that don't need it).
   *  `environment` (SANDBOX | PRODUCTION) routes UPS to the matching UPS OAuth
   *  host — a CIE Consumer Key 401s against the production host and vice
   *  versa. Ignored by carriers where the endpoint is env-agnostic. */
  verifyCredentials: (payload: {
    carrierCode: string
    clientId: string
    clientSecret: string
    accountNumber?: string
    environment?: string
  }) => {
    return apiClient.post<ApiResponse<CredentialCheck>>('/carrier-accounts/verify-credentials', payload)
  },

  /**
   * Platform-account metadata for a carrier, to pre-fill Client ID in the
   * add-account drawer. Sprint 49 Tier 1: the API no longer returns the
   * plaintext client_secret — only a masked preview. The drawer prompts
   * the admin to re-enter or paste the secret.
   */
  getPlatformCredentials: (carrierCode: string) => {
    return apiClient.get<ApiResponse<{
      carrierCode: string;
      clientId: string | null;
      clientSecretMasked: string | null;
      hasClientSecret: boolean;
      found: boolean
    }>>(
      `/carrier-accounts/platform-credentials/${encodeURIComponent(carrierCode)}`
    )
  },

  /**
   * Verified + active accounts (platform + client) for a carrier, sorted
   * platform-first-newest. Powers the /settings/shipping-catalog sync
   * menu — the operator picks env + account before pulling the carrier's
   * service or package catalog. Returns [] when no verified accounts
   * exist so the caller can show a "connect an account first" empty
   * state instead of a network error.
   */
  listSyncEligible: async (carrier: string): Promise<SyncEligibleAccount[]> => {
    const response = await apiClient.get<ApiResponse<SyncEligibleAccount[]>>(
      `/carrier-accounts/sync-eligible?carrier=${encodeURIComponent(carrier)}`,
    )
    return Array.isArray(response.data) ? response.data : []
  },

  /** Bulk preview of the generation scenario for each order. */
  resolveOrders: async (orderNos: number[]): Promise<OrderAccountResolution[]> => {
    if (!orderNos.length) {
      return []
    }

    const response = await apiClient.post<ApiResponse<OrderAccountResolution[]>>('/orders/resolve-accounts', orderNos)
    return Array.isArray(response.data) ? response.data : []
  },
}
