import type { ManualShipmentAddress, ManualShipmentItem, ManualShipmentPayload } from '../api/orderService'
import type { CarrierAccountRef } from '../api/accountRefService'
import type { Client } from '../api/clientService'
import type { PackagePreset } from '../api/shippingConfigService'
import type { DangerousGoodsBlock } from '../api/dgService'
import type { NdsShipmentPrefill } from '../api/ndsShipmentService'
import type { Party } from '../components/modals/ShipmentPartiesOverrideModal'
import type { ExtraPackage, NewShipmentItemRow } from './useManualShipment'
import { canon } from '../utils/carrierCanon'

/**
 * Manual Shipment redesign, Phase 3 — everything the wire body is built
 * from, as one explicit object. The form assembles it from its state in
 * one place; nothing else may add to the payload. Golden-tested per mode
 * in buildShipmentPayload.test.ts, so the Phase 3b reducer can change the
 * form's state shape without changing a byte on the wire.
 */
export interface ShipmentPayloadInputs {
  sender: ManualShipmentAddress
  recipient: ManualShipmentAddress
  isReturn: boolean
  returnType: 'PRINT' | 'EMAIL'
  returnPickupRequested: boolean
  rmaNumber: string
  returnReason: string
  originalOrderNo: string
  reference: string
  dtcLineId: number | null
  fixOrderNo: number | null
  fixChannel: 'D2C' | 'B2B' | null
  note: string
  ndsStdReplacementErpCode: string | null
  carrier: string
  accountNumber: string
  accounts: CarrierAccountRef[]
  serviceId: number | ''
  isCustomPkg: boolean
  packageChoice: string
  packagesForCarrier: PackagePreset[]
  customBoxes: PackagePreset[]
  length: string
  width: string
  height: string
  dimUnit: 'IN' | 'CM'
  weight: string
  weightUnit: 'LB' | 'KG'
  clientCode: string
  warehouseCode: string
  declaredValue: string
  currency: string
  dgBlock: DangerousGoodsBlock | null
  signatureOption: 'NONE' | 'INDIRECT' | 'DIRECT' | 'ADULT'
  insuredValue: string
  customFieldValues: Record<string, string>
  labelImageType: string
  labelStockType: string
  labelImageFormat: string
  pickupType: string
  extraPackages: ExtraPackage[]
  isInternational: boolean
  items: NewShipmentItemRow[]
  reasonForExport: string
  incoterms: string
  clearanceOption: string
  dutiesAccount: string
  billTo: 'SENDER' | 'THIRD_PARTY'
  thirdPartyAccount: string
  thirdPartyPostalCode: string
  thirdPartyCountry: string
  ftrExemption: string
  aesCitation: string
  exportDeclarationReference: string
  override: { importer: Party; broker: Party } | null
  ndsQueue: NdsShipmentPrefill[]
  ndsActiveIdx: number
  clients: Client[]
}

