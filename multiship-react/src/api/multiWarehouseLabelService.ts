import { apiClient } from './apiClient'
import type { ApiResponse, ManualShipmentAddress } from './orderService'

/**
 * Sprint 47 — split a single shipment across multiple warehouses.
 * Backend groups the request's `lines` by `warehouseCode` and buys one
 * label per group. Any child failure aborts the whole batch (fail-all
 * rollback) — the operator never sees a half-split state.
 */
export interface MultiWarehouseLineItem {
  /** Ship-from warehouse for this line. Optional in the PREVIEW call —
   *  the backend runs the selector to fill it in. Required for the write
   *  endpoint (otherwise it 400s). */
  warehouseCode?: string | null
  itemNo?: string
  description?: string
  quantity?: number | null
  unitValue?: number | null
  weight?: number | null
  weightUnit?: string
  hsCode?: string
  countryOfOrigin?: string
  packagePresetId?: number | null
}

export interface MultiWarehouseLabelPayload {
  clientCode: string
  /** Parent order # (optional — ad-hoc splits have no parent). */
  orderNo?: number | null

  recipient?: ManualShipmentAddress | null

  /** Carrier + service hints (optional; routing rules may override). */
  carrierCode?: string
  serviceId?: number | null
  accountId?: number | null
  accountNumber?: string

  packagePresetId?: number | null
  length?: number | null
  width?: number | null
  height?: number | null
  dimUnit?: string
  /** Falls back per-child from sum(line.weight * qty) when set, else this. */
  weight?: number | null
  weightUnit?: string

  declaredValue?: number | null
  currency?: string

  goodsDescription?: string
  reference?: string
  source?: string

  incoterms?: string
  reasonForExport?: string
  importer?: Record<string, unknown>
  broker?: Record<string, unknown>

  signatureOption?: string
  insuredValue?: number | null
  insuredValueCurrency?: string

  lines: MultiWarehouseLineItem[]
}

export interface MultiWarehouseChildShipment {
  shipmentId: number | null
  warehouseCode: string
  carrierCode: string | null
  serviceCode: string | null
  trackingNumber: string | null
  trackingUrl: string | null
  labelUrl: string | null
  labelPdf: string | null
  carrierAmount: number | null
  billableAmount: number | null
  currency: string | null
  status: string
  message: string | null
  lineCount: number
}

export interface MultiWarehouseLabelResponse {
  groupId: number
  clientCode: string
  orderNo: number | null
  shipmentCount: number
  shipments: MultiWarehouseChildShipment[]
}

// ===== preview =====

export interface MultiWarehousePreviewLine {
  lineIndex: number
  itemNo: string | null
  quantity: number | null
  assignedWarehouseCode: string | null
  /** EXPLICIT | AUTO | NONE. */
  source: 'EXPLICIT' | 'AUTO' | 'NONE' | string
  matchReason: string | null
  selectedWarehouseId: number | null
  selectedWarehouseName: string | null
}

export interface MultiWarehousePreviewGroup {
  /** Null iff this is the unassigned bucket. */
  warehouseCode: string | null
  warehouseName: string | null
  lineCount: number
}

export interface MultiWarehousePreviewResponse {
  clientCode: string
  orderNo: number | null
  totalLines: number
  shipmentCount: number
  unassignedLineCount: number
  groups: MultiWarehousePreviewGroup[]
  lines: MultiWarehousePreviewLine[]
}

export const multiWarehouseLabelService = {
  /** Dry-run — resolves per-line warehouse assignment without buying anything. */
  preview: (payload: MultiWarehouseLabelPayload) =>
    apiClient.post<ApiResponse<MultiWarehousePreviewResponse>>(
      '/orders/multi-warehouse-preview',
      payload,
    ),

  /**
   * Buy labels for every group. Backend responds with 422 CARRIER_FAILURE
   * if any child fails — the {@link ApiError} carries the offending
   * warehouse in `payload.message`.
   */
  generate: (payload: MultiWarehouseLabelPayload) =>
    apiClient.post<ApiResponse<MultiWarehouseLabelResponse>>(
      '/orders/multi-warehouse-label',
      payload,
    ),
}
