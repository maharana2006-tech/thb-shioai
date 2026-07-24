import { apiClient } from './apiClient'
import type { ApiResponse } from './orderService'

/**
 * One entry in a client's allowed-service list. The service summary is
 * flattened onto the row so the UI can render each entry without a
 * follow-up catalog fetch (backend already joins them in).
 */
export interface ClientAllowedService {
  id: number
  clientCode: string
  serviceId: number
  carrier: string | null
  serviceCode: string | null
  serviceName: string | null
  scope: string | null
  originCountry: string | null
  isDefault: boolean
  createdAt: string | null
  updatedAt: string | null
}

/** POST /clients/{code}/services body. */
export interface AllowServicePayload {
  serviceId: number
  makeDefault: boolean
}

export interface ClientAllowedPackage {
  id: number
  clientCode: string
  presetId: number
  name: string | null
  /** CARRIER | CUSTOM */
  kind: string | null
  carrier: string | null
  carrierPackageCode: string | null
  originCountry: string | null
  /** DOMESTIC | INTERNATIONAL | BOTH */
  scope: string | null
  length: number | null
  width: number | null
  height: number | null
  dimUnit: string | null
  maxWeight: number | null
  weightUnit: string | null
  isDefault: boolean
  createdAt: string | null
  updatedAt: string | null
}

/** POST /clients/{code}/packages body. */
export interface AllowPackagePayload {
  presetId: number
  makeDefault: boolean
}

export const clientAllowedServicesService = {
  listForClient: (clientCode: string) =>
    apiClient.get<ApiResponse<ClientAllowedService[]>>(
      `/clients/${encodeURIComponent(clientCode)}/services`,
    ),

  allow: (clientCode: string, payload: AllowServicePayload) =>
    apiClient.post<ApiResponse<ClientAllowedService>>(
      `/clients/${encodeURIComponent(clientCode)}/services`,
      payload,
    ),

  remove: (clientCode: string, serviceId: number) =>
    apiClient.delete<ApiResponse<void>>(
      `/clients/${encodeURIComponent(clientCode)}/services/${serviceId}`,
    ),

  setDefault: (clientCode: string, serviceId: number) =>
    apiClient.put<ApiResponse<ClientAllowedService>>(
      `/clients/${encodeURIComponent(clientCode)}/services/${serviceId}/default`,
      {},
    ),
}

export const clientAllowedPackagesService = {
  listForClient: (clientCode: string) =>
    apiClient.get<ApiResponse<ClientAllowedPackage[]>>(
      `/clients/${encodeURIComponent(clientCode)}/packages`,
    ),

  allow: (clientCode: string, payload: AllowPackagePayload) =>
    apiClient.post<ApiResponse<ClientAllowedPackage>>(
      `/clients/${encodeURIComponent(clientCode)}/packages`,
      payload,
    ),

  remove: (clientCode: string, presetId: number) =>
    apiClient.delete<ApiResponse<void>>(
      `/clients/${encodeURIComponent(clientCode)}/packages/${presetId}`,
    ),

  setDefault: (clientCode: string, presetId: number) =>
    apiClient.put<ApiResponse<ClientAllowedPackage>>(
      `/clients/${encodeURIComponent(clientCode)}/packages/${presetId}/default`,
      {},
    ),
}

/**
 * Cross-client usage reads for the settings catalog pages. Each endpoint
 * returns every client's allowlist entry for the given catalog; the caller
 * groups by service/preset id to render "Assigned to (n)" columns and
 * drawers of the client codes.
 */
export const allowlistUsageService = {
  services: () =>
    apiClient.get<ApiResponse<ClientAllowedService[]>>('/allowlist-usage/services'),

  packages: () =>
    apiClient.get<ApiResponse<ClientAllowedPackage[]>>('/allowlist-usage/packages'),
}
