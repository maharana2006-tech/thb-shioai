import { describe, expect, it, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import CustomsWizard from './CustomsWizard'
import type { OrderCustomsPayload } from '../../api/customsService'

/**
 * US Export EEI gating on /orders/new — verifies the FTR §30.37 / AES ITN
 * picker only shows on the US-origin non-Canada route and that the
 * high-value banner fires above the $2,500 threshold when neither field
 * is populated. Companion of IntlShipmentValidatorTest.eei*() on the
 * backend — same rule, different layer.
 */

function baseValue(overrides: Partial<OrderCustomsPayload> = {}): OrderCustomsPayload {
  return {
    incoterms: 'DDP',
    reasonForExport: 'SALE',
    currency: 'USD',
    weightUnit: 'LB',
    items: [
      {
        description: 'Widget',
        hsCode: '6104.62.20',
        countryOfOrigin: 'US',
        quantity: 1,
        unitValue: 3000,
        sku: 'SKU-1',
      },
    ],
    ...overrides,
  }
}

function renderAtDutiesStep(props: {
  originCountry: string | null
  destinationCountry: string | null
  value?: Partial<OrderCustomsPayload>
}) {
  const onChange = vi.fn()
  render(
    <CustomsWizard
      carrierCode="FEDEX"
      originCountry={props.originCountry}
      destinationCountry={props.destinationCountry}
      value={baseValue(props.value)}
      onChange={onChange}
      onComplete={vi.fn()}
      onCancel={vi.fn()}
    />,
  )
  // Wizard opens on 'shipment-type' — advance twice via Next to reach the
  // 'duties' step. Forward navigation is gated by canProceed(), which the
  // seeded valid commodity + defaulted currency both pass.
  fireEvent.click(screen.getByRole('button', { name: /^Next/i }))
  fireEvent.click(screen.getByRole('button', { name: /^Next/i }))
  return onChange
}

describe('CustomsWizard — US Export EEI section', () => {
  it('does not render EEI inputs on non-US origin', () => {
    renderAtDutiesStep({ originCountry: 'GB', destinationCountry: 'DE' })
    expect(screen.queryByText(/Electronic Export Information/i)).not.toBeInTheDocument()
  })

  it('does not render EEI inputs on US→CA (§30.36 bilateral)', () => {
    renderAtDutiesStep({ originCountry: 'US', destinationCountry: 'CA' })
    expect(screen.queryByText(/Electronic Export Information/i)).not.toBeInTheDocument()
  })

  it('renders EEI inputs on US→DE', () => {
    renderAtDutiesStep({ originCountry: 'US', destinationCountry: 'DE' })
    expect(screen.getByText(/Electronic Export Information/i)).toBeInTheDocument()
    expect(screen.getByPlaceholderText(/X20260101123456/)).toBeInTheDocument()
  })

  it('shows high-value banner at ≥ $2,500 USD without FTR or AES', () => {
    renderAtDutiesStep({
      originCountry: 'US',
      destinationCountry: 'DE',
      value: { items: [{ description: 'W', quantity: 1, unitValue: 3000, hsCode: '', countryOfOrigin: 'US' }] },
    })
    expect(screen.getByText(/EEI required for this value/i)).toBeInTheDocument()
  })

  it('hides the high-value banner once an FTR exemption is picked', () => {
    renderAtDutiesStep({
      originCountry: 'US',
      destinationCountry: 'DE',
      value: {
        ftrExemption: 'NO_EEI_30_37_h',
        items: [{ description: 'W', quantity: 1, unitValue: 3000, hsCode: '', countryOfOrigin: 'US' }],
      },
    })
    expect(screen.queryByText(/EEI required for this value/i)).not.toBeInTheDocument()
  })

  it('hides the high-value banner once an AES citation is entered', () => {
    renderAtDutiesStep({
      originCountry: 'US',
      destinationCountry: 'DE',
      value: {
        aesCitation: 'X20260101123456',
        items: [{ description: 'W', quantity: 1, unitValue: 3000, hsCode: '', countryOfOrigin: 'US' }],
      },
    })
    expect(screen.queryByText(/EEI required for this value/i)).not.toBeInTheDocument()
  })

  it('does not show the high-value banner under $2,500', () => {
    renderAtDutiesStep({
      originCountry: 'US',
      destinationCountry: 'DE',
      value: { items: [{ description: 'W', quantity: 1, unitValue: 100, hsCode: '', countryOfOrigin: 'US' }] },
    })
    expect(screen.queryByText(/EEI required for this value/i)).not.toBeInTheDocument()
  })
})

describe('CustomsWizard — generic export declaration reference', () => {
  it('renders the export-declaration input on any intl duties step (non-US too)', () => {
    renderAtDutiesStep({ originCountry: 'GB', destinationCountry: 'DE' })
    // Placeholder is unique per-input; more stable than getByLabelText
    // when labels aren't htmlFor-associated.
    expect(screen.getByPlaceholderText(/Reference issued by the origin/i)).toBeInTheDocument()
  })

  it('shows the advisory banner when high-value intl has no ref at all', () => {
    renderAtDutiesStep({
      originCountry: 'GB',
      destinationCountry: 'DE',
      value: { items: [{ description: 'W', quantity: 1, unitValue: 3000, hsCode: '', countryOfOrigin: 'GB' }] },
    })
    expect(screen.getByText(/Most origin countries require an export declaration/i)).toBeInTheDocument()
  })

  it('suppresses the advisory when exportDeclarationReference is populated', () => {
    renderAtDutiesStep({
      originCountry: 'GB',
      destinationCountry: 'DE',
      value: {
        exportDeclarationReference: 'GB-CDS-2026-99999',
        items: [{ description: 'W', quantity: 1, unitValue: 3000, hsCode: '', countryOfOrigin: 'GB' }],
      },
    })
    expect(screen.queryByText(/Most origin countries require an export declaration/i)).not.toBeInTheDocument()
  })
})
