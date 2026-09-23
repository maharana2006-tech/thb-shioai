import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { OrderImportRow } from '../../api/orderImportService'

const printDocuments = vi.fn()
const voidBatchLabels = vi.fn()
const confirm = vi.fn()
const success = vi.fn()
const info = vi.fn()
vi.mock('../../api/orderService', () => ({ orderService: { printDocuments: (...a: unknown[]) => printDocuments(...a) } }))
vi.mock('../../api/orderImportService', () => ({ orderImportService: { voidBatchLabels: (...a: unknown[]) => voidBatchLabels(...a) } }))
vi.mock('../../utils/printPdf', () => ({ printPdfBlob: vi.fn() }))
vi.mock('../../utils/notify', () => ({
  notify: {
    confirm: (...a: unknown[]) => confirm(...a),
    success: (...a: unknown[]) => success(...a),
    info: (...a: unknown[]) => info(...a),
    error: vi.fn(),
    apiError: vi.fn(),
  },
}))
vi.mock('../workspace/SendToPrinterDialog', () => ({
  default: ({ orderNumbers }: { orderNumbers: number[] }) => <div data-testid="send-dialog">{orderNumbers.join(',')}</div>,
}))

import BatchLabelBar from './BatchLabelBar'

const row = (n: number, orderNo: number | null, status: string | null): OrderImportRow =>
  ({ rowNumber: n, generatedOrderNo: orderNo, generatedStatus: status, errors: [] } as unknown as OrderImportRow)

// Rows 1+2 are one order; 4 is voided; 5 is pending.
const rows = [row(1, 5001, 'GENERATED'), row(2, 5001, 'GENERATED'), row(3, 5002, 'GENERATED'), row(4, 5003, 'VOIDED'), row(5, null, null)]

const renderBar = (picked: number[] = [], extra: Partial<Parameters<typeof BatchLabelBar>[0]> = {}) => {
  const onChanged = vi.fn()
  const onClearPick = vi.fn()
  render(<BatchLabelBar batchId={121} rows={rows} picked={picked} onPickAllLive={vi.fn()} onClearPick={onClearPick}
    onChanged={onChanged} canWrite canManagePrinters locked={false} onOpenPrinterSettings={vi.fn()} {...extra} />)
  return { onChanged, onClearPick }
}

describe('BatchLabelBar', () => {
  beforeEach(() => { vi.clearAllMocks() })

  it('acts on every live label when nothing is ticked — each order once, voided ones left out', async () => {
    printDocuments.mockResolvedValue({ blob: new Blob(), included: 2, skipped: 0, skippedOrders: [] })
    renderBar()
    expect(screen.getByText(/live labels in this batch/)).toHaveTextContent('2 live labels in this batch')
    await userEvent.click(screen.getByRole('button', { name: /^Print$/ }))
    await userEvent.click(screen.getByRole('menuitem', { name: /Print labels/ }))
    expect(printDocuments).toHaveBeenCalledWith([5001, 5002], 'LABEL')
    await userEvent.click(screen.getByRole('button', { name: /^Print$/ }))
    await userEvent.click(screen.getByRole('menuitem', { name: /Send to printer/ }))
    expect(screen.getByTestId('send-dialog')).toHaveTextContent('5001,5002')
  })

  it('acts on the ticked rows only', async () => {
    printDocuments.mockResolvedValue({ blob: new Blob(), included: 1, skipped: 0, skippedOrders: [] })
    renderBar([3])
    await userEvent.click(screen.getByRole('button', { name: /^Print$/ }))
    await userEvent.click(screen.getByRole('menuitem', { name: /Print invoices/ }))
    expect(printDocuments).toHaveBeenCalledWith([5002], 'COMMERCIAL_INVOICE')
  })

  it('asks before voiding, then reports a refusal as a refusal', async () => {
    confirm.mockResolvedValue(true)
    voidBatchLabels.mockResolvedValue({ data: { voided: 1, refused: 1, orders: [
      { orderNo: 5001, rowNumbers: [1, 2], voided: true, message: 'Voided.' },
      { orderNo: 5002, rowNumbers: [3], voided: false, message: 'FedEx: already picked up.' },
    ] } })
    const { onChanged } = renderBar()
    await userEvent.click(screen.getByRole('button', { name: /Void all/ }))
    expect(confirm).toHaveBeenCalled()
    await waitFor(() => expect(voidBatchLabels).toHaveBeenCalledWith(121, []))
    expect(info).toHaveBeenCalledWith(expect.objectContaining({ title: '1 voided, 1 refused by the carrier' }))
    expect(onChanged).toHaveBeenCalled()
  })

  it('does not void when the confirmation is declined, and not at all while locked', async () => {
    confirm.mockResolvedValue(false)
    renderBar([1])
    await userEvent.click(screen.getByRole('button', { name: /Void 1/ }))
    expect(voidBatchLabels).not.toHaveBeenCalled()
  })

  it('offers no void while the batch is generating or in Trash', () => {
    renderBar([], { locked: true })
    expect(screen.getByRole('button', { name: /Void all/ })).toBeDisabled()
  })

  it('hides itself when the batch has no live labels', () => {
    render(<BatchLabelBar batchId={1} rows={[row(1, null, null), row(2, 7, 'VOIDED')]} picked={[]} onPickAllLive={vi.fn()}
      onClearPick={vi.fn()} onChanged={vi.fn()} canWrite canManagePrinters locked={false} onOpenPrinterSettings={vi.fn()} />)
    expect(screen.queryByTestId('batch-label-bar')).toBeNull()
  })
})
