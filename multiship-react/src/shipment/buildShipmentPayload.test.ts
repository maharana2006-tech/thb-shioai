import { describe, expect, it } from 'vitest'
import { buildShipmentPayload, type ShipmentPayloadInputs } from './buildShipmentPayload'
import type { ManualShipmentAddress } from '../api/orderService'
import type { PackagePreset } from '../api/shippingConfigService'
import type { CarrierAccountRef } from '../api/accountRefService'

/**
 * Phase 3 golden tests. Each case pins the exact wire body for one mode of
 * the Manual Shipment form. Phase 3b (reducer/section split) must keep every
 * one of these green byte-for-byte — if a test here needs to change, the
 * change is a contract change and belongs in its own commit.
 */

const sender: ManualShipmentAddress = {
  name: 'ZZ Sender', company: 'ZZ Co', addressLine1: '1 Test Way', addressLine2: '', city: 'Austin',
  state: 'TX', postalCode: '78701', countryCode: 'US', phone: '5125550100', email: 'zz-s@example.com',
} as ManualShipmentAddress
const recipient: ManualShipmentAddress = {
  name: 'ZZ Recipient', company: '', addressLine1: '2 Test Ave', addressLine2: '', city: 'Denver',
  state: 'CO', postalCode: '80202', countryCode: 'US', phone: '3035550100', email: 'zz-r@example.com',
} as ManualShipmentAddress

const flatRate: PackagePreset = { id: 19, weightUnit: 'LB', enabled: true, name: 'Flat Rate Envelope', kind: 'CARRIER', carrierPackageCode: 'FLAT_RATE_ENVELOPE', dimUnit: 'IN', length: 12.5, width: 9.5, height: 0.75 }
const thbBox: PackagePreset = { id: 31, weightUnit: 'LB', enabled: true, name: 'THB-PACKAGE', kind: 'CUSTOM', carrierPackageCode: 'PACKAGE', dimUnit: 'IN', length: 10, width: 6, height: 2 }
const uspsAccount = { id: 7, accountNumber: 'ZZ-USPS-1', carrierCode: 'STAMPS' } as CarrierAccountRef

const base: ShipmentPayloadInputs = {
  sender, recipient,
  isReturn: false, returnType: 'PRINT', returnPickupRequested: false, rmaNumber: '', returnReason: '', originalOrderNo: '',
  reference: ' PO-1 ', dtcLineId: null, fixOrderNo: null, fixChannel: null, note: '', ndsStdReplacementErpCode: null,
  carrier: 'USPS', accountNumber: ' zz-usps-1 ', accounts: [uspsAccount],
  serviceId: 42, isCustomPkg: false, packageChoice: '19', packagesForCarrier: [flatRate], customBoxes: [thbBox],
  length: '', width: '', height: '', dimUnit: 'IN', weight: '1.5', weightUnit: 'LB',
  clientCode: 'ARH173', warehouseCode: '', declaredValue: '', currency: 'USD',
  dgBlock: null, signatureOption: 'NONE', insuredValue: '', customFieldValues: {},
  labelImageType: '', labelStockType: '', labelImageFormat: '', pickupType: 'USE_SCHEDULED_PICKUP',
  extraPackages: [], isInternational: false,
  items: [], reasonForExport: '', incoterms: '', clearanceOption: '', dutiesAccount: '', ftrExemption: '', aesCitation: '',
  exportDeclarationReference: '', override: null, ndsQueue: [], ndsActiveIdx: 0, clients: [],
}

