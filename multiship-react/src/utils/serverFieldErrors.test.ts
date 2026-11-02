import { describe, expect, it } from 'vitest'
import { ApiError } from '../api/apiClient'
import { fieldErrorsFromIssues, serverFieldError } from './serverFieldErrors'

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

describe('serverFieldError', () => {
  it('returns the field and message from payload.errors', () => {
    const err = new ApiError('Validation failed', 422, {
      errors: { field: 'name', code: 'VALIDATION_ERROR', message: 'Name is already in use.' },
    })
    expect(serverFieldError(err)).toEqual({ field: 'name', message: 'Name is already in use.' })
  })
  it('returns null when field or message is missing or the shape is not a single error', () => {
    expect(serverFieldError(new ApiError('x', 422, { errors: { code: 'X', message: 'm' } }))).toBeNull()
    expect(serverFieldError(new ApiError('x', 422, { errors: { field: 'name', code: 'X' } }))).toBeNull()
    expect(serverFieldError(new ApiError('x', 422, { errors: [{ field: 'name', message: 'm' }] }))).toBeNull()
    expect(serverFieldError(new ApiError('x', 500, { message: 'boom' }))).toBeNull()
    expect(serverFieldError(new Error('plain'))).toBeNull()
    expect(serverFieldError(null)).toBeNull()
  })
})
