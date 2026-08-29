import { describe, expect, it } from 'vitest'
import type { ManualShipmentAddress } from '../api/orderService'
import { recipientFieldsFrom, shipperFieldsFrom } from './shipmentAddressFields'

/**
 * Sprint 51 refactor — helpers that flatten a ManualShipmentAddress into
 * shipper* / recipient* keys for the rate-shop and shipment request bodies.
 * Existed because #508 uncovered a class of bug where the manually-mapped
 * builder in NewShipmentPage silently dropped wire-required fields
 * (shipperPhone / recipientPhone).
 *
 * The invariants worth locking down:
 *  1. Every field the operator sees in the form flows through to the DTO
 *     (regression net for #508 and future re-introductions).
 *  2. Empty strings coerce to `undefined` on the OPTIONAL fields (so the
 *     JSON body doesn't send `"phone": ""` — which satisfies FE "is
 *     defined" checks while failing the backend @NotBlank).
 *  3. Country + postal code are NEVER hardcoded — pass through the
 *     operator's actual choice, empty string when unset. This is
 *     explicitly what the user asked for after seeing the pre-fix code
 *     silently substituting `"US"`.
 *  4. Fields not on the DTO yet (company, email) are intentionally
 *     dropped, not renamed.
 */

const filled = (over?: Partial<ManualShipmentAddress>): ManualShipmentAddress => ({
  name: 'Jane Doe',
  company: 'Zymeworks',
  phone: '+1 555-123-4567',
  phoneCountryCode: '1',
  email: 'jane@acme.com',
  addressLine1: '108 Patriot Drive',
  addressLine2: 'Suite A',
  addressLine3: 'Bldg 3',
  city: 'Middletown',
  state: 'DE',
  postalCode: '19709',
  countryCode: 'US',
  residential: false,
  ...over,
})

const blank = (over?: Partial<ManualShipmentAddress>): ManualShipmentAddress => ({
  name: '',
  company: '',
  phone: '',
  phoneCountryCode: '',
  email: '',
  addressLine1: '',
  addressLine2: '',
  addressLine3: '',
  city: '',
  state: '',
  postalCode: '',
  countryCode: '',
  ...over,
})

describe('shipperFieldsFrom', () => {
  it('spreads every wire-relevant field with the operator-entered values', () => {
    const out = shipperFieldsFrom(filled())
    expect(out).toEqual({
      shipperName: 'Jane Doe',
      shipperPhone: '+1 555-123-4567',
      shipperCompany: 'Zymeworks',
      shipperEmail: 'jane@acme.com',
      shipperAddressLine1: '108 Patriot Drive',
      shipperAddressLine2: 'Suite A',
      shipperCity: 'Middletown',
      shipperState: 'DE',
      shipperPostalCode: '19709',
      shipperCountryCode: 'US',
    })
  })

  it('coerces empty optional fields to undefined (not empty string on the wire)', () => {
    const out = shipperFieldsFrom(blank({ postalCode: '19709', countryCode: 'US' }))
    expect(out.shipperName).toBeUndefined()
    expect(out.shipperPhone).toBeUndefined()
    expect(out.shipperAddressLine1).toBeUndefined()
    expect(out.shipperAddressLine2).toBeUndefined()
    expect(out.shipperCity).toBeUndefined()
    expect(out.shipperState).toBeUndefined()
  })

  it('leaves country code as-is when operator picked one — no US hardcode', () => {
    expect(shipperFieldsFrom(filled({ countryCode: 'IN' })).shipperCountryCode).toBe('IN')
    expect(shipperFieldsFrom(filled({ countryCode: 'GB' })).shipperCountryCode).toBe('GB')
    expect(shipperFieldsFrom(filled({ countryCode: 'DE' })).shipperCountryCode).toBe('DE')
  })

  it('empty country code passes through as empty string — backend @NotBlank surfaces it', () => {
    // Pre-refactor this coerced to "US" and silently mis-routed labels.
    expect(shipperFieldsFrom(blank()).shipperCountryCode).toBe('')
    expect(shipperFieldsFrom(blank()).shipperPostalCode).toBe('')
  })

  it('emits company + email as shipper* keys (Sprint 51 email+company)', () => {
    const out = shipperFieldsFrom(filled()) as Record<string, unknown>
    expect(out.shipperCompany).toBe('Zymeworks')
    expect(out.shipperEmail).toBe('jane@acme.com')
    // FE-native keys are still stripped — the DTO uses the shipper*
    // prefix; the raw "company" / "email" would be silently ignored
    // by the backend.
    expect('company' in out).toBe(false)
    expect('email' in out).toBe(false)
  })

  it('empty company/email coerce to undefined (not empty string on wire)', () => {
    const out = shipperFieldsFrom(blank({ postalCode: '19709', countryCode: 'US' }))
    expect(out.shipperCompany).toBeUndefined()
    expect(out.shipperEmail).toBeUndefined()
  })

  it('trims whitespace-only fields to undefined', () => {
    const out = shipperFieldsFrom(filled({ phone: '   ', addressLine2: '  \t  ' }))
    expect(out.shipperPhone).toBeUndefined()
    expect(out.shipperAddressLine2).toBeUndefined()
  })
})

