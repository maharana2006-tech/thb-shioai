import { apiClient, authFetch } from './apiClient'
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
  /** Populated on every DTO by the backend — true when logoBase64 is
   *  non-blank. Set even on list summaries (where logoBase64 itself is
   *  stripped for payload size). */
  hasLogo?: boolean | null
  /** Phase 1 drag-drop editor payload. Serialized {@link TemplateLayout}. */
  layoutJson?: string | null
  createdAt?: string | null
  updatedAt?: string | null
}

export interface LabelTemplateListParams {
  search?: string
  templateType?: string
  /** 'Y' | 'N' — passed through as boolean to the backend. */
  hasLogo?: 'Y' | 'N' | ''
  sortBy?: string
  sortDirection?: 'ASC' | 'DESC'
  page?: number
  size?: number
}

export interface LabelTemplatePage {
  content: LabelTemplate[]
  pageNumber: number
  pageSize: number
  totalElements: number
  totalPages: number
  first: boolean
  last: boolean
  empty: boolean
  sortBy: string | null
  sortDirection: string | null
}

const BASE = '/label-templates'

/**
 * Phase 2a preview call — POST the current layoutJson to /label-templates/preview
 * and return the rendered HTML for display in an iframe. Uses the built-in
 * sample shipment context on the backend (no shipment id needed yet).
 *
 * Not routed through apiClient because the response is text/html rather than
 * JSON; the auth header is still attached by hand so the endpoint's
 * @PreAuthorize passes.
 */
export async function previewTemplateHtml(layoutJson: string | null): Promise<string> {
  // Sprint 50 PR Q3 — authFetch handles credentials:'include' + CSRF echo.
  // authFetch throws on non-2xx, so no separate response.ok check.
  const response = await authFetch(`${BASE}/preview`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ layoutJson }),
  })
  return response.text()
}

/**
 * Phase 2b PDF preview — POST the current layoutJson and get back a Blob
 * object URL suitable for a new-tab open or an <a download> click. Caller
 * MUST revoke the returned URL when done so the Blob doesn't leak for the
 * tab's lifetime.
 */
export async function previewTemplatePdfObjectUrl(layoutJson: string | null): Promise<string> {
  const response = await authFetch(`${BASE}/preview.pdf`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ layoutJson }),
  })
  const blob = await response.blob()
  return URL.createObjectURL(blob)
}

/**
 * Phase 2c ZPL preview — POST the current layoutJson and get back the raw
 * ZPL string. Caller can dump it to a Blob for download, or eventually
 * pipe it to a WebUSB/print bridge. dpi selects 203 (default 4×6"
 * shipping printer) or 300 (high-res thermal).
 */
export async function previewTemplateZpl(
  layoutJson: string | null,
  dpi: 203 | 300 = 203,
): Promise<string> {
  const response = await authFetch(`${BASE}/preview.zpl?dpi=${dpi}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ layoutJson }),
  })
  return response.text()
}

export const labelTemplateService = {
  /**
   * Cross-tenant list for the operator settings page. Filters skip
   * on null/blank; sort defaults to updatedAt DESC on the server.
   * Response strips {@code logoBase64} to keep the page bounded
   * (logo lives on the detail response the editor loads).
   */
  listTemplates: (params: LabelTemplateListParams = {}) => {
    const query = new URLSearchParams()
    if (params.search?.trim()) query.set('search', params.search.trim())
    if (params.templateType?.trim()) query.set('templateType', params.templateType.trim())
    if (params.hasLogo === 'Y' || params.hasLogo === 'N') query.set('hasLogo', params.hasLogo)
    if (params.sortBy) query.set('sortBy', params.sortBy)
    if (params.sortDirection) query.set('sortDirection', params.sortDirection)
    query.set('page', String(params.page ?? 0))
    query.set('size', String(params.size ?? 25))
    return apiClient.get<ApiResponse<LabelTemplatePage>>(`${BASE}?${query.toString()}`)
  },

  /**
   * Fetch a specific template by id. Used by the editor page when
   * navigating to /settings/label-templates/:id. Wraps a repository
   * lookup — the endpoint returns 404 when the id doesn't exist.
   */
  getById: (id: number) => apiClient.get<ApiResponse<LabelTemplate>>(`${BASE}/${id}`),

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
    // Sprint 50 PR Q3 — cookie-mode auth via authFetch (credentials:'include').
    const resp = await authFetch(`/orders/${orderNo}/packing-slip`)
    const blob = await resp.blob()
    return URL.createObjectURL(blob)
  },
}
