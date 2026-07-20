import { apiClient } from './apiClient'
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

export const customsProfileService = {
  /** Every profile across all clients (for the management page). */
  listAll: async (): Promise<CustomsProfile[]> => {
    const r = await apiClient.get<ApiResponse<CustomsProfile[]>>('/customs-profiles')
    return Array.isArray(r.data) ? r.data : []
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
