import { apiClient, BASE_URL } from './apiClient'
import type { ApiResponse } from './orderService'

/**
 * Phase 2 (Shipment & Label re-architecture) — typed client for the
 * /api/v1/labels resource. Every field below is read from stored columns
 * on the server; nothing is inferred from label bytes on the client.
 */

export type LabelFormat = 'ZPL' | 'PDF' | 'GIF' | 'PNG' | 'UNKNOWN'
/** CARRIER_NATIVE = real thermal markup; CARRIER_RASTER = an image the ZPL endpoint wraps in ^GFA. */
export type LabelSource = 'CARRIER_NATIVE' | 'CARRIER_RASTER' | 'UNKNOWN'
/** BUG-049 — VOIDED added so the FE can short-circuit Download/Print on
 *  a voided label. The stored bytes stay on disk (audit); the backend
 *  returns 404 on download endpoints and the FE hides the actions.
 *  A reissue flips state back to GENERATED. */
export type LabelState = 'GENERATED' | 'PENDING' | 'VOIDED'

export interface PackageArtifact {
  seq: number
  format: LabelFormat
  source: LabelSource
  tracking: string | null
  /** The carrier's tracking page for this package, when it returned one. */
  trackingUrl: string | null
  generatedAt: string | null
}

export interface LabelArtifact {
  orderNo: number
  state: LabelState
  carrier: string | null
  /** SANDBOX | PRODUCTION, from the account the order shipped under. */
  environment: string | null
  generatedAt: string | null
  packages: PackageArtifact[]
  /** BUG-028 — true when the order has customs data and the commercial
   *  invoice PDF would render. Hides the Commercial invoice button on
   *  domestic orders rather than offer it only to 422 on click. */
  hasCommercialInvoice: boolean
}

/** BUG-049 — a 410 carries the server's reason ("Label … was voided"); show it instead of the status code. */
async function unavailable(response: Response, what: string): Promise<Error> {
  const reason = response.status === 410 ? (await response.text().catch(() => '')).trim() : ''
  return new Error(reason || `${what} is unavailable (HTTP ${response.status}).`)
}

async function text(url: string, what: string): Promise<string> {
  const response = await fetch(url, { credentials: 'include' })
  if (!response.ok) {
    throw await unavailable(response, what)
  }
  return response.text()
}

export const labelService = {
  /** The screen's model: state, carrier, environment, per-package format and source. */
  describe: (orderNo: number) => apiClient.get<ApiResponse<LabelArtifact>>(`/labels/${orderNo}`),

  /** One package's ZPL — the exact bytes the preview was rendered from. */
  packageZpl: (orderNo: number, seq: number) =>
    text(`${BASE_URL}/labels/${orderNo}/packages/${seq}/zpl`, 'ZPL'),

  /** Every package's ZPL concatenated, for spooling to a thermal printer. */
  allZpl: (orderNo: number) => text(`${BASE_URL}/labels/${orderNo}/zpl`, 'ZPL'),

  /** <img src> for the preview; the server tags it with the ZPL's SHA-256 as ETag. */
  previewPngUrl: (orderNo: number, seq: number): string =>
    `${BASE_URL}/labels/${orderNo}/packages/${seq}/preview.png`,

  /** Cheap existence probe — no render on the server. */
  headPreview: async (orderNo: number, seq: number): Promise<boolean> => {
    try {
      const response = await fetch(`${BASE_URL}/labels/${orderNo}/packages/${seq}/preview.png`, {
        method: 'HEAD',
        credentials: 'include',
      })
      return response.ok
    } catch {
      return false
    }
  },

  /** Printable PDF: one 4×6 page per package, or a single package with seq. */
  pdfUrl: (orderNo: number, seq?: number): string =>
    `${BASE_URL}/labels/${orderNo}/pdf${seq ? `?seq=${seq}` : ''}`,

  getPdf: async (orderNo: number, seq?: number): Promise<Blob> => {
    const response = await fetch(labelService.pdfUrl(orderNo, seq), { credentials: 'include' })
    if (!response.ok) {
      throw await unavailable(response, 'PDF')
    }
    return response.blob()
  },
}
