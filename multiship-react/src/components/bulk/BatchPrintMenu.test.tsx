import { describe, it, expect, vi, afterEach } from 'vitest'
import { render, screen, cleanup } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { OrderImportRow } from '../../api/orderImportService'
import BatchPrintMenu from './BatchPrintMenu'

const row = (n: number, orderNo: number | null, status: string | null, countryCode = 'US') =>
  ({ rowNumber: n, generatedOrderNo: orderNo, generatedStatus: status, countryCode, errors: [] } as unknown as OrderImportRow)

const open = async (rows: OrderImportRow[]) => {
  const onPrint = vi.fn()
  const onSend = vi.fn()
  render(<BatchPrintMenu scope="batch #128" busy={false} loadRows={() => Promise.resolve(rows)} onPrint={onPrint} onSend={onSend} />)
  await userEvent.click(screen.getByRole('button', { name: 'Print batch' }))
  return { onPrint, onSend }
}
afterEach(cleanup)

describe('BatchPrintMenu', () => {
  it('a domestic batch: labels and send to printer, no invoices', async () => {
    const { onPrint, onSend } = await open([row(1, 906985, 'GENERATED'), row(2, 906986, 'GENERATED'), row(3, null, null)])
    expect(await screen.findByRole('menuitem', { name: /Download labels/ })).toHaveTextContent('2 labels')
    expect(screen.queryByRole('menuitem', { name: /Download invoices/ })).toBeNull()
    await userEvent.click(screen.getByRole('menuitem', { name: /Download labels/ }))
    expect(onPrint).toHaveBeenCalledWith([906985, 906986], 'LABEL')
    await userEvent.click(screen.getByRole('button', { name: 'Print batch' }))
    await userEvent.click(await screen.findByRole('menuitem', { name: /Send to printer/ }))
    expect(onSend).toHaveBeenCalledWith([906985, 906986])
  })

  it('invoices only for the live international orders', async () => {
    const { onPrint } = await open([row(1, 7001, 'GENERATED', 'DE'), row(2, 7001, 'GENERATED', 'DE'), row(3, 7002, 'GENERATED'),
      row(4, 7003, 'VOIDED', 'FR'), row(5, 7004, 'GENERATED', 'FR')])
    const invoices = await screen.findByRole('menuitem', { name: /Download invoices/ })
    expect(invoices).toHaveTextContent('2 international orders')
    await userEvent.click(invoices)
    expect(onPrint).toHaveBeenCalledWith([7001, 7004], 'COMMERCIAL_INVOICE')
  })

  it('says so when nothing in the batch has a live label', async () => {
    await open([row(1, 7003, 'VOIDED'), row(2, null, null)])
    expect(await screen.findByText('No live labels in batch #128.')).toBeInTheDocument()
    expect(screen.queryByRole('menuitem')).toBeNull()
  })
})
