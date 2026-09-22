/**
 * Client for the NDS Shipment prefill endpoint added in backend PR #735.
 *
 * <p>Scanner-driven Manual Shipment flow: operator scans `.X<containerId>`
 * or `.Y<batchId>`, this service hits the backend which reads NDS Oracle
 * via the S1 external-systems framework and returns a snapshot of the
 * order — client, recipient, ship method, packages, notify email, CI
 * items — for the FE to populate the form with.
 *
 * <p>HTTP contract from the backend controller
 * ({@code NdsShipmentLookupController}):
 * <ul>
 *   <li>200 — OK / WARNING / BLOCKED (payload always present; caller
 *       decides visual treatment from {@code status}).</li>
 *   <li>404 — nothing matched the scanned value.</li>
 *   <li>422 — scan value is unparseable (bad prefix / empty payload).</li>
 *   <li>503 — NDS is unreachable / mis-configured.</li>
 * </ul>
 * All error paths surface as a rejected promise with {@link ApiError};
 * callers should switch on {@code error.status} to render the right
 * banner (see NewShipmentPage's scan handler for the mapping).
 */
import { apiClient } from './apiClient'
import type { ApiResponse } from './orderService'

export type NdsPrefillStatus = 'OK' | 'WARNING' | 'BLOCKED'
export type NdsPrefillScope = 'DIRECT' | 'BATCH'
export type NdsMessageSeverity = 'INFO' | 'WARNING' | 'BLOCKED'

export interface NdsMessage {
  severity: NdsMessageSeverity
  text: string
}

export interface NdsOrderRef {
  orderNo: number | null
  orderSuffix: number | null
  invNo: string | null
  thpAccount: string | null
}

export interface NdsRecipient {
  attn: string | null
  name: string | null
  addr1: string | null
  addr2: string | null
  addr3: string | null
  city: string | null
  state: string | null
  zip: string | null
  countryCd: string | null
  phone: string | null
  /** True when backend substituted +1 616 772 3513 for a missing/garbage phone. */
  phoneDefaulted: boolean
  sourceOrderNo: number | null
}

export interface NdsShipMethod {
  code: string
  description: string | null
  /** Null when NDS's SHIPVIA_CD isn't in our ClientShipviaCodeMap/ShipViaMapping. */
  mappedServiceId: number | null
}

export interface NdsPackage {
  sequence: number
  containerNo: string
  containerIds: number[]
  orderNos: number[]
  orderSuffix: number | null
  weight: number | null
  weightSource: string | null
  length: number | null
  width: number | null
  height: number | null
  packDt: string | null
  shippedFlag: string | null
  /** True for the exact container the operator scanned (.X only). */
  isScanned: boolean
}

export interface NdsNotify {
  sendTo: string | null
  copyTo: string | null
  /** True when backend substituted support@thbred.com for an empty notify. */
  emailDefaulted: boolean
}

export interface NdsInternationalItem {
  orderNo: number | null
  lineNo: number | null
  linkLineNo: number | null
  itemNo: string | null
  unitPrice: number | null
  customsDeclValue: number | null
  description: string | null
  qtyShipped: number | null
  countryOfOrigin: string | null
  harmonizeCode: string | null
  harmonizeCodeDesc: string | null
  unitCost: number | null
}

export interface NdsInternational {
  international: boolean
  packageCount: number
  totalWeight: number | null
  letterOfCredit: string | null
  items: NdsInternationalItem[]
}

export interface NdsShipmentPrefill {
  status: NdsPrefillStatus
  messages: NdsMessage[]
  scope: NdsPrefillScope
  scannedValue: string
  clientCode: string
  batchId: string | null
  orders: NdsOrderRef[]
  recipient: NdsRecipient | null
  shipMethod: NdsShipMethod | null
  packages: NdsPackage[]
  notifyBlock: NdsNotify | null
  international: NdsInternational | null
  /** Machine-readable list of fields the backend substituted defaults into
   *  (e.g. `["recipient.phone", "notify.sendTo"]`). FE uses this to decide
   *  when to fall back to the sender/shipper block for the operator. */
  defaultedFields: string[]
}

export const ndsShipmentService = {
  lookup: (scan: string) =>
    apiClient
      .get<ApiResponse<NdsShipmentPrefill>>(
        `/manual-shipment/nds-lookup?scan=${encodeURIComponent(scan)}`,
      )
      .then((res) => res.data as NdsShipmentPrefill),
}
