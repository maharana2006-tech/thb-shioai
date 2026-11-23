import { describe, expect, it } from 'vitest'
import type { ManualShipmentAddress } from '../../api/orderService'
import { normalizeParsedAddress } from './parsedAddress'

const blank: ManualShipmentAddress = {
  name: '', addressLine1: '', city: '', postalCode: '', countryCode: '',
}

describe('normalizeParsedAddress', () => {
  it('maps country names and state labels to the codes the form uses', () => {
    const { patch, filled } = normalizeParsedAddress(
      { name: 'Jane Doe', countryCode: 'United States', state: 'New York', city: 'Buffalo' },
      blank,
    )
    expect(patch).toMatchObject({ countryCode: 'US', state: 'NY', phoneCountryCode: '1' })
    expect(filled).toBe(4)
  })

  it('strips the dial code from the phone so it is not sent twice', () => {
    const { patch } = normalizeParsedAddress({ phone: '+1 (212) 555-0100', countryCode: 'USA' }, blank)
    expect(patch.phone).toBe('2125550100')
    expect(patch.countryCode).toBe('US')
  })

  it('keeps a short national number untouched and falls back to the form country', () => {
    const { patch } = normalizeParsedAddress({ phone: '020 7946 0958' }, { ...blank, countryCode: 'GB' })
    expect(patch.phone).toBe('02079460958')
    expect(patch.countryCode).toBeUndefined()
  })

  it('never writes blanks and counts replaced values', () => {
    const { patch, replaced } = normalizeParsedAddress(
      { name: 'New Name', company: '  ', city: 'Buffalo' },
      { ...blank, name: 'Old Name', city: 'Buffalo' },
    )
    expect(patch).toEqual({ name: 'New Name', city: 'Buffalo' })
    expect(replaced).toBe(1)
  })
})
