import { apiClient } from './apiClient'
import type { ApiResponse } from './orderService'

/** One entry in the curated HS code directory. */
export interface HsCodeEntry {
  code: string
  description: string
  category: string | null
}

export const hsCodeService = {
  /** Search the curated directory — code prefix OR description substring. Capped at 25. */
  search: async (q: string): Promise<HsCodeEntry[]> => {
    if (!q || q.trim().length < 2) return []
    const response = await apiClient.get<ApiResponse<HsCodeEntry[]>>(
      `/customs/hs-codes/search?q=${encodeURIComponent(q.trim())}`,
    )
    return Array.isArray(response.data) ? response.data : []
  },

  /** Exact lookup — dot / space / hyphen tolerant. Returns null when not in the curated set. */
  byCode: async (code: string): Promise<HsCodeEntry | null> => {
    try {
      const response = await apiClient.get<ApiResponse<HsCodeEntry>>(
        `/customs/hs-codes/${encodeURIComponent(code)}`,
      )
      return response.data ?? null
    } catch {
      // 404 for unknown code — surface as null, not a thrown error.
      return null
    }
  },
}
