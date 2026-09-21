import { describe, expect, it, vi } from 'vitest'
import { screen, fireEvent, waitFor } from '@testing-library/react'
import { renderWithProviders } from '../test/renderWithProviders'

/**
 * The address-book search in Ship to. Picking an entry must REPLACE the whole
 * recipient: it used to merge, so picking Jane then Marco gave Marco Jane's
 * email, her "Suite 400" and her state — the wrong contact on a real label.
 */
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


const inputsHolding = (value: string) =>
  Array.from(document.querySelectorAll('input')).filter((i) => (i as HTMLInputElement).value === value)

async function pick(name: RegExp, box: HTMLElement) {
  fireEvent.change(box, { target: { value: 'zz' } })
  const option = await screen.findByRole('option', { name }, { timeout: 2000 })
  fireEvent.mouseDown(option)
}

describe('NewShipmentPage — address book', () => {
  it('picking a second person clears what the first one left behind', async () => {
    const { default: NewShipmentPage } = await import('./NewShipmentPage')
    renderWithProviders(<NewShipmentPage />)
    const box = await screen.findByRole('combobox', { name: /search the address book/i })

    await pick(/ZZ Jane Carter/, box)
    await waitFor(() => expect(inputsHolding('jane@example.com')).toHaveLength(1))
    expect(inputsHolding('Suite 400')).toHaveLength(1)

    await pick(/ZZ Marco Rossi/, box)
    await waitFor(() => expect(inputsHolding('Via Montenapoleone 8')).toHaveLength(1))
    expect(inputsHolding('jane@example.com'), 'Jane\'s email is gone').toHaveLength(0)
    expect(inputsHolding('Suite 400'), 'her suite is gone').toHaveLength(0)
    expect(inputsHolding('Carter Supply'), 'her company is gone').toHaveLength(0)
    expect(inputsHolding('IL'), 'her state is gone').toHaveLength(0)
  })

  it('arrow keys and Enter pick a suggestion; Escape closes the list', async () => {
    const { default: NewShipmentPage } = await import('./NewShipmentPage')
    renderWithProviders(<NewShipmentPage />)
    const box = await screen.findByRole('combobox', { name: /search the address book/i })

    fireEvent.change(box, { target: { value: 'zz' } })
    // Wait for the address list itself — the page's <select>s hold plenty of <option>s.
    await screen.findByRole('listbox', { name: /saved addresses/i }, { timeout: 2000 })
    fireEvent.keyDown(box, { key: 'Escape' })
    await waitFor(() => expect(screen.queryByRole('listbox', { name: /saved addresses/i })).toBeNull())

    fireEvent.keyDown(box, { key: 'ArrowDown' })   // reopens on the first suggestion
    await waitFor(() => expect(box.getAttribute('aria-activedescendant')).toBe('recipient-book-0'))
    fireEvent.keyDown(box, { key: 'Enter' })
    await waitFor(() => expect(inputsHolding('Carter Supply')).toHaveLength(1))
  })
})
