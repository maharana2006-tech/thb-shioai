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
})