describe('recipientFieldsFrom', () => {
  it('spreads every wire-relevant field including the three street lines + phone country code', () => {
    const out = recipientFieldsFrom(filled({ residential: true }))
    expect(out).toEqual({
      recipientName: 'Jane Doe',
      recipientPhone: '+1 555-123-4567',
      recipientCompany: 'Zymeworks',
      recipientEmail: 'jane@acme.com',
      recipientPhoneCountryCode: '1',
      recipientAddressLine1: '108 Patriot Drive',
      recipientAddressLine2: 'Suite A',
      recipientAddressLine3: 'Bldg 3',
      recipientCity: 'Middletown',
      recipientState: 'DE',
      recipientPostalCode: '19709',
      recipientCountryCode: 'US',
      recipientResidential: true,
    })
  })

  it('residential passes through undefined when the operator hasn\'t picked yet', () => {
    // Undefined = "unknown, use carrier default"; false/true is a deliberate choice.
    const out = recipientFieldsFrom(filled({ residential: undefined }))
    expect(out.recipientResidential).toBeUndefined()
  })

  it('residential=false is preserved (deliberate commercial choice)', () => {
    const out = recipientFieldsFrom(filled({ residential: false }))
    expect(out.recipientResidential).toBe(false)
  })

  it('coerces empty optional fields to undefined', () => {
    const out = recipientFieldsFrom(blank({ postalCode: '19709', countryCode: 'US' }))
    expect(out.recipientName).toBeUndefined()
    expect(out.recipientPhone).toBeUndefined()
    expect(out.recipientCompany).toBeUndefined()
    expect(out.recipientEmail).toBeUndefined()
    expect(out.recipientPhoneCountryCode).toBeUndefined()
    expect(out.recipientAddressLine1).toBeUndefined()
    expect(out.recipientAddressLine2).toBeUndefined()
    expect(out.recipientAddressLine3).toBeUndefined()
    expect(out.recipientCity).toBeUndefined()
    expect(out.recipientState).toBeUndefined()
  })

  it('country + postal codes pass through as empty string when unset — no US hardcode', () => {
    expect(recipientFieldsFrom(blank()).recipientCountryCode).toBe('')
    expect(recipientFieldsFrom(blank()).recipientPostalCode).toBe('')
  })

  it('non-US recipient countries flow through untouched', () => {
    expect(recipientFieldsFrom(filled({ countryCode: 'IN' })).recipientCountryCode).toBe('IN')
    expect(recipientFieldsFrom(filled({ countryCode: 'JP' })).recipientCountryCode).toBe('JP')
  })

  it('regression net for #508 — shipperPhone + recipientPhone always present when filled', () => {
    // If a future refactor drops the phone key again, this test fails
    // loudly — matches the exact bug class the helpers were built to
    // prevent (FE cherry-picking silently omitted the field).
    expect(shipperFieldsFrom(filled()).shipperPhone).toBe('+1 555-123-4567')
    expect(recipientFieldsFrom(filled()).recipientPhone).toBe('+1 555-123-4567')
  })
})
