import { apiClient } from './apiClient'
import type { ApiResponse } from './orderService'
import type { ImportBatchSummary, ImportBatchDetail } from './orderImportService'

/** Result of a WMS pull — mirrors the backend WmsPullResultDTO. */
export interface WmsPullResult {
  configured: boolean
  fetched: number
  imported: number
  skipped: number
  failed: number
  /** Batch id all orders from this fetch were grouped under (null if none imported). */
  batchId: number | null
  /** Import-history batch id recording this fetch (null if none imported). */
  importBatchId: number | null
  importedOrderNos: number[]
  messages: string[]
}

export const wmsService = {
  /** Is a WMS base URL configured on the backend? */
  status: () => apiClient.get<ApiResponse<{ configured: boolean }>>('/wms/status'),

  /** Pull the WMS's current pending shipments into Multiship as PENDING orders. */
  pull: () => apiClient.post<ApiResponse<WmsPullResult>>('/wms/pull', {}),

  /** List API/WMS fetch batches (one per fetch) for the API section. */
  batches: () => apiClient.get<ApiResponse<ImportBatchSummary[]>>('/wms/batches'),

  /** One API/WMS batch with its shipment rows (to expand a batch card). */
  batch: (id: number) => apiClient.get<ApiResponse<ImportBatchDetail>>(`/wms/batches/${id}`),
}
