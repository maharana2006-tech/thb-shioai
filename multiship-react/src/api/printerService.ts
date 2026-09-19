import { apiClient } from './apiClient'
import type { ApiResponse } from './orderService'
import { notify } from '../utils/notify'

/**
 * Printer management + client routing (Settings → Printers) and "Send to
 * printer" from the Orders page. Reads are open to operators; changes are
 * ADMIN-only on the backend.
 */

export type PrinterConnection = 'RAW_9100' | 'IPP'
export type PrinterFormat = 'ZPL' | 'PDF'
export type PrinterPaper = 'LABEL_4X6' | 'A4' | 'LETTER'
export type PrintDocType = 'LABEL' | 'COMMERCIAL_INVOICE'

export interface Printer {
  id: number
  name: string
  location: string | null
  connection: PrinterConnection
  host: string
  port: number
  queuePath: string | null
  format: PrinterFormat
  paper: PrinterPaper
  active: boolean
  lastTestAt: string | null
  lastTestOk: boolean | null
  lastTestMessage: string | null
  createdAt: string
  updatedAt: string
}

export interface PrinterInput {
  name: string
  location?: string | null
  connection: PrinterConnection
  host: string
  /** Blank = 9100 for RAW_9100, 631 for IPP. */
  port?: number | null
  queuePath?: string | null
  format: PrinterFormat
  paper?: PrinterPaper | null
  active: boolean
}

export interface PrinterAssignment {
  id: number
  /** null = the default for every client without its own printer. */
  clientCode: string | null
  docType: PrintDocType
  printerId: number
}

export interface SendToPrinterResult {
  docType: PrintDocType
  sent: number
  printers: Array<{
    printerId: number
    printer: string
    format: PrinterFormat
    orders: number
    documents: number
    ok: boolean
    message: string
  }>
  unassigned: number[]
  skipped: number[]
  wrongFormat: number[]
}

export const printerService = {
  list: () => apiClient.get<ApiResponse<Printer[]>>('/printers'),
  create: (input: PrinterInput) => apiClient.post<ApiResponse<Printer>>('/printers', input),
  update: (id: number, input: PrinterInput) => apiClient.put<ApiResponse<Printer>>(`/printers/${id}`, input),
  remove: (id: number) => apiClient.delete<ApiResponse<void>>(`/printers/${id}`),
  test: (id: number) => apiClient.post<ApiResponse<Printer>>(`/printers/${id}/test`, {}),

  listAssignments: () => apiClient.get<ApiResponse<PrinterAssignment[]>>('/printers/assignments'),
  assign: (clientCode: string | null, docType: PrintDocType, printerId: number) =>
    apiClient.put<ApiResponse<PrinterAssignment>>('/printers/assignments', { clientCode, docType, printerId }),
  unassign: (id: number) => apiClient.delete<ApiResponse<void>>(`/printers/assignments/${id}`),

  // PR-Printer-R9.5a — rolling test-print log. Feeds the FE
  // PrinterDetailsPanel > Test history section. Default backend
  // limit is 10; capped at 100.
  listPrinterTestHistory: (id: number, limit?: number) =>
    apiClient.get<ApiResponse<PrinterTestHistoryEntry[]>>(
      `/printers/${id}/test-history${limit != null ? `?limit=${limit}` : ''}`,
    ),

  // PR-Printer-R11 — queue depth poll: our in-flight send() count
  // (portable) + IPP Get-Jobs count (IPP printers only). Feeds the
  // FE PrinterDetailsPanel > Queue depth section with 5s auto-refresh.
  getPrinterQueueDepth: (id: number) =>
    apiClient.get<ApiResponse<PrinterQueueDepth>>(`/printers/${id}/queue-depth`),

  // PR-Printer-R8a — free-form tags per printer (M2M). replacePrinterTags
  // is bulk (send the full desired set on save; backend diffs). Distinct
  // list feeds the FE autocomplete on the editor input.
  listPrinterTags: (id: number) =>
    apiClient.get<ApiResponse<PrinterTag[]>>(`/printers/${id}/tags`),
  replacePrinterTags: (id: number, tags: string[]) =>
    apiClient.put<ApiResponse<PrinterTag[]>>(`/printers/${id}/tags`, { tags }),
  listDistinctPrinterTags: () =>
    apiClient.get<ApiResponse<string[]>>('/printers/tags/distinct'),

  // PR-Printer-R7a — per (client, carrier) commercial-invoice copies.
  // clientCode=null on upsert = tenant-wide default row for the carrier.
  // Fallback chain at print time: (client, carrier) → (null, carrier) → 1.
  listInvoiceCopies: () =>
    apiClient.get<ApiResponse<InvoiceCopiesRule[]>>('/invoice-copies'),
  upsertInvoiceCopies: (clientCode: string | null, carrierCode: string, copies: number) =>
    apiClient.put<ApiResponse<InvoiceCopiesRule>>('/invoice-copies', {
      clientCode: clientCode ?? null, carrierCode, copies,
    }),
  deleteInvoiceCopiesTenantDefault: (carrierCode: string) =>
    apiClient.delete<ApiResponse<void>>(`/invoice-copies/${encodeURIComponent(carrierCode)}`),
  deleteInvoiceCopiesClientRule: (carrierCode: string, clientCode: string) =>
    apiClient.delete<ApiResponse<void>>(
      `/invoice-copies/${encodeURIComponent(carrierCode)}/clients/${encodeURIComponent(clientCode)}`,
    ),

  /** Each order to its client's printer (or printerId for all). */
  sendToPrinter: (orderNumbers: number[], docType: PrintDocType, printerId?: number | null) =>
    apiClient.post<ApiResponse<SendToPrinterResult>>('/orders/documents/send-to-printer', {
      orderNumbers,
      docType,
      printerId: printerId ?? null,
    }),

  // PR-Printer-P1.6 — LAN scan agent (admin surface only; the agent
  // endpoints /printer-scan-agents/poll + /printers/discovered use the
  // X-Printer-Scan-Key header from the Docker agent itself, never from
  // the SPA). See docs/printer-auto-detect-design.md.
  enrollScanAgent: (tenantCode: string, agentId: string, hostname?: string | null) =>
    apiClient.post<ApiResponse<PrinterScanAgentEnrollResponse>>(
      `/tenants/${encodeURIComponent(tenantCode)}/printer-scan-agents`,
      { agentId, hostname: hostname ?? null },
    ),
  listScanAgents: (tenantCode: string) =>
    apiClient.get<ApiResponse<PrinterScanAgent[]>>(
      `/tenants/${encodeURIComponent(tenantCode)}/printer-scan-agents`,
    ),
  // PR-Printer-P4b — revoke a specific enrollment. Idempotent server-side;
  // the agent's next long-poll returns 401 within ~5s. Re-enrolling with
  // the same agentId reuses the row and issues a fresh key.
  revokeScanAgent: (tenantCode: string, id: number) =>
    apiClient.delete<ApiResponse<{ revoked: boolean }>>(
      `/tenants/${encodeURIComponent(tenantCode)}/printer-scan-agents/${id}`,
    ),
  // PR-Printer-R1 — reverse of revoke. Reactivates a previously-revoked
  // enrollment WITHOUT rotating its api_key_hash — the customer's agent
  // resumes on its next poll using the original key. USE ONLY for
  // accidental revokes; for a leaked key, re-enroll instead.
  unrevokeScanAgent: (tenantCode: string, id: number) =>
    apiClient.patch<ApiResponse<{ reactivated: boolean }>>(
      `/tenants/${encodeURIComponent(tenantCode)}/printer-scan-agents/${id}/reactivate`,
      {},
    ),
  listRevokedScanAgents: (tenantCode: string) =>
    apiClient.get<ApiResponse<PrinterScanAgent[]>>(
      `/tenants/${encodeURIComponent(tenantCode)}/printer-scan-agents/revoked`,
    ),
  scanNow: (tenantCode: string) =>
    apiClient.post<ApiResponse<{ agentsNudged: number }>>(
      `/tenants/${encodeURIComponent(tenantCode)}/printers/scan-now`,
      {},
    ),
  latestDiscovered: (tenantCode: string) =>
    apiClient.get<ApiResponse<PrinterDiscovered[]>>(
      `/tenants/${encodeURIComponent(tenantCode)}/printers/discovered/latest`,
    ),
}

