import { apiClient } from './apiClient'
import type { ApiResponse } from './orderService'

/**
 * Sprint 50 Tier 0.5 PR E — /settings/users page bindings. ADMIN-only.
 */

/** Build a `?k=v&…` suffix from a params object, skipping null/undefined/empty. */
function toQueryString(params: object): string {
  const qs = new URLSearchParams()
  for (const [k, v] of Object.entries(params)) {
    if (v === null || v === undefined || v === '') continue
    qs.set(k, String(v))
  }
  const s = qs.toString()
  return s ? `?${s}` : ''
}

export interface AdminUser {
  id: number
  username: string
  email: string
  fullName: string | null
  role: string
  clientCode: string | null
  emailVerified: boolean
  deactivatedAt: string | null
  deactivatedBy: string | null
  createdAt: string | null
}

export interface AdminUserAudit {
  id: number
  subjectUserId: number
  subjectUsername: string
  action: string
  oldClientCode: string | null
  newClientCode: string | null
  actorUsername: string
  reason: string | null
  createdAt: string
}

export interface AdminUserAssignClientPayload {
  clientCode: string | null
  reason?: string | null
}

export interface AdminUserListParams {
  search?: string
  role?: string
  clientCode?: string
  activeOnly?: boolean
  /** 0-based page index. Backend default: 0. */
  page?: number
  /** Rows per page. Backend default: 50. FE now exposes this so a 50+-user
   *  org can page through instead of silently truncating (audit #295). */
  size?: number
}

export const adminUserService = {
  list: (params: AdminUserListParams = {}) =>
    apiClient.get<ApiResponse<AdminUser[]>>(`/admin/users${toQueryString(params)}`),

  assignClient: (id: number, payload: AdminUserAssignClientPayload) =>
    apiClient.patch<ApiResponse<AdminUser>>(`/admin/users/${id}/client`, payload),

  deactivate: (id: number, reason?: string) =>
    apiClient.post<ApiResponse<AdminUser>>(`/admin/users/${id}/deactivate`, { reason: reason ?? null }),

  reactivate: (id: number, reason?: string) =>
    apiClient.post<ApiResponse<AdminUser>>(`/admin/users/${id}/reactivate`, { reason: reason ?? null }),

  recentAudit: (limit = 50) =>
    apiClient.get<ApiResponse<AdminUserAudit[]>>(`/admin/users/audit${toQueryString({ limit })}`),

  userAudit: (id: number) =>
    apiClient.get<ApiResponse<AdminUserAudit[]>>(`/admin/users/${id}/audit`),
}
