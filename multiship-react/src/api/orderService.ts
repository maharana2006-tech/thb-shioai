import { apiClient, BASE_URL } from './apiClient'
import type { OrderAccountResolution } from './accountRefService'

// ===== Request/Response Types =====

export interface OrderDetails {
  orderNo: number
  orderSuffix: number
  status: string
  customerCode: string
  goodsDescription: string
  createdDate: string
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
  goodsDesc: string | null
  createdDate: string | null
  orderLines: OrderLine[]
}

/** Configured ship-from address exposed by /orders/{orderNo}/label. */
export interface ShipperInfo {
  name: string | null
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

export interface LabelDocumentPayload {
  order: OrderWithLines
  /** Which account the order ships with, from the three-scenario cascade. */
  resolution?: LabelDocumentResolution | null
  /** Legacy field from older backend builds. */
  carrierAccount?: OrderWithLinesPayload['carrierAccount']
  label: LabelDetails | null
  shipper: ShipperInfo
  /** International-only customs blocks resolved from the client's profile. */
  international?: boolean
  importer?: LabelImporter | null
  broker?: LabelBroker | null
  /** CARRIER_DEFAULT (carrier's included brokerage) | BROKER_SELECT (named broker). */
  brokerage?: 'CARRIER_DEFAULT' | 'BROKER_SELECT' | null
  customsDefaults?: LabelCustomsDefaults | null
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
    /** Negotiated rate discount % from the service↔package link. */
    discountPct?: number | null
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
   */
  getLabelZpl: async (orderNo: number): Promise<string> => {
    const token = localStorage.getItem('multiship_token')
    const response = await fetch(`${BASE_URL}/orders/${orderNo}/label/zpl`, {
      headers: token ? { Authorization: `Bearer ${token}` } : {},
    })

    if (!response.ok) {
      throw new Error(`ZPL is unavailable (HTTP ${response.status}) — restart the backend if it was just updated.`)
    }

    return response.text()
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

  /**
   * Get city distribution (using stats endpoint)
   */
  getCityDistribution: () => {
    return apiClient.get<ApiResponse<DashboardStats>>('/orders/stats')
  },
}
