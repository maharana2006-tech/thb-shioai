import { fireEvent, render, screen, within } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import type { ShipmentValidationResult } from '../api/shipmentValidationService'
import { buildCheckGroups, checklistHeadline } from '../utils/validationChecklist'
import ValidationChecklist from './ValidationChecklist'

const result = (over: Partial<ShipmentValidationResult> = {}): ShipmentValidationResult => ({
  overall: 'FAIL',
  message: '3 issues must be fixed before this shipment can be generated.',
  localErrors: [
    { code: 'VALIDATION_ERROR', message: "FEDEX can't deliver to a PO box (\"PO Box 118\").", field: 'recipient.addressLine1' },
    { code: 'VALIDATION_ERROR', message: 'Box 2: 180 lb exceeds FEDEX FEDEX_GROUND parcel weight limit of 150 lb.', field: 'packagePresetId' },
    { code: 'VALIDATION_ERROR', message: "Couldn't sign in to FedEx with this account's login keys.", field: 'carrier' },
  ],
  localWarnings: [
    { code: 'VALIDATION_ERROR', message: 'Reference PO-7781 already shipped on order #905958.', field: 'reference' },
  ],
  skipped: [{ name: 'customs', reason: 'domestic shipment (sender/recipient in same territory)' }],
  address: null,
  carrier: { carrierCode: 'FEDEX', valid: false, matchLevel: 'ERROR', kind: 'SHIPMENT', warnings: [],
    errors: ['Token acquisition failed: FedEx rejected the credentials (NOT.AUTHORIZED.ERROR)'],
    message: "Couldn't sign in to FedEx with this account's login keys." },
  international: false,
  quote: { status: 'WEIGHT_ONLY', billableWeight: 3.45, actualWeight: 2, weightUnit: 'LB', dimensional: true, message: 'No price.' },
  ...over,
})

describe('buildCheckGroups', () => {
  it('sorts each issue into its area and moves carrier errors to the carrier row', () => {
    const g = Object.fromEntries(buildCheckGroups(result()).map((x) => [x.key, x]))
    expect(g.addresses.status).toBe('fail')
    expect(g.package.errors[0]).toMatch(/^Box 2/)
    expect(g.service.status).toBe('warn')
    expect(g.service.errors).toEqual([])
    // One problem, one line — the raw carrier reply is a detail, not a second error.
    expect(g.carrier.errors).toEqual(["Couldn't sign in to FedEx with this account's login keys."])
    expect(g.carrier.details).toEqual(['Token acquisition failed: FedEx rejected the credentials (NOT.AUTHORIZED.ERROR)'])
    expect(checklistHeadline(Object.values(g))).toBe('3 issues to fix before the label can be bought · 1 to review')
    expect(g.customs.status).toBe('skipped')
  })

  it('puts customs codes under customs even without a field', () => {
    const g = buildCheckGroups(result({
      localErrors: [{ code: 'customs.commodities.empty', message: 'At least one commodity line is required.', field: null }],
      localWarnings: [], carrier: null, international: true,
    }))
    expect(g.find((x) => x.key === 'customs')!.status).toBe('fail')
    expect(g.find((x) => x.key === 'carrier')!.status).toBe('skipped')
  })
})

describe('ValidationChecklist', () => {
  it('opens the rows with something to fix, marks a stale check, and re-runs', () => {
    const onRevalidate = vi.fn()
    render(<ValidationChecklist result={result()} checkedAt={new Date()} stale busy={false}
      onRevalidate={onRevalidate} onClose={() => {}} />)
    const addresses = screen.getByTestId('check-addresses')
    expect(within(addresses).getByText('1 to fix')).toBeInTheDocument()
    expect(within(addresses).getByText(/can't deliver to a PO box/)).toBeInTheDocument()
    expect(within(screen.getByTestId('check-service')).getByText('1 to review')).toBeInTheDocument()
    expect(screen.getByText(/The form changed — check again/)).toBeInTheDocument()
    // The customs row is closed until opened, then explains why it wasn't checked.
    const customs = screen.getByTestId('check-customs')
    expect(within(customs).queryByText(/domestic shipment/)).toBeNull()
    fireEvent.click(within(customs).getByRole('button'))
    expect(within(customs).getByText(/Skipped — Customs: domestic shipment/)).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: /check again/i }))
    expect(onRevalidate).toHaveBeenCalled()
  })

  it('shows the raw carrier reply only on request, and minimises to one line', () => {
    render(<ValidationChecklist result={result()} checkedAt={null} stale={false} busy={false}
      onRevalidate={() => {}} onClose={() => {}} />)
    const carrierRow = screen.getByTestId('check-carrier')
    expect(within(carrierRow).getByText('1 to fix')).toBeInTheDocument()
    expect(within(carrierRow).queryByText(/NOT.AUTHORIZED/)).toBeNull()
    fireEvent.click(within(carrierRow).getByRole('button', { name: /details for an admin/i }))
    expect(within(carrierRow).getByText(/NOT.AUTHORIZED/)).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Minimise' }))
    expect(screen.queryByTestId('check-carrier')).toBeNull()
    expect(screen.getByText(/3 to fix · 1 to review — the rows are hidden while you edit/)).toBeInTheDocument()
  })
})
