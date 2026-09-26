import { apiClient } from './apiClient'
import type { ApiResponse } from './orderService'
import type { ImportBatchSummary, ImportBatchDetail } from './orderImportService'
import type { WmsPullResult } from './wmsService'

/**
 * D2C History — "Fetch from NDS" pulls the Oracle NDS view
 * (TB_SHIPX_DTC_UVW) into an ImportBatch with source=DTC.
 * Wire shape mirrors wmsService so the copied DataHistoryPage works
 * with a service swap.
 */
export const dtcService = {
  /** Is the D2C (Oracle NDS) integration configured on the backend? */
  status: () => apiClient.get<ApiResponse<{ configured: boolean }>>('/d2c/status'),

  /** Pull the Oracle NDS view's current pending shipments into Multiship. */
  pull: () => apiClient.post<ApiResponse<WmsPullResult>>('/d2c/pull', {}),

  /** List DTC fetch batches (one per fetch). */
  batches: () => apiClient.get<ApiResponse<ImportBatchSummary[]>>('/d2c/batches'),

  /** One DTC batch with its shipment rows. */
  batch: (slug: string) => apiClient.get<ApiResponse<ImportBatchDetail>>(`/d2c/batches/${slug}`),
}
