import { apiClient } from './apiClient'
import type { ApiResponse } from './orderService'
import type { CarrierEnvironment } from '../utils/carrierUtils'

export interface Carrier {
  carrierCode: string
  carrierName: string
  description?: string | null
  accountType?: string | null
  environment?: string | null
  documentationUrl?: string | null
  connectionGuide?: string | null
}

export interface CarrierStatus {
  connected: boolean
  carrierCode: string | null
  carrierName: string | null
  accountNumber: string | null
  environment: string | null
  connectedAt: string | null
  tokenValid: boolean
}

export interface CarrierConnectionPayload {
  carrierCode: string
  clientId: string
  clientSecret: string
  accountNumber: string
  environment: CarrierEnvironment
  /** Optional tenant (customer) code — stores this connection as a tenant carrier account. */
  tenantId?: string
  /** With tenantId: make this account the tenant's default for label generation. */
  setAsDefault?: boolean
}

/** Backend CarrierAccountDTO: a tenant's carrier account stored in carrier_config. */
export interface TenantCarrierAccount {
  id: number
  tenantId: string
  carrierCode: string
  carrierName: string
  accountNumber: string | null
  accountCode: string | null
  isDefault: boolean
  active: boolean | null
  environment: string | null
  shipViaCd: string | null
  shipViaDescription: string | null
  createdAt: string | null
  updatedAt: string | null
}

const isRecord = (value: unknown): value is Record<string, unknown> =>
  typeof value === 'object' && value !== null && !Array.isArray(value)

const getString = (value: unknown) => (typeof value === 'string' ? value : null)

const getNumber = (value: unknown) => (typeof value === 'number' ? value : null)

const getBoolean = (value: unknown, fallback = false) =>
  typeof value === 'boolean' ? value : fallback

export const normalizeCarrier = (value: unknown): Carrier | null => {
  if (!isRecord(value)) {
    return null
  }

  const carrierCode = getString(value.carrierCode) ?? getString(value.code)
  const carrierName = getString(value.carrierName) ?? getString(value.name)

  if (!carrierCode || !carrierName) {
    return null
  }

  return {
    carrierCode,
    carrierName,
    description: getString(value.description),
    accountType: getString(value.accountType),
    environment: getString(value.environment),
    documentationUrl: getString(value.documentationUrl),
    connectionGuide: getString(value.connectionGuide),
  }
}

export const normalizeCarrierStatus = (value: unknown): CarrierStatus | null => {
  if (!isRecord(value)) {
    return null
  }

  return {
    connected: getBoolean(value.connected),
    carrierCode: getString(value.carrierCode),
    carrierName: getString(value.carrierName),
    accountNumber: getString(value.accountNumber),
    environment: getString(value.environment),
    connectedAt: getString(value.connectedAt),
    // Backend reports `tokenExpired`; treat a missing flag as a valid token.
    tokenValid: !getBoolean(value.tokenExpired, false),
  }
}

export const normalizeTenantAccount = (value: unknown): TenantCarrierAccount | null => {
  if (!isRecord(value)) {
    return null
  }

  const id = getNumber(value.id)
  const tenantId = getString(value.tenantId)
  const carrierCode = getString(value.carrierCode)

  if (id === null || !tenantId || !carrierCode) {
    return null
  }

  return {
    id,
    tenantId,
    carrierCode,
    carrierName: getString(value.carrierName) ?? carrierCode,
    accountNumber: getString(value.accountNumber),
    accountCode: getString(value.accountCode),
    isDefault: getBoolean(value.isDefault),
    active: typeof value.active === 'boolean' ? value.active : null,
    environment: getString(value.environment),
    shipViaCd: getString(value.shipViaCd),
    shipViaDescription: getString(value.shipViaDescription),
    createdAt: getString(value.createdAt),
    updatedAt: getString(value.updatedAt),
  }
}

export const carrierService = {
  getAvailableCarriers: async () => {
    const response = await apiClient.get<ApiResponse<unknown>>('/carriers')
    const payload = response.data

    if (!Array.isArray(payload)) {
      return []
    }

    return payload.flatMap((carrier) => {
      const normalizedCarrier = normalizeCarrier(carrier)
      return normalizedCarrier ? [normalizedCarrier] : []
    })
  },

  getCarrierStatus: async () => {
    const response = await apiClient.get<ApiResponse<unknown>>('/carriers/status')
    return normalizeCarrierStatus(response.data)
  },

  connectCarrier: async (payload: CarrierConnectionPayload) => {
    const response = await apiClient.post<ApiResponse<unknown>>('/carriers/connect', payload)
    return normalizeCarrierStatus(response.data)
  },

  disconnectCarrier: async (carrierCode?: string | null) => {
    const response = await apiClient.post<ApiResponse<unknown>>('/carriers/disconnect', carrierCode ? { carrierCode } : {})
    return normalizeCarrierStatus(response.data)
  },

}
