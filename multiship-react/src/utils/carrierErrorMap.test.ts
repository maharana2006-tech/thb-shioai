import { describe, it, expect } from 'vitest'
import { summarizeCarrierError } from './carrierErrorMap'

describe('summarizeCarrierError', () => {
  it('extracts FedEx-style "message" key (regression for FedEx/UPS/DHL blobs)', () => {
    const raw = 'FEDEX createShipment HTTP 400: {"errors":[{"message":"Weight required"}]}'
    expect(summarizeCarrierError(raw)).toBe('Weight required')
  })

  it('extracts SERA-style "error_message" key — the 933787 regression (now humanised)', () => {
    // Operator used to see the raw SERA message verbatim; now the Stamps
    // humanizer routes known codes/text through actionable sentences.
    const raw = 'STAMPS createShipmentSera[pkg 1/1] HTTP 400: '
      + '{"error_reference_id":"c1fe7548","errors":[{"error_code":"5636353",'
      + '"error_message":"Insufficient account balance. Please add funds to your account. Account exception "}]}'
    const out = summarizeCarrierError(raw)
    expect(out).toContain('postage balance')  // the friendly rewrite
    expect(out).toContain('Settings')          // tells operator where to go
    expect(out).not.toContain('HTTP 400')
    expect(out).not.toContain('error_reference_id')
    expect(out).not.toContain('Account exception')  // the raw SERA jargon suffix
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

  // ───── Stamps.com SERA friendly mappings ─────

  it('insufficient balance → actionable top-up guidance', () => {
    const raw = 'STAMPS createShipmentSera[pkg 1/1] HTTP 400: '
      + '{"errors":[{"error_code":"5636353","error_message":"Insufficient account balance. Please add funds."}]}'
    const out = summarizeCarrierError(raw)
    expect(out).toContain('postage balance')
    expect(out).toContain('Settings')
  })

  it('payment server error → check payment method', () => {
    const raw = 'STAMPS /balance/add-funds HTTP 400: {"errors":[{"error_code":"5636370","error_message":"Payment Server error occurred."}]}'
    expect(summarizeCarrierError(raw)).toContain('payment method')
  })

  it('contents_description invalid → short-summary suggestion', () => {
    const raw = 'STAMPS createShipmentSera[pkg 1/1] HTTP 400: {"errors":[{"error_message":"The contents_description specified is invalid."}]}'
    expect(summarizeCarrierError(raw)).toContain('customs contents description')
    expect(summarizeCarrierError(raw)).toContain('bicycle parts')
  })

  it('service_type invalid → pick a different service', () => {
    const raw = 'STAMPS getRatesSera[pkg 1/1] HTTP 400: {"errors":[{"error_message":"The service_type specified is invalid."}]}'
    expect(summarizeCarrierError(raw)).toContain('different service')
  })

  it('packaging_type invalid → use package or flat-rate', () => {
    const raw = 'STAMPS createShipmentSera[pkg 1/1] HTTP 400: {"errors":[{"error_message":"The packaging_type specified is invalid."}]}'
    expect(summarizeCarrierError(raw)).toContain('flat-rate')
  })

  it('certificate_number invalid → check ITN', () => {
    const raw = 'STAMPS createShipmentSera[pkg 1/1] HTTP 400: {"errors":[{"error_message":"The certificate_number specified is invalid."}]}'
    expect(summarizeCarrierError(raw)).toContain('export certificate')
  })

  it('transient TLS/handshake → retry hint', () => {
    const raw = 'STAMPS getRatesSera[pkg 1/1] timed out: I/O error on POST request for "https://api.testing.stampsendicia.com/sera/v1/rates": Request cancelled'
    expect(summarizeCarrierError(raw)).toContain('temporarily unreachable')
  })

  it('non-stamps carrier errors pass through unchanged by Stamps mapper', () => {
    // A FedEx insufficient-balance error must NOT get rewritten by the Stamps
    // humanizer (different carrier, different fix action).
    const raw = 'FEDEX createShipment HTTP 400: {"errors":[{"message":"Insufficient balance on FedEx account."}]}'
    expect(summarizeCarrierError(raw)).toBe('Insufficient balance on FedEx account.')
    expect(summarizeCarrierError(raw)).not.toContain('Settings → Carriers')
  })

  it('unknown stamps error falls back to the raw error_message', () => {
    // Safeguard: a SERA error we haven't humanised shouldn't be dropped.
    const raw = 'STAMPS createShipmentSera[pkg 1/1] HTTP 400: {"errors":[{"error_message":"Some brand new error we have not mapped yet."}]}'
    expect(summarizeCarrierError(raw)).toContain('brand new error')
  })
})
