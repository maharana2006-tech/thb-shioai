import { apiClient, BASE_URL } from './apiClient'
import type { OrderAccountResolution } from './accountRefService'
import type { DangerousGoodsBlock } from './dgService'

// ===== Request/Response Types =====

export interface OrderDetails {
  orderNo: number
  orderSuffix: number
  status: string
  customerCode: string
  goodsDescription: string
  createdDate: string
  /** Where the order came from: MANUAL | WMS | API | ERP. */
  source?: string | null
  /** The WMS's own order number, as sent on the external shipment request. */
  refOrderNumber?: string | null
  /** Id shared by every order generated from the same CSV/XLSX import upload. Null for non-import orders. */
  batchId?: number | null
}

export interface ShippingDetails {
  city: string
  state: string
  zipCode: string
  shipVia: string
  weight: number
  shipViaDescription: string | null
}

export interface LabelDetails {
  status: string
  isGenerated: boolean
  trackingNumber: string | null
  trackingUrl: string | null
  labelFilePath: string | null
  generatedAt: string | null
}

export interface ErrorDetails {
  hasError: boolean
  errorMessage: string | null
}

export interface CarrierAccountInfo {
  accountCode: string | null
  carrierCode: string | null
  isDefault: boolean
}

export interface Order {
  orderDetails: OrderDetails
  shippingDetails: ShippingDetails
  labelDetails: LabelDetails
  errorDetails: ErrorDetails
  carrierAccount?: CarrierAccountInfo | null
  /** Cascade result for this order; present when listed with includeResolution=true. */
  accountResolution?: OrderAccountResolution | null
  /** Sprint 43 — tenant-defined custom field values (fieldKey -> value).
   *  Populated on single-order reads only; omitted from list responses. */
  customFields?: Record<string, string> | null
}

export interface OrderSummary {
  totalWeight: number
  averageWeight: number
  pendingLabels: number
  generatedLabels: number
  failedLabels: number
  cities: string[]
  statusCounts: Record<string, number>
}

export interface OrderListData {
  totalRecords: number
  orders: Order[]
  summary: OrderSummary
}

export interface PaginatedOrderData {
  content: Order[]
  pageNumber: number
  pageSize: number
  totalElements: number
  totalPages: number
  first: boolean
  last: boolean
  empty: boolean
  sortBy: string
  sortDirection: 'ASC' | 'DESC'
}

export interface OrderLine {
  id: number
  lineNo: number
  itemNo: string | null
  itemDescription: string | null
  qtyShipped: number | null
  hsCode: string | null
  hsDesc: string | null
  description: string | null
  countryOfOrigin: string | null
  customsDeclValue: number | null
  unitPrice: number | null
  totalPrice: number | null
}

/** Backend OrderWithLinesDTO (flat order shape used by /orders/{orderNo}/details). */
/** Per-package row persisted alongside a multi-package shipment. Backend
 *  attaches this list to /orders/{orderNo}/label so the label renderer can
 *  print the correct per-box tracking number, weight, and dimensions. */
export interface OrderPackage {
  sequenceNumber?: number | null
  trackingNumber?: string | null
  weight?: number | null
  weightUnit?: string | null
  length?: number | null
  width?: number | null
  height?: number | null
  dimUnit?: string | null
}

