import { describe, expect, it } from 'vitest'
import {
  SHIPPING_PURPOSES,
  clearanceOptionsForCarrier,
  clearanceLabel,
  purposeLabel,
} from './customsOptions'

/**
 * F6-F — coverage for the clearance-option and shipping-purpose helpers.
 * The helpers are the single source of truth the CarrierConnections drawer
 * + ClientEditorPage default-account editor read from, so a wrong entry
 * would produce mismatched selects across every "set defaults" surface.
 */
describe('customsOptions', () => {
  describe('SHIPPING_PURPOSES', () => {
    it('stays in sync with the 8-value backend enum (ShipmentDefaultsResolver.SHIPPING_PURPOSE_ENUM)', () => {
      // Any drift here means the backend resolver would throw
      // ShipmentDefaultsException for a value the FE lets an operator pick.
      const values = SHIPPING_PURPOSES.map((p) => p.value).sort()
      expect(values).toEqual([
        'DOCUMENTS',
        'GIFT',
        'MERCHANDISE',
        'PERSONAL_USE',
        'REPAIR_AND_RETURN',
        'RETURN',
        'SALE',
        'SAMPLE',
      ])
    })
  })

  describe('clearanceOptionsForCarrier', () => {
    it('returns UPS billing enum (SENDER / RECEIVER / THIRD_PARTY)', () => {
      expect(clearanceOptionsForCarrier('UPS').map((o) => o.value))
        .toEqual(['SENDER', 'RECEIVER', 'THIRD_PARTY'])
    })

    it('returns FedEx billing enum — RECIPIENT differs from UPS RECEIVER', () => {
      // Cross-carrier gotcha the connector envelopes care about:
      // FedEx paymentType wants RECIPIENT; UPS wants RECEIVER.
      expect(clearanceOptionsForCarrier('FEDEX').map((o) => o.value))
        .toEqual(['SENDER', 'RECIPIENT', 'THIRD_PARTY'])
    })

    it('returns USPS deprecated-DDU vocabulary (SWSIM still speaks it)', () => {
      expect(clearanceOptionsForCarrier('USPS').map((o) => o.value))
        .toEqual(['DDU', 'DDP'])
    })

    it('returns DHL Incoterms subset — DAP / DDP / EXW', () => {
      // F6-F added DHL. The 3 codes are the ones DHL Express commonly
      // quotes for parcel; FCA/CPT/CIP/DPU are freight-oriented and
      // deliberately omitted to prevent mis-selection.
      expect(clearanceOptionsForCarrier('DHL').map((o) => o.value))
        .toEqual(['DAP', 'DDP', 'EXW'])
    })

    it('normalises carrier code case-insensitively', () => {
      expect(clearanceOptionsForCarrier('dhl').length).toBe(3)
      expect(clearanceOptionsForCarrier('DhL').length).toBe(3)
    })

    it('returns an empty array for unknown / missing carriers', () => {
      // Empty triggers the drawer/select "disabled + no options" state
      // instead of showing wrong picks under the wrong carrier.
      expect(clearanceOptionsForCarrier(null)).toEqual([])
      expect(clearanceOptionsForCarrier(undefined)).toEqual([])
      expect(clearanceOptionsForCarrier('')).toEqual([])
      expect(clearanceOptionsForCarrier('UNKNOWN')).toEqual([])
    })
  })

  describe('clearanceLabel', () => {
    it('resolves DHL DDP to the human-readable label', () => {
      expect(clearanceLabel('DHL', 'DDP'))
        .toBe('DDP — Delivered Duty Paid (sender pays duties)')
    })

    it('falls back to the raw code when the carrier does not model the value', () => {
      // Legacy rows sometimes carry a value the current enum doesn't list
      // (e.g. a FedEx account written before we split RECIPIENT vs
      // RECEIVER). The table cell must never say "undefined" for those.
      expect(clearanceLabel('UPS', 'RECIPIENT')).toBe('RECIPIENT')
    })

    it('returns empty string for null / undefined', () => {
      expect(clearanceLabel('DHL', null)).toBe('')
      expect(clearanceLabel('DHL', undefined)).toBe('')
    })
  })

  describe('purposeLabel', () => {
    it('maps a known code to its label', () => {
      expect(purposeLabel('REPAIR_AND_RETURN')).toBe('Repair and return')
    })

    it('falls back to the raw value for unknown codes', () => {
      expect(purposeLabel('UNKNOWN_PURPOSE')).toBe('UNKNOWN_PURPOSE')
    })
  })
})
