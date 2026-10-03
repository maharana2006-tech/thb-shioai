/**
 * V115 — read-only client for /api/v1/admin/roles. ADMIN-only.
 */
import { apiClient } from './apiClient'
import type { ApiResponse } from './orderService'

export interface RoleRow {
  code: string
  name: string | null
  isInvitable: boolean
  updatedAt: string | null
}

export const rolesService = {
  list: () =>
    apiClient
      .get<ApiResponse<RoleRow[]>>('/admin/roles')
      .then((res) => res.data ?? []),
}