describe('buildShipmentPayload', () => {
  it('domestic single piece on a carrier preset', () => {
    expect(buildShipmentPayload(base)).toEqual({
      sender, recipient, isReturn: false,
      reference: 'PO-1',
      carrierCode: 'USPS', accountNumber: 'zz-usps-1', accountId: 7, serviceId: 42,
      packagePresetId: 19, length: null, width: null, height: null, dimUnit: 'IN',
      weight: 1.5, weightUnit: 'LB', clientCode: 'ARH173', warehouseCode: undefined,
      declaredValue: null, currency: 'USD',
    })
  })

  it('return mode wires the return block and only the typed extras', () => {
    const p = buildShipmentPayload({ ...base, isReturn: true, returnType: 'EMAIL', returnPickupRequested: true, rmaNumber: ' RMA9 ', originalOrderNo: '900001', returnReason: '' })
    expect(p).toMatchObject({ isReturn: true, returnType: 'EMAIL', returnPickupRequested: true, rmaNumber: 'RMA9', originalOrderNo: 900001 })
    expect(p).not.toHaveProperty('returnReason')
    // FedEx pickupType is never sent for returns
    expect(buildShipmentPayload({ ...base, carrier: 'FEDEX', isReturn: true, pickupType: 'DROPOFF_AT_FEDEX_LOCATION' })).not.toHaveProperty('pickupType')
  })

  it('carrier-specific label options are gated by carrier', () => {
    const fedex = buildShipmentPayload({ ...base, carrier: 'FEDEX', accounts: [], labelImageType: 'ZPLII', labelStockType: 'STOCK_4X6', labelImageFormat: 'ZPL', pickupType: 'DROPOFF_AT_FEDEX_LOCATION', signatureOption: 'ADULT', insuredValue: '25' })
    expect(fedex).toMatchObject({ labelImageType: 'ZPLII', labelStockType: 'STOCK_4X6', pickupType: 'DROPOFF_AT_FEDEX_LOCATION', signatureOption: 'ADULT', insuredValue: 25, insuredValueCurrency: 'USD', accountId: null })
    expect(fedex).not.toHaveProperty('labelImageFormat')
    const ups = buildShipmentPayload({ ...base, carrier: 'UPS', labelImageType: 'ZPLII', labelImageFormat: 'ZPL' })
    expect(ups).toMatchObject({ labelImageFormat: 'ZPL' })
    expect(ups).not.toHaveProperty('labelImageType')
  })

  it('multi-package: carrier preset omits dims, custom preset threads them, typed dims win', () => {
    const p = buildShipmentPayload({
      ...base, declaredValue: '10',
      extraPackages: [
        { weight: '2', length: '', width: '', height: '', packageType: 'THB-PACKAGE', declaredValue: '' },
        { weight: '3', length: '4', width: '5', height: '6', packageType: '', declaredValue: '7' },
      ],
    })
    expect(p.packages).toEqual([
      { sequenceNumber: 1, packageType: 'FLAT_RATE_ENVELOPE', weight: 1.5, weightUnit: 'LB', length: undefined, width: undefined, height: undefined, dimUnit: 'IN', declaredValue: 10 },
      { sequenceNumber: 2, packageType: 'THB-PACKAGE', weight: 2, weightUnit: 'LB', length: 10, width: 6, height: 2, dimUnit: 'IN', declaredValue: undefined },
      { sequenceNumber: 3, packageType: undefined, weight: 3, weightUnit: 'LB', length: 4, width: 5, height: 6, dimUnit: 'IN', declaredValue: 7 },
    ])
    const custom = buildShipmentPayload({ ...base, isCustomPkg: true, packageChoice: 'CUSTOM_PKG', length: '8', width: '6', height: '4', extraPackages: [{ weight: '1', length: '', width: '', height: '', packageType: '', declaredValue: '' }] })
    expect(custom).toMatchObject({ packagePresetId: null, length: 8, width: 6, height: 4 })
    expect(custom.packages?.[0]).toMatchObject({ packageType: undefined, length: 8, width: 6, height: 4 })
  })

  it('international: items are cleaned, customs extras gated, importer/broker only with an override', () => {
    const p = buildShipmentPayload({
      ...base, isInternational: true, recipient: { ...recipient, countryCode: 'CA' },
      items: [
        { description: ' Widget ', sku: ' W1 ', hsCode: '', countryOfOrigin: 'us', quantity: '2', unitValue: '9.5', weight: '', boxSeq: '2' },
        { description: '   ', sku: 'ignored', hsCode: '', countryOfOrigin: '', quantity: '1', unitValue: '1', weight: '', boxSeq: '' },
      ] as ShipmentPayloadInputs['items'],
      reasonForExport: 'SALE', incoterms: 'DDP', clearanceOption: 'THIRD_PARTY', dutiesAccount: ' 123 ', ftrExemption: '30.37(a)', aesCitation: '', exportDeclarationReference: '',
      override: { importer: { name: 'Imp' }, broker: { name: 'Brk' } },
    })
    expect(p.items).toEqual([{ description: 'Widget', sku: 'W1', hsCode: undefined, countryOfOrigin: 'US', quantity: 2, unitValue: 9.5, weight: null, boxSeq: 2 }])
    expect(p).toMatchObject({ reasonForExport: 'SALE', incoterms: 'DDP', clearanceOption: 'THIRD_PARTY', dutiesAccount: '123', ftrExemption: '30.37(a)', importer: { name: 'Imp' }, broker: { name: 'Brk' } })
    expect(p).not.toHaveProperty('aesCitation')
    expect(buildShipmentPayload({ ...base, isInternational: true, clearanceOption: 'DAP', dutiesAccount: '123' })).not.toHaveProperty('dutiesAccount')
  })

  it('channel: D2C line wins over fix-order channel; NDS batch threads batch id and packages', () => {
    expect(buildShipmentPayload({ ...base, dtcLineId: 5, fixOrderNo: 900002, fixChannel: 'B2B' })).toMatchObject({ channel: 'D2C' })
    expect(buildShipmentPayload({ ...base, fixOrderNo: 900002, fixChannel: 'B2B' })).toMatchObject({ channel: 'B2B' })
    expect(buildShipmentPayload(base)).not.toHaveProperty('channel')
    const nds = buildShipmentPayload({
      ...base,
      ndsQueue: [{ batchId: 77, packages: [{ containerNo: 'C1', containerIds: [1, 2], orderNos: [900003], orderSuffix: null, weight: '2.5' }] }] as unknown as ShipmentPayloadInputs['ndsQueue'],
      ndsActiveIdx: 0,
      clients: [{ clientCode: 'ARH173', defaultWeightUnit: 'KG' }] as ShipmentPayloadInputs['clients'],
    })
    expect(nds).toMatchObject({ ndsBatchId: 77, ndsPackages: [{ sequence: 1, containerNo: 'C1', containerIds: [1, 2], orderNos: [900003], orderSuffix: null, weight: 2.5, weightUnit: 'KG' }] })
  })
})
