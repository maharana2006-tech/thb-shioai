import { describe, expect, it } from 'vitest'
import { fieldLabelFor } from './fieldLabels'

describe('fieldLabelFor', () => {
  it('names fields the way the page labels them', () => {
    expect(fieldLabelFor('recipient.name')).toBe('Ship to name')
    expect(fieldLabelFor('sender.phone')).toBe('Ship from phone')
    expect(fieldLabelFor('items[1].hsCode')).toBe('Item 2 HS code')
    expect(fieldLabelFor('insuredValue')).toBe('Insured value')
    expect(fieldLabelFor('extraPackages[0].weight')).toBeNull()
  })

  // BUG-041 — in return mode the UI blocks are "Return from · customer"
  // and "Return to · your address"; the toast must match.
  it('names fields as "Return from / to" when isReturn is set', () => {
    expect(fieldLabelFor('sender.name', { isReturn: true })).toBe('Return from name')
    expect(fieldLabelFor('recipient.phone', { isReturn: true })).toBe('Return to phone')
    // Non-address fields unchanged — only sender/recipient flip.
    expect(fieldLabelFor('insuredValue', { isReturn: true })).toBe('Insured value')
    expect(fieldLabelFor('items[0].hsCode', { isReturn: true })).toBe('Item 1 HS code')
  })
})