export interface OrderWithLines {
  orderNo: number
  orderSuffix: number | null
  orderStatus: string | null
  custNo: string | null
  shipName: string | null
  shipAttn: string | null
  shipAddr1: string | null
  phone: string | null
  shiptoCity: string | null
  shiptoState: string | null
  shiptoZip: string | null
  shiptoCountryCd: string | null
  shipviaCd: string | null
  tenantId: string | null
  weight: number | null
  /** LB | KG — unit for the top-level `weight` field. Falls back per label renderer. */
  weightUnit?: string | null
  /** Declared / customs value entered for the shipment. */
  declaredValue?: number | null
  goodsDesc: string | null
  createdDate: string | null
  orderLines: OrderLine[]
  // Ship-FROM (origin) captured on manual shipments; null on ERP/WMS orders.
  shipFromName?: string | null
  shipFromCompany?: string | null
  shipFromAddr1?: string | null
  shipFromAddr2?: string | null
  shipFromCity?: string | null
  shipFromState?: string | null
  shipFromZip?: string | null
  shipFromCountryCd?: string | null
  shipFromPhone?: string | null
  /** True when ship-from came from the client warehouse / platform default
   *  rather than a sender entered on the order (bulk/ERP orders). */
  shipFromResolved?: boolean | null
  /** 'Y'|'N' — cross-border classification recorded at label time. */
  intlYn?: string | null
  /** Sprint 29 — multi-package count column on the order (1 for legacy single-box). */
  packageCount?: number | null
  /** Sprint 29 — per-package rows (tracking / weight / dims) when this is a multi-package shipment. */
  packages?: OrderPackage[] | null
  /** PR #543 — order source (`MANUAL | BULK | WMS | API | ERP`) drives
   *  the PO field on the JSX label facsimile:
   *    - `MANUAL` / `BULK`  → `MAN{orderNo}`
   *    - `WMS`              → `wmsExternalId` when set, else `orderNo`
   *    - other (`API` / `ERP` / null) → orderNo bare
   *  Null on legacy rows — facsimile falls through to orderNo bare. */
  source?: string | null
  /** PR #543 — external order id from a WMS pull, used as PO when
   *  `source === 'WMS'`. Null for non-WMS orders. */
  wmsExternalId?: string | null
}

/** Configured ship-from address exposed by /orders/{orderNo}/label. */
export interface ShipperInfo {
  name: string | null
  /** PR #535 — separate COMPANY line when the shipper's Address has its
   *  own name (warehouse alias) that differs from the client's
   *  registered name. Backend populates via OrderController.addressMap. */
  company?: string | null
  phone: string | null
  addressLine1: string | null
  addressLine2: string | null
  city: string | null
  state: string | null
  postalCode: string | null
  countryCode: string | null
}

/** Account resolution attached to label documents (mirrors OrderAccountResolutionDTO). */
export interface LabelDocumentResolution {
  scenario: string
  carrierCode: string | null
  accountNumber: string | null
  accountName: string | null
  environment: string | null
}

/** Payload of GET /orders/{orderNo}/label. */
/** Importer of record resolved from the client's customs profile (international only). */
export interface LabelImporter {
  /** RECEIVER = the consignee imports (DAP); BUSINESS = fixed registered entity (DDP). */
  type?: 'RECEIVER' | 'BUSINESS' | null
  name?: string | null
  contact?: string | null
  countryCode?: string | null
  addressLine1?: string | null
  addressLine2?: string | null
  phone?: string | null
  city?: string | null
  state?: string | null
  postalCode?: string | null
  taxId?: string | null
  taxIdType?: string | null
  eori?: string | null
  ioss?: string | null
  companyReg?: string | null
  iec?: string | null
  gstin?: string | null
}

/** Named broker (Broker Select) — absent when the carrier's own brokerage clears. */
export interface LabelBroker {
  name?: string | null
  company?: string | null
  countryCode?: string | null
  addressLine1?: string | null
  addressLine2?: string | null
  phone?: string | null
  city?: string | null
  state?: string | null
  postalCode?: string | null
  brokerId?: string | null
  license?: string | null
}

export interface LabelCustomsDefaults {
  incoterms?: string | null
  dutiesBillTo?: string | null
  dutiesAccount?: string | null
  reasonForExport?: string | null
  currency?: string | null
}

/** One commercial-invoice line item entered against an order (customs.items). */
export interface LabelCustomsItem {
  description?: string | null
  hsCode?: string | null
  countryOfOrigin?: string | null
  quantity?: number | null
  unitValue?: number | null
  weight?: number | null
  sku?: string | null
}

