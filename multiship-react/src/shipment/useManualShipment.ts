import { useMemo, useReducer, type Dispatch, type SetStateAction } from 'react'
import type { ManualShipmentAddress } from '../api/orderService'
import type { DangerousGoodsBlock } from '../api/dgService'
import type { NdsShipmentPrefill } from '../api/ndsShipmentService'
import type { Party } from '../components/modals/ShipmentPartiesOverrideModal'
import { readSticky } from '../utils/sticky'

/** Boxes 2..N of a multi-package shipment; box 1 uses the top-level fields. */
export type ExtraPackage = {
  weight: string
  length: string
  width: string
  height: string
  packageType: string  // may be empty → falls back to shipment-level
  declaredValue: string
}

export type NewShipmentItemRow = {
  description: string
  sku: string
  hsCode: string
  countryOfOrigin: string
  quantity: string
  unitValue: string
  boxSeq: string
  /**
   * PR #558 — per-commodity weight for international shipments. FedEx
   * requires it and rejected "Commodity weight is missing or invalid"
   * when zero. When left blank on international, FE + BE fall back to
   * spreading `packageWeight × qty / totalQty` across items; operator
   * can override this cell for uneven weight distribution.
   */
  weight: string
}

/**
 * Manual Shipment redesign, Phase 3b — the form's wire-relevant fields as
 * one reducer-owned object. Everything buildShipmentPayload reads lives
 * here; catalogs, async flags and modal toggles stay as page state.
 *
 * `set` keeps React's setState contract (value or updater) so handlers
 * written against useState keep working; `patch` applies a prefill
 * (fix-order, NDS, D2C, address book) as one atomic transition.
 */
export interface ShipmentForm {
  sender: ManualShipmentAddress
  recipient: ManualShipmentAddress
  note: string
  mode: 'SHIPMENT' | 'RETURN'
  returnType: 'PRINT' | 'EMAIL'
  returnPickupRequested: boolean
  rmaNumber: string
  returnReason: string
  originalOrderNo: string
  fixChannel: 'D2C' | 'B2B' | null
  carrier: string
  accountNumber: string
  serviceId: number | ''
  incoterms: string
  clearanceOption: string
  dutiesAccount: string
  /** Who pays the shipping charges. THIRD_PARTY bills the payer account below, not the shipper account. */
  billTo: 'SENDER' | 'THIRD_PARTY'
  thirdPartyAccount: string
  thirdPartyPostalCode: string
  thirdPartyCountry: string
  packageChoice: string
  length: string
  width: string
  height: string
  dimUnit: 'IN' | 'CM'
  weight: string
  weightUnit: 'LB' | 'KG'
  declaredValue: string
  reference: string
  clientCode: string
  warehouseCode: string
  ndsQueue: NdsShipmentPrefill[]
  ndsActiveIdx: number
  ndsStdReplacementErpCode: string | null
  signatureOption: 'NONE' | 'INDIRECT' | 'DIRECT' | 'ADULT'
  insuredValue: string
  labelImageType: string
  labelStockType: string
  labelImageFormat: string
  pickupType: string
  dgBlock: DangerousGoodsBlock | null
  extraPackages: ExtraPackage[]
  customFieldValues: Record<string, string>
  items: NewShipmentItemRow[]
  reasonForExport: string
  currency: string
  ftrExemption: string
  aesCitation: string
  exportDeclarationReference: string
  override: { importer: Party; broker: Party } | null
}

export const blankAddress = (): ManualShipmentAddress => ({
  name: '',
  company: '',
  addressLine1: '',
  addressLine2: '',
  addressLine3: '',
  city: '',
  state: '',
  postalCode: '',
  countryCode: 'US',
  phone: '',
  email: '',
})

/** Every line belongs to a box from the start (box 1 by default). */
export const blankItem = (): NewShipmentItemRow =>
  ({ description: '', sku: '', hsCode: '', countryOfOrigin: '', quantity: '1', unitValue: '', boxSeq: '1', weight: '' })

export function initialShipmentForm(opts: { isReturnPath: boolean }): ShipmentForm {
  return {
    sender: blankAddress(),
    recipient: blankAddress(),
    note: '',
    mode: opts.isReturnPath ? 'RETURN' : 'SHIPMENT',
    returnType: 'PRINT',
    returnPickupRequested: true,
    rmaNumber: '',
    returnReason: '',
    originalOrderNo: '',
    fixChannel: null,
    carrier: '',
    accountNumber: '',
    serviceId: '',
    incoterms: readSticky('ms:lastIncoterms', ''),
    clearanceOption: '',
    dutiesAccount: '',
    billTo: 'SENDER',
    thirdPartyAccount: '',
    thirdPartyPostalCode: '',
    thirdPartyCountry: '',
    packageChoice: '',
    length: '',
    width: '',
    height: '',
    dimUnit: readSticky('ms:lastDimUnit', 'IN') === 'CM' ? 'CM' : 'IN',
    weight: '',
    weightUnit: readSticky('ms:lastWeightUnit', 'LB') === 'KG' ? 'KG' : 'LB',
    declaredValue: '',
    reference: '',
    clientCode: '',
    warehouseCode: '',
    ndsQueue: [],
    ndsActiveIdx: -1,
    ndsStdReplacementErpCode: null,
    signatureOption: 'NONE',
    insuredValue: '',
    labelImageType: '',
    labelStockType: '',
    labelImageFormat: '',
    pickupType: readSticky('ms:lastPickupType', 'USE_SCHEDULED_PICKUP'),
    dgBlock: null,
    extraPackages: [],
    customFieldValues: {},
    items: [blankItem()],
    reasonForExport: readSticky('ms:lastReason', ''),
    currency: readSticky('ms:lastCurrency', 'USD'),
    ftrExemption: '',
    aesCitation: '',
    exportDeclarationReference: '',
    override: null,
  }
}

type Updater<T> = T | ((prev: T) => T)
export type ShipmentFormAction =
  | { [K in keyof ShipmentForm]: { type: 'set'; field: K; value: Updater<ShipmentForm[K]> } }[keyof ShipmentForm]
  | { type: 'patch'; patch: Partial<ShipmentForm> }

export function shipmentFormReducer(state: ShipmentForm, action: ShipmentFormAction): ShipmentForm {
  switch (action.type) {
    case 'set': {
      const prev = state[action.field]
      const next = typeof action.value === 'function' ? (action.value as (p: typeof prev) => typeof prev)(prev) : action.value
      return Object.is(prev, next) ? state : { ...state, [action.field]: next }
    }
    case 'patch':
      return { ...state, ...action.patch }
  }
}

export type ShipmentFormSetters = {
  [K in keyof ShipmentForm as `set${Capitalize<K>}`]: Dispatch<SetStateAction<ShipmentForm[K]>>
}

const FIELDS = Object.keys(initialShipmentForm({ isReturnPath: false })) as (keyof ShipmentForm)[]

export function useManualShipment(opts: { isReturnPath: boolean }) {
  const [form, dispatch] = useReducer(shipmentFormReducer, opts, initialShipmentForm)
  const setters = useMemo(() => {
    const out: Record<string, (value: unknown) => void> = {}
    for (const field of FIELDS) {
      out[`set${field[0].toUpperCase()}${field.slice(1)}`] = (value) =>
        dispatch({ type: 'set', field, value } as ShipmentFormAction)
    }
    return out as unknown as ShipmentFormSetters
  }, [])
  const patch = useMemo(() => (p: Partial<ShipmentForm>) => dispatch({ type: 'patch', patch: p }), [])
  return { form, setters, patch }
}
