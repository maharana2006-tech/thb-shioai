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

  /** Paged list of synced orders in the dtc_orders table. */
  orders: (p: DtcOrderQuery) => {
    const qs = new URLSearchParams({
      page: String(p.page), size: String(p.size),
      sort: p.sort ?? 'createdAt', dir: p.dir ?? 'DESC',
      tenantId: p.tenantId ?? '', q: p.q ?? '',
    })
    return apiClient.get<ApiResponse<DtcOrderPage>>(`/dtc/orders?${qs}`)
  },

  /** Tenants present in dtc_orders (tenant filter options). */
  orderTenants: () => apiClient.get<ApiResponse<string[]>>('/dtc/orders/tenants'),

  /** Copy pending orders from the Oracle view into dtc_orders. Empty tenant = all. */
  syncOracle: (tenantId = '') =>
    apiClient.post<ApiResponse<DtcSyncResult>>(`/dtc/sync/oracle?tenantId=${encodeURIComponent(tenantId)}`, {}),
}

/** One row of dtc_orders (DtcOrder entity). */
export interface DtcOrder {
  id: number
  batchId: number
  orderNo: number | null
  orderSuffix: number | null
  orderStatus: string | null
  custNo: string | null
  custPo: string | null
  tenantId: string
  shipViaCode: string | null
  shipVia: string | null
  termsCode: string | null
  shipName: string | null
  shipAttn: string | null
  shipAddr1: string | null
  shipAddr2: string | null
  shipAddr3: string | null
  shipToCity: string | null
  shipToState: string | null
  shipToZip: string | null
  shipToCountryCode: string | null
  countryName: string | null
  phone: string | null
  email: string | null
  weight: number | null
  unitValue: number | null
  price: string | null
  freightCost: number | null
  goodsDesc: string | null
  intlYn: string | null
  toteNumber: string
  location: string | null
  track: string | null
  thirdPartyAccount: string | null
  shipDate: string | null
  ffSchemaSubstr: string | null
  createdAt: string | null
}

export interface DtcOrderQuery {
  page: number
  size: number
  sort?: string
  dir?: 'ASC' | 'DESC'
  tenantId?: string
  q?: string
}

export interface DtcOrderPage {
  content: DtcOrder[]
  pageNumber: number
  pageSize: number
  totalElements: number
  totalPages: number
}

export interface DtcSyncResult {
  fetched: number
  imported: number
  skipped: number
  message: string
}