export interface LabelDocumentPayload {
  order: OrderWithLines
  /** Which account the order ships with, from the three-scenario cascade. */
  resolution?: LabelDocumentResolution | null
  /** Legacy field from older backend builds. */
  carrierAccount?: OrderWithLinesPayload['carrierAccount']
  label: LabelDetails | null
  shipper: ShipperInfo
  /** True when this is a reverse/return label. */
  isReturn?: boolean
  /** International-only customs blocks resolved from the client's profile. */
  international?: boolean
  importer?: LabelImporter | null
  broker?: LabelBroker | null
  /** CARRIER_DEFAULT (carrier's included brokerage) | BROKER_SELECT (named broker). */
  brokerage?: 'CARRIER_DEFAULT' | 'BROKER_SELECT' | null
  customsDefaults?: LabelCustomsDefaults | null
  /** Per-order commercial-invoice line items entered against the order (international). */
  customs?: {
    items?: LabelCustomsItem[] | null
    incoterms?: string | null
    reasonForExport?: string | null
    currency?: string | null
    weightUnit?: string | null
    notes?: string | null
  } | null
  /** What the shipment was billed — freight for the commercial invoice.
   *  0.00 in sandbox until a production rate is captured. */
  charges?: {
    freight?: number | null
    carrierAmount?: number | null
    billableAmount?: number | null
    currency?: string | null
  } | null
  /** Service level resolved from the ERP ship-via mapping (Settings → Shipping Services). */
  service?: { carrier: string; code: string; name: string; scope?: string } | null
  /** The default package preset (Settings → Packages) used for type + dimensions. */
  packagePreset?: {
    name: string
    kind: string
    carrierPackageCode?: string | null
    length?: number | null
    width?: number | null
    height?: number | null
    dimUnit?: string
    maxWeight?: number | null
    weightUnit?: string
  } | null
}

/** Payload of GET /orders/{orderNo}/details: the order plus the tenant's carrier account. */
export interface OrderWithLinesPayload {
  order: OrderWithLines
  carrierAccount: {
    id: number
    tenantId: string
    carrierCode: string
    carrierName: string
    accountNumber: string | null
    accountCode: string | null
    isDefault: boolean
    active: boolean | null
    environment: string | null
    shipViaCd: string | null
    shipViaDescription: string | null
  } | null
}

/** Item of GET /orders/tenant/{tenantId}: order paired with its tenant carrier account. */
export interface OrderWithCarrier {
  order: Order
  carrierAccount: OrderWithLinesPayload['carrierAccount']
}

export interface ApiResponse<T> {
  status: string
  code: number
  message: string
  timestamp: string
  data: T
  errors?: {
    field: string
    code: string
    message: string
  }
}

export interface DashboardStats {
  orderStats: {
    totalOrders: number
    pendingOrders: number
    generatedOrders: number
    failedOrders: number
  }
  weightStats: {
    totalWeight: number
    averageWeight: number
  }
  cityDistribution: Array<{
    city: string
    count: number
  }>
  statusSummary: {
    labelGenerated: number
    pendingLabels: number
    errorLabels: number
    completionRate: string
  }
}

export interface LabelGenerationRequest {
  orderNo: number
}

export interface LabelGenerationResponse {
  success: boolean
  message: string
  trackingNumber?: string
  trackingUrl?: string
  labelFilePath?: string
  status?: string
  /** Which source shipped this label: ORDER, REFERENCE, or DEFAULT. */
  accountSource?: string
  // Scenario 2: generation paused — the order needs its carrier details completed.
  needsDetails?: boolean
  missingFields?: string[] | null
  prefillAccountNumber?: string | null
  prefillCarrierCode?: string | null
  prefillClientId?: string | null
  prefillEnvironment?: string | null
  /** Set on CLIENT_MISSING / CHOOSE_ACCOUNT payloads. */
  clientCode?: string | null
  /** Manual one-shot shipments echo the generated order number so the UI can open the label. */
  orderNo?: number
  carrierCode?: string
  carrierName?: string
}

/** One party (sender or recipient) on a manual shipment. */
export interface ManualShipmentAddress {
  name: string
  company?: string
  phone?: string
  email?: string
  addressLine1: string
  addressLine2?: string
  /** Third street line — JP/CN/IN long addresses. Optional; carriers accept 3+. */
  addressLine3?: string
  city: string
  state?: string
  postalCode: string
  countryCode: string
  /** True when this is a residence — carriers apply a residential surcharge on international. */
  residential?: boolean
  /** ISO dial code (no plus): "1", "44", "91". Prepended to phone at carrier time. */
  phoneCountryCode?: string
}

