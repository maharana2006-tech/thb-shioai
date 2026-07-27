import { apiClient } from './apiClient'
import type { ApiResponse } from './orderService'

/**
 * Sprint 42 — tenant-branded packing-slip template. The carrier's
 * shipping label is not editable (carrier-mandated); this drives the
 * branded page that ships INSIDE the parcel.
 */
export interface LabelTemplate {
  id?: number | null
  tenantId?: string | null
  templateType?: string  // defaults to PACKING_SLIP server-side
  logoBase64?: string | null
  primaryColor?: string | null
  headerText?: string | null
  footerText?: string | null
  showItems?: boolean | null
  createdAt?: string | null
  updatedAt?: string | null
}

const BASE = '/label-templates'

export const labelTemplateService = {
  /** Resolve tenant + fallback platform default. Returns null on 404. */
  resolve: async (
    tenantId?: string | null,
    templateType: string = 'PACKING_SLIP',
  ): Promise<LabelTemplate | null> => {
    const params = new URLSearchParams({ templateType })
    if (tenantId) params.set('tenantId', tenantId)
    try {
      const response = await apiClient.get<ApiResponse<LabelTemplate>>(
        `${BASE}/resolve?${params.toString()}`,
      )
      return response.data ?? null
    } catch (err: any) {
      if (err?.response?.status === 404) return null
      throw err
    }
  },

  /** Fetch the tenant's own template only (no platform fallback). */
  forTenant: async (
    tenantId?: string | null,
    templateType: string = 'PACKING_SLIP',
  ): Promise<LabelTemplate | null> => {
    const params = new URLSearchParams({ templateType })
    if (tenantId) params.set('tenantId', tenantId)
    try {
      const response = await apiClient.get<ApiResponse<LabelTemplate>>(
        `${BASE}/tenant?${params.toString()}`,
      )
      return response.data ?? null
    } catch (err: any) {
      if (err?.response?.status === 404) return null
      throw err
    }
  },

  save: (template: LabelTemplate) =>
    apiClient.post<ApiResponse<LabelTemplate>>(BASE, template),

  remove: (id: number) => apiClient.delete<ApiResponse<void>>(`${BASE}/${id}`),

  /**
   * Preview URL for the branded packing slip of a given order. The
   * PDF endpoint is served under /api/v1/orders/{orderNo}/packing-slip
   * and returns application/pdf inline; embed via <iframe src=..>
   * or open in a new tab.
   */
  previewUrl: (orderNo: number | string): string =>
    `/api/v1/orders/${orderNo}/packing-slip`,
}
