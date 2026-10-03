/**
 * V120 — read-only clients for /api/v1/admin/carrier-dropdowns/*. ADMIN-only.
 */
import { apiClient } from './apiClient'
import type { ApiResponse } from './orderService'

export interface CarrierLabelFormatRow {
  carrier: string
  code: string
  label: string
  isStockType: boolean
  sortOrder: number | null
}

export interface CarrierPickupTypeRow {
  carrier: string
  code: string
  label: string
  sortOrder: number | null
}

export interface CarrierClearanceOptionRow {
  carrier: string
  code: string
  label: string
  sortOrder: number | null
}

const BASE = '/admin/carrier-dropdowns'

export const carrierDropdownsService = {
  labelFormats: () =>
    apiClient
      .get<ApiResponse<CarrierLabelFormatRow[]>>(`${BASE}/label-formats`)
      .then((res) => res.data ?? []),

  pickupTypes: () =>
    apiClient
      .get<ApiResponse<CarrierPickupTypeRow[]>>(`${BASE}/pickup-types`)
      .then((res) => res.data ?? []),

  clearanceOptions: () =>
    apiClient
      .get<ApiResponse<CarrierClearanceOptionRow[]>>(`${BASE}/clearance-options`)
      .then((res) => res.data ?? []),
}