/** One commercial-invoice line on an international manual shipment. */
export interface ManualShipmentItem {
  description: string
  hsCode?: string
  countryOfOrigin?: string
  quantity?: number | null
  unitValue?: number | null
  weight?: number | null
  sku?: string
  /**
   * Sprint 48 B11 — 1-based package index this item belongs to. Backend
   * groups items by boxSeq to derive per-package declared value (sum of
   * unitValue × quantity). Null = unassigned; treated as "all in box 1"
   * (backward-compat with legacy single-box CI).
   */
  boxSeq?: number
}

/** Payload for the one-shot manual shipment / label endpoint. */
export interface ManualShipmentPayload {
  sender: ManualShipmentAddress
  recipient: ManualShipmentAddress
  /** True = reverse/return label (customer ships back); false/omitted = normal outbound shipment. */
  isReturn?: boolean
  /** Return delivery type — PRINT | EMAIL. */
  returnType?: string
  /** Optional credential-account hint; resolved from accountNumber + carrierCode when absent. */
  accountId?: number | null
  /** Carrier (UPS/FEDEX/USPS) — needed to resolve credentials for a manually-typed account. */
  carrierCode?: string
  /** Bill-to account number (may be typed manually). */
  accountNumber?: string
  serviceId?: number | null
  packagePresetId?: number | null
  length?: number | null
  width?: number | null
  height?: number | null
  dimUnit?: string
  weight: number
  weightUnit?: string
  clientCode?: string
  /** Ship-from warehouse (must be attached to clientCode). When set, its
   *  address overrides the sender block. Ignored for ad-hoc shipments. */
  warehouseCode?: string
  declaredValue?: number | null
  goodsDescription?: string
  reference?: string
  // International (cross-border) only:
  items?: ManualShipmentItem[]
  incoterms?: string
  reasonForExport?: string
  /** F6-C — per-carrier customs clearance option. Values differ per
   *  carrier (UPS SENDER/RECEIVER/THIRD_PARTY vs FedEx SENDER/RECIPIENT/
   *  THIRD_PARTY vs USPS DDU/DDP vs DHL DAP/DDP/EXW). Null / omitted →
   *  connector applies its own carrier default. */
  clearanceOption?: string | null
  /** Per-shipment importer/broker override (does not touch the client's saved profile). */
  importer?: Record<string, string>
  broker?: Record<string, string>
  currency?: string
  /** Sprint 27 — dangerous goods declaration. Backend threads this into
   *  the ShipmentRequestDTO.dangerousGoods field which every connector's
   *  wire format keys off. Null on non-hazmat shipments. */
  dangerousGoods?: DangerousGoodsBlock | null
  /** Sprint 29 — multi-package. When present, connectors iterate this
   *  list and emit one carrier-specific package block per entry
   *  (Shipment.Package[] on UPS, requestedPackageLineItems[] on FedEx,
   *  content.packages[] on DHL, N CreateIndicium calls on USPS/Stamps).
   *  Null / omitted → backend synthesises a single-package list from the
   *  top-level weight/length/width/height fields (existing behavior). */
  packages?: PackageDetail[]
  /** Sprint 35 — signature at delivery. NONE | INDIRECT | DIRECT | ADULT.
   *  Null / omitted → carrier default (usually no signature on domestic
   *  ground, indirect on air). */
  signatureOption?: 'NONE' | 'INDIRECT' | 'DIRECT' | 'ADULT' | null
  /** Sprint 35 — insured value beyond the carrier's free tier ($100 UPS
   *  / FedEx / USPS Priority Ground). Null / 0 → no explicit insurance
   *  requested; the free tier still applies. */
  insuredValue?: number | null
  insuredValueCurrency?: string | null
  /** Per-shipment override of the account's default label file format —
   *  UPS/DHL/USPS only (FedEx uses labelImageType/labelStockType instead).
   *  Null / omitted → use the account default. */
  labelImageFormat?: string | null
  /** FDX-H3 — per-shipment override of the FedEx account's default
   *  labelSpecification.imageType. Null / omitted → use the account
   *  default (which itself falls back to PDF). Only FedEx maps this. */
  labelImageType?: string | null
  /** FDX-H3 — per-shipment override of the FedEx account's default
   *  labelSpecification.labelStockType. Null / omitted → use the account
   *  default (which itself falls back to PAPER_4X6). Only FedEx maps this. */
  labelStockType?: string | null
  /** FDX-H1 — per-shipment override of the FedEx account's default
   *  pickupType. Null / omitted → use the account default (which itself
   *  falls back to USE_SCHEDULED_PICKUP). Only FedEx maps this; UPS /
   *  DHL / USPS ignore. Return labels bypass this and always emit
   *  CONTACT_FEDEX_TO_SCHEDULE. */
  pickupType?: string | null
}

