import { apiClient } from './apiClient'
import type { ApiResponse } from './orderService'

/** USPS postage on Stamps.com (ADMIN). Stamps.com charges the payment method saved on its own account. */
export interface PostageBalance {
  available: number | null
  maxBalance: number | null
  currency: string | null
}

export interface PostageFunding {
  id: number
  amount: number
  currency: string
  status: 'ADDED' | 'FAILED' | 'PENDING'
  balanceAfter: number | null
  message: string | null
  createdBy: string | null
  createdAt: string
}

/** Stamps.com pages the screen links to; each link works once, so it's fetched on click. */
export type StampsPage = 'store_default_payment_methods' | 'reports_balances'

const base = '/carrier-accounts/stamps-sera'

export const stampsPostageService = {
  balance: async (accountId: number): Promise<PostageBalance> =>
    (await apiClient.get<ApiResponse<PostageBalance>>(`${base}/balance/${accountId}`)).data,

  /** requestKey: one v4 UUID per confirmed purchase, reused on retry, so it's charged once. */
  addFunds: async (accountId: number, amount: number, requestKey: string) =>
    (await apiClient.post<ApiResponse<{ funding: PostageFunding; balance: PostageBalance | null }>>(
      `${base}/add-funds/${accountId}`, { amount, requestKey })).data,

  history: async (accountId: number): Promise<PostageFunding[]> =>
    (await apiClient.get<ApiResponse<PostageFunding[]>>(`${base}/funding/${accountId}`)).data ?? [],

  pageUrl: async (accountId: number, page: StampsPage): Promise<string> =>
    (await apiClient.get<ApiResponse<{ url: string }>>(`${base}/page-url/${accountId}?page=${page}`)).data.url,
}
