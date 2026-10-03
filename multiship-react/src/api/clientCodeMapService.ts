import { apiClient } from './apiClient'
import type { ApiResponse } from './orderService'

/** Which alias table a row lives in. Sent as-is by the frontend, translated
 *  to URL-slug (dash-case) at the API boundary. */
export type CodeMapKind = 'SHIPVIA' | 'SERVICE' | 'DEST_COUNTRY' | 'PACKAGE'

export interface ClientCodeMap {
  id: number
  kind: CodeMapKind
  clientCode: string
  erpCode: string
  /** Populated for SHIPVIA / SERVICE / PACKAGE. */
  targetId: number | null
  /** Populated for DEST_COUNTRY. */
  iso2: string | null
  /** Human-readable target summary for list rendering. */
  targetLabel: string | null
  /**
   * Destination scope for SHIPVIA / SERVICE / PACKAGE aliases. null in
   * both = "any destination" (fallback). Country wins over region when
   * both are set.
   */
  destCountry: string | null
  destRegion: string | null
  /** V126 — SHIPVIA only. null = any origin warehouse. */
  warehouseId: number | null
  /** Display label for the SHIPVIA warehouse pick, e.g. "WH1 — Chicago". */
  warehouseLabel: string | null
  /** V127 — SHIPVIA only. Empty = unrestricted; non-empty = allowlist. */
  allowedPresetIds: number[] | null
  createdAt: string | null
  updatedAt: string | null
}

export interface UpsertCodeMapPayload {
  erpCode: string
  targetId?: number | null
  iso2?: string | null
  destCountry?: string | null
  destRegion?: string | null
  /** V126 — SHIPVIA only; omit on other kinds. */
  warehouseId?: number | null
  /** V127 — SHIPVIA only; omit on other kinds. Null = don't touch sidecar,
   *  [] = clear it, non-empty = replace. */
  allowedPresetIds?: number[] | null
}

/** enum → URL slug (SHIPVIA → shipvia, DEST_COUNTRY → dest-country). */
const kindToSlug = (kind: CodeMapKind): string =>
  kind.toLowerCase().replace(/_/g, '-')

export const clientCodeMapService = {
  list: (clientCode: string, kind: CodeMapKind) =>
    apiClient.get<ApiResponse<ClientCodeMap[]>>(
      `/clients/${encodeURIComponent(clientCode)}/code-maps/${kindToSlug(kind)}`,
    ),

  upsert: (clientCode: string, kind: CodeMapKind, payload: UpsertCodeMapPayload) =>
    apiClient.post<ApiResponse<ClientCodeMap>>(
      `/clients/${encodeURIComponent(clientCode)}/code-maps/${kindToSlug(kind)}`,
      payload,
    ),

  remove: (clientCode: string, kind: CodeMapKind, id: number) =>
    apiClient.delete<ApiResponse<void>>(
      `/clients/${encodeURIComponent(clientCode)}/code-maps/${kindToSlug(kind)}/${id}`,
    ),
}