/** One box in a multi-package shipment — mirrors backend PackageDetailDTO. */
export interface PackageDetail {
  sequenceNumber?: number
  packageType?: string
  weight: number
  weightUnit?: 'LB' | 'KG'
  length?: number
  width?: number
  height?: number
  dimUnit?: 'IN' | 'CM'
  declaredValue?: number
  description?: string
  reference?: string
}

/** One scan / status update on a carrier's timeline. Timestamps are ISO-8601
 *  strings on the wire; the timeline component parses to Date for display. */
export interface TrackingEventDTO {
  timestamp: string | null
  status: string | null
  description: string
  location: string | null
}

/** Sprint 30 response for POST /api/v1/orders/{n}/void. Status is the
 *  enum from the connector's VoidResult: VOIDED (carrier confirmed),
 *  ALREADY_VOIDED (idempotent no-op), NOT_SUPPORTED (no live credentials
 *  or no wire implementation), ERROR (carrier rejected or call failed). */
export interface VoidLabelResponse {
  orderNo: number
  trackingNumber: string | null
  carrierCode: string | null
  voided: boolean
  status: 'VOIDED' | 'ALREADY_VOIDED' | 'NOT_SUPPORTED' | 'ERROR'
  message: string
}

/** Backend response for /api/v1/orders/{n}/tracking/live. Source flag drives
 *  the freshness badge — LIVE (just checked) / CACHE (served from memory) /
 *  STUB (no live credentials, only the tracking URL is available). */
export interface TrackingResponseDTO {
  trackingNumber: string
  carrierCode: string | null
  status: string | null
  delivered: boolean | null
  trackingUrl: string | null
  currentLocation: string | null
  estimatedDelivery: string | null
  events: TrackingEventDTO[]
  source: 'LIVE' | 'CACHE' | 'STUB'
}

// ===== Order Service =====

/** Session-stable Idempotency-Keys, one per order (see generateLabel). */
const generationKeys = new Map<number, string>()

export interface OrderListParams {
  page?: number
  size?: number
  sortBy?: string
  sortDirection?: 'ASC' | 'DESC'
  /** PENDING | GENERATED | ERROR */
  status?: string
  /** Scope to one tenant (required for TENANT users). */
  tenantId?: string
  /** Matches order #, city, customer code, or tracking number. */
  search?: string
  /** READY | NEEDS_DETAILS | BLOCKED (cascade scenario, server-computed). */
  resolution?: string
  /** Attach the cascade's account pick to each row. */
  includeResolution?: boolean
  /** Column filter: client code contains. */
  customer?: string
  /** Column filter: destination city/state contains. */
  city?: string
  /** Column filter: order # contains. */
  orderNo?: string
  /** Column filter: tracking number contains. */
  tracking?: string
  /** Created on or after (yyyy-MM-dd). */
  createdFrom?: string
  /** Created on or before (yyyy-MM-dd). */
  createdTo?: string
  /** Order source: MANUAL | BULK | API | WMS | ERP. Empty = all sources. */
  source?: string
}

/** Tab counts for the Labels work queue. */
export interface QueueStats {
  ready: number
  needsDetails: number
  chooseAccount: number
  clientMissing: number
  failed: number
  generated: number
}

