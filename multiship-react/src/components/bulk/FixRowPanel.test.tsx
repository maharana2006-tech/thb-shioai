import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, cleanup, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { OrderImportRow } from '../../api/orderImportService'

const shipViaCodes = vi.fn()
vi.mock('../../api/shippingConfigService', () => ({
  shippingConfigService: { shipViaCodes: (...a: unknown[]) => shipViaCodes(...a) },
}))

import FixRowPanel from './FixRowPanel'

/** bulk-10-mixed.csv's T10-02 and T10-08, as the batch page holds them after save. */
const row = (over: Partial<OrderImportRow>): OrderImportRow => ({
  rowNumber: 2, orderRef: 'T10-02', clientCode: 'DES875', recipientName: 'Liam Brown', recipientPhone: '2125551002',
  addressLine1: '102 Main Street', city: 'Chicago', state: 'IL', postalCode: '60601', countryCode: 'US',
  carrierCode: 'UPS', accountNumber: '746W05', serviceType: '03', weight: 3, weightUnit: 'LB', errors: [],
  ...over,
} as OrderImportRow)

beforeEach(() => {
  shipViaCodes.mockResolvedValue({ data: [
    { code: 'U11', clientCode: 'DES875', carrier: 'UPS', serviceCode: '03', serviceName: 'UPS Ground', enabled: true, destination: null },
    { code: 'U43', clientCode: 'DES875', carrier: 'UPS', serviceCode: '02', serviceName: 'UPS 2nd Day Air', enabled: true, destination: null },
    { code: 'OFF', clientCode: null, carrier: 'UPS', serviceCode: '12', serviceName: 'UPS 3 Day Select', enabled: false, destination: null },
  ] })
})
afterEach(cleanup)

describe('FixRowPanel', () => {
  it('lists the problem, offers the client’s mapped codes and saves only what changed', async () => {
    const onSave = vi.fn()
    const onMap = vi.fn()
    render(<FixRowPanel row={row({ errors: ["serviceType '03' is not mapped for DES875 — it is a UPS service code, but a file import must use a Shipping Service Mapping rule."] })}
      saving={false} canMap onSave={onSave} onMap={onMap} onClose={() => {}} />)

    expect(within(screen.getByRole('region', { name: 'Problems' })).getByText(/1 to fix/)).toBeInTheDocument()
    expect(shipViaCodes).toHaveBeenCalledWith('DES875')
    const service = await screen.findByRole('combobox', { name: 'Service' })
    // Only switched-on codes; the file's unmapped value stays visible as such.
    expect(within(service).getAllByRole('option').map((o) => o.textContent)).toEqual(['03 (not mapped)', 'U11 — UPS Ground', 'U43 — UPS 2nd Day Air'])

    await userEvent.click(screen.getByRole('button', { name: 'Map 03…' }))
    expect(onMap).toHaveBeenCalledWith('03')

    expect(screen.getByRole('button', { name: 'Save & re-check' })).toBeDisabled()
    await userEvent.selectOptions(service, 'U43')
    await userEvent.click(screen.getByRole('button', { name: 'Save & re-check' }))
    const sent = onSave.mock.calls[0][0] as OrderImportRow
    expect(sent.serviceType).toBe('U43')
    expect(sent.weight).toBe(3) // untouched fields keep their stored type
  })

  it('offers the real state list for a bad state and writes numbers back as numbers', async () => {
    const onSave = vi.fn()
    render(<FixRowPanel row={row({ rowNumber: 8, state: 'ZZ', weight: 0,
      errors: ["state 'ZZ' is not a valid US state/province code", 'weight must be > 0'] })}
      saving={false} canMap={false} onSave={onSave} onMap={() => {}} onClose={() => {}} />)

    expect(screen.getByText(/2 to fix/)).toBeInTheDocument()
    const state = screen.getByLabelText('State')
    expect(within(state).getAllByRole('option')[0]).toHaveTextContent('ZZ (not valid)')
    await userEvent.selectOptions(state, 'GA')
    const weight = screen.getByLabelText('Weight')
    await userEvent.clear(weight)
    await userEvent.type(weight, '3')
    await userEvent.click(screen.getByRole('button', { name: 'Save & re-check' }))
    const sent = onSave.mock.calls[0][0] as OrderImportRow
    expect(sent.state).toBe('GA')
    expect(sent.weight).toBe(3)
    expect(screen.queryByRole('button', { name: /Map/ })).toBeNull()
  })

  it('starts again from the row the server sends back, and closes on Escape', async () => {
    const onClose = vi.fn()
    const { rerender } = render(<FixRowPanel row={row({ recipientPhone: '', errors: ['recipientPhone is required'] })}
      saving={false} canMap onSave={() => {}} onMap={() => {}} onClose={onClose} />)
    await userEvent.type(screen.getByLabelText('Recipient phone'), '2125551004')
    rerender(<FixRowPanel row={row({ recipientPhone: '2125551004', errors: [] })}
      saving={false} canMap onSave={() => {}} onMap={() => {}} onClose={onClose} />)
    expect(screen.getByText('No errors on this row.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Save & re-check' })).toBeDisabled()
    await userEvent.keyboard('{Escape}')
    expect(onClose).toHaveBeenCalled()
  })
})
