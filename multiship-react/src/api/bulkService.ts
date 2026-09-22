import { apiClient } from './apiClient'
import type { ApiResponse } from './orderService'
import type { ImportBatchSummary } from './orderImportService'

/** Which Bulk Mailer list: file imports, WMS/API fetches, or Trash (any source). */
export type BulkView = 'FILE' | 'API' | 'TRASH'

/** The Import history toolbar's filters, sent to the server. */
export interface BulkBatchQuery {
  view: BulkView
  status?: string
  q?: string
  /** yyyy-mm-dd */
  from?: string
  to?: string
  createdBy?: string
  /** HAS · NONE — has a label batch yet. */
  labelBatch?: 'HAS' | 'NONE'
  minSaved?: number
  sort?: 'created' | 'fileName' | 'savedRows' | 'status' | 'labelBatch'
  dir?: 'ASC' | 'DESC'
  /** 0-based */
  page?: number
  size?: number
}

export interface BulkBatchPage {
  content: ImportBatchSummary[]
  totalElements: number
  totalPages: number
  number: number
  size: number
}

/** Counts for the cards and status chips — over the whole view, not the filters. */
export interface BulkSummary {
  total: number
  readyToGenerate: number
  generating: number
  needsFixes: number
  completedThisWeek: number
  statusCounts: Record<string, number>
  creators: string[]
}

/** The query string, leaving blank filters out. */
export const bulkQueryString = (q: BulkBatchQuery) => {
  const params = new URLSearchParams()
  for (const [k, v] of Object.entries(q)) {
    if (v !== undefined && v !== null && String(v).trim() !== '') params.set(k, String(v).trim())
  }
  return params.toString()
}

export const bulkService = {
  /** One page of batches, filtered and sorted by the server. */
  listBatches: (q: BulkBatchQuery) =>
    apiClient.get<ApiResponse<BulkBatchPage>>(`/bulk/batches?${bulkQueryString(q)}`),
  summary: (view: BulkView) =>
    apiClient.get<ApiResponse<BulkSummary>>(`/bulk/summary?view=${view}`),
}
