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
  createdAt: string | null
  updatedAt: string | null
}

export interface CredentialCheck {
  verified: boolean
  message: string
  checkedAt: string | null
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
  | 'NEEDS_DETAILS'
  | 'CLIENT_DEFAULT'
  | 'MANUAL'
  | 'CHOOSE_ACCOUNT'
  | 'DEFAULT'
  | 'CLIENT_MISSING'
  | 'CLIENT_INACTIVE'
  | 'NO_DEFAULT'

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

  /** Platform-account credentials for a carrier, to pre-fill the add-account drawer. */
  getPlatformCredentials: (carrierCode: string) => {
    return apiClient.get<ApiResponse<{ carrierCode: string; clientId: string | null; clientSecret: string | null; found: boolean }>>(
      `/carrier-accounts/platform-credentials/${encodeURIComponent(carrierCode)}`
    )
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
