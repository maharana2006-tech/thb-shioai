import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import ValidationQuote from './ValidationQuote'

describe('ValidationQuote', () => {
  it('shows the client price, the carrier cost, transit and billed weight', () => {
    render(<ValidationQuote quote={{
      status: 'QUOTED', carrierCode: 'FEDEX', serviceCode: 'FEDEX_GROUND', serviceName: 'FedEx Ground',
      carrierAmount: 12.4, amount: 14.26, currency: 'USD', markup: 'PERCENT 15', transitDays: 4,
      billableWeight: 21.76, actualWeight: 5, weightUnit: 'LB', dimensional: true,
      otherServices: ['FEDEX_2_DAY — USD 30.10 · 2 days'],
    }} />)
    expect(screen.getByText(/USD 14\.26/)).toBeInTheDocument()
    expect(screen.getByText(/carrier USD 12\.40 \+ markup percent 15/)).toBeInTheDocument()
    expect(screen.getByText(/4 business days/)).toBeInTheDocument()
    expect(screen.getByText(/charged on box size, not the 5 lb it weighs/)).toBeInTheDocument()
    expect(screen.getByText(/Other FEDEX services on this lane \(1\)/)).toBeInTheDocument()
  })

  it('explains why there is no price and still shows the billed weight', () => {
    render(<ValidationQuote quote={{
      status: 'WEIGHT_ONLY', billableWeight: 2, actualWeight: 2, weightUnit: 'LB', dimensional: false,
      message: 'No price: FEDEX refused the account\'s credentials.',
    }} />)
    expect(screen.queryByText('Price')).toBeNull()
    expect(screen.getByText(/refused the account's credentials/)).toBeInTheDocument()
    expect(screen.getByText('2 lb')).toBeInTheDocument()
  })
})