export const orderService = {
  /**
   * THE order list: one server-side paginated, sorted, filtered endpoint
   * behind every order table in the app.
   */
  listOrders: (params: OrderListParams = {}) => {
    const query = new URLSearchParams()
    query.set('page', String(params.page ?? 0))
    query.set('size', String(params.size ?? 20))
    query.set('sortBy', params.sortBy ?? 'orderNo')
    query.set('sortDirection', params.sortDirection ?? 'ASC')
    if (params.status) query.set('status', params.status)
    if (params.tenantId) query.set('tenantId', params.tenantId)
    if (params.search?.trim()) query.set('search', params.search.trim())
    if (params.resolution) query.set('resolution', params.resolution)
    if (params.includeResolution) query.set('includeResolution', 'true')
    if (params.customer?.trim()) query.set('customer', params.customer.trim())
    if (params.city?.trim()) query.set('city', params.city.trim())
    if (params.orderNo?.trim()) query.set('orderNo', params.orderNo.trim())
    if (params.tracking?.trim()) query.set('tracking', params.tracking.trim())
    if (params.createdFrom) query.set('createdFrom', params.createdFrom)
    if (params.createdTo) query.set('createdTo', params.createdTo)
    if (params.source) query.set('source', params.source)

    return apiClient.get<ApiResponse<PaginatedOrderData>>(`/orders?${query.toString()}`)
  },

  /** Work-queue tab counts (ready / needsDetails / blocked / failed / generated). */
  getQueueStats: () => {
    return apiClient.get<ApiResponse<QueueStats>>('/orders/queue-stats')
  },

  /**
   * Get single order with tracking details
   */
  getOrderById: (orderNo: number) => {
    return apiClient.get<ApiResponse<Order>>(`/orders/${orderNo}?includeAccountInfo=true`)
  },

  /**
   * Raw ZPL for the order's 4x6 thermal label (text/plain, not ApiResponse-wrapped) —
   * ready to send to a Zebra printer or preview on labelary.com.
   *
   * Audit L1 — accepts `pkgIndex` for multi-package shipments; backend
   * already supports `?pkg=N` and returns the per-package ZPL. Pre-fix,
   * an operator viewing pkg 3 of 5 in the label doc UI would still
   * download pkg 1's ZPL silently.
   */
  getLabelZpl: async (orderNo: number, pkgIndex?: number): Promise<string> => {
    const qs = pkgIndex && pkgIndex > 1 ? `?pkg=${pkgIndex}` : ''
    // Sprint 50 PR Q3 — cookie-mode auth.
    const response = await fetch(`${BASE_URL}/orders/${orderNo}/label/zpl${qs}`, {
      credentials: 'include',
    })

    if (!response.ok) {
      throw new Error(`ZPL is unavailable (HTTP ${response.status}) — restart the backend if it was just updated.`)
    }

    return response.text()
  },

  /**
   * Sprint 52 PR A — 4x6" PDF facsimile of the shipping label. Same
   * ?pkg semantics as getLabelZpl: omitted on a multi-box shipment
   * returns all packages as one PDF with N pages. Returns a Blob so
   * the caller can trigger a download or preview inline.
   *
   * PR B will layer in carrier-artifact passthrough (return the real
   * PDF bytes when the carrier's stored artifact is PDF format).
   */
  getLabelPdf: async (orderNo: number, pkgIndex?: number): Promise<Blob> => {
    const qs = pkgIndex && pkgIndex > 1 ? `?pkg=${pkgIndex}` : ''
    const response = await fetch(`${BASE_URL}/orders/${orderNo}/label/pdf${qs}`, {
      credentials: 'include',
    })
    if (!response.ok) {
      throw new Error(`Label PDF is unavailable (HTTP ${response.status}) — restart the backend if it was just updated.`)
    }
    return response.blob()
  },

  /**
   * PR #538 — probe for the carrier-ZPL PNG preview endpoint. Returns
   * true iff the backend has label.render-carrier-zpl=true AND the
   * carrier stored parseable ZPL bytes for the order. 404 (flag off /
   * not ZPL) or 502 (renderer failed) → false. Silent — the FE keeps
   * its JSX facsimile visible when this returns false, no error toast.
   *
   * Uses HEAD to avoid downloading the PNG until we know we want it;
   * the actual <img> renders the same URL with a fresh GET which the
   * browser caches per Cache-Control: private, max-age=60.
   */
  headLabelPreviewPng: async (orderNo: number): Promise<boolean> => {
    try {
      const response = await fetch(`${BASE_URL}/orders/${orderNo}/label/preview.png`, {
        method: 'HEAD',
        credentials: 'include',
      })
      return response.ok
    } catch {
      return false
    }
  },

  /** PR #538 — URL builder for the <img src=> tag once the HEAD probe
   *  above confirms the PNG endpoint is live. Kept as a helper so
   *  LabelDocumentPage doesn't hardcode the path. */
  labelPreviewPngUrl: (orderNo: number): string =>
    `${BASE_URL}/orders/${orderNo}/label/preview.png`,

  /**
   * The order's commercial invoice as a PDF blob (Sprint 51). The platform's
   * own copy, rendered from the persisted customs data and available on
   * demand for any international order. 422 means the order has no customs
   * data (domestic / not international).
   */
  getCommercialInvoicePdf: async (orderNo: number): Promise<Blob> => {
    const response = await fetch(`${BASE_URL}/orders/${orderNo}/commercial-invoice`, {
      credentials: 'include',
    })
    if (response.status === 422) {
      throw new Error('This order has no customs data — a commercial invoice only applies to international shipments.')
    }
    if (!response.ok) {
      throw new Error(`Commercial invoice is unavailable (HTTP ${response.status}).`)
    }
    return response.blob()
  },

  /**
   * Get an order with its line items plus the tenant's carrier account
   * (backend resolves the account from the order's tenant).
   */
  getOrderWithLines: (orderNo: number) => {
    return apiClient.get<ApiResponse<OrderWithLinesPayload>>(`/orders/${orderNo}/details`)
  },

  /**
   * Everything needed to render the shipping label + commercial invoice:
   * order with lines, tenant carrier account, label details, shipper address.
   */
  getLabelDocument: (orderNo: number) => {
    return apiClient.get<ApiResponse<LabelDocumentPayload>>(`/orders/${orderNo}/label`)
  },

  /**
   * Get dashboard statistics
   */
  getDashboardStats: () => {
    return apiClient.get<ApiResponse<DashboardStats>>('/orders/stats')
  },

  /**
   * Generate label for an order. Sends a per-order Idempotency-Key that is
   * stable for this browser session: if the response of a successful
   * generation is lost (network drop) and the user retries, the server
   * recognizes the key and returns the existing label as a success instead
   * of a 409 — the carrier is never billed twice.
   */
  generateLabel: (orderNo: number, accountId?: number) => {
    let key = generationKeys.get(orderNo)
    if (!key) {
      key = typeof crypto?.randomUUID === 'function' ? crypto.randomUUID() : `gen-${orderNo}-${Date.now()}`
      generationKeys.set(orderNo, key)
    }

    return apiClient.post<ApiResponse<LabelGenerationResponse>>(
      `/orders/${orderNo}/label`,
      accountId != null ? { accountId } : undefined,
      { headers: { 'Idempotency-Key': key } }
    )
  },

  /** One-shot manual shipment: create + purchase the label in a single call. */
  generateManualLabel: (payload: ManualShipmentPayload) =>
    apiClient.post<ApiResponse<LabelGenerationResponse>>('/orders/manual-label', payload),

  /** Fix a failed order and regenerate its label in place (same order number).
   *  Same payload shape as generateManualLabel; the order flips ERROR → GENERATED
   *  on success, or stays ERROR with the new carrier message on failure. */
  regenerateOrder: (orderNo: number, payload: ManualShipmentPayload) =>
    apiClient.post<ApiResponse<LabelGenerationResponse>>(`/orders/${orderNo}/regenerate`, payload),

  /**
   * Live tracking for an order — status, events, estimated delivery. Backend
   * caches for 5 min (in-flight) / 24 h (delivered), so calling this on a
   * quick UI refresh is safe. Every failure mode falls back to a URL-only
   * stub (source === 'STUB'); callers should render the timeline when
   * events[] is non-empty and fall back to the trackingUrl link when it's
   * empty.
   */
  getLiveTracking: (orderNo: number | string) =>
    apiClient.get<ApiResponse<TrackingResponseDTO>>(
      `/orders/${encodeURIComponent(String(orderNo))}/tracking/live`,
    ),

  /**
   * Sprint 30 — void / cancel a previously-issued label at the carrier.
   * Idempotent — voiding an already-VOIDED order returns 200 without a
   * carrier round-trip.
   */
  voidLabel: (orderNo: number | string) =>
    apiClient.post<ApiResponse<VoidLabelResponse>>(
      `/orders/${encodeURIComponent(String(orderNo))}/void`,
      {},
    ),

  /**
   * Get city distribution (using stats endpoint)
   */
  getCityDistribution: () => {
    return apiClient.get<ApiResponse<DashboardStats>>('/orders/stats')
  },
}