export function buildShipmentPayload(inputs: ShipmentPayloadInputs): ManualShipmentPayload {
  const {
    sender, recipient, isReturn, returnType, returnPickupRequested, rmaNumber, returnReason, originalOrderNo,
    reference, dtcLineId, fixOrderNo, fixChannel, note, ndsStdReplacementErpCode, carrier, accountNumber, accounts,
    serviceId, isCustomPkg, packageChoice, packagesForCarrier, customBoxes, length, width, height, dimUnit, weight,
    weightUnit, clientCode, warehouseCode, declaredValue, currency, dgBlock, signatureOption, insuredValue,
    customFieldValues, labelImageType, labelStockType, labelImageFormat, pickupType, extraPackages, isInternational,
    items, reasonForExport, incoterms, clearanceOption, dutiesAccount, ftrExemption, aesCitation,
    exportDeclarationReference, override, ndsQueue, ndsActiveIdx, clients,
    billTo, thirdPartyAccount, thirdPartyPostalCode, thirdPartyCountry,
  } = inputs
  const active = ndsQueue[ndsActiveIdx]
  const w = Number(weight)

  const cleanItems: ManualShipmentItem[] = items
    .filter((it) => it.description.trim())
    .map((it) => ({
      description: it.description.trim(),
      sku: it.sku.trim() || undefined,
      hsCode: it.hsCode.trim() || undefined,
      countryOfOrigin: it.countryOfOrigin.trim().toUpperCase() || undefined,
      quantity: it.quantity ? Number(it.quantity) : null,
      unitValue: it.unitValue ? Number(it.unitValue) : null,
      // PR #558 — per-item weight; auto-computed from pkg weight when blank.
      // Typed weights only. The auto-apportioned figure is shown as the
      // placeholder for transparency but NOT posted: persisting it made an
      // estimate look like a declared per-item weight (no "(est)" on the
      // invoice). The backend spreads the shipment weight for the carrier.
      weight: it.weight ? Number(it.weight) || null : null,
      // Sprint 48 B11 — assign item to a specific physical package;
      // backend derives per-box declared value from sum of items
      // when at least one item is assigned.
      boxSeq: it.boxSeq && Number(it.boxSeq) > 0 ? Number(it.boxSeq) : 1,
    }))

  // Match the typed bill-to number to a known account on this carrier (for credentials);
  // if it's not on file, the backend falls back to the carrier's platform credentials.
  const matched = accounts.find(
    (a) => canon(a.carrierCode) === carrier && (a.accountNumber || '').toLowerCase() === accountNumber.trim().toLowerCase(),
  )

  const payload: ManualShipmentPayload = {
    sender,
    recipient,
    isReturn,
    // Return delivery mode — only meaningful when isReturn=true.
    // Carrier connectors key off this: UPS ReturnService.Code 8/9
    // (with LabelDelivery.EMail block), FedEx returnedShipmentDetail
    // .returnType (PRINT_RETURN_LABEL / EMAIL_LABEL), etc.
    ...(isReturn
      ? {
          returnType,
          returnPickupRequested,
          // Returns F10 — only wire rmaNumber when operator typed one;
          // blank/trim-empty stays off the payload so the server-side
          // null-vs-"" distinction holds.
          ...(rmaNumber.trim() ? { rmaNumber: rmaNumber.trim() } : {}),
          // Returns F11 — same contract for the reason picklist.
          ...(returnReason ? { returnReason } : {}),
          // Returns F12 — only wire when numeric; server validates
          // existence and drops bad refs to null.
          ...(originalOrderNo.trim() && Number.isFinite(Number(originalOrderNo))
              ? { originalOrderNo: Number(originalOrderNo) }
              : {}),
        }
      : {}),
    reference: reference.trim() || undefined,
    // A D2C line ships to a consumer; its attention line lands in Company,
    // which would otherwise classify the order as B2B.
    ...(dtcLineId ? { channel: 'D2C' as const } : fixOrderNo && fixChannel ? { channel: fixChannel } : {}),
    // V76 — internal per-order ops note; omit when blank so the
    // wire only carries populated fields.
    ...(note.trim() ? { note: note.trim() } : {}),
    ...(ndsStdReplacementErpCode ? { stdReplacementErpCode: ndsStdReplacementErpCode } : {}),
    carrierCode: carrier,
    accountNumber: accountNumber.trim(),
    accountId: matched?.id ?? null,
    serviceId: serviceId === '' ? null : Number(serviceId),
    packagePresetId: isCustomPkg ? null : Number(packageChoice),
    length: isCustomPkg ? Number(length) : null,
    width: isCustomPkg ? Number(width) : null,
    height: isCustomPkg ? Number(height) : null,
    dimUnit,
    weight: w,
    weightUnit,
    clientCode: clientCode.trim() || undefined,
    warehouseCode: warehouseCode || undefined,
    declaredValue: declaredValue ? Number(declaredValue) : null,
    // Sprint 51 — currency is now always sent (not just intl). A
    // European tenant shipping GB→GB should see GBP; pre-fix the
    // domestic branch dropped currency and the backend fell to the
    // carrier's home currency (USD for US carriers, EUR for DHL).
    currency,
    // Sprint 27 — attach the DG block when populated; backend threads
    // it into ShipmentRequestDTO.dangerousGoods and every connector's
    // hazmat wire format keys off it.
    ...(dgBlock ? { dangerousGoods: dgBlock } : {}),
    // Sprint 35 — signature + insurance. NONE → omit so the backend
    // uses the carrier default; explicit values flow through to each
    // connector's per-carrier wire format.
    ...(signatureOption !== 'NONE' ? { signatureOption } : {}),
    ...(insuredValue && Number(insuredValue) > 0
      ? { insuredValue: Number(insuredValue), insuredValueCurrency: currency }
      : {}),
    // R2-BUG-008 — thread filled custom-field values into the generate
    // payload so BE validate + Generate's required-field guard see them.
    // Pre-fix these were persisted only via a post-generate upsert, so
    // the BE never knew whether a 'required on new shipments' field was
    // filled or empty before buying the label.
    ...(Object.keys(customFieldValues).length > 0 ? { customFields: customFieldValues } : {}),
    // FDX-H3 — per-shipment FedEx labelSpecification override. Blank →
    // omit so the backend falls back to the account default (which
    // itself falls back to PDF/PAPER_4X6). Only meaningful for FEDEX.
    ...(canon(carrier) === 'FEDEX' && labelImageType ? { labelImageType } : {}),
    ...(canon(carrier) === 'FEDEX' && labelStockType ? { labelStockType } : {}),
    // Per-shipment label format override — UPS/DHL/USPS only. Blank →
    // omit so the backend falls back to the account default.
    ...(['UPS', 'DHL', 'USPS'].includes(canon(carrier)) && labelImageFormat
      ? { labelImageFormat }
      : {}),
    // FDX-H1 — per-shipment pickupType. FedEx-only; skipped in
    // Return mode (connector overrides to CONTACT_FEDEX_TO_SCHEDULE).
    ...(canon(carrier) === 'FEDEX' && !isReturn && pickupType
      ? { pickupType }
      : {}),
    // Sprint 29 — multi-package. When extra boxes are present, build
    // a packages[] array with box 1 mirroring the top-level fields and
    // boxes 2..N from extraPackages. Backend's effectivePackages() also
    // handles the null case, so empty extraPackages leaves the payload
    // untouched (existing single-package behavior).
    ...(extraPackages.length > 0 ? {
      packages: [
        {
          sequenceNumber: 1,
          // packageChoice is the PackagePreset.id as a string; resolve
          // to the carrier's packaging code (e.g. "FLAT_RATE_ENVELOPE")
          // before sending. Pre-fix we posted the raw id ("19") and SERA
          // rejected with 899999 "packaging_type specified is invalid."
          // Backend uses PackageDetailDTO.packageType verbatim for the
          // carrier wire body; top-level packagePresetId (line below)
          // handles the non-MPS single-piece path. For MPS, each piece
          // needs its own carrier code.
          packageType: isCustomPkg
            ? undefined
            : ([...packagesForCarrier, ...customBoxes].find((pk) => String(pk.id) === packageChoice)?.carrierPackageCode
                || undefined),
          weight: w,
          weightUnit,
          // Dim threading — rules by preset kind:
          //   · Custom (user typed dims directly): send typed dims.
          //   · CUSTOM-kind preset (e.g. "THB-PACKAGE" 10×6×2): send
          //     preset's L/W/H. SERA treats these as generic "package"
          //     and rejects with "Dimensions are required" without dims.
          //   · CARRIER-kind preset (flat-rate boxes, UPS branded boxes):
          //     OMIT dims. SERA knows the dims for these codes; sending
          //     our rounded seed values (8.69×5.44×1.75 vs USPS official
          //     8.6875×5.4375×1.75) tripped SERA's dim validation with
          //     "No rate found for the specified mail class, package,
          //     and dimensions" during QA R6.
          length: isCustomPkg
            ? Number(length)
            : (([...packagesForCarrier, ...customBoxes].find((pk) => String(pk.id) === packageChoice)?.kind === 'CUSTOM')
                ? ([...packagesForCarrier, ...customBoxes].find((pk) => String(pk.id) === packageChoice)?.length ?? undefined)
                : undefined),
          width: isCustomPkg
            ? Number(width)
            : (([...packagesForCarrier, ...customBoxes].find((pk) => String(pk.id) === packageChoice)?.kind === 'CUSTOM')
                ? ([...packagesForCarrier, ...customBoxes].find((pk) => String(pk.id) === packageChoice)?.width ?? undefined)
                : undefined),
          height: isCustomPkg
            ? Number(height)
            : (([...packagesForCarrier, ...customBoxes].find((pk) => String(pk.id) === packageChoice)?.kind === 'CUSTOM')
                ? ([...packagesForCarrier, ...customBoxes].find((pk) => String(pk.id) === packageChoice)?.height ?? undefined)
                : undefined),
          dimUnit,
          declaredValue: declaredValue ? Number(declaredValue) : undefined,
        },
        ...extraPackages.map((p, i) => {
          // Mirror the box-1 dim-threading rule for box 2+. If the box
          // was given a packageType (preset's carrier code), look up the
          // preset to decide: thread L/W/H when CUSTOM-kind, omit when
          // CARRIER-kind. User-typed p.length/width/height always win.
          // Also search customBoxes — CUSTOM-kind presets may store
          // their name in packageType when extraPackage's packageType is
          // populated from "Apply box 1 to all". Match by code OR name.
          const extraPreset = p.packageType
            ? [...packagesForCarrier, ...customBoxes].find(
                (pk) => (pk.carrierPackageCode || '') === p.packageType
                    || pk.name === p.packageType)
            : null
          const extraPresetIsCustom = extraPreset?.kind === 'CUSTOM'
          return {
            sequenceNumber: i + 2,
            packageType: p.packageType || undefined,
            weight: Number(p.weight),
            weightUnit,
            length: p.length ? Number(p.length)
              : (extraPresetIsCustom ? extraPreset?.length ?? undefined : undefined),
            width: p.width ? Number(p.width)
              : (extraPresetIsCustom ? extraPreset?.width ?? undefined : undefined),
            height: p.height ? Number(p.height)
              : (extraPresetIsCustom ? extraPreset?.height ?? undefined : undefined),
            dimUnit,
            declaredValue: p.declaredValue ? Number(p.declaredValue) : undefined,
          }
        }),
      ],
    } : {}),
    // Currency moved to top-level (always sent). Intl still carries the
    // customs-specific extras (items, reason for export, incoterms).
    ...(isInternational ? {
      items: cleanItems,
      reasonForExport,
      incoterms,
      ...(clearanceOption ? { clearanceOption } : {}),
      ...(/THIRD/.test(clearanceOption.toUpperCase()) && dutiesAccount.trim() ? { dutiesAccount: dutiesAccount.trim() } : {}),
      ...(ftrExemption ? { ftrExemption } : {}),
      ...(aesCitation ? { aesCitation } : {}),
      ...(exportDeclarationReference ? { exportDeclarationReference } : {}),
    } : {}),
    ...(isInternational && override ? { importer: override.importer, broker: override.broker } : {}),
    // Shipping charges billed to a third party's carrier account. SENDER is
    // the backend default, so it is only sent when it is not the default.
    ...(billTo === 'THIRD_PARTY' && thirdPartyAccount.trim() ? {
      billTo,
      thirdPartyAccount: thirdPartyAccount.trim(),
      ...(thirdPartyPostalCode.trim() ? { thirdPartyPostalCode: thirdPartyPostalCode.trim() } : {}),
      ...(thirdPartyCountry ? { thirdPartyCountry } : {}),
    } : {}),
    // B6 — thread the NDS batch id + per-container package rows through
    // to the writeback so NdsShipmentOracleWriter can fan CLIPPER updates
    // across every sibling order in the batch. Only when the shipment
    // came from an NDS scan; manual entry leaves both null.
    ...(active?.batchId
        ? { ndsBatchId: active!.batchId }
        : {}),
    ...(active?.packages?.length
        ? {
            ndsPackages: active!.packages.map((pk, idx) => ({
              sequence: idx + 1,
              containerNo: pk.containerNo ?? null,
              containerIds: pk.containerIds ?? [],
              orderNos: pk.orderNos ?? [],
              orderSuffix: pk.orderSuffix ?? null,
              weight: pk.weight != null ? Number(pk.weight) : null,
              // Oracle NDS doesn't carry a weight unit on the package;
              // use the owning client's defaultWeightUnit so the number
              // is reported in the same unit the client works in. 'LB'
              // when the client isn't resolved or defaultWeightUnit is
              // null (keeps the pre-fix wire shape).
              weightUnit: ((clients.find((c) => c.clientCode === clientCode)?.defaultWeightUnit) === 'KG' ? 'KG' : 'LB') as 'LB' | 'KG',
            })),
          }
        : {}),
  }
  return payload
}
