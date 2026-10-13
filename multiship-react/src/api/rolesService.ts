/**
 * Admin role registry. Built-in roles are read-only; custom roles have a base
 * role (their backend security floor) and a set of module keys they may see.
 */
import { apiClient } from './apiClient'

export interface RoleRow {
  code: string
  name: string | null
  isInvitable: boolean
  baseRole: string | null
  builtin: boolean
  modules: string[]
  updatedAt: string | null
}

export interface RoleUpsert {
  code?: string
  name: string
  baseRole: string
  invitable: boolean
  modules: string[]
}

export const rolesService = {
  list: (): Promise<RoleRow[]> => apiClient.get<RoleRow[]>('/admin/roles'),

  create: (role: RoleUpsert): Promise<RoleRow> => apiClient.post<RoleRow>('/admin/roles', role),

  update: (code: string, role: Omit<RoleUpsert, 'code'>): Promise<RoleRow> =>
    apiClient.put<RoleRow>(`/admin/roles/${encodeURIComponent(code)}`, role),

  remove: (code: string): Promise<unknown> =>
    apiClient.delete(`/admin/roles/${encodeURIComponent(code)}`),
}
