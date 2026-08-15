import { apiClient } from './apiClient'
import type { ApiResponse } from './orderService'

/**
 * Sprint 52 — output-destination admin bindings. Backs
 * /settings/output-destinations. ADMIN-only (the routes hide the entry
 * for non-admins; the backend @PreAuthorize enforces).
 *
 * Destination types + doc types match the backend enums exactly.
 * Config is stored as a JSON string on the wire — the UI parses it
 * per-destination-type for the edit form and re-serialises on save.
 */

export type DocType = 'LABEL' | 'COMMERCIAL_INVOICE'
export type DestinationType = 'LOCAL_FS' | 'SFTP' | 'PRINTER'

export interface OutputDestination {
  id: number
  clientCode: string
  docType: DocType
  destinationType: DestinationType
  /**
   * Sanitised config JSON — the server replaces SFTP secret pointers
   * with "***set***" markers so the UI can render "auth: KEY (set)"
   * without ever seeing the underlying pointer id.
   */
  configSafe: string
  active: boolean
  notes: string | null
  createdAt: string | null
  updatedAt: string | null
}

export interface OutputDestinationUpsertPayload {
  clientCode: string
  docType: DocType
  destinationType: DestinationType
  /** Raw JSON config string — shape depends on destinationType. */
  config: string
  active: boolean
  notes?: string | null
  /** SFTP + PASSWORD auth only. Write-only; never echoed on GET. */
  sftpPasswordPlain?: string | null
  /** SFTP + KEY auth only. Write-only. */
  sftpPrivateKeyPlain?: string | null
}

export interface DispatchResultItem {
  destinationId: number
  destinationType: DestinationType
  success: boolean
  failureMessage: string | null
}

export interface DispatchResult {
  shipmentDocumentId: number | null
  totalDestinations: number
  successCount: number
  failureCount: number
  items: DispatchResultItem[]
}

export const outputDestinationService = {
  list: (clientCode?: string) =>
    apiClient.get<ApiResponse<OutputDestination[]>>(
      `/admin/output-destinations${
        clientCode ? `?clientCode=${encodeURIComponent(clientCode)}` : ''
      }`,
    ),

  get: (id: number) =>
    apiClient.get<ApiResponse<OutputDestination>>(`/admin/output-destinations/${id}`),

  create: (payload: OutputDestinationUpsertPayload) =>
    apiClient.post<ApiResponse<OutputDestination>>(`/admin/output-destinations`, payload),

  update: (id: number, payload: OutputDestinationUpsertPayload) =>
    apiClient.put<ApiResponse<OutputDestination>>(`/admin/output-destinations/${id}`, payload),

  delete: (id: number) =>
    apiClient.delete<ApiResponse<void>>(`/admin/output-destinations/${id}`),

  test: (id: number) =>
    apiClient.post<ApiResponse<DispatchResult>>(`/admin/output-destinations/${id}/test`, {}),
}
