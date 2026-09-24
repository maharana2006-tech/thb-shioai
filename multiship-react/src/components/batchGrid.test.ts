import { describe, it, expect } from 'vitest'
import { fieldLabel, rowStatus } from './batchGrid'

describe('rowStatus — the Orders-page pill for an import row', () => {
  it('maps every generation state', () => {
    expect(rowStatus({ generatedStatus: 'GENERATED' }, true).short).toBe('GEN')
    expect(rowStatus({ generatedStatus: 'VOIDED' }, true).short).toBe('VOID')
    expect(rowStatus({ generatedStatus: 'QUEUED_USPS' }, true).short).toBe('QUEUED')
    expect(rowStatus({ generatedStatus: 'FAILED', generatedMessage: 'UPS rejected' }, true)).toMatchObject({ short: 'ERR', label: 'UPS rejected' })
    expect(rowStatus({ errors: ['weight missing'] }, true)).toMatchObject({ short: 'ERR', label: '1 error — fix the red cells' })
    expect(rowStatus({ errors: [], orderRef: 'INTL-02-DE' }, false)).toMatchObject({ short: 'FIX' })
    expect(rowStatus({ errors: [] }, true).short).toBe('PEND')
  })
})

describe('fieldLabel', () => {
  it('keeps a column’s own label and spells out the rest', () => {
    expect(fieldLabel({ key: 'weight', label: 'Weight' })).toBe('Weight')
    expect(fieldLabel({ key: 'recipientPhone' })).toBe('Recipient phone')
    expect(fieldLabel({ key: 'postalCode' })).toBe('Postal code')
  })
})
