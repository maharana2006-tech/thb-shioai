import { describe, it, expect } from 'vitest'
import { fieldLabel, messageUnder, readableError, rowStatus, shipViaHint, shownValue } from './batchGrid'

describe('rowStatus — the Orders-page pill for an import row', () => {
  it('maps every generation state', () => {
    expect(rowStatus({ generatedStatus: 'GENERATED' }, true).short).toBe('GEN')
    expect(rowStatus({ generatedStatus: 'VOIDED' }, true).short).toBe('VOID')
    expect(rowStatus({ generatedStatus: 'QUEUED_USPS' }, true).short).toBe('QUEUED')
    expect(rowStatus({ generatedStatus: 'FAILED', generatedMessage: 'UPS rejected' }, true)).toMatchObject({ short: 'ERR', label: 'UPS rejected' })
    expect(rowStatus({ errors: ['weight missing'] }, true)).toMatchObject({ short: 'ERR', label: '1 error in Weight' })
    expect(rowStatus({ errors: ['recipientName is required', 'postalCode 60606 is in IL, not CA'] }, true).label)
      .toBe('2 errors in Recipient, Postal code')
    expect(rowStatus({ errors: ['every row of one order must agree'] }, true).label).toBe('1 error — see the ⓘ for details')
    expect(rowStatus({ errors: [], orderRef: 'INTL-02-DE' }, false)).toMatchObject({ short: 'FIX' })
    expect(rowStatus({ errors: [] }, true).short).toBe('PEND')
  })
})

describe('readableError', () => {
  it('shows field names as their labels, keeping plain words mid-sentence', () => {
    expect(readableError('recipientName is required')).toBe('Recipient is required')
    expect(readableError('serviceType — UPS doesn\'t offer UPS Ground (U11) to this address')).toBe('Service — UPS doesn\'t offer UPS Ground (U11) to this address')
    expect(readableError('addressLine1 is a PO Box')).toBe('Address line 1 is a PO Box')
    expect(readableError('state — UPS doesn\'t accept this state or province')).toBe('State — UPS doesn\'t accept this state or province')
    expect(readableError('itemUnitValue must be > 0 when itemQuantity is set')).toBe('Item unit value must be > 0 when Item quantity is set')
  })
})

describe('messageUnder', () => {
  it('drops the field name the tag already shows, and trademark signs', () => {
    expect(messageUnder('serviceType', "serviceType — UPS Ground (U11) doesn't deliver to HI. Use U43 (UPS 2nd Day Air®) instead"))
      .toBe("UPS Ground (U11) doesn't deliver to HI. Use U43 (UPS 2nd Day Air) instead")
    expect(messageUnder('city', 'city is required')).toBe('City is required')
  })
})

describe('shownValue', () => {
  it('shows known codes in words and leaves the rest', () => {
    expect(shownValue('YOUR_PACKAGING')).toBe('Your packaging')
    expect(shownValue('02')).toBe('02')
  })
})

describe('shipViaHint', () => {
  it('names the client code and the service, not the carrier code', () => {
    expect(shipViaHint('U11 maps to UPS Ground (UPS 03)')).toBe('U11 = UPS Ground')
    expect(shipViaHint('U43 maps to UPS 2nd Day Air® (UPS 02)')).toBe('U43 = UPS 2nd Day Air')
    expect(shipViaHint(null)).toBeNull()
  })
})

describe('fieldLabel', () => {
  it('keeps a column’s own label and spells out the rest', () => {
    expect(fieldLabel({ key: 'weight', label: 'Weight' })).toBe('Weight')
    expect(fieldLabel({ key: 'recipientPhone' })).toBe('Recipient phone')
    expect(fieldLabel({ key: 'postalCode' })).toBe('Postal code')
  })
})
