import { apiClient, BASE_URL } from './apiClient'
import type { ApiResponse } from './orderService'

export interface BulkLabelJob {
  id: number
  status: 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED'
  totalCount: number
  successfulCount: number
  failedCount: number
  failureMessage: string | null
  createdAt: string | null
  startedAt: string | null
  completedAt: string | null
  downloadable: boolean
}

export const bulkLabelService = {
  submit: (orderNumbers: number[]) =>
    apiClient.post<ApiResponse<BulkLabelJob>>('/bulk-labels', { orderNumbers }),

  status: (jobId: number) =>
    apiClient.get<ApiResponse<BulkLabelJob>>(`/bulk-labels/${jobId}`),

  /** Direct download URL — the endpoint streams the ZIP as an
   *  octet-stream, so the browser handles it natively via <a href>. */
  downloadUrl: (jobId: number) => `${BASE_URL}/bulk-labels/${jobId}/download`,
}
