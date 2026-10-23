import { describe, expect, it } from 'vitest'
import { fieldErrorsFromIssues } from './serverFieldErrors'

describe('fieldErrorsFromIssues', () => {
  it('maps bare block names to the block’s first field and keeps nested paths', () => {
    expect(fieldErrorsFromIssues([
      { field: 'sender', message: 'Shipper defaults missing: shipper.name' },
      { field: 'recipient.postalCode', message: 'Invalid ZIP' },
      { field: 'recipient', message: 'second message is ignored for a taken path' },
      { field: 'recipient.addressLine1', message: 'first wins' },
    ])).toEqual({
      'sender.addressLine1': 'Shipper defaults missing: shipper.name',
      'recipient.postalCode': 'Invalid ZIP',
      'recipient.addressLine1': 'second message is ignored for a taken path',
    })
  })
  it('ignores issues without a field or message', () => {
    expect(fieldErrorsFromIssues([{ message: 'no field' }, { field: 'weight' }, null as never, undefined as never])).toEqual({})
    expect(fieldErrorsFromIssues(null)).toEqual({})
  })
})
