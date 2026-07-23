import { apiClient, BASE_URL } from './apiClient'
import type { ApiResponse } from './orderService'

/** A client's importer + broker profile, applied to a set of destination countries. */
export interface CustomsProfile {
  id?: number
  clientCode?: string
  clientName?: string | null
  /** Destination countries this profile covers (ISO alpha-2). */
  countries: string[]
  // importer
  /** RECEIVER (DAP — the order's consignee is the importer) | BUSINESS (DDP — fixed entity). */
  importerType?: 'RECEIVER' | 'BUSINESS' | null
  importerName?: string | null
  importerContact?: string | null
  importerCountry?: string | null
  importerAddress1?: string | null
  importerAddress2?: string | null
  importerPhone?: string | null
  importerCity?: string | null
  importerState?: string | null
  importerPostcode?: string | null
  importerTaxId?: string | null
  importerTaxIdType?: string | null
  importerEori?: string | null
  importerIoss?: string | null
  importerCompanyReg?: string | null
  importerIec?: string | null
  importerGstin?: string | null
  // broker
  brokerName?: string | null
  brokerCompany?: string | null
  brokerCountry?: string | null
  brokerAddress1?: string | null
  brokerAddress2?: string | null
  brokerPhone?: string | null
  brokerCity?: string | null
  brokerState?: string | null
  brokerPostcode?: string | null
  brokerId?: string | null
  brokerLicense?: string | null
  // shipment defaults
  incoterms?: string | null
  dutiesBillTo?: string | null
  dutiesAccount?: string | null
  reasonForExport?: string | null
  currency?: string | null
  // account
  accountCarrier?: string | null
  accountNo?: string | null
  createdAt?: string | null
  updatedAt?: string | null
}

/** Server params for the paginated list. Region filtering is client-side:
 *  callers expand a chosen region into its ISO-2 country codes here. */
export interface CustomsProfileListParams {
  search?: string
  clientCode?: string
  carrier?: string
  /** YES | NO — has own broker (YES) or carrier default (NO). */
  broker?: string
  /** Restrict to profiles that cover any of these ISO-2 country codes. */
  countries?: string[]
  /** client | destinations | importer | broker */
  sortBy?: string
  sortDirection?: 'ASC' | 'DESC'
  page?: number
  size?: number
}

export interface CustomsProfilePage {
  content: CustomsProfile[]
  pageNumber: number
  pageSize: number
  totalElements: number
  totalPages: number
}

/** Aggregate counts for the Importer/Broker page's health strip. */
export interface CustomsProfileStats {
  profiles: number
  destinationsCovered: number
  clientsConfigured: number
}

const appendListParams = (query: URLSearchParams, params: CustomsProfileListParams): void => {
  if (params.search?.trim()) query.set('search', params.search.trim())
  if (params.clientCode?.trim()) query.set('clientCode', params.clientCode.trim())
  if (params.carrier?.trim()) query.set('carrier', params.carrier.trim())
  if (params.broker?.trim()) query.set('broker', params.broker.trim())
  if (params.countries?.length) {
    for (const c of params.countries) if (c?.trim()) query.append('countries', c.trim())
  }
  query.set('sortBy', params.sortBy ?? 'client')
  query.set('sortDirection', params.sortDirection ?? 'ASC')
}

export const customsProfileService = {
  /** Paginated master list for the settings/importer-broker page. */
  listProfiles: async (params: CustomsProfileListParams = {}) => {
    const query = new URLSearchParams()
    query.set('page', String(params.page ?? 0))
    query.set('size', String(params.size ?? 25))
    appendListParams(query, params)
    return apiClient.get<ApiResponse<CustomsProfilePage>>(`/customs-profiles?${query.toString()}`)
  },

  /** Aggregate totals for the health strip on the Importer/Broker page. */
  stats: async (): Promise<CustomsProfileStats> => {
    const r = await apiClient.get<ApiResponse<CustomsProfileStats>>('/customs-profiles/stats')
    return r.data ?? { profiles: 0, destinationsCovered: 0, clientsConfigured: 0 }
  },

  /**
   * Download every filtered row as CSV. Bypasses the JSON apiClient because the
   * backend returns text/csv; builds the auth-bearing request by hand and
   * triggers a synthetic anchor click to save the file.
   */
  exportProfilesCsv: async (params: CustomsProfileListParams = {}): Promise<void> => {
    const query = new URLSearchParams()
    appendListParams(query, params)
    const token = localStorage.getItem('multiship_token')
    const response = await fetch(`${BASE_URL}/customs-profiles/export.csv?${query.toString()}`, {
      method: 'GET',
      headers: token ? { Authorization: `Bearer ${token}` } : undefined,
    })
    if (!response.ok) throw new Error(`Export failed (HTTP ${response.status}).`)
    const blob = await response.blob()
    const stamp = new Date().toISOString().replace(/[:.]/g, '-')
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = `customs-profiles-${stamp}.csv`
    link.click()
    URL.revokeObjectURL(url)
  },

  list: async (clientCode: string): Promise<CustomsProfile[]> => {
    const r = await apiClient.get<ApiResponse<CustomsProfile[]>>(
      `/clients/${encodeURIComponent(clientCode)}/customs-profiles`
    )
    return Array.isArray(r.data) ? r.data : []
  },

  /** Create (no id) or update (id set) a client's profile. */
  save: (clientCode: string, payload: CustomsProfile) => {
    const base = `/clients/${encodeURIComponent(clientCode)}/customs-profiles`
    return payload.id
      ? apiClient.put<ApiResponse<CustomsProfile>>(`${base}/${payload.id}`, payload)
      : apiClient.post<ApiResponse<CustomsProfile>>(base, payload)
  },

  remove: (clientCode: string, id: number) =>
    apiClient.delete<ApiResponse<void>>(
      `/clients/${encodeURIComponent(clientCode)}/customs-profiles/${id}`
    ),
}