// ----- PR-Printer-P1.6 — scan agent types -----
export interface PrinterScanAgent {
  id: number
  tenantCode: string
  agentId: string
  hostname: string | null
  enrolledAt: string
  enrolledBy: string | null
  lastSeenAt: string | null
  scanRequestedAt: string | null
  active: boolean
  revokedAt: string | null
}

export interface PrinterScanAgentEnrollResponse {
  agentRowId: number
  /** Shown to admin ONCE. Never re-fetchable. Paste into the Docker
   *  agent's MULTISHIP_AGENT_KEY env; lost = rotate via revoke + re-enroll. */
  rawKey: string
}

export interface PrinterQueueDepth {
  /** Our in-JVM concurrent send() count for this printer. */
  inFlight: number
  /** Printer's own IPP Get-Jobs count. null for RAW_9100 (protocol
   *  has no queue-status) or when the IPP poll failed. */
  ippQueue: number | null
  /** Reason ippQueue is null: "Not supported for RAW_9100 printers." /
   *  network error / IPP refusal / etc. Null when ippQueue is a real
   *  number. */
  ippQueueError: string | null
}

export interface PrinterTestHistoryEntry {
  id: number
  printerId: number
  /** ISO-8601. */
  testedAt: string
  ok: boolean
  /** Long carrier / IPP / raw-9100 responses fit here (TEXT column). */
  message: string | null
  /** JWT username who clicked Test; null for scheduled tests. */
  testedBy: string | null
}

export interface PrinterTag {
  id: number
  printerId: number
  /** Backend stores lowercased for stable dedupe. FE may title-case on display. */
  tag: string
  createdAt: string
}

export interface InvoiceCopiesRule {
  id: number
  /** null = tenant-wide default row for this carrier. */
  clientCode: string | null
  carrierCode: string
  copies: number
  createdAt: string
  updatedAt: string
}

export interface PrinterDiscovered {
  id: number
  tenantCode: string
  agentId: string
  host: string
  port: number
  name: string | null
  location: string | null
  connectionGuess: PrinterConnection | null
  formatGuess: PrinterFormat | null
  paperGuess: PrinterPaper | null
  queuePath: string | null
  rawTxt: string | null
  discoveredAt: string
  scanSeq: number
}

/**
 * Printer problems are shown with a printer-specific title and the server's own
 * reason (the generic API error titles talk about shipments). They dismiss
 * themselves: the page also shows the outcome inline, so a sticky stack of
 * toasts would only cover the controls.
 */
export function notifyPrinterProblem(title: string, err: unknown, fallback: string) {
  const body = err instanceof Error && err.message ? err.message : fallback
  return notify.error({ title, body, durationMs: 10_000 })
}

export const CONNECTION_LABEL: Record<PrinterConnection, string> = {
  RAW_9100: 'Network port (9100)',
  IPP: 'IPP',
}

export const PAPER_LABEL: Record<PrinterPaper, string> = {
  LABEL_4X6: '4×6 label',
  A4: 'A4',
  LETTER: 'Letter',
}
