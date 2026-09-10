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
  // CANCELLED was added in the bulk-labels cancel-endpoint commit — a job
  // finishes in CANCELLED when an operator called DELETE /bulk-labels/{id}
  // during the run. Labels that finished BEFORE the cancel toggle are still
  // in the ZIP (downloadable=true), so the operator can print/discard them.
  status: 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'CANCELLED'
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
   * Request cooperative cancellation of a running bulk-label job.
   * Backend flips a flag; workers pick it up between per-order carrier
   * calls. Already-in-flight carrier calls run to completion because we
   * can't interrupt a paid label mid-request without leaking it.
   * 404 if the jobId is unknown, 409 BULK_JOB_ALREADY_TERMINAL if the
   * job is already COMPLETED / FAILED / CANCELLED.
   */
  cancel: (jobId: number) =>
    apiClient.delete<ApiResponse<BulkLabelJob>>(`/bulk-labels/${jobId}`),

  /**
   * Download the ZIP for a completed job. Endpoint is JWT-gated so a
   * plain <a href> would 401 — we fetch with Bearer and blob-download.
   */
  download: downloadZip,
}
