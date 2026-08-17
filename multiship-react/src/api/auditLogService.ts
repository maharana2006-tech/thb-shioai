import { apiClient } from './apiClient'
import type { ApiResponse } from './orderService'

/**
 * Read-only feed of every write recorded via the backend AuditService.
 * Rows are append-only — deletes never come through this API.
 */
export interface AuditLogEntry {
  id: number
  actor: string | null
  action: string
  entityType: string
  entityId: string | null
  entityKey: string | null
  changes: string | null
  notes: string | null
  createdAt: string
}

export interface AuditLogListParams {
  actor?: string
  entityType?: string
  action?: string
  entityKey?: string
  since?: string
  until?: string
  /** Audit A3 — "property,direction" (e.g. "actor,ASC"). Backend
   *  whitelists properties createdAt / actor / action / entityType /
   *  entityKey; anything else falls back to createdAt. Direction is
   *  ASC or DESC; missing → DESC. */
  sort?: string
  page?: number
  size?: number
}

export interface AuditLogPage {
  content: AuditLogEntry[]
  pageNumber: number
  pageSize: number
  totalElements: number
  totalPages: number
  first: boolean
  last: boolean
  empty: boolean
}

export const auditLogService = {
  list: (params: AuditLogListParams = {}) => {
    const query = new URLSearchParams()
    if (params.actor?.trim()) query.set('actor', params.actor.trim())
    if (params.entityType?.trim()) query.set('entityType', params.entityType.trim())
    if (params.action?.trim()) query.set('action', params.action.trim())
    if (params.entityKey?.trim()) query.set('entityKey', params.entityKey.trim())
    if (params.since?.trim()) query.set('since', params.since.trim())
    if (params.until?.trim()) query.set('until', params.until.trim())
    if (params.sort?.trim()) query.set('sort', params.sort.trim())
    query.set('page', String(params.page ?? 0))
    query.set('size', String(params.size ?? 25))
    return apiClient.get<ApiResponse<AuditLogPage>>(`/audit-log?${query.toString()}`)
  },
}
