/**
 * Client for the S2 admin CRUD REST at /api/v1/admin/external-systems.
 * Drives the S3 /settings/external-systems FE page.
 *
 * <p>All calls require ADMIN role; the backend rejects non-admin with
 * 403. Secret values are never returned by the server — the FE only
 * knows whether a secret is set (via the `isSet` flag on PUT responses
 * or a per-connection status column derived elsewhere).
 */
import { apiClient } from './apiClient'
import type { ApiResponse } from './orderService'

export interface ConnectorSummary {
  systemType: string
  configType: string
}

export interface ConnectionSummary {
  id: number
  name: string
  systemType: string
  active: boolean
  updatedAt: string | null
  updatedBy: string | null
}

export interface ConnectionDetail {
  id: number
  name: string
  systemType: string
  active: boolean
  configJson: string
  createdAt: string | null
  updatedAt: string | null
  updatedBy: string | null
  // V89 — writeback flags. One boolean per payload field. Same flag
  // gates both label-generate push and label-void clear.
  writebackTracking: boolean
  writebackShipDate: boolean
  writebackStatus: boolean
  writebackCarrier: boolean
  writebackService: boolean
  writebackFreight: boolean
}

export interface ConnectionUpsertRequest {
  name?: string
  systemType?: string
  active?: boolean
  configJson?: string
  // V89 — all six sent together on save; omit to leave a flag alone.
  writebackTracking?: boolean
  writebackShipDate?: boolean
  writebackStatus?: boolean
  writebackCarrier?: boolean
  writebackService?: boolean
  writebackFreight?: boolean
}

/** Health / test-connection response payload shape. */
export interface HealthSnapshot {
  connectionName: string
  systemType: string
  status: 'UP' | 'DOWN' | 'UNKNOWN'
  message: string | null
  checkedAt: string | null
  details: Record<string, unknown>
}

/** Optional profile + clientCode for POST /test-connection. */
export interface TestConnectionRequest {
  loginProfile?: string
  clientCode?: string
}

/** Row shape for GET /{id}/client-overrides (no password ever). */
export interface ClientLoginOverrideRow {
  clientCode: string
  username: string
  updatedAt: string | null
  updatedBy: string | null
}

const BASE = '/admin/external-systems'

export const externalSystemsService = {
  listConnectors: () =>
    apiClient
      .get<ApiResponse<ConnectorSummary[]>>(`${BASE}/connectors`)
      .then((res) => res.data ?? []),

  list: () =>
    apiClient
      .get<ApiResponse<ConnectionSummary[]>>(BASE)
      .then((res) => res.data ?? []),

  get: (id: number) =>
    apiClient
      .get<ApiResponse<ConnectionDetail>>(`${BASE}/${id}`)
      .then((res) => res.data as ConnectionDetail),

  create: (req: ConnectionUpsertRequest) =>
    apiClient
      .post<ApiResponse<ConnectionDetail>>(BASE, req)
      .then((res) => res.data as ConnectionDetail),

  update: (id: number, req: ConnectionUpsertRequest) =>
    apiClient
      .put<ApiResponse<ConnectionDetail>>(`${BASE}/${id}`, req)
      .then((res) => res.data as ConnectionDetail),

  delete: (id: number) =>
    apiClient.delete<ApiResponse<void>>(`${BASE}/${id}`),

  /** Passing null / empty plaintext deletes the secret row. */
  putSecret: (id: number, key: string, plaintext: string | null) =>
    apiClient
      .put<ApiResponse<{ connectionId: number; secretKey: string; isSet: boolean }>>(
        `${BASE}/${id}/secrets/${encodeURIComponent(key)}`,
        { plaintext },
      )
      .then((res) => res.data as { connectionId: number; secretKey: string; isSet: boolean }),

  listClientOverrides: (id: number) =>
    apiClient
      .get<ApiResponse<ClientLoginOverrideRow[]>>(`${BASE}/${id}/client-overrides`)
      .then((res) => res.data ?? []),

  putClientOverride: (id: number, clientCode: string, username: string, password: string) =>
    apiClient
      .put<ApiResponse<{ isSet: boolean }>>(
        `${BASE}/${id}/client-overrides/${encodeURIComponent(clientCode)}`,
        { username, password },
      )
      .then((res) => res.data),

  deleteClientOverride: (id: number, clientCode: string) =>
    apiClient.delete<ApiResponse<void>>(
      `${BASE}/${id}/client-overrides/${encodeURIComponent(clientCode)}`,
    ),

  // PR #750 follow-up — tenant → writeback-connection routing. Empty
  // list on GET means no clients currently route their writeback here.
  listRoutedTenants: (id: number) =>
    apiClient
      .get<ApiResponse<string[]>>(`${BASE}/${id}/tenant-routings`)
      .then((res) => res.data ?? []),

  addRoutedTenant: (id: number, tenantCode: string) =>
    apiClient
      .put<ApiResponse<{ isSet: boolean }>>(
        `${BASE}/${id}/tenant-routings/${encodeURIComponent(tenantCode)}`,
        {},
      )
      .then((res) => res.data),

  removeRoutedTenant: (id: number, tenantCode: string) =>
    apiClient.delete<ApiResponse<void>>(
      `${BASE}/${id}/tenant-routings/${encodeURIComponent(tenantCode)}`,
    ),

  health: (id: number) =>
    apiClient
      .get<ApiResponse<HealthSnapshot>>(`${BASE}/${id}/health`)
      .then((res) => res.data as HealthSnapshot),

  testConnection: (id: number, req: TestConnectionRequest) =>
    apiClient
      .post<ApiResponse<Record<string, unknown>>>(`${BASE}/${id}/test-connection`, req)
      .then((res) => res),
}
