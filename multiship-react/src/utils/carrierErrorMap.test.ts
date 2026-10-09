import { describe, it, expect } from 'vitest'
import { summarizeCarrierError } from './carrierErrorMap'

describe('summarizeCarrierError', () => {
  it('extracts FedEx-style "message" key (regression for FedEx/UPS/DHL blobs)', () => {
    const raw = 'FEDEX createShipment HTTP 400: {"errors":[{"message":"Weight required"}]}'
    expect(summarizeCarrierError(raw)).toBe('Weight required')
  })

  it('extracts SERA-style "error_message" key — the 933787 regression', () => {
    const raw = 'STAMPS createShipmentSera[pkg 1/1] HTTP 400: '
      + '{"error_reference_id":"c1fe7548","errors":[{"error_code":"5636353",'
      + '"error_message":"Insufficient account balance. Please add funds to your account. Account exception "}]}'
    const out = summarizeCarrierError(raw)
    expect(out).toContain('Insufficient account balance')
    expect(out).toContain('Please add funds')
    expect(out).not.toContain('HTTP 400')
    expect(out).not.toContain('error_reference_id')
  })

  it('joins multiple errors with the bullet separator', () => {
    const raw = '{"errors":[{"error_message":"foo"},{"error_message":"bar"}]}'
    expect(summarizeCarrierError(raw)).toBe('foo · bar')
  })

  it('fallback strip for no-JSON STAMPS blob still removes the connector prefix', () => {
    // Simulates the pre-fix output: body was already stripped upstream;
    // only the wrapper prefix remains.
    expect(summarizeCarrierError('STAMPS createShipmentSera[pkg 1/1] HTTP 400: '))
      .toBe('Label failed')
  })

  it('fallback strip removes the piece-bracket prefix too', () => {
    const raw = 'STAMPS getRatesSera[pkg 1/1] HTTP 400: some free text here'
    expect(summarizeCarrierError(raw)).toBe('some free text here')
  })

  it('null/empty input returns Label failed', () => {
    expect(summarizeCarrierError(null)).toBe('Label failed')
    expect(summarizeCarrierError('')).toBe('Label failed')
    expect(summarizeCarrierError(undefined)).toBe('Label failed')
  })

  it('normalises sentence-spacing even on extracted messages', () => {
    // SERA / Stamps sometimes concatenates sentences without a space.
    const raw = '{"errors":[{"error_message":"Address invalid.Please fix."}]}'
    expect(summarizeCarrierError(raw)).toBe('Address invalid. Please fix.')
  })
})
