import { apiClient, BASE_URL } from './apiClient'
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
  /**
   * Resolve the effective template for a tenant, falling back to the platform
   * default (tenantId=null). Returns null when neither exists — the endpoint
   * always answers 200 (see LabelTemplateController).
   */
  resolve: async (
    tenantId?: string | null,
    templateType: string = 'PACKING_SLIP',
  ): Promise<LabelTemplate | null> => {
    const params = new URLSearchParams({ templateType })
    if (tenantId) params.set('tenantId', tenantId)
    const response = await apiClient.get<ApiResponse<LabelTemplate>>(
      `${BASE}/resolve?${params.toString()}`,
    )
    return response.data ?? null
  },

  /**
   * The tenant's own template only (no platform fallback). Returns null when
   * the tenant hasn't saved one yet — always 200.
   */
  forTenant: async (
    tenantId?: string | null,
    templateType: string = 'PACKING_SLIP',
  ): Promise<LabelTemplate | null> => {
    const params = new URLSearchParams({ templateType })
    if (tenantId) params.set('tenantId', tenantId)
    const response = await apiClient.get<ApiResponse<LabelTemplate>>(
      `${BASE}/tenant?${params.toString()}`,
    )
    return response.data ?? null
  },

  save: (template: LabelTemplate) =>
    apiClient.post<ApiResponse<LabelTemplate>>(BASE, template),

  remove: (id: number) => apiClient.delete<ApiResponse<void>>(`${BASE}/${id}`),

  /**
   * Fetch the branded packing-slip PDF for one order and return a Blob
   * object URL suitable for an <iframe src> or a new-tab open. The
   * endpoint is JWT-gated and lives on a different origin in dev
   * (:8080 vs Vite's :5173), so a bare href would both 401 and resolve
   * against the wrong origin. Caller MUST revoke the returned URL when
   * done to avoid leaking a Blob into memory for the tab's lifetime.
   */
  fetchPreviewObjectUrl: async (orderNo: number | string): Promise<string> => {
    const token = localStorage.getItem('multiship_token')
    const resp = await fetch(`${BASE_URL}/orders/${orderNo}/packing-slip`, {
      headers: token ? { Authorization: `Bearer ${token}` } : undefined,
    })
    if (!resp.ok) {
      throw new Error(`Packing-slip preview failed (HTTP ${resp.status}).`)
    }
    const blob = await resp.blob()
    return URL.createObjectURL(blob)
  },
}
