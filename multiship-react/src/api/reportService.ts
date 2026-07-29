import { apiClient, BASE_URL } from './apiClient'
import type { ApiResponse } from './orderService'

export type Dataset = 'ORDERS' | 'TRACKING' | 'RATE_SHOP' | 'BILLING'
export type Frequency = 'DAILY' | 'WEEKLY' | 'MONTHLY'
export type DeliveryType = 'DASHBOARD' | 'EMAIL' | 'WEBHOOK'

export interface ReportFilters {
  from?: string
  to?: string
  customerNo?: string
  carrier?: string
  service?: string
  status?: string
  originCountry?: string
  destCountry?: string
  minWeightLb?: number
  maxWeightLb?: number
  minDeclaredValue?: number
  maxDeclaredValue?: number
}

export interface ScheduledReport {
  id?: number | null
  tenantId?: string | null
  name: string
  dataset: Dataset
  frequency: Frequency
  filtersJson?: string | null
  deliveryType: DeliveryType
  deliveryEmail?: string | null
  deliveryWebhookUrl?: string | null
  active: boolean
  nextRunAt?: string | null
  lastRunAt?: string | null
  createdAt?: string | null
  updatedAt?: string | null
}

export interface GeneratedReport {
  id: number
  scheduleId: number
  tenantId?: string | null
  dataset: string
  filename: string
  sizeBytes: number
  deliveryStatus: 'PENDING' | 'DELIVERED' | 'FAILED'
  deliveryMessage?: string | null
  generatedAt: string
}

const DATASET_PATH: Record<Dataset, string> = {
  ORDERS: '/reports/orders',
  TRACKING: '/reports/tracking',
  RATE_SHOP: '/reports/rate-shop',
  BILLING: '/reports/billing',
}

/**
 * Trigger a CSV download for the given dataset. apiClient buffers
 * responses, but the browser needs an anchor click on a URL with the
 * right filters + auth — build the URL by hand, attach the Bearer
 * token via `fetch`, and stream via a Blob download.
 */
async function downloadCsv(dataset: Dataset, filters: ReportFilters, filename: string) {
  const params = new URLSearchParams()
  Object.entries(filters).forEach(([k, v]) => {
    if (v !== undefined && v !== null && v !== '') params.set(k, String(v))
  })
  const url = `${BASE_URL}${DATASET_PATH[dataset]}?${params.toString()}`
  const token = localStorage.getItem('multiship_token')
  const resp = await fetch(url, {
    headers: token ? { Authorization: `Bearer ${token}` } : undefined,
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

export const reportService = {
  downloadCsv,

  listSchedules: async (tenantId?: string): Promise<ScheduledReport[]> => {
    const qs = tenantId ? `?tenantId=${encodeURIComponent(tenantId)}` : ''
    const resp = await apiClient.get<ApiResponse<ScheduledReport[]>>(`/scheduled-reports${qs}`)
    return resp.data ?? []
  },

  saveSchedule: (s: ScheduledReport) =>
    apiClient.post<ApiResponse<ScheduledReport>>('/scheduled-reports', s),

  deleteSchedule: (id: number) =>
    apiClient.delete<ApiResponse<void>>(`/scheduled-reports/${id}`),

  runNow: (id: number) =>
    apiClient.post<ApiResponse<number>>(`/scheduled-reports/${id}/run-now`, {}),

  listGenerated: async (tenantId?: string): Promise<GeneratedReport[]> => {
    const qs = tenantId ? `?tenantId=${encodeURIComponent(tenantId)}` : ''
    const resp = await apiClient.get<ApiResponse<GeneratedReport[]>>(`/scheduled-reports/generated${qs}`)
    return resp.data ?? []
  },

  downloadGenerated: async (id: number, filename: string) => {
    const url = `${BASE_URL}/scheduled-reports/generated/${id}/download`
    const token = localStorage.getItem('multiship_token')
    const resp = await fetch(url, {
      headers: token ? { Authorization: `Bearer ${token}` } : undefined,
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
  },
}
