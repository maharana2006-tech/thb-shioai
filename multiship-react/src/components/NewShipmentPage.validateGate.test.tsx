import { describe, expect, it, vi } from 'vitest'
import { screen } from '@testing-library/react'
import { renderWithProviders } from '../test/renderWithProviders'

/** Generate label buys a label; it stays locked until the form has passed Validate. */
vi.mock('../api/accountRefService', () => ({
  accountRefService: {
    listAccounts: vi.fn().mockResolvedValue({ data: [] }),
    getPlatformCredentials: vi.fn().mockResolvedValue({ data: { found: false } }),
  },
}))
vi.mock('../api/clientService', () => ({
  clientService: {
    list: vi.fn().mockResolvedValue({ data: [] }),
  },
}))
vi.mock('../api/customsProfileService', () => ({
  customsProfileService: {
    list: vi.fn().mockResolvedValue({ data: [] }),
  },
}))
vi.mock('../api/shippingConfigService', () => ({
  shippingConfigService: {
    listShippingServices: vi.fn().mockResolvedValue({ data: [] }),
    listPackages: vi.fn().mockResolvedValue({ data: [] }),
    listCarriers: vi.fn().mockResolvedValue({ data: [] }),
  },
}))
vi.mock('../api/addressService', () => ({
  addressService: { validate: vi.fn().mockResolvedValue({ data: null }) },
}))
vi.mock('../api/addressValidationService', () => ({
  addressValidationService: { validate: vi.fn().mockResolvedValue({ data: null }) },
}))
vi.mock('../api/recipientBookService', () => ({
  recipientBookService: {
    search: vi.fn().mockResolvedValue([
      { id: 1, name: 'ZZ Jane Carter', company: 'Carter Supply', phone: '3125550101', email: 'jane@example.com',
        addressLine1: '233 S Wacker Dr', addressLine2: 'Suite 400', city: 'Chicago', state: 'IL',
        postalCode: '60606', countryCode: 'US', residential: false, tag: 'wholesale' },
      { id: 2, name: 'ZZ Marco Rossi', company: null, phone: '0212345678', email: null,
        addressLine1: 'Via Montenapoleone 8', addressLine2: null, city: 'Milano', state: null,
        postalCode: '20121', countryCode: 'IT', residential: true, tag: 'EU' },
    ]),
    list: vi.fn().mockResolvedValue({ data: [] }),
  },
}))
vi.mock('../api/warehouseService', () => ({
  clientWarehouseService: {
    list: vi.fn().mockResolvedValue({ data: [] }),
  },
}))
vi.mock('../api/aiService', () => ({
  aiService: {
    reviewShipment: vi.fn().mockResolvedValue({ data: null }),
  },
}))

describe('NewShipmentPage — Generate label waits for Validate', () => {
  it('is locked, and says why, before any check has run', async () => {
    const { default: NewShipmentPage } = await import('./NewShipmentPage')
    renderWithProviders(<NewShipmentPage />)
    const generate = await screen.findByRole('button', { name: /generate label/i })
    expect(generate).toBeDisabled()
    expect(generate).toHaveAttribute('title', 'Validate the shipment first. Generate label unlocks after a successful check.')
    expect(screen.getByText(/Generate label unlocks after a successful check/)).toBeInTheDocument()
  })
})
