import { apiClient } from './apiClient'
import type { ApiResponse, ManualShipmentAddress } from './orderService'

export interface AddressValidationResult {
  valid: boolean
  issues: string[]
  normalized: ManualShipmentAddress
  carrierValidated: boolean
}

export const addressService = {
  /** Structural validation of a recipient/sender address (name, line1, city, postal, country). */
  validate(address: ManualShipmentAddress): Promise<ApiResponse<AddressValidationResult>> {
    return apiClient.post<ApiResponse<AddressValidationResult>>('/addresses/validate', address)
  },
}
