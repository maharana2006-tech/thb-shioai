import { apiClient } from './apiClient'
import type { ApiResponse } from './orderService'

export type CustomFieldType = 'TEXT' | 'NUMBER' | 'DATE' | 'SELECT'

/** Sprint 43 — per-tenant custom field definition. */
export interface CustomFieldDefinition {
  id?: number | null
  tenantId?: string | null
  fieldKey: string
  label: string
  fieldType: CustomFieldType
  selectOptions?: string | null
  required?: boolean
  position?: number
  active?: boolean
  createdAt?: string | null
  updatedAt?: string | null
}

const BASE = '/custom-fields'

export const customFieldService = {
  /** Every definition for a tenant (active + inactive) — settings view. */
  list: async (tenantId?: string | null): Promise<CustomFieldDefinition[]> => {
    const params = new URLSearchParams()
    if (tenantId) params.set('tenantId', tenantId)
    const resp = await apiClient.get<ApiResponse<CustomFieldDefinition[]>>(
      params.toString() ? `${BASE}?${params}` : BASE,
    )
    return resp.data ?? []
  },

  /** Only active fields that should appear on the order form. */
  applicable: async (tenantId?: string | null): Promise<CustomFieldDefinition[]> => {
    const params = new URLSearchParams()
    if (tenantId) params.set('tenantId', tenantId)
    const resp = await apiClient.get<ApiResponse<CustomFieldDefinition[]>>(
      `${BASE}/applicable${params.toString() ? `?${params}` : ''}`,
    )
    return resp.data ?? []
  },

  save: (def: CustomFieldDefinition) =>
    apiClient.post<ApiResponse<CustomFieldDefinition>>(BASE, def),

  remove: (id: number) => apiClient.delete<ApiResponse<void>>(`${BASE}/${id}`),

  values: async (orderNo: number | string): Promise<Record<string, string>> => {
    const resp = await apiClient.get<ApiResponse<Record<string, string>>>(
      `/orders/${orderNo}/custom-fields`,
    )
    return resp.data ?? {}
  },

  upsertValues: (
    orderNo: number | string,
    values: Record<string, string>,
    tenantId?: string | null,
  ) => {
    const qs = tenantId ? `?tenantId=${encodeURIComponent(tenantId)}` : ''
    return apiClient.post<ApiResponse<Record<string, string>>>(
      `/orders/${orderNo}/custom-fields${qs}`,
      values,
    )
  },
}
