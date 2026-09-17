import { apiClient } from './apiClient'
import type { ApiResponse } from './orderService'

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

  /** Each order to its client's printer (or printerId for all). */
  sendToPrinter: (orderNumbers: number[], docType: PrintDocType, printerId?: number | null) =>
    apiClient.post<ApiResponse<SendToPrinterResult>>('/orders/documents/send-to-printer', {
      orderNumbers,
      docType,
      printerId: printerId ?? null,
    }),
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
