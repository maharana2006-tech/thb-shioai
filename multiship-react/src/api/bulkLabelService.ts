import { apiClient, BASE_URL } from './apiClient'
import type { ApiResponse } from './orderService'

/**
 * Fetch the ZIP with the auth cookie and trigger a save via a synthetic
 * anchor click. Necessary because the endpoint is JWT-gated — a plain
 * <a href> download would 401 without the same-origin cookie flow, and
 * we need control of the response headers for the filename. Mirrors the
 * pattern in reportService.
 */
async function downloadZip(jobId: number, filename: string): Promise<void> {
  // Sprint 50 PR Q3 — cookie-mode auth. credentials:'include' sends the
  // httpOnly JWT cookie; no manual Authorization header needed.
  const resp = await fetch(`${BASE_URL}/bulk-labels/${jobId}/download`, {
    credentials: 'include',
  })
  if (!resp.ok) throw new Error(`Download failed: HTTP ${resp.status}`)
  const blob = await resp.blob()
  const objectUrl = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = objectUrl
  a.download = filename
  document.body.appendChild(a)
  a.click()
  a.remove()
  URL.revokeObjectURL(objectUrl)
}

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

  /**
   * Download the ZIP for a completed job. Endpoint is JWT-gated so a
   * plain <a href> would 401 — we fetch with Bearer and blob-download.
   */
  download: downloadZip,
}
