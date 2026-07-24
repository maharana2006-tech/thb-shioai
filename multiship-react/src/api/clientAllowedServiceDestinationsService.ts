import { apiClient } from './apiClient'
import type { ApiResponse } from './orderService'

export interface AllowedServiceDestinations {
  clientCode: string
  serviceId: number
  /** ISO-2 codes, sorted A→Z. Empty = unrestricted (any destination). */
  countries: string[]
}

export interface ReplaceAllowedServiceDestinationsPayload {
  countries: string[]
}

/** Destination-gate on a single ClientAllowedService row. */
export const clientAllowedServiceDestinationsService = {
  get: (clientCode: string, serviceId: number) =>
    apiClient.get<ApiResponse<AllowedServiceDestinations>>(
      `/clients/${encodeURIComponent(clientCode)}/services/${serviceId}/destinations`,
    ),

  replace: (clientCode: string, serviceId: number, payload: ReplaceAllowedServiceDestinationsPayload) =>
    apiClient.put<ApiResponse<AllowedServiceDestinations>>(
      `/clients/${encodeURIComponent(clientCode)}/services/${serviceId}/destinations`,
      payload,
    ),

  clear: (clientCode: string, serviceId: number) =>
    apiClient.delete<ApiResponse<void>>(
      `/clients/${encodeURIComponent(clientCode)}/services/${serviceId}/destinations`,
    ),
}
