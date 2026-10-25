import { useEffect, useMemo, useRef, useState } from 'react'
import { useLocation, useNavigate, useSearchParams } from 'react-router-dom'
import { notify } from '../../utils/notify'
import { ApiError } from '../../api/apiClient'
import {
  orderService,
  type ManualShipmentAddress,
  type ManualShipmentPayload,
  type SplitRequiredPayload,
  type SplitStrategy, newAttemptKey } from '../../api/orderService'
import { accountRefService, type CarrierAccountRef } from '../../api/accountRefService'
import { clientService, type Client } from '../../api/clientService'
import { customsProfileService, type CustomsProfile } from '../../api/customsProfileService'
import { shippingConfigService, type ShippingServiceItem, type PackagePreset, type ServicePackageLink } from '../../api/shippingConfigService'
import { customsService } from '../../api/customsService'
import { dtcService, type DtcOrder } from '../../api/dtcService'
import { mapCarrierErrorToFields, summarizeCarrierError } from '../../utils/carrierErrorMap'
import { isDuplicateSave, recipientBookService, type SavedRecipient } from '../../api/recipientBookService'
import { clientWarehouseService, type ClientWarehouse } from '../../api/warehouseService'
import {
  clientAllowedPackagesService,
  clientAllowedServicesService,
} from '../../api/clientCatalogService'
import {
  clientDestinationsService,
  type ClientDestinationRules,
} from '../../api/clientPolicyService'
import { aiService, type ShipmentWarning } from '../../api/aiService'
import { type Party } from '../modals/ShipmentPartiesOverrideModal'
import { customFieldService } from '../../api/customFieldService'
import { type PastedItem } from '../modals/parsePastedItems'
import type { RateShopRequest } from '../../api/rateShopService'
import type { LandedCostRequest } from '../../api/landedCostService'
import type { CustomsItem, OrderCustomsPayload } from '../../api/customsService'
import { parseIntlValidationMessage } from '../../utils/intlValidationErrors'
import { useFormik, getIn } from 'formik'
import { shipmentSchema, type ShipmentFormValues } from '../../validation/yup/shipmentSchema'
import { dialCodeFor } from '../../utils/countryFormats'
import { canon } from '../../utils/carrierCanon'
import { readSticky, writeSticky } from '../../utils/sticky'
import { useManualShipment, blankAddress, blankItem, type ExtraPackage, type NewShipmentItemRow } from '../../shipment/useManualShipment'
import { buildShipmentPayload } from '../../shipment/buildShipmentPayload'
import { fieldErrorsFromIssues } from '../../utils/serverFieldErrors'
import { usePendingAttempt, rememberPendingAttempt, forgetPendingAttempt, settlePendingAttempt } from '../../hooks/usePendingAttempt'
import { shipperFieldsFrom, recipientFieldsFrom } from '../../utils/shipmentAddressFields'
import { compatiblePresetIds } from '../../utils/servicePackageCompatibility'
import { shipmentValidationService, type ShipmentValidationResult } from '../../api/shipmentValidationService'
import {
  ndsShipmentService,
  type NdsShipmentPrefill,
  type NdsPrefillStatus,
  type NdsMessage,
} from '../../api/ndsShipmentService'
import { clearanceOptionsForCarrier, EEI_THRESHOLD_USD, setDbClearanceByCarrier, setDbPickupByCarrier, setDbLabelFormatByCarrier } from '../../utils/customsOptions'
import { carrierDropdownsService } from '../../api/carrierDropdownsService'
import { isServiceAllowedForUsTerritory, isUpsDdpDisallowedForTerritory } from '../../utils/usTerritoryServices'
import { fieldLabelFor } from '../../utils/fieldLabels'
import { useKnownCarriers, toCarrierLabelMap } from '../../hooks/useKnownCarriers'
import { useShipperDefault } from '../../hooks/useShipperDefault'
import type { ResolvedShipper } from '../../api/shipperResolveService'


// F5-C — COUNTRIES / COUNTRY_NAME / REGION_NAMES / countryNameFor +
// CountrySelect component moved to ./NewShipmentComponents/CountrySelect.tsx.
// Re-imported here so existing call sites in NewShipmentPage keep working
// while the sub-components live in their own files.

// Reason of export choices come from the canonical SHIPPING_PURPOSES
// list (customsOptions.ts) so what the operator picks matches the
// backend's SHIPPING_PURPOSE_ENUM byte-for-byte. Prior 6-value
// hardcode (SALE|GIFT|SAMPLE|RETURN|REPAIR|PERSONAL) sent invalid
// enum values (REPAIR / PERSONAL not accepted; DOCUMENTS /
// MERCHANDISE / REPAIR_AND_RETURN / PERSONAL_USE missing).
// BUG-125 — shared with CustomsProfileModal + ClientMarkup/IdentityStep so
// the three pickers offer the same 15 currencies. Previous page-local
// 10-currency list left MXN/NZD/HKD/BRL/CHF choosable on the client
// profile but not on the order form.
import { CURRENCY_ALLOWLIST } from '../../utils/money'

const CURRENCIES = CURRENCY_ALLOWLIST


/**
 * C2 — build a ship-from address from the DB-driven resolved shipper.
 * When the resolve hasn't returned yet (or fails), returns a blank
 * address so the form starts empty rather than showing a stale
 * hardcoded NYC address that never applies to any real tenant.
 */
const defaultSenderFrom = (resolved: ResolvedShipper | null): ManualShipmentAddress => {
  if (!resolved) return blankAddress()
  const country = (resolved.countryCode ?? '').trim() || 'US'
  return {
    name:            resolved.name ?? '',
    company:         '',
    phone:           resolved.phone ?? '',
    email:           '',
    addressLine1:    resolved.addressLine1 ?? '',
    addressLine2:    resolved.addressLine2 ?? '',
    city:            resolved.city ?? '',
    state:           resolved.state ?? '',
    postalCode:      resolved.postalCode ?? '',
    countryCode:     country,
    phoneCountryCode: dialCodeFor(country) || '',
    }
}


const CUSTOM_PKG = 'CUSTOM'

/** Join non-empty parts with a separator (skips blanks). */
const joinParts = (parts: (string | null | undefined)[], sep = ', ') =>
  parts.map((p) => (p || '').trim()).filter(Boolean).join(sep)

/** US-territory ISO codes carriers treat as "international" for customs
 *  paperwork purposes (commercial invoice, FTR/AES, incoterms) even though
 *  they're US soil. Hoisted to module scope so useMemo deps around it
 *  don't churn on every render. */
const US_TERRITORY_CODES = new Set(['PR', 'VI', 'GU', 'AS', 'MP', 'UM'])


/**
 * Manual Shipment redesign, Phase 3b — every hook, derived value and handler
 * of the New Shipment form, in one place; the page and its section components
 * only render. Phase 3b discovers the surface by tsc; a later pass splits it
 * per section.
 */
export type NewShipmentModel = ReturnType<typeof useNewShipmentModel>

export function useNewShipmentModel() {
  const navigate = useNavigate()
  const [searchParams] = useSearchParams()
  // Returns F4 — /orders/new/return opens the form with mode preselected
  // to RETURN so operators have a direct link instead of filling in and
  // then flipping the toggle.
  const location = useLocation()
  const isReturnPath = location.pathname.endsWith('/return')
  // Fix-a-failed-order mode: /orders/new?fixOrder=900046 pre-fills this form
  // with the failed order's data + carrier error, and submitting regenerates
  // that same order in place instead of creating a new one.
  const fixOrderNo = (() => {
    const n = Number(searchParams.get('fixOrder'))
    return Number.isInteger(n) && n > 0 ? n : null
  })()
  // Phase 3b — wire-relevant fields live in one reducer; the setters keep
  // React's setState contract so every handler below is unchanged.
  const { form, setters: formSetters } = useManualShipment({ isReturnPath })
  const {
    sender, recipient, note, mode, returnType, returnPickupRequested, rmaNumber, returnReason, originalOrderNo, fixChannel, carrier, accountNumber, serviceId, incoterms, clearanceOption, dutiesAccount, packageChoice, length, width, height, dimUnit, weight, weightUnit, declaredValue, reference, clientCode, warehouseCode, ndsQueue, ndsActiveIdx, ndsStdReplacementErpCode, signatureOption, insuredValue, labelImageType, labelStockType, labelImageFormat, pickupType, dgBlock, extraPackages, customFieldValues, items, reasonForExport, currency, ftrExemption, aesCitation, exportDeclarationReference, override,
  } = form
  const {
    setSender, setRecipient, setNote, setMode, setReturnType, setReturnPickupRequested, setRmaNumber, setReturnReason, setOriginalOrderNo, setFixChannel, setCarrier, setAccountNumber, setServiceId, setIncoterms, setClearanceOption, setDutiesAccount, setPackageChoice, setLength, setWidth, setHeight, setDimUnit, setWeight, setWeightUnit, setDeclaredValue, setReference, setClientCode, setWarehouseCode, setNdsQueue, setNdsActiveIdx, setNdsStdReplacementErpCode, setSignatureOption, setInsuredValue, setLabelImageType, setLabelStockType, setLabelImageFormat, setPickupType, setDgBlock, setExtraPackages, setCustomFieldValues, setItems, setReasonForExport, setCurrency, setFtrExemption, setAesCitation, setExportDeclarationReference, setOverride,
  } = formSetters
  const [fixError, setFixError] = useState<string | null>(null)
  /** True when the order being reopened is a VOIDED (reissue) rather than a failed one. */
  const [fixVoided, setFixVoided] = useState(false)
  const [fixLoading, setFixLoading] = useState<boolean>(!!fixOrderNo)
  /** The fixed order's own channel, re-sent on regenerate so it isn't reclassified from the recipient. */
  // D2C-line mode: /orders/new?dtcLine=11&batch=141&tenant=ARHDEV opens a D2C
  // line whose label run failed before any order existed. The form is
  // pre-filled from the line, and the label it buys is linked back to the line
  // so the batch shows it as generated (and never buys it again).
  const dtcLineId = (() => {
    const n = Number(searchParams.get('dtcLine'))
    return Number.isInteger(n) && n > 0 && !fixOrderNo ? n : null
  })()
  const dtcBatch = searchParams.get('batch') ?? ''
  const dtcTenant = searchParams.get('tenant') ?? ''
  const [dtcLine, setDtcLine] = useState<DtcOrder | null>(null)
  const dtcAppliedRef = useRef(false)

  const [accounts, setAccounts] = useState<CarrierAccountRef[]>([])
  const [services, setServices] = useState<ShippingServiceItem[]>([])
  const [packages, setPackages] = useState<PackagePreset[]>([])
  // Sprint 52 PR 2 — service_package many-to-many. Populated from
  // shippingConfigService.catalog().links. Drives the packaging dropdown
  // filter: only CARRIER-kind presets linked to the picked service show.
  // Mirrors the backend PackagingCompatibilityGuard (Sprint 52 PR 1) so
  // the operator can't pick a combo the server would reject.
  const [servicePackageLinks, setServicePackageLinks] = useState<ServicePackageLink[]>([])
  const [clients, setClients] = useState<Client[]>([])
  const [profiles, setProfiles] = useState<CustomsProfile[]>([])
  // Per-shipment importer/broker override (null = use the client's saved profile).
  const [overrideEditorOpen, setOverrideEditorOpen] = useState(false)
  const [loading, setLoading] = useState(true)
  const [submitting, setSubmitting] = useState(false)
  // Commodity auto-split modal (2026-09-06). Populated when the manual-
  // shipment submit returns 422 SPLIT_REQUIRED — the payload lists
  // strategy options; on operator pick, we re-submit with splitStrategy.
  const [splitPrompt, setSplitPrompt] = useState<SplitRequiredPayload | null>(null)
  const [pendingSplitPayload, setPendingSplitPayload] = useState<ManualShipmentPayload | null>(null)
  /**
   * PR-G4 (USPS_DIRECT UX) — set when the submit response comes back
   * with {@code status='QUEUED'} or {@code 'QUEUED_MPS'}. Under
   * USPS_DIRECT the label was parked in the 55/hr platform queue;
   * we surface a compact "Queued for USPS" panel inline instead of
   * navigating to /label/{n} (there IS no label yet). {@code isMps}
   * gates the {@code MpsProgressCard} render.
   */
  const [queuedInfo, setQueuedInfo] = useState<{
    orderNo: number | null
    queueItemId: number | null
    isMps: boolean
    mpsPieceCount: number | null
  } | null>(null)

  // C2 — seed blank; the effect below fills in from resolvedShipper as
  // soon as the /me/shipper-default resolve returns (if the operator
  // hasn't started typing already).

  // C2 — apply the DB-driven shipper default once the /me/shipper-default
  // resolve returns. Skip when the operator has already typed anything into
  // V120 + V121 swap seam — prefetch all three per-carrier dropdown
  // vocabularies once per mount. Admin-only route (401 for non-admin
  // swallowed; each picker falls through to bootstrap defaults in
  // customsOptions.ts). Three calls fire in parallel.
  useEffect(() => {
    void (async () => {
      const groupByCarrier = <T extends { carrier: string; code: string; label: string }>(rows: T[]) => {
        const out: Record<string, Array<{ value: string; label: string }>> = {}
        for (const r of rows) {
          const key = r.carrier.toUpperCase()
          if (!out[key]) out[key] = []
          out[key].push({ value: r.code, label: r.label })
        }
        return out
      }
      try {
        const [clearance, pickups, labelFormats] = await Promise.all([
          carrierDropdownsService.clearanceOptions(),
          carrierDropdownsService.pickupTypes(),
          carrierDropdownsService.labelFormats(),
        ])
        setDbClearanceByCarrier(groupByCarrier(clearance))
        setDbPickupByCarrier(groupByCarrier(pickups))
        setDbLabelFormatByCarrier(groupByCarrier(labelFormats))
      } catch {
        // Non-admin users 401 here — bootstrap stays authoritative.
      }
    })()
  }, [])

  // Note — the resolvedShipper → setSender/setRecipient useEffect used
  // to live here, but it referenced isReturn + resolvedShipper (both
  // declared later in the component) inside its deps array. Const TDZ
  // meant every render threw a ReferenceError at the deps array
  // evaluation — which silently broke every test on this page for
  // weeks. Moved below resolvedShipper's declaration + the `isReturn`
  // `const` so references resolve. See the effect at line ~390.
  /** V76 — internal per-order ops note (500 char, multi-line). Placed
   *  under the Ship From section for lack of a better home; the data
   *  attaches to the order as a whole. Not sent to any external
   *  surface (label / carrier / webhook) — internal ops only. */
  /**
   * Origin of the recipient.residential flag — 'auto' when we ticked
   * it because the operator picked a service that requires residential
   * (e.g. FedEx GROUND_HOME_DELIVERY), 'manual' when the operator
   * ticked it themselves, undefined when the flag is unset. Drives the
   * "un-tick when switching AWAY from Home Delivery" behaviour so we
   * only revert flags WE set — an operator's deliberate tick
   * ('manual') is sacred and never auto-unticked.
   */
  const [residentialOrigin, setResidentialOrigin] = useState<'auto' | 'manual' | undefined>(undefined)
  // SHIPMENT = outbound (you → customer); RETURN = reverse (customer → you).
  // Returns F4 — initial mode comes from the URL path when the user lands
  // via /orders/new/return; the mode toggle still works after that.
  const isReturn = mode === 'RETURN'
  /**
   * How the carrier should deliver the return label — only meaningful when
   * isReturn=true. PRINT = carrier returns the label PDF to us (default);
   * EMAIL = carrier emails the label directly to the customer (requires
   * the customer's email on the sender block). Threaded to
   * ManualShipmentRequest.returnType → ShipmentRequestDTO.returnType →
   * the UPS ReturnService.Code + LabelDelivery.EMail block, and mirrored
   * on FedEx (Code 9 = ELECTRONIC_SHIPPING_INFORMATION emails the label).
   */
  // Returns F8 — DHL Global Return pickup toggle. Default true = carrier
  // schedules collection (prior behaviour); false = customer drops at a
  // DHL ServicePoint. UI only shows when isReturn && carrier=DHL.
  // Returns F10 — RMA number operator issued for this return. Persisted on
  // label_batch.rma_number so warehouse can reconcile. UI only on return mode.
  // Returns F11 — canonical reason code. Empty = unselected (will persist
  // as NULL and roll up under "Unknown"). Enum must stay in sync with
  // CarrierServiceImpl.RETURN_REASON_CODES.
  // Returns F12 — outbound order no this return came from. Server drops
  // invalid refs to null so a typo doesn't block the label.
  /**
   * UPS rejects return labels with 9120145 "Missing label delivery
   * information" when the LabelDelivery.EMail block is absent — and we
   * can't build that block without the customer's email. Also required
   * for EMAIL mode on every carrier. Same rule for both modes on returns
   * to keep the UI predictable; the boundary guard on CarrierServiceImpl
   * also throws on this to catch programmatic callers.
   */
  const returnEmailMissing = isReturn && !sender.email?.trim()

  // C4 — DB-driven carrier registry (carrier_alias). Retires the hardcoded
  // KNOWN_CARRIERS + CARRIER_LABEL constants. First render uses a fallback
  // (4 canonicals); the DB list overrides on the next tick.
  const knownCarriers = useKnownCarriers()
  const knownCarrierCodes = useMemo(() => knownCarriers.map((c) => c.code), [knownCarriers])
  const carrierLabelMap = useMemo(() => toCarrierLabelMap(knownCarriers), [knownCarriers])

  // Incoterms prefills from ClientCustomsProfile.incoterms via the
  // resolved importerProfile (per client + destination country). On
  // lanes with no saved profile, falls back to the operator's last
  // picked value (sticky, matches reason/currency). Submit guard
  // still blocks blank + intl so force-pick safety survives.
  // F6-C — per-carrier customs clearance option. Prefills from
  // CarrierAccountRef.clearanceOption when the value is in the current
  // carrier's vocabulary (UPS SENDER/RECEIVER/THIRD_PARTY vs FedEx
  // SENDER/RECIPIENT/THIRD_PARTY vs USPS DDU/DDP vs DHL DAP/DDP/EXW).
  // Blank = backend connector picks its own default (usually sender-
  // pays / DAP). International-only; hidden on domestic.
  /** Payer's carrier account when duties are billed to a third party. */
  // Seed from sticky so returning operators don't see 'IN' flash on
  // every visit; client.defaultDimUnit still wins when it resolves
  // (applyClient at :1525), unless the operator has already touched
  // the toggle this session.
  const [dimUnitTouched, setDimUnitTouched] = useState(false)
  // Same shape as dimUnit — sticky seed, operator pick survives client
  // switch via weightUnitTouched. AI Suggest + customs-wizard Save still
  // force the unit on purpose (AI returns LB; wizard = fresh truth).
  const [weightUnitTouched, setWeightUnitTouched] = useState(false)
  /** Customer reference / PO — stored as the order's Ref #, printed on the label and invoice,
   *  and what the importer's duplicate guard matches on. */

  // C2 — DB-driven ship-from default (tenant_settings.shipper.*). Retires
  // the hardcoded 350 5th Ave NYC block. Refetches whenever the operator
  // picks a different client, so cross-tenant users get the right defaults.
  const resolvedShipper = useShipperDefault(clientCode || undefined)

  // When resolvedShipper first lands (or the operator changes mode
  // between SHIPMENT and RETURN), apply it to the still-untouched
  // sender / recipient so we never clobber their input. Moved here
  // from the top of the component — see the TDZ comment at line ~274
  // for why.
  useEffect(() => {
    if (!resolvedShipper) return
    const filled = defaultSenderFrom(resolvedShipper)
    const untouched = (a: ManualShipmentAddress) =>
      !a.name && !a.addressLine1 && !a.city && !a.postalCode
    if (isReturn) {
      setRecipient((cur) => (untouched(cur) ? filled : cur))
      // BUG-136 R6: Return mode's "Return from · customer" sender block
      // used to inherit the warehouse address from an earlier SHIPMENT
      // paint — operator saw the client's own warehouse as the return
      // origin, which is never right (the customer is the shipper on a
      // return). Clear the sender when it still holds the exact warehouse
      // address so the operator fills in the customer's address instead;
      // leave anything the operator has already typed alone.
      setSender((cur) =>
        cur.name === filled.name
          && cur.addressLine1 === filled.addressLine1
          && cur.postalCode === filled.postalCode
          ? blankAddress() : cur,
      )
    } else {
      setSender((cur) => (untouched(cur) ? filled : cur))
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps -- deliberately reactive to resolvedShipper only; sender/recipient are checked inside the setter.
  }, [resolvedShipper, isReturn])
  // NDS Shipment prefill (PR #735 backend / PR2 FE): operator scans
  // .X<containerId> or .Y<batchId>; response prefills client + recipient
  // + packages + notify + international items. Banner colours mirror
  // the response's Status enum.
  const [ndsScan, setNdsScan] = useState('')
  const [ndsLoading, setNdsLoading] = useState(false)
  const [ndsBanner, setNdsBanner] = useState<
    { status: NdsPrefillStatus; messages: NdsMessage[]; text?: string } | null
  >(null)
  // Multi-container scan (2026-09-28): operator enters comma/space-separated
  // container/batch tokens. Each lookup returns the full order (all sibling
  // packages), so we dedup by orderNo. Same order → filled as-is. Multiple
  // orders → this page loads the first; the rest sit in the "Queued
  // shipments" chip strip below the scan input for click-to-swap.
  /** Tracks phone/email fields the backend flagged as `defaulted` so a
   *  later sender-side fill can override the fallback value. */
  const senderFallbackPendingRef = useRef<{ phone: boolean; email: boolean } | null>(null)
  // G6 — STD-replacement erp_code from the NDS prefill; sent on submit so
  // NdsShipmentOracleWriter can UPDATE OEHEAD.SHIPVIA_CD after label success.
  // Sprint 35 — signature at delivery + insured value beyond the
  // carrier's free tier. Signature is a per-shipment enum; insured
  // value is a separate money amount from declared/customs value.
  // FDX-H3 — per-shipment override of the FedEx account's default
  // labelSpecification. Blank = use the account default.
  // Per-shipment override of the account's default label file format —
  // UPS/DHL/USPS only (FedEx uses labelImageType/labelStockType above).
  // Blank = use the account default.
  // FDX-H1 — per-shipment override of the FedEx account's default
  // pickupType. Prefills from CarrierAccountRef.pickupType on account
  // change; falls back to USE_SCHEDULED_PICKUP when the account has
  // NULL (matches the backend's hardcoded default so the operator
  // sees the effective value in the UI). FedEx-only; hidden in
  // Return mode (connector always emits CONTACT_FEDEX_TO_SCHEDULE
  // for returns regardless).
  // Sticky seed; carrier-account default (:1340) wins on carrier/account
  // resolve only when the operator hasn't touched the dropdown.
  const [pickupTypeTouched, setPickupTypeTouched] = useState(false)
  // Sprint 27 — dangerous goods declaration. Null on non-hazmat shipments;
  // populated by DangerousGoodsWizard and threaded into the manual
  // shipment payload as dangerousGoods.
  const [dgWizardOpen, setDgWizardOpen] = useState(false)
  // Sprint 32 — landed cost modal. Opens on demand for cross-border
  // shipments; backend routes to UPS Landed Cost / FedEx EDT / DHL
  // Duties + Taxes based on the carrier the operator picked.
  const [landedCostOpen, setLandedCostOpen] = useState(false)
  // Sprint 29 — multi-package. The first box uses the existing top-level
  // weight/dims/packaging fields; extraPackages holds boxes 2..N. When
  // non-empty the payload emits packages[] (backend keys off effective-
  // Packages()); when empty the existing single-package payload is sent.
  const blankExtraPackage = (): ExtraPackage => ({
    weight: '', length: '', width: '', height: '',
    packageType: '', declaredValue: '',
  })
  /**
   * UPS's Ship API rejects multi-package returns natively — its
   * documented workaround is one API call per box. The backend
   * (UpsConnector.createSplitReturnShipment) does that split
   * transparently so the operator can still add multiple boxes here
   * and get a single order with N return-label pieces. This flag
   * lights the informational note only; it does NOT block submit.
   */
  const upsReturnMultiPkgNote = isReturn && canon(carrier) === 'UPS' && extraPackages.length > 0
  // Bulk-entry contracts for multi-package orders (a 45-box shipment is a
  // normal day, not an edge case): duplicate a box, add N like the last,
  // apply box 1 everywhere, and collapse filled boxes to one-line summaries.
  const [expandedBoxes, setExpandedBoxes] = useState<Set<number>>(new Set())
  const [bulkAddCount, setBulkAddCount] = useState(() => readSticky('ms:lastBulkAddCount', '5'))
  const toggleBox = (idx: number) =>
    setExpandedBoxes((cur) => { const n = new Set(cur); if (n.has(idx)) n.delete(idx); else n.add(idx); return n })
  const duplicateBox = (idx: number) =>
    setExtraPackages((cur) => { const next = [...cur]; next.splice(idx + 1, 0, { ...cur[idx] }); return next })
  const removeBox = (idx: number) => {
    setExtraPackages((cur) => cur.filter((_, i) => i !== idx))
    setExpandedBoxes(new Set())
  }

  // Sprint 43 — custom field values keyed by fieldKey.

  // 3PL guardrails: client's attached warehouses, allowlists, and destination
  // rules. Fetched when clientCode changes; drive the warehouse picker,
  // filtered dropdowns, and the ship-to warning banner.
  const [clientWarehouses, setClientWarehouses] = useState<ClientWarehouse[]>([])
  /** Fix-order mode: suppress the ONE-TIME default-warehouse auto-pick on
   *  arrival so the order's prefilled sender isn't overlaid (defect 3.7/§6). */
  const fixKeepSenderRef = useRef<boolean>(!!fixOrderNo)
  /** Set of service ids on the client's allowlist. null = client has no
   *  allowlist yet, treat as unrestricted so shipments still ship. */
  const [allowedServiceIds, setAllowedServiceIds] = useState<Set<number> | null>(null)
  const [allowedPackageIds, setAllowedPackageIds] = useState<Set<number> | null>(null)
  /** Client's default from ClientAllowedService.isDefault — used to preselect
   *  the picker on client change rather than falling back to sort-order first. */
  const [defaultServiceId, setDefaultServiceId] = useState<number | null>(null)
  const [defaultPackagePresetId, setDefaultPackagePresetId] = useState<number | null>(null)
  const [destRules, setDestRules] = useState<ClientDestinationRules | null>(null)

  const [carrierValidating, setCarrierValidating] = useState(false)
  // Sprint 52 — full shipment-validation result; drives the checklist.
  const [shipmentValidationResult, setShipmentValidationResult] = useState<ShipmentValidationResult | null>(null)
  /** When the check ran, and the request it checked — to say "the form changed". */
  const [validatedAt, setValidatedAt] = useState<Date | null>(null)
  const [validatedRequest, setValidatedRequest] = useState('')
  const [checklistOpen, setChecklistOpen] = useState(false)
  // Sprint 38 — saved recipients: address-book search + save UI.
  const [recipientSearch, setRecipientSearch] = useState('')
  const [recipientSuggestions, setRecipientSuggestions] = useState<SavedRecipient[]>([])
  const [recipientDropdownOpen, setRecipientDropdownOpen] = useState(false)
  /** Keyboard-highlighted suggestion (-1 = none). */
  const [recipientActive, setRecipientActive] = useState(-1)
  /** A search finished with nothing — show "No saved addresses match". */
  const [recipientNoMatch, setRecipientNoMatch] = useState(false)
  // Debounce + stale-response guard: typing "chicago" used to fire six
  // requests, and a slow early one could land last and show the wrong list.
  const recipientSearchTimer = useRef<ReturnType<typeof setTimeout> | null>(null)
  const recipientSearchSeq = useRef(0)

  // AI-assist per-section busy flags + pre-ship review result.
  const [pkgBusy, setPkgBusy] = useState(false)
  const [svcBusy, setSvcBusy] = useState(false)
  const [reviewBusy, setReviewBusy] = useState(false)
  const [reviewWarnings, setReviewWarnings] = useState<ShipmentWarning[] | null>(null)

  // International commercial-invoice line items (shown only for cross-border lanes).
  // Sprint 48 B11 — each item can be assigned to a physical package (boxSeq).
  // When every item is assigned, backend derives per-package declared value
  // from sum(unitValue × quantity in that box). null boxSeq = unassigned
  // (falls into box 1 by default; treated as legacy single-box CI).
  type ItemRow = NewShipmentItemRow
  // Every line belongs to a box from the start (box 1 by default) so the
  // commercial invoice can always say which package holds which lines.
  // Reason of export: force-pick guard in submit/validate still fires
  // when blank + intl (PR #529). Sticky-read seeds the dropdown with
  // the operator's last pick as a hint — convenience, not a safety
  // release — so lane changes (CA → DE) don't drop them back to
  // "-- Select --" when the client has no saved CustomsProfile.
  // EEI — US Foreign Trade Regulations §30.37 exemption code (from the
  // FTR_EXEMPTIONS list) or the AES ITN filed with US Census. Mutually
  // exclusive; validation banner fires on high-value US exports without
  // either. Not sticky — the ITN is per-shipment.
  // Generic export declaration reference — non-US origins (CA B13A / GB
  // CDS / EU MRN / AU EDN / JP declaration / IN SB). Backend WARNs (not
  // errors) on high-value intl when this + ftrExemption + aesCitation
  // are all blank.

  // Optional guided-wizard for the customs section. Off by default; users
  // click "Open guided wizard" to enter a 4-step flow that maps onto the
  // same state as the inline form. Closing without Save preserves the
  // inline form's current values.
  const [wizardOpen, setWizardOpen] = useState(false)

  useEffect(() => {
    let alive = true
    ;(async () => {
      try {
        const [accs, catalog, presets, clientPage] = await Promise.all([
          accountRefService.listAccounts(),
          shippingConfigService.catalog(),
          shippingConfigService.listPresets(),
          clientService.listClients({ status: 'ACTIVE', size: 200 }),
        ])
        if (!alive) return
        setAccounts(accs.filter((a) => a.active))
        setServices(catalog.services.filter((s) => s.enabled))
        setPackages(presets)
        setServicePackageLinks(catalog.links)
        setClients(clientPage.data?.content ?? [])
      } catch (e) {
        notify.apiError(e, 'Failed to load shipment options.')
      } finally {
        if (alive) setLoading(false)
      }
    })()
    return () => {
      alive = false
    }
  }, [])

  // Carrier options split into two pools so the dropdown can render
  // the client's own carrier accounts first, verified platform
  // accounts underneath:
  //   - `clientCarriers` — accounts whose `customerNo` matches the
  //     picked client. Empty when no client is picked or the client
  //     has no accounts. NOT verification-filtered — client-owned
  //     accounts are the operator's own credentials.
  //   - `platformCarriers` — accounts with null `customerNo` (shared
  //     across all clients) AND `verified === true` (last credential
  //     check succeeded). Unverified platform accounts are hidden to
  //     prevent operators picking a carrier the platform can't
  //     actually ship on.
  // Both are further intersected with `services` (a carrier with no
  // live service can't ship regardless of account state).
  const svcCarrierSet = useMemo(
    () => new Set(services.map((s) => canon(s.carrier))),
    [services],
  )
  const clientCarriers = useMemo(() => {
    if (!clientCode) return [] as string[]
    const own = accounts.filter(
      (a) => (a.customerNo || '').toUpperCase() === clientCode.toUpperCase(),
    )
    return [...new Set(own.map((a) => canon(a.carrierCode)))]
      .filter((c) => svcCarrierSet.has(c))
      .sort()
  }, [accounts, clientCode, svcCarrierSet])
  const platformCarriers = useMemo(() => {
    const plat = accounts.filter((a) => !a.customerNo && a.verified === true)
    return [...new Set(plat.map((a) => canon(a.carrierCode)))]
      .filter((c) => svcCarrierSet.has(c) && !clientCarriers.includes(c))
      .sort()
  }, [accounts, svcCarrierSet, clientCarriers])
  const carrierOptions = useMemo(
    () => [...clientCarriers, ...platformCarriers],
    [clientCarriers, platformCarriers],
  )
  /**
   * Whether ANY shippable carrier exists in the workspace — client-owned
   * accounts for ANY client count, not just the picked one. carrierOptions
   * alone can't answer this: before a client is picked clientCarriers is
   * empty, and when no VERIFIED PLATFORM account exists platformCarriers is
   * empty too — the page then wrongly showed the red "No carriers connected"
   * blocker even though a verified client account (FedEx/ACME) could ship.
   */
  const anyShippableCarrier = useMemo(
    () => accounts.some((a) =>
      (a.customerNo || a.verified === true) && svcCarrierSet.has(canon(a.carrierCode))),
    [accounts, svcCarrierSet],
  )

  useEffect(() => {
     
    if (!carrier && carrierOptions.length) setCarrier(carrierOptions[0])
  }, [carrierOptions, carrier, setCarrier])

  // The selected client's importer/broker profiles (per destination-country set).
  useEffect(() => {
    if (!clientCode) {
      // eslint-disable-next-line react-hooks/set-state-in-effect -- clear stale profiles when client is cleared; async fetch below repopulates for a non-empty client
      setProfiles([])
      return
    }
    let alive = true
    customsProfileService
      .list(clientCode)
      .then((ps) => alive && setProfiles(ps))
      .catch(() => alive && setProfiles([]))
    return () => {
      alive = false
    }
  }, [clientCode])

  // Client-scoped 3PL config: warehouses, service/package allowlists, ship-to
  // rules. Load in one round-trip; the page silently degrades on failure so a
  // half-configured client still ships.
  useEffect(() => {
    if (!clientCode) {
      // eslint-disable-next-line react-hooks/set-state-in-effect -- reset client-scoped config when no client is picked; the async fetch below repopulates for a non-empty client
      setClientWarehouses([])
      setWarehouseCode('')
      setAllowedServiceIds(null)
      setAllowedPackageIds(null)
      setDefaultServiceId(null)
      setDefaultPackagePresetId(null)
      setDestRules(null)
      return
    }
    let alive = true
    ;(async () => {
      try {
        const [whResp, svcResp, pkgResp, destResp] = await Promise.all([
          clientWarehouseService.listForClient(clientCode),
          clientAllowedServicesService.listForClient(clientCode),
          clientAllowedPackagesService.listForClient(clientCode),
          clientDestinationsService.get(clientCode),
        ])
        if (!alive) return
        const warehouses = whResp.data ?? []
        setClientWarehouses(warehouses)
        // Auto-pick the default; else first available. Empty = keep whatever
        // sender address applyClient already produced.
        // Fix-order mode: skip the auto-pick ONCE on arrival — the sender was
        // prefilled from the ORDER, and auto-selecting the default warehouse
        // overlaid its name before the operator ever saw the screen. Picking
        // a warehouse deliberately afterwards still overlays as usual.
        if (fixKeepSenderRef.current) {
          fixKeepSenderRef.current = false
          setWarehouseCode('')
        } else {
          const def = warehouses.find((w) => w.isDefault) || warehouses[0] || null
          setWarehouseCode(def?.warehouse?.code ?? '')
        }
        // Empty allowlists mean "not yet configured" — treat as unrestricted so
        // shipping still works. Phase 4 flips this to strict once the backend
        // resolver rejects on empty.
        const svcs = svcResp.data ?? []
        setAllowedServiceIds(svcs.length ? new Set(svcs.map((s) => s.serviceId)) : null)
        setDefaultServiceId(svcs.find((s) => s.isDefault)?.serviceId ?? null)
        const pkgs = pkgResp.data ?? []
        setAllowedPackageIds(pkgs.length ? new Set(pkgs.map((p) => p.presetId)) : null)
        setDefaultPackagePresetId(pkgs.find((p) => p.isDefault)?.presetId ?? null)
        setDestRules(destResp.data ?? null)
      } catch {
        // Silent degrade: same behaviour as before the 3PL settings existed.
      }
    })()
    return () => {
      alive = false
    }
  }, [clientCode, setWarehouseCode])

  // When the picked warehouse changes, overwrite the sender block with the
  // warehouse's ADDRESS. Identity fields (name/company) are NOT touched —
  // the warehouse is where the parcel ships from, but the shipper on the
  // label is the client (applyClient set it); overwriting printed the
  // facility alias ("Main Fulfillment Center") as the shipper on labels
  // and commercial invoices instead of the client's company.
  //
  // Audit #276 — the partial-fill (`a.city ? { city } : {}`) is DELIBERATE,
  // not a bug. Picking a warehouse whose address has holes (e.g.
  // `city = null` for a drop-ship-only location) PRESERVES the operator's
  // typed city instead of clearing it. If this ever becomes "operator
  // expected a full reset" (no repro reported as of filing), the fix is
  // to overlay `{ ...blankAddress(), ...filledFields }` instead of
  // `{ ...cur, ...filledFields }` — swap `cur` on the first spread.
  useEffect(() => {
    if (!warehouseCode) return
    // BUG-011 — in return mode sender is the CUSTOMER (Return from),
    // not the merchant warehouse. Painting the client's warehouse
    // address onto sender on return mode caused the customer block to
    // show '8191 Logistics Dr' (merchant address) with only name blank;
    // operator who didn't notice could cut a label from the merchant's
    // own address. Merchant side ('Return to · your address' =
    // recipient) is filled by applyClient from client.returnAddress.
    if (isReturn) return
    const cw = clientWarehouses.find((w) => w.warehouse?.code === warehouseCode)
    const a = cw?.warehouse?.address
    if (!a) return
     
    setSender((cur) => {
      // Country -> dial code fully derived (2026-09-12): when the
      // warehouse pick changes the country, resync phoneCountryCode.
      const nextCountry = a.country ?? cur.countryCode
      return {
        ...cur,
        // Name only as a fallback when nothing identifies the sender yet.
        ...(a.name && !cur.name ? { name: a.name } : {}),
        ...(a.line1 ? { addressLine1: a.line1 } : {}),
        ...(a.line2 != null ? { addressLine2: a.line2 || '' } : {}),
        ...(a.city ? { city: a.city } : {}),
        ...(a.state ? { state: a.state } : {}),
        ...(a.zip ? { postalCode: a.zip } : {}),
        ...(a.country ? { countryCode: a.country, phoneCountryCode: dialCodeFor(nextCountry) || '' } : {}),
        ...(a.phone ? { phone: a.phone } : {}),
      }
    })
  }, [warehouseCode, clientWarehouses, isReturn, setSender])

  // Route: does this shipment cross a customs border? Drives which services/packages apply.
  const EU = new Set([
    'AT', 'BE', 'BG', 'HR', 'CY', 'CZ', 'DK', 'EE', 'FI', 'FR', 'DE', 'GR', 'HU', 'IE', 'IT',
    'LV', 'LT', 'LU', 'MT', 'NL', 'PL', 'PT', 'RO', 'SK', 'SI', 'ES', 'SE',
  ])
  // 2026-09-08 fix — US and its outlying territories are NOT one customs
  // territory. UPS Ship API (and FedEx) require a full customs object
  // (InternationalForms + InvoiceLineTotal + commodity lines) for US→PR/
  // VI/GU/AS/MP shipments even though the service level is a US domestic
  // service. Ref: docs.shippingapi.pitneybowes.com/carriers/ups.html —
  // "For US→PR set toAddress.countryCode=PR AND include the customs
  // object." PR #610 normalizes the wire country to PR at the connector
  // boundary; the FE must then let operators enter commodities or UPS
  // rejects with 120502 (InvoiceLineTotal MonetaryValue must be > 0).
  //
  // sameTerritory now only groups exact-match countries plus the EU
  // customs union. US→any US territory is treated as INTERNATIONAL for
  // customs-UI purposes (commodities section visible, incoterms + reason
  // for export required). Service-level "domestic-lite" routing is still
  // handled server-side by CarrierServiceImpl + ServiceLevelResolver.
  const sameTerritory = (a: string, b: string) =>
    a === b
    || (EU.has(a) && EU.has(b))
  // US-territory detection has to happen BEFORE isInternational so that
  // country=US + state=PR (or VI/GU/AS/MP/UM) opts the shipment into the
  // intl UI path (commercial invoice, FTR/AES, incoterms). Pre-fix, the
  // banner + service filter used this predicate but isInternational did
  // not, so the customs UI stayed hidden and the wire went out with
  // country=PR but no commodities → UPS 120502.
  const recipientTerritoryEarly = useMemo<string | null>(() => {
    const c = (recipient.countryCode || '').trim().toUpperCase()
    const s = (recipient.state || '').trim().toUpperCase()
    if ((c === 'US' || c === '') && US_TERRITORY_CODES.has(s)) return s
    if (US_TERRITORY_CODES.has(c)) return c
    return null
  }, [recipient.countryCode, recipient.state])
  const senderTerritoryEarly = useMemo<string | null>(() => {
    const c = (sender.countryCode || '').trim().toUpperCase()
    const s = (sender.state || '').trim().toUpperCase()
    if ((c === 'US' || c === '') && US_TERRITORY_CODES.has(s)) return s
    if (US_TERRITORY_CODES.has(c)) return c
    return null
  }, [sender.countryCode, sender.state])
  // "Effective country" for classification purposes — treat a US-territory
  // state as if it were the territory country code, mirroring what the
  // wire payload actually sends (per UsTerritoryNormalizer on the backend).
  const senderEffectiveCountry = senderTerritoryEarly
    ?? (sender.countryCode || '').toUpperCase()
  const recipientEffectiveCountry = recipientTerritoryEarly
    ?? (recipient.countryCode || '').toUpperCase()
  const isInternational =
    !!sender.countryCode &&
    !!recipient.countryCode &&
    !sameTerritory(senderEffectiveCountry, recipientEffectiveCountry)
  // Territory lanes are the awkward middle case: customs paperwork is
  // international (isInternational=true → commercial invoice + FTR/AES
  // required) but the carrier moves them on the DOMESTIC network — so
  // service scope stays DOMESTIC to keep Air / Overnight products
  // visible. Pre-fix, isInternational=true forced neededScope=
  // INTERNATIONAL which dropped 2nd Day / Next Day Air off the picker
  // for US→PR, leaving only Worldwide services that UPS rejects with
  // 121100 "service invalid for origin". Decoupling: territory ⇒
  // customs docs, but keep the domestic catalogue.
  const isTerritoryLane =
    (senderTerritoryEarly !== null || (sender.countryCode || '').toUpperCase() === 'US')
    && recipientTerritoryEarly !== null
  const neededScope: 'DOMESTIC' | 'INTERNATIONAL' =
    isInternational && !isTerritoryLane ? 'INTERNATIONAL' : 'DOMESTIC'
  const scopeFits = (scope?: string | null) => !scope || scope === 'BOTH' || scope === neededScope

  // Compare a catalog row's origin country to the sender's selected country.
  // Neither side falls back to a hardcoded 'US' — an unset origin on the
  // row matches any sender (permissive), and an unset sender country
  // matches nothing (until the operator picks one).
  const originMatch = (o?: string | null) => {
    const rowOrigin = (o || '').toUpperCase()
    const senderOrigin = (sender.countryCode || '').toUpperCase()
    if (!rowOrigin) return true         // row has no origin → available everywhere
    if (!senderOrigin) return false     // sender hasn't picked yet → no rows match
    return rowOrigin === senderOrigin
  }

  // Sprint 22 — build a rate-shop request from the current form state.
  // Postal codes + weight are the minimum required set; the picker disables
  // its trigger button when they're missing.
  const rateShopRequest = useMemo<RateShopRequest>(() => ({
    // Sprint 51 refactor — spread the whole sender / recipient objects
    // via shipperFieldsFrom / recipientFieldsFrom rather than cherry-
    // picking fields. Prevents the class of bug where a wire-required
    // field silently gets omitted (#508 was shipperPhone / recipientPhone
    // for the exact same reason — the builder was hand-mapping a subset).
    // Country codes are NOT hardcoded — pass through what the operator
    // picked; backend @NotBlank surfaces genuine misses.
    shipment: {
      carrierCode: carrier || undefined,
      accountNumber: accountNumber || undefined,
      // Sprint 51 rate-shop gap-fill — pre-fix the operator picked a
      // service level but rate-shop still fanned out across every service
      // in the account. Sending serviceType filters the fan-out so the
      // quote reflects the operator's actual intent.
      serviceType: serviceId !== ''
        ? services.find((s) => s.id === Number(serviceId))?.serviceCode
        : undefined,
      packageType: packageChoice || undefined,
      weight: Number(weight) || 0,
      weightUnit,
      length: length ? Number(length) : undefined,
      width: width ? Number(width) : undefined,
      height: height ? Number(height) : undefined,
      dimUnit,
      ...shipperFieldsFrom(sender),
      ...recipientFieldsFrom(recipient),
      declaredValue: declaredValue ? Number(declaredValue) : undefined,
      // Currency is always sent — quotes come back in the operator's
      // billing currency instead of the carrier's home currency (which
      // silently defaulted to USD for US carriers pre-fix).
      declaredValueCurrency: currency || undefined,
      // Surcharges that materially move the quoted price — none of these
      // were sent pre-fix, so the picker consistently under-quoted.
      // Only emit when set so the JSON body stays minimal.
      ...(signatureOption !== 'NONE' ? { signatureOption } : {}),
      ...(insuredValue && Number(insuredValue) > 0
        ? {
            insuredValue: Number(insuredValue),
            insuredValueCurrency: currency || undefined,
          }
        : {}),
      ...(dgBlock ? { dangerousGoods: dgBlock } : {}),
      // Return labels rate differently on FedEx SmartPost, UPS Returns.
      isReturn,
    },
    customerNo: clientCode || null,
    // No carriers whitelist — let the backend fan out to every configured
    // carrier so the picker can compare across the tenant's full inventory.
  }), [carrier, accountNumber, serviceId, services, packageChoice, weight, weightUnit,
        length, width, height, dimUnit, sender, recipient, declaredValue, currency,
        signatureOption, insuredValue, dgBlock, isReturn, clientCode])


  /**
   * Sprint 32 — landed cost request built from the current form. Requires
   * a picked carrier, a cross-border lane, and a customs block (declared
   * value + items) — USPS returns NOT_SUPPORTED regardless, and domestic
   * lanes come back with source=NOT_SUPPORTED from every carrier.
   */
  const landedCostRequest = useMemo<LandedCostRequest>(() => ({
    carrierCode: carrier || '',
    customerNo: clientCode || null,
    shipment: {
      ...rateShopRequest.shipment,
      declaredValueCurrency: currency,
    },
  }), [carrier, clientCode, rateShopRequest.shipment, currency])

  const canEstimateLandedCost = Boolean(
    carrier
    && accountNumber
    && serviceId
    && recipient.postalCode
    && Number(weight) > 0
    && isInternational
    && Number(declaredValue) > 0,
  )


  const accountsForCarrier = useMemo(() => {
    const onCarrier = accounts.filter((a) => canon(a.carrierCode) === carrier)
    if (!clientCode) return onCarrier
    const own = onCarrier.filter((a) => (a.customerNo || '').toUpperCase() === clientCode.toUpperCase())
    return own.length ? own : onCarrier
  }, [accounts, carrier, clientCode])
  // Services offered on THIS route: right carrier, ship-from country, and
  // domestic/international scope — then further filtered to the client's
  // allowlist when one exists.
  // US-territory detection (2026-09-07). FedEx + UPS treat PR/VI/GU/AS/
  // MP/UM as separate countries for shipping; operator's `country=US`
  // with a territory state won't ship on Ground / Home Delivery /
  // SmartPost / UPS Ground / 3 Day Select. Backend rewrites the
  // countryCode at wire time (safety net); FE nudges the operator,
  // hides the incompatible services here, AND opts the shipment into
  // the intl UI path via `isInternational` (see above).
  const recipientTerritory = recipientTerritoryEarly
  const isRecipientUsTerritory = recipientTerritory !== null

  const servicesForCarrier = useMemo(
    () =>
      // Per-territory service allowlist (2026-09-08). UPS + FedEx don't
      // serve every US territory with the same services — VI/GU/AS/MP/UM
      // reject domestic Air (UPS 121100 "service invalid for origin"),
      // PR accepts domestic Air but rejects Ground family. See
      // utils/usTerritoryServices for the validated per-territory map;
      // recipientTerritory=null falls through as pass-through.
      //
      // Territory-lane fix (2026-09-08): skip scopeFits on territory
      // lanes and let the per-territory allowlist be the SOLE gate. The
      // catalog seeds UPS Worldwide (07/08/54/65) + FedEx INTERNATIONAL_*
      // with scope=INTERNATIONAL; with neededScope=DOMESTIC for territory
      // lanes those services were dropped BEFORE the allowlist ran, so
      // the picker only saw the domestic half. On non-territory lanes
      // scopeFits stays authoritative (unchanged).
      services
        .filter((s) => canon(s.carrier) === carrier && originMatch(s.originCountry))
        .filter((s) => isTerritoryLane || scopeFits(s.scope))
        .filter((s) => !allowedServiceIds || allowedServiceIds.has(s.id))
        .filter((s) => isServiceAllowedForUsTerritory(recipientTerritory, carrier, s.serviceCode)),
    // eslint-disable-next-line react-hooks/exhaustive-deps -- originMatch / scopeFits close over sender.countryCode (already in deps) and neededScope (already in deps); helpers themselves are pure and don't need to be listed
    [services, carrier, sender.countryCode, neededScope, allowedServiceIds, recipientTerritory, isTerritoryLane],
  )
  // Sprint 52 PR 2 — service_package compatibility. Empty set for a
  // service means "admin hasn't linked any preset to this service yet"
  // — leave the pool alone rather than hide every CARRIER preset, so
  // the operator sees the same choices they used to (server-side guard
  // still throws SERVICE_HAS_NO_LINKED_PACKAGES with the fix path).
  // Ready-to-use set exists → filter to only linked presets.
  const compatiblePresetIdsForService = useMemo<Set<number> | null>(
    () => compatiblePresetIds(servicePackageLinks, serviceId),
    [serviceId, servicePackageLinks],
  )

  const packagesForCarrier = useMemo(
    () =>
      // Territory-lane fix (2026-09-08): mirror the servicesForCarrier
      // rule — skip scopeFits when the recipient is a US territory so
      // INTERNATIONAL-scoped carrier packages (needed for the intl
      // service family that PR/VI/etc. actually accept) stay visible.
      // The service_package linkage below is still the authoritative
      // per-service package gate.
      packages
        .filter((p) => p.kind === 'CARRIER' && canon(p.carrier) === carrier && originMatch(p.originCountry))
        .filter((p) => isTerritoryLane || scopeFits(p.scope))
        .filter((p) => !allowedPackageIds || p.id == null || allowedPackageIds.has(p.id))
        // Sprint 52 PR 2 — hide CARRIER presets not linked to the picked
        // service. CUSTOM presets ("Your boxes") are in a separate memo
        // and stay unfiltered (server guard treats them as always-allowed
        // via the kind=CUSTOM short-circuit). Null set = no filter.
        .filter((p) => !compatiblePresetIdsForService || p.id == null || compatiblePresetIdsForService.has(p.id)),
    // eslint-disable-next-line react-hooks/exhaustive-deps -- originMatch / scopeFits close over sender.countryCode (already in deps) and neededScope (already in deps); helpers themselves are pure and don't need to be listed
    [packages, carrier, sender.countryCode, neededScope, allowedPackageIds, compatiblePresetIdsForService, isTerritoryLane],
  )
  const customBoxes = useMemo(
    () =>
      packages
        .filter((p) => p.kind !== 'CARRIER')
        .filter((p) => !allowedPackageIds || p.id == null || allowedPackageIds.has(p.id)),
    [packages, allowedPackageIds],
  )

  // Ship-to gate. mode=null => no rule, everything allowed. ALLOW list =>
  // country must be listed. DENY list => country must NOT be listed. Shown as
  // an inline warning; submit is not blocked here — Phase 4 backend enforces
  // the hard 422 SHIP_TO_DENIED.
  const destAllowed = useMemo(() => {
    const country = (recipient.countryCode || '').toUpperCase()
    if (!destRules || !destRules.mode || !country) return true
    const listed = destRules.countries.some((c) => (c || '').toUpperCase() === country)
    return destRules.mode === 'ALLOW' ? listed : !listed
  }, [destRules, recipient.countryCode])

  useEffect(() => {
     
    setAccountNumber((cur) =>
      accountsForCarrier.some((a) => (a.accountNumber || '').toLowerCase() === cur.trim().toLowerCase())
        ? cur
        : accountsForCarrier[0]?.accountNumber ?? cur,
    )
    // Phase 5f — prefer the client's default from ClientAllowedService.isDefault
    // over "first available in filtered list". Falls back to first when the
    // client has no default configured or the default isn't in the current
    // carrier/scope filter.
    setServiceId((cur) => {
      if (servicesForCarrier.some((s) => s.id === cur)) return cur
      if (defaultServiceId != null && servicesForCarrier.some((s) => s.id === defaultServiceId)) {
        return defaultServiceId
      }
      return servicesForCarrier[0]?.id ?? ''
    })
    setPackageChoice((cur) => {
      if (cur === CUSTOM_PKG || packagesForCarrier.some((p) => String(p.id) === cur)) return cur
      // BUG-150 R7 — carrier switch was silently clearing a packaging
      // preset the operator had picked (e.g. UPS Letter → FedEx 2Day,
      // where UPS Letter has no FedEx equivalent). Surface the swap in
      // a toast so they know their choice changed under them.
      const prior = packages.find((p) => String(p.id) === cur)
      const next = (defaultPackagePresetId != null
        && packagesForCarrier.some((p) => p.id === defaultPackagePresetId))
        ? packagesForCarrier.find((p) => p.id === defaultPackagePresetId)
        : packagesForCarrier[0]
      if (prior && cur && cur !== CUSTOM_PKG) {
        const nextLabel = next?.name ?? 'Your own box'
        // Updaters run during render; a toast there is a cross-component
        // setState React warns about. Defer it past the render.
        queueMicrotask(() => notify.info(`Packaging "${prior.name}" isn't available on ${carrier}; switched to "${nextLabel}".`))
      }
      return next?.id != null ? String(next.id) : CUSTOM_PKG
    })
    // Re-validate account/service/package whenever the carrier, client, or route changes.
  }, [carrier, clientCode, accountsForCarrier, servicesForCarrier, packagesForCarrier, defaultServiceId, defaultPackagePresetId, setAccountNumber, setPackageChoice, setServiceId])

  // Fix-a-failed-order: load the order's data + carrier error and pre-fill the
  // form. The service/account are stashed here and applied once the catalog for
  // the carrier has loaded (below), so the auto-select effect above doesn't
  // clobber them.
  const pendingFixRef = useRef<{ serviceCode?: string | null; accountNumber?: string | null } | null>(null)
  useEffect(() => {
    if (!fixOrderNo) return
    let cancelled = false
    ;(async () => {
      try {
        const [details, byId, customs, cfValues] = await Promise.all([
          orderService.getOrderWithLines(fixOrderNo),
          orderService.getOrderById(fixOrderNo).catch(() => null),
          customsService.getCustoms(fixOrderNo).catch(() => null),
          // R2-BUG-008 run-5 residual — carry the stored custom-field
          // values across to the reissue form. Pre-fix this was never
          // fetched, so a required field (qa_1018e_dept = "Dept-42")
          // showed as blank and blocked Generate until the operator
          // retyped. 404 / null degrades gracefully to no prefill —
          // same as the other optional-fetch siblings above.
          customFieldService.values(fixOrderNo).catch(() => null),
        ])
        if (cancelled) return
        const o = details.data.order
        // Country -> dial code is fully derived (2026-09-12) — re-derive
        // phoneCountryCode from the loaded country instead of trusting a
        // stored value that may not match.
        const recipCountry = o.shiptoCountryCd ?? 'US'
        // D2C feeds put a bare tote sequence ("9") in ship_name — the
        // attention line is the real recipient then.
        const bareNo = !!o.shipName && /^\d+$/.test(o.shipName.trim()) && !!o.shipAttn?.trim()
        setRecipient({
          name: (bareNo ? o.shipAttn : o.shipName) ?? '', company: bareNo ? '' : o.shipAttn ?? '', phone: o.phone ?? '', email: '',
          // BUG-034 — Order.location persists the recipient's Address line 2
          // (manual/API path). Pre-fix this was hard-coded to '', so Fix &
          // regenerate / Reissue label sent an empty line 2 back and wiped
          // the stored suite/apt from the order + the printed label.
          addressLine1: o.shipAddr1 ?? '', addressLine2: o.shipAddr2 ?? '', city: o.shiptoCity ?? '',
          state: o.shiptoState ?? '', postalCode: o.shiptoZip ?? '', countryCode: recipCountry,
          phoneCountryCode: dialCodeFor(recipCountry) || '',
        })
        if (o.shipFromCountryCd) {
          const senderCountry = o.shipFromCountryCd ?? 'US'
          setSender({
            name: o.shipFromName ?? '', company: o.shipFromCompany ?? '', phone: o.shipFromPhone ?? '', email: '',
            addressLine1: o.shipFromAddr1 ?? '', addressLine2: o.shipFromAddr2 ?? '', city: o.shipFromCity ?? '',
            state: o.shipFromState ?? '', postalCode: o.shipFromZip ?? '', countryCode: senderCountry,
            phoneCountryCode: dialCodeFor(senderCountry) || '',
          })
        }
        if (o.weight != null) setWeight(String(o.weight))
        if (o.weightUnit === 'LB' || o.weightUnit === 'KG') setWeightUnit(o.weightUnit)
        // Dimensions + units from the stored first package (bulk rows carry
        // them there) — the repair form used to open with empty inch dims and
        // then block on "Required".
        const p0 = o.packages?.[0]
        if (p0) {
          if (p0.length != null) setLength(String(p0.length))
          if (p0.width != null) setWidth(String(p0.width))
          if (p0.height != null) setHeight(String(p0.height))
          if (p0.dimUnit === 'IN' || p0.dimUnit === 'CM') setDimUnit(p0.dimUnit)
          if (p0.weightUnit === 'LB' || p0.weightUnit === 'KG') setWeightUnit(p0.weightUnit)
        }
        if (o.declaredValue != null) setDeclaredValue(String(o.declaredValue))
        // Prefill the CLIENT from the order (tenantId, customerReferenceId fallback) —
        // its absence left two required fields empty (CLIENT, and CARRIER
        // whose option pool is client-derived) and forced the operator
        // through applyClient, which overwrites the faithfully-prefilled
        // ship-from with the client's warehouse default. Direct setClientCode
        // keeps every prefilled field intact. "MANUAL" is the legacy
        // no-client sentinel, not a real client.
        const orderClient = (o.tenantId || o.customerReferenceId || '').trim().toUpperCase()
        if (orderClient && orderClient !== 'MANUAL') setClientCode(orderClient)
        // Carrier: from the ship-via (a service code) when it identifies one,
        // else from the account the label was booked on. A stored value the
        // catalog doesn't know (e.g. a legacy "GROUND") used to become the
        // carrier itself, so the Service picker offered nothing but
        // "Carrier default" and the order could not be repaired.
        const fromShipVia = canon(String(o.shipviaCd ?? ''))
        const fromAccount = canon(details.data.carrierAccount?.carrierCode ?? '')
        const carrierCanon = knownCarrierCodes.includes(fromShipVia)
          ? fromShipVia
          : knownCarrierCodes.includes(fromAccount) ? fromAccount : fromShipVia
        if (carrierCanon) setCarrier(carrierCanon)
        if (customs) {
          if (customs.currency) setCurrency(customs.currency)
          if (customs.incoterms) setIncoterms(customs.incoterms)
          if (customs.reasonForExport) setReasonForExport(customs.reasonForExport)
          if (customs.ftrExemption) setFtrExemption(customs.ftrExemption)
          if (customs.aesCitation) setAesCitation(customs.aesCitation)
          if (customs.exportDeclarationReference) setExportDeclarationReference(customs.exportDeclarationReference)
          if (customs.items?.length) {
            setItems(customs.items.map((it) => ({
              description: it.description ?? '', sku: it.sku ?? '', hsCode: it.hsCode ?? '',
              countryOfOrigin: it.countryOfOrigin ?? '', quantity: String(it.quantity ?? 1),
              unitValue: it.unitValue != null ? String(it.unitValue) : '',
              boxSeq: it.boxSeq != null && Number(it.boxSeq) > 0 ? String(it.boxSeq) : '1',
              // PR #558 — hydrate per-item weight when the customs record
              // carries it; blank string when null so the auto-fill
              // placeholder (pkgWeight × qty / totalQty) shows.
              weight: it.weight != null ? String(it.weight) : '',
            })))
          }
        }
        if (cfValues && Object.keys(cfValues).length > 0) {
          setCustomFieldValues(cfValues)
        }
        // BUG-034 — return-flow Reason + RMA# live on Order.return_reason /
        // Order.rma_number (columns since Returns F9). Pre-fix the Reissue
        // return form opened with both blank, so the operator had to retype
        // a required field and risked losing the RMA on a second save.
        if (o.returnReason) setReturnReason(o.returnReason)
        if (o.rmaNumber) setRmaNumber(o.rmaNumber)
        pendingFixRef.current = {
          serviceCode: o.shipviaCd,
          accountNumber: details.data.carrierAccount?.accountNumber ?? null,
        }
        setFixError(byId?.data?.errorDetails?.errorMessage ?? null)
        setFixVoided((byId?.data?.labelDetails?.status ?? '').toUpperCase() === 'VOIDED')
        const ch = (o.orderChannel ?? '').toUpperCase()
        setFixChannel(ch === 'D2C' || ch === 'B2B' ? ch : null)
        // Older D2C orders stored "DTC batch 98.00 / tote 2".
        if (byId?.data?.orderDetails?.refOrderNumber) {
          setReference(byId.data.orderDetails.refOrderNumber.replace(/^(DTC batch \d+)\.0+(?=\s|$)/, '$1'))
        }
      } catch (err) {
        if (!cancelled) notify.apiError(err, `Could not load order ${fixOrderNo} to fix.`)
      } finally {
        if (!cancelled) setFixLoading(false)
      }
    })()
    return () => { cancelled = true }
  }, [fixOrderNo, setAesCitation, setCarrier, setClientCode, setCurrency, setCustomFieldValues, setDeclaredValue, setDimUnit, setExportDeclarationReference, setFixChannel, setFtrExemption, setHeight, setIncoterms, setItems, setLength, setReasonForExport, setRecipient, setReference, setReturnReason, setRmaNumber, setSender, setWeight, setWeightUnit, setWidth])

  // Apply the stashed service/account once the carrier's catalog is loaded.
  useEffect(() => {
    const pending = pendingFixRef.current
    if (!pending) return
    if (pending.accountNumber) setAccountNumber(pending.accountNumber)
    if (pending.serviceCode) {
      const match = servicesForCarrier.find((s) => s.serviceCode === pending.serviceCode)
      if (match) {
        setServiceId(match.id)
        pendingFixRef.current = null
      } else if (servicesForCarrier.length > 0) {
        // The order's original service isn't offered for this lane (e.g.
        // FEDEX_GROUND on an international destination). The picker will
        // fall back to a lane default — say so instead of substituting
        // silently, so the operator knows a deliberate choice was replaced.
        notify.info(
          `The order's original service ${pending.serviceCode} isn't offered for this lane — review the auto-selected service before regenerating.`,
        )
        pendingFixRef.current = null
      }
    } else {
      pendingFixRef.current = null
    }
  }, [servicesForCarrier, setAccountNumber, setServiceId])

  /**
   * The CarrierAccountRef row matching the currently-picked carrier +
   * accountNumber. Drives the Label Type / Label Size prefill below.
   * Nullable: an ad-hoc / typed accountNumber that doesn't match any
   * saved account leaves this null, and the label dropdowns fall back
   * to the "please select" required state.
   */
  const matchedAccount = useMemo(() => {
    if (!accountNumber.trim()) return null
    return accounts.find(
      (a) => canon(a.carrierCode) === canon(carrier)
        && (a.accountNumber || '').toLowerCase() === accountNumber.trim().toLowerCase(),
    ) ?? null
  }, [accounts, carrier, accountNumber])

  /**
   * Prefill Label Type / Label Size / Label format from the selected
   * carrier account. Fires whenever the resolved account changes
   * (client swap → carrier reset → accountNumber reset → matched
   * account changes, or a manual account swap). User overrides made
   * AFTER this fire are preserved until the NEXT account change.
   *
   * Null on the account leaves the field blank — combined with the
   * required-when-null guard in `submit()` this forces the operator
   * to pick a value before generating a label, closing the
   * "silently defaulted to PDF/PAPER_4X6/GIF" surprise the old
   * account-null branch produced.
   */
  // Prefill label fields when the resolved account changes; hard-overwrite
  // by design so switching accounts always shows the new account's saved
  // defaults, not stale user overrides from the previous account. Unlike
  // the label fields, pickupType falls back to the backend's hardcoded
  // default (USE_SCHEDULED_PICKUP) when the account has NULL — no force-
  // pick, per operator call. Reason of export prefills from
  // account.shippingPurpose; NULL leaves the field blank and the guard in
  // submit/validate blocks international shipments until the operator
  // picks. Clearance option (customs duties): prefill only when the saved
  // value is in the current carrier's vocabulary; guards against admin
  // misconfiguration (UPS account with a FedEx-only 'RECIPIENT' value) and
  // carrier-swap staleness.
  useEffect(() => {
     
    // The account's saved default wins; an account without one keeps what the
    // operator already picked instead of silently blanking it.
    setLabelImageType((cur) => matchedAccount?.labelImageType ?? cur)
    setLabelStockType((cur) => matchedAccount?.labelStockType ?? cur)
    // BUG-020 — handled in a separate effect below; this account-switch
    // path keeps the saved stock type verbatim so the operator's account
    // defaults aren't overwritten here.
    setLabelImageFormat((cur) => matchedAccount?.labelImageFormat ?? cur)
    if (!pickupTypeTouched) setPickupType(matchedAccount?.pickupType ?? readSticky('ms:lastPickupType', 'USE_SCHEDULED_PICKUP'))
    // BUG-143 — reason of export used to reset to the saved account
    // default on every carrier switch, silently discarding the
    // operator's picked value. 'Sale' flipped to 'Sample' across a
    // USPS → FedEx switch, so a sale shipment went out on the commercial
    // invoice declared as a sample. Only auto-fill when the field is
    // empty — once the operator has picked a reason, keep it.
    // BUG-002 — dropped the `matchedAccount?.shippingPurpose` fallback.
    // Account defaults to 'Sample' silently classified commercial sales
    // as samples on customs. Keep user-set value (sticky across carrier
    // swaps per BUG-143); otherwise blank → force-pick guard fires.
    setReasonForExport((cur) => cur || '')
    const saved = matchedAccount?.clearanceOption
    const allowed = clearanceOptionsForCarrier(canon(carrier))
    setClearanceOption(saved && allowed.some((o) => o.value === saved) ? saved : '')
     
  }, [matchedAccount, carrier, setClearanceOption, setLabelImageFormat, setLabelImageType, setLabelStockType, setPickupType, setReasonForExport])

  // BUG-020 — Label size should track Label type for FedEx: switching
  // to PDF/PNG implies paper stock, switching back to ZPLII implies
  // thermal stock. Only auto-flip when the current value is in the
  // other family so the operator's custom pick isn't blown away.
  useEffect(() => {
    const type = (labelImageType ?? '').toUpperCase()
    const stock = (labelStockType ?? '').toUpperCase()
    if ((type === 'PDF' || type === 'PNG') && stock.startsWith('STOCK_')) {
      setLabelStockType('PAPER_4X6')
    } else if (type === 'ZPLII' && stock.startsWith('PAPER_')) {
      setLabelStockType('STOCK_4X6')
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps -- only fires on labelImageType change; reading stock here would re-trigger ourselves
  }, [labelImageType])

  /**
   * Which label fields are required-but-blank for the currently-picked
   * carrier. FedEx needs both labelImageType + labelStockType; UPS /
   * DHL / USPS need labelImageFormat. The `submit()` and
   * `validateShipment()` guards use this to block with an operator
   * message; the UI marks the missing fields required.
   */
  const missingLabelFields = useMemo(() => {
    const missing: string[] = []
    const cc = canon(carrier)
    if (cc === 'FEDEX') {
      if (!labelImageType) missing.push('Label Type')
      if (!labelStockType) missing.push('Label Size')
    } else if (['UPS', 'DHL', 'USPS'].includes(cc)) {
      if (!labelImageFormat) missing.push('Label Type')
    }
    // PR Y — picking a CARRIER packaging preset without an explicit
    // service lets the connector fall back to GROUND, which rejects
    // branded packaging at the wire. Force the pick instead.
    if (serviceId === '' && packageChoice && packageChoice !== CUSTOM_PKG) {
      missing.push('Service')
    }
    // Reason of export is required on international shipments — same
    // force-pick pattern as label fields (see prefill useEffect above).
    // Not label-scoped but the guard hook is the same one used by
    // submit()/validateShipment() so we co-locate.
    if (isInternational && !reasonForExport) missing.push('Reason of export')
    // US Export EEI — mirror the backend validator (IntlShipmentValidator
    // CODE_EEI_REQUIRED). US-origin + non-Canada dest + USD invoice
    // >= $2,500 without FTR exemption or AES ITN → forcing pick keeps
    // the operator from a FedEx server-side reject.
    // 2026-09-09 — use recipientEffectiveCountry so US → PR/VI/GU/AS/MP/UM
    // lanes fire the gate (matches the reversed UsFtr30_37Policy set:
    // no territory is exempt from EEI above $2,500).
    const originIsUs = (sender.countryCode ?? '').trim().toUpperCase() === 'US'
    const destCcNorm = recipientEffectiveCountry
    if (isInternational
        && originIsUs
        && destCcNorm && destCcNorm !== 'CA' && destCcNorm !== 'US'
        && (currency ?? 'USD').toUpperCase() === 'USD'
        && Number(declaredValue || 0) >= EEI_THRESHOLD_USD
        && !ftrExemption && !aesCitation) {
      missing.push(`FTR exemption or AES ITN (required at ≥ $${EEI_THRESHOLD_USD.toLocaleString()})`)
    }
    // BUG-072 — US → Canada under $2,500 still needs ONE of {FTR
    // exemption, AES ITN}. Pre-fix the exemption picker defaulted to
    // 'None (using AES)' but no AES ITN was typed, so Customs passed
    // locally and FedEx rejected ('FTR Exemption or AES Citation ...
    // not valid for EEI'). Require a pick when both are empty.
    if (isInternational
        && originIsUs
        && destCcNorm === 'CA'
        && !ftrExemption && !aesCitation) {
      missing.push('FTR exemption (pick NOEEI 30.36 for Canada) or AES ITN')
    }
    // Incoterms same pattern as Reason of export — prefill from
    // ClientCustomsProfile via importerProfile; NULL profile / value
    // blocks with a targeted toast.
    if (isInternational && !incoterms) missing.push('Incoterms')
    if (isInternational && /THIRD/.test(clearanceOption.toUpperCase()) && !dutiesAccount.trim()) missing.push('Duties payor account')
    return missing
  }, [carrier, labelImageType, labelStockType, labelImageFormat, isInternational, reasonForExport, incoterms,
      // recipient.countryCode is intentionally omitted — recipientEffectiveCountry
      // is derived from it and already covers the value the memo actually reads.
      sender.countryCode, recipientEffectiveCountry, currency, declaredValue, ftrExemption, aesCitation,
      // typed fields the list checks — without these the memo kept reporting
      // "Duties payor account" after the operator had typed one
      clearanceOption, dutiesAccount,
      // PR Y — carrier-preset-without-service guard
      serviceId, packageChoice])

  /**
   * Select a client: fill YOUR address on the correct side and auto-pick its
   * default carrier + account. For a shipment your address is the origin (sender);
   * for a return it's the destination (recipient), taken from the return address.
   */
  const applyClient = (code: string) => {
    setClientCode(code)
    const client = clients.find((c) => c.clientCode === code)
    if (!client) {
      // Clearing the client used to return here and leave the previous
      // client's company and warehouse as the shipper. Back to the default
      // ship-from (fix-order mode keeps the order's own sender, as below).
      if (!fixOrderNo) {
        if (isReturn) setRecipient(defaultSenderFrom(resolvedShipper))
        else setSender(defaultSenderFrom(resolvedShipper))
      }
      return
    }
    const yourAddr = isReturn ? client.returnAddress ?? client.shipFrom : client.shipFrom
    // Always reset to defaultSenderFrom(resolvedShipper) before overlay —
    // otherwise switching from Client A (with shipFrom) to Client B (without)
    // would leave A's address in place. C2 — base comes from tenant_settings
    // instead of hardcoded NYC. The subsequent warehouse-change effect
    // re-overlays if the newly-picked client has warehouses attached.
    const base = defaultSenderFrom(resolvedShipper)
    const mergedCountry = yourAddr ? (yourAddr.country || base.countryCode) : base.countryCode
    const merged: ManualShipmentAddress = yourAddr
      ? {
          name: client.name || base.name,
          company: client.name || base.company,
          phone: client.phone || base.phone,
          email: client.email || base.email,
          addressLine1: yourAddr.line1 || base.addressLine1,
          addressLine2: yourAddr.line2 || '',
          city: yourAddr.city || base.city,
          state: yourAddr.state || base.state,
          postalCode: yourAddr.zip || base.postalCode,
          countryCode: mergedCountry,
          // Country -> dial code fully derived (2026-09-12).
          phoneCountryCode: dialCodeFor(mergedCountry) || '',
        }
      : base
    // Fix-order mode: the ship-from was prefilled from the ORDER (the address
    // the failed label actually used). Overlaying the client's warehouse
    // default here silently rewrote it — keep the order's sender.
    if (!fixOrderNo) {
      if (isReturn) setRecipient(merged)
      else setSender(merged)
    }
    const accts = (client.carrierAccounts || []).filter((a) => a.active)
    const def = accts.find((a) => a.clientDefault) || accts[0]
    if (def) {
      const canonical = canon(def.carrierCode)
      if (carrierOptions.includes(canonical) || svcCarrierSet.has(canonical)) {
        setCarrier(canonical)
        setAccountNumber(def.accountNumber || '')
      } else {
        // Client's default account is for a carrier this workspace hasn't
        // connected. Silently skipping would leave the operator wondering
        // why the picker didn't pre-fill; toast so it's obvious what to do.
        notify.info(
          `${client.name || client.clientCode}'s default carrier ${canonical} isn't connected in this workspace — pick a carrier manually.`,
        )
      }
    }
    // Currency prefill from the client's default; falls through to
    // the sticky last-used when null (per PR #527 — no force-pick on
    // currency, unlike Label Type / Size where the account NULL is
    // rare enough to warrant blocking). Overwrites the operator's
    // prior manual pick, same semantic as the carrier prefill above.
    if (client.defaultCurrency) {
      setCurrency(client.defaultCurrency)
    }
    // #275 — same semantic for weight + dim units. Client-saved default
    // wins over the LB/IN session fallback. A Canadian / UK client set
    // to KG/CM at /settings/clients lands on the shipment form with the
    // right unit already picked — no per-shipment manual flip. Null /
    // unrecognised value leaves the current session unit intact.
    if (!weightUnitTouched && (client.defaultWeightUnit === 'LB' || client.defaultWeightUnit === 'KG')) {
      setWeightUnit(client.defaultWeightUnit)
    }
    if (!dimUnitTouched && (client.defaultDimUnit === 'IN' || client.defaultDimUnit === 'CM')) {
      setDimUnit(client.defaultDimUnit)
    }
  }

  useEffect(() => {
    if (!dtcLineId || !dtcBatch || !dtcTenant) return
    let cancelled = false
    dtcService.line(dtcBatch, dtcLineId, dtcTenant)
      .then((res) => { if (!cancelled) setDtcLine(res.data) })
      .catch((err) => { if (!cancelled) notify.apiError(err, `Could not load D2C line ${dtcLineId}.`) })
    return () => { cancelled = true }
  }, [dtcLineId, dtcBatch, dtcTenant])

  // Apply the line once the client list is in, so the client's own ship-from
  // and default carrier come through applyClient like a normal pick.
  useEffect(() => {
    if (!dtcLine || !clients.length || dtcAppliedRef.current) return
    dtcAppliedRef.current = true
    const o = dtcLine
     
    if (clients.some((c) => c.clientCode === o.tenantId)) applyClient(o.tenantId)
    else setClientCode(o.tenantId)
    const country = (o.shipToCountryCode || 'US').toUpperCase()
    setRecipient({
      name: o.shipName ?? '', company: o.shipAttn ?? '', phone: o.phone ?? '', email: o.email ?? '',
      addressLine1: o.shipAddr1 ?? '',
      addressLine2: [o.shipAddr2, o.shipAddr3].filter((l) => l && l.trim()).join(', '),
      city: o.shipToCity ?? '', state: o.shipToState ?? '', postalCode: o.shipToZip ?? '', countryCode: country,
      phoneCountryCode: dialCodeFor(country) || '',
    })
    if (o.weight != null && o.weight > 0) setWeight(String(o.weight))
    // weightUnit comes from applyClient(o.tenantId) above (client's
    // defaultWeightUnit), honouring weightUnitTouched. DtcOrder carries
    // no unit of its own.
    setReference(`DTC batch ${Number(o.batchId)}${o.toteNumber ? ` / tote ${o.toteNumber}` : ''}`)
    if (o.goodsDesc) {
      setItems([{ ...blankItem(), description: o.goodsDesc, unitValue: o.unitValue != null ? String(o.unitValue) : '' }])
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps -- one-shot prefill; applyClient is recreated every render
  }, [dtcLine, clients])

  /** Link the label just bought to its D2C line. Best-effort: the label is bought either way. */
  const linkDtcLine = async (orderNo: number) => {
    if (!dtcLine) return
    try {
      await dtcService.editLine(String(dtcLine.batchId), dtcLine.id, dtcLine.tenantId, { adoptOrderNo: orderNo })
      notify.success(`D2C line (tote ${dtcLine.toteNumber ?? dtcLine.id}) now carries order ${orderNo}.`)
    } catch (err) {
      notify.apiError(err, `Label ${orderNo} was bought, but linking it to the D2C line failed — link it from the batch page.`)
    }
  }

  /**
   * Apply a fresh NDS prefill response into the form. Client comes first
   * so `applyClient` can kick off warehouse/sender loading; the rest of
   * the fields flip synchronously off the response.
   *
   * <p>Phone / notify email flagged as `defaulted` by the backend are
   * kept for now, but {@link senderFallbackPendingRef} is set so the
   * sender-fallback effect can supersede them once the shipper block
   * finishes loading — matches the operator ask "use shipper details
   * for missing NDS values."
   */
  const applyNdsPrefill = (p: NdsShipmentPrefill) => {
    if (p.clientCode) applyClient(p.clientCode)
    const r = p.recipient
    if (r) {
      const country = (r.countryCd || 'US').toUpperCase()
      setRecipient({
        name: r.name ?? '',
        company: r.attn ?? '',
        phone: r.phone ?? '',
        email: '',
        addressLine1: r.addr1 ?? '',
        addressLine2: [r.addr2, r.addr3].filter(Boolean).join(', '),
        city: r.city ?? '',
        state: r.state ?? '',
        postalCode: r.zip ?? '',
        countryCode: country,
        phoneCountryCode: dialCodeFor(country) || '',
      })
    }
    if (p.shipMethod?.mappedServiceId != null) {
      // The mapped service decides the carrier. applyClient above picked the
      // client's DEFAULT carrier account (e.g. FedEx); leaving it there made
      // the re-validate effect drop a UPS mapped service (not in FedEx's
      // servicesForCarrier) and substitute the FedEx default service.
      const mapped = services.find((s) => s.id === p.shipMethod!.mappedServiceId)
      const mappedCarrier = canon(mapped?.carrier)
      if (mappedCarrier) setCarrier(mappedCarrier)
      setServiceId(p.shipMethod.mappedServiceId)
    }
    if (p.packages.length > 0) {
      const first = p.packages[0]
      if (first.weight != null) setWeight(String(first.weight))
      if (first.length != null) setLength(String(first.length))
      if (first.width != null) setWidth(String(first.width))
      if (first.height != null) setHeight(String(first.height))
      setExtraPackages(
        p.packages.slice(1).map((pk) => ({
          weight: pk.weight != null ? String(pk.weight) : '',
          length: pk.length != null ? String(pk.length) : '',
          width: pk.width != null ? String(pk.width) : '',
          height: pk.height != null ? String(pk.height) : '',
          packageType: '',
          declaredValue: '',
        })),
      )
    }
    if (p.international?.items?.length) {
      setItems(
        p.international.items.map((it) => ({
          description: it.description ?? '',
          sku: it.itemNo ?? '',
          hsCode: it.harmonizeCode ?? '',
          countryOfOrigin: it.countryOfOrigin ?? '',
          quantity: String(it.qtyShipped ?? 1),
          unitValue: it.unitPrice != null ? String(it.unitPrice) : '',
          boxSeq: '1',
          weight: '',
        })),
      )
    }
    if (p.notifyBlock?.sendTo) {
      setRecipient((prev) => ({ ...prev, email: p.notifyBlock!.sendTo ?? '' }))
    }
    if (p.orders.length > 0 && p.orders[0].orderNo != null) {
      setReference(String(p.orders[0].orderNo))
    }
    senderFallbackPendingRef.current = {
      phone: !!r?.phoneDefaulted,
      email: !!p.notifyBlock?.emailDefaulted,
    }
    // G6 — stash the STD-replacement ERP code so submit can round-trip it
    // to the backend for the NDS OEHEAD.SHIPVIA_CD writeback.
    setNdsStdReplacementErpCode(p.stdReplacementErpCode ?? null)
  }

  /**
   * Trigger a lookup for the current scan value. Runs on Enter — matches
   * USB scanners that auto-append CR/LF. Maps HTTP status → banner text:
   * 404 = "not found", 422 = "bad format", 503 = "NDS unavailable".
   */
  const onScanSubmit = async () => {
    const raw = ndsScan.trim()
    if (!raw) return
    // Split on comma OR whitespace; bare tokens default to .X, and existing
    // .X / .Y prefixes are preserved verbatim.
    const tokens = raw.split(/[,\s]+/).map((t) => t.trim()).filter(Boolean)
      .map((t) => (/^\.[XY]/i.test(t) ? t : `.X${t}`))
    if (tokens.length === 0) return
    setNdsLoading(true)
    setNdsBanner(null)
    try {
      // Parallel calls — one per token. Existing endpoint returns the full
      // order for every .X (all sibling packages), so multiple containers
      // of the same order collapse via dedup below.
      const results = await Promise.allSettled(tokens.map((t) => ndsShipmentService.lookup(t, clientCode || null)))
      const prefills: NdsShipmentPrefill[] = []
      const failures: { token: string; status?: number; msg?: string }[] = []
      results.forEach((r, i) => {
        if (r.status === 'fulfilled') prefills.push(r.value)
        else failures.push({ token: tokens[i], status: (r.reason as ApiError)?.status, msg: (r.reason as ApiError)?.message })
      })
      // Dedup by primary orderNo (fallback: scannedValue).
      const seen = new Set<string>()
      const unique: NdsShipmentPrefill[] = []
      for (const p of prefills) {
        const key = p.orders?.[0]?.orderNo != null
          ? `order:${p.orders[0].orderNo}` : `scan:${p.scannedValue}`
        if (seen.has(key)) continue
        seen.add(key)
        unique.push(p)
      }
      if (unique.length === 0) {
        // Every token failed — surface the first failure's status.
        const first = failures[0]
        setNdsBanner({
          status: first?.status === 503 ? 'WARNING' : 'BLOCKED',
          messages: [],
          text: first?.status === 404 ? 'No NDS record matched the scanned value.'
              : first?.status === 422 ? (first.msg ?? 'Scan format invalid — use .X<containerId> or .Y<batchId>.')
              : first?.status === 503 ? 'NDS is unavailable right now. Try again in a moment.'
              : 'NDS lookup failed.',
        })
        return
      }
      // Apply the first prefill. Rest (if any) queue for click-to-swap.
      applyNdsPrefill(unique[0])
      setNdsQueue(unique)
      setNdsActiveIdx(0)
      const bannerText = unique.length > 1
        ? `${unique.length} orders scanned — loaded the first; the rest are queued below.`
        : failures.length > 0
          ? `${failures.length} of ${tokens.length} scans failed; loaded ${unique.length}.`
          : undefined
      setNdsBanner({
        status: unique[0].status,
        messages: unique[0].messages ?? [],
        text: bannerText,
      })
    } finally {
      setNdsLoading(false)
    }
  }

  /** Click a queued order chip → swap it in as the active shipment. */
  const activateQueued = (idx: number) => {
    if (idx < 0 || idx >= ndsQueue.length || idx === ndsActiveIdx) return
    applyNdsPrefill(ndsQueue[idx])
    setNdsActiveIdx(idx)
    setNdsBanner({
      status: ndsQueue[idx].status,
      messages: ndsQueue[idx].messages ?? [],
    })
  }

  /** X on a chip → drop that order from the queue. Removing the active
   *  one advances to the next; empty queue clears the strip. */
  const removeQueued = (idx: number) => {
    if (idx < 0 || idx >= ndsQueue.length) return
    const next = ndsQueue.filter((_, i) => i !== idx)
    if (next.length === 0) {
      setNdsQueue([])
      setNdsActiveIdx(-1)
      return
    }
    setNdsQueue(next)
    if (idx === ndsActiveIdx) {
      const newActive = Math.min(idx, next.length - 1)
      setNdsActiveIdx(newActive)
      applyNdsPrefill(next[newActive])
    } else if (idx < ndsActiveIdx) {
      setNdsActiveIdx(ndsActiveIdx - 1)
    }
  }

  /** Toggle Shipment ⇆ Return. A return is the swapped shipment, so flip the two parties. */
  const switchMode = (next: 'SHIPMENT' | 'RETURN') => {
    if (next === mode) return
    setSender(recipient)
    setRecipient(sender)
    // Return mode always ships as RETURN reason. Shipment mode
    // defers to the account.shippingPurpose prefill / user pick /
    // force-pick guard — don't overwrite with a hardcoded 'SALE'.
    if (next === 'RETURN') setReasonForExport('RETURN')
    setMode(next)
  }

  // Sender-fallback for NDS-defaulted phone / notify-email: once the
  // shipper block has been filled (by applyClient's warehouse overlay),
  // supersede the backend fallback with the operator's shipper values.
  // Fires exactly once per prefill — clears the ref when both slots
  // are handled or the shipper values remain empty for this render.
  useEffect(() => {
    const pending = senderFallbackPendingRef.current
    if (!pending) return
    let mutated = false
    const senderPhone = sender.phone?.trim()
    const senderEmail = sender.email?.trim()
    if (pending.phone && senderPhone) {
      setRecipient((prev) => ({ ...prev, phone: senderPhone }))
      pending.phone = false
      mutated = true
    }
    if (pending.email && senderEmail) {
      setRecipient((prev) => ({ ...prev, email: senderEmail }))
      pending.email = false
      mutated = true
    }
    if (mutated && !pending.phone && !pending.email) {
      senderFallbackPendingRef.current = null
    }
  }, [sender.phone, sender.email, setRecipient])

  const isCustomPkg = packageChoice === CUSTOM_PKG

  // Package ↔ service compatibility. FedEx branded packaging (its own boxes,
  // envelope, pak, tube — carrier_package_code starting FEDEX_) is NOT valid
  // with FedEx Ground / Home Delivery; the carrier 400s with
  // PACKAGINGTYPE.VALIDATION.ERROR. Catch it here instead of after a failed
  // label. (Custom / "your packaging" is fine with any service.)
  const pkgServiceWarning = useMemo(() => {
    if (carrier !== 'FEDEX' || isCustomPkg) return null
    const preset = packagesForCarrier.find((p) => String(p.id) === packageChoice)
    const svc = servicesForCarrier.find((s) => s.id === serviceId)
    const code = (preset?.carrierPackageCode || '').toUpperCase()
    const svcCode = (svc?.serviceCode || '').toUpperCase()
    const isBrandedFedex = code.startsWith('FEDEX_')
    const isGround = svcCode === 'FEDEX_GROUND' || svcCode.includes('GROUND') || svcCode.includes('SMART_POST')
    if (isBrandedFedex && isGround) {
      return `${preset?.name || 'FedEx branded packaging'} can't ship on ${svc?.name || 'a FedEx Ground service'}. `
        + 'FedEx-branded boxes require a FedEx Express service (2Day, Express Saver, Priority/Standard Overnight). '
        + 'Pick an Express service, or choose “Custom package…” to use your own box on Ground.'
    }
    return null
  }, [carrier, isCustomPkg, packagesForCarrier, servicesForCarrier, packageChoice, serviceId])

  // Inline max-weight warning. When the operator picks a carrier packaging
  // preset that has a max_weight set (e.g. UPS Express Pak = 3 lb, FedEx
  // Envelope = 10 oz) and their entered weight exceeds it, the carrier
  // will reject at label time. Surface it inline so they can bump to
  // Custom or pick a larger box before hitting Generate Label.
  const weightOverPresetMaxWarning = useMemo(() => {
    if (isCustomPkg || !weight) return null
    const preset = packagesForCarrier.find((p) => String(p.id) === packageChoice)
    if (!preset?.maxWeight) return null
    const enteredLb = weightUnit === 'KG' ? Number(weight) * 2.20462 : Number(weight)
    const limitLb = preset.weightUnit === 'KG' ? preset.maxWeight * 2.20462 : preset.maxWeight
    if (!Number.isFinite(enteredLb) || !Number.isFinite(limitLb)) return null
    if (enteredLb <= limitLb) return null
    return `Exceeds ${preset.name}'s ${preset.maxWeight} ${(preset.weightUnit || 'LB').toLowerCase()} max — carrier will reject. Pick a larger box or Custom package.`
  }, [isCustomPkg, weight, weightUnit, packagesForCarrier, packageChoice])

  // Effective pieces-per-label cap — min(packaging, service). Null on
  // both sides = no limit. V141 added package-level cap (UPS Letter = 1);
  // V142 added service-level cap for the symmetric case (rare but
  // real for return/LTL add-ons). The lower of the two wins; whichever
  // dimension drove the cap gets named in the warning so the operator
  // knows what to change.
  const maxPiecesContext = useMemo(() => {
    const preset = isCustomPkg
      ? null
      : packagesForCarrier.find((p) => String(p.id) === packageChoice)
    const svc = servicesForCarrier.find((s) => s.id === serviceId)
    const presetCap = preset?.maxPieces ?? null
    const serviceCap = svc?.maxPieces ?? null
    let cap: number | null = null
    let source: string | null = null
    if (presetCap != null && (serviceCap == null || presetCap <= serviceCap)) {
      cap = presetCap
      source = preset?.name || 'This packaging'
    } else if (serviceCap != null) {
      cap = serviceCap
      source = svc?.name || 'This service'
    }
    return { cap, source }
  }, [isCustomPkg, packagesForCarrier, packageChoice, servicesForCarrier, serviceId])
  const currentBoxCount = 1 + extraPackages.length
  const canAddMoreBoxes = maxPiecesContext.cap == null || currentBoxCount < maxPiecesContext.cap
  const splitHint = useMemo(() => {
    if (maxPiecesContext.cap !== 1) return ''
    return currentBoxCount >= 2
      ? ' To ship multiple boxes, create each one as a separate shipment.'
      : ' If you need to ship more than one box, create each one as a separate shipment.'
  }, [maxPiecesContext.cap, currentBoxCount])
  const addBoxBlockedMessage = useMemo(() => {
    if (maxPiecesContext.cap == null) return null
    return `${maxPiecesContext.source} ships at most ${maxPiecesContext.cap} `
      + `box${maxPiecesContext.cap === 1 ? '' : 'es'} per label.${splitHint}`
  }, [maxPiecesContext, splitHint])
  // Submit-gate warning — a mismatch from switching to a lower cap
  // AFTER the operator already added extras. The add buttons HARD-block
  // growth; this catches the switch-after case so submit still refuses.
  const singlePieceOnlyWarning = useMemo(() => {
    if (maxPiecesContext.cap == null || currentBoxCount <= maxPiecesContext.cap) return null
    return `${maxPiecesContext.source} ships at most ${maxPiecesContext.cap} `
      + `box${maxPiecesContext.cap === 1 ? '' : 'es'} per label — the carrier will reject this `
      + `${currentBoxCount}-box shipment. Remove the extra boxes or pick a larger packaging.`
      + splitHint
  }, [maxPiecesContext, currentBoxCount, splitHint])

  // ── Yup + Formik validation ────────────────────────────────────────────────
  // The page keeps its own useState for each field (needed by AI autofill, the
  // rate picker, warehouse resolution, etc.), so Formik is wired as a pure
  // validation layer: we mirror the live state into `formValues`, feed it to
  // useFormik via enableReinitialize, and read back `formik.errors`. Messages
  // only render once the user has attempted "Generate label" (submitAttempted).
  const [submitAttempted, setSubmitAttempted] = useState(false)
  // Non-blocking validation toast (replaces the old error modal). Field-level
  // red messages under each input remain the primary guidance.
  const [toast, setToast] = useState<{ id: number; title: string; body: string } | null>(null)
  const showToast = (body: string, title = 'A few details need fixing') =>
     
    setToast({ id: Date.now(), title, body })
  useEffect(() => {
    if (!toast) return
    const t = window.setTimeout(() => setToast(null), 6000)
    return () => window.clearTimeout(t)
  }, [toast])
  /** Smooth-scroll the first inline error message into view after a failed submit. */
  // Phase 3 — attempt recovery: a reload mid-purchase asks the server what
  // happened to the attempt instead of leaving a blank form (or a second buy).
  usePendingAttempt(navigate)

  const scrollToFirstError = () => {
    window.setTimeout(() => {
      const el = document.querySelector('.ms-field-error')
      el?.scrollIntoView({ behavior: 'smooth', block: 'center' })
    }, 60)
  }
  const pickAddr = (a: ManualShipmentAddress) => ({
    name: a.name ?? '',
    company: a.company ?? '',
    addressLine1: a.addressLine1 ?? '',
    addressLine2: a.addressLine2 ?? '',
    city: a.city ?? '',
    state: a.state ?? '',
    postalCode: a.postalCode ?? '',
    countryCode: a.countryCode ?? '',
    phone: a.phone ?? '',
    phoneCountryCode: a.phoneCountryCode ?? '',
    email: a.email ?? '',
  })
  const formValues = useMemo(
    () => ({
      isInternational,
      isCustomPackage: isCustomPkg,
      clientCode,
      carrier,
      account: accountNumber,
      incoterms,
      reasonForExport,
      currency,
      sender: pickAddr(sender),
      recipient: pickAddr(recipient),
      weight,
      declaredValue,
      insuredValue,
      length,
      width,
      height,
      // Validate the rows the operator actually started filling. A pristine
      // trailing "Add item" row (all fields blank) is skipped so it doesn't trip
      // "Description/HS required" — the submit payload filters the same blanks
      // via cleanItems. BUT on an international shipment where EVERY row is blank
      // we keep the first row, so its per-field required errors render UNDER the
      // inputs instead of only a top-level "needs at least one item" toast.
      items: (() => {
        const withDest = (it: ItemRow) => ({
          description: it.description,
          sku: it.sku,
          hsCode: it.hsCode,
          countryOfOrigin: it.countryOfOrigin,
          quantity: it.quantity,
          unitValue: it.unitValue,
          // Destination country rides on each item so the HS-code rule can key
          // its required digit-length off the importing country.
          destCountry: (recipientTerritoryEarly ?? recipient.countryCode ?? '').toUpperCase(),
        })
        const started = items.filter((it) => it.description?.trim() || it.unitValue?.trim()
          || it.hsCode?.trim() || it.sku?.trim() || it.countryOfOrigin?.trim())
        if (started.length > 0) return started.map(withDest)
        return isInternational && items.length > 0 ? [withDest(items[0])] : []
      })(),
      // Boxes 2..N — a box without its own packaging uses the shipment's; when
      // that is a custom box, this one needs its own size.
      extraPackages: extraPackages.map((p) => ({ ...p, needsDims: !p.packageType && isCustomPkg })),
    }),
    [isInternational, isCustomPkg, clientCode, carrier, accountNumber, incoterms, reasonForExport,
      currency, sender, recipient, weight, declaredValue, insuredValue, length, width, height, items,
      recipientTerritoryEarly, extraPackages],
  )
  const formik = useFormik<ShipmentFormValues>({
    initialValues: formValues as unknown as ShipmentFormValues,
    enableReinitialize: true,
    validationSchema: shipmentSchema,
    onSubmit: () => {},
  })
  // In edit/fix mode, map the carrier error to the specific fields it implicates
  // so the reason shows UNDER the wrong/missing field (not just the top banner).
  // These surface immediately (no submit needed), and clear once the operator
  // edits that field.
  // Phase 3 — field errors the server named (a 422 `errors.field` on purchase,
  // or `localErrors[].field` on a failed dry run) render inline like Yup's.
  const [serverFieldErrors, setServerFieldErrors] = useState<Record<string, string>>({})
  const { fields: fixFieldErrors, hasItemError: fixItemError } = useMemo(() => {
    const carrier = fixOrderNo ? mapCarrierErrorToFields(fixError) : { fields: {}, hasItemError: false }
    return { fields: { ...carrier.fields, ...serverFieldErrors }, hasItemError: carrier.hasItemError }
  }, [fixOrderNo, fixError, serverFieldErrors])
  const [clearedFixKeys, setClearedFixKeys] = useState<Set<string>>(new Set())
  const carrierErrAt = (path: string): string | undefined =>
    clearedFixKeys.has(path) ? undefined : fixFieldErrors[path]
  /** Mark a carrier- or server-mapped field error as resolved once the operator edits it. */
  const clearFixKey = (path: string) => {
    if (fixFieldErrors[path] && !clearedFixKeys.has(path)) {
      setClearedFixKeys((s) => new Set(s).add(path))
    }
  }
  /** Field error at `path` — carrier error first (edit mode), then form validation. */
  const errAt = (path: string): string | undefined =>
    carrierErrAt(path) ?? (submitAttempted ? (getIn(formik.errors, path) as string | undefined) : undefined)
  /** Whole address-block error map for AddressBlock (sender / recipient). */
  const addrErrors = (prefix: 'sender' | 'recipient'): AddressErrors | undefined => {
    const base = (submitAttempted ? getIn(formik.errors, prefix) : undefined) as AddressErrors | undefined
    const overlay: AddressErrors = {}
    for (const key of Object.keys(fixFieldErrors)) {
      if (key.startsWith(`${prefix}.`)) {
        const field = key.slice(prefix.length + 1) as keyof AddressErrors
        const msg = carrierErrAt(key)
        if (msg) overlay[field] = msg
      }
    }
    if (!base && Object.keys(overlay).length === 0) return undefined
    return { ...(base ?? {}), ...overlay }
  }
  /**
   * Per-item error map (description, quantity, unitValue, …).
   *
   * PR #557 audit — for international shipments, `unitValue` errors fire
   * IMMEDIATELY (not just after a submit-attempt). FedEx rejects the
   * shipment when any commodity has a 0 / empty unit value with an
   * unreadable "COMMODITY_INDEX — 1" wire error; catching it inline saves
   * the operator a round-trip to the carrier. Other fields keep the
   * post-submit-attempt gating (avoids alarming users mid-typing on
   * fields they haven't reached yet).
   */
  const itemErrAt = (index: number, field: string): string | undefined => {
    const err = getIn(formik.errors, `items[${index}].${field}`) as string | undefined
    if (!err) return undefined
    if (field === 'unitValue' && isInternational) return err
    return submitAttempted ? err : undefined
  }

  // Importer/broker resolve from the client's profile covering the destination country.
  // Territory-aware destination — when recipient is a US territory
  // (country=US + state=PR/VI/GU/AS/MP/UM), the effective destination
  // for customs / importer purposes is the territory code, not "US".
  // Keeps the importer chip + modal + banner in sync with the wire.
  const destCountry = (recipientTerritoryEarly ?? recipient.countryCode ?? '').toUpperCase()
  const importerProfile = useMemo(
    () =>
      isInternational && clientCode
        ? profiles.find((p) => (p.countries || []).some((c) => (c || '').toUpperCase() === destCountry)) ?? null
        : null,
    [profiles, isInternational, clientCode, destCountry],
  )

  /**
   * Incoterms prefill from the resolved ClientCustomsProfile. Fires
   * on client OR destination-country change (importerProfile is a
   * memo over both). Force-pick semantic: NULL profile / NULL value
   * leaves the field blank and the guard in submit()/validate()
   * blocks international shipments until picked. User's manual pick
   * AFTER prefill is preserved until the next importerProfile change.
   *
   * <p>Only mutates state when isInternational — on domestic
   * shipments the field isn't rendered so leaving stale value is
   * fine (and switching back to intl re-runs this effect via
   * importerProfile change).
   */
  useEffect(() => {
    // Priority: saved CustomsProfile → operator's prior pick → last-
    // used sticky value → blank. Sticky kicks in only when the lane
    // has no profile row AND the field is still untouched, so operator
    // picks are preserved across lane changes.
     
    if (isInternational) {
      setIncoterms((cur) => importerProfile?.incoterms || cur || readSticky('ms:lastIncoterms', ''))
    }
  }, [importerProfile, isInternational, setIncoterms])

  /**
   * Reset incoterms to blank if the operator lands on a UPS+AS/MP lane
   * with DDP prefilled from the customs profile (or set earlier). The
   * FE hides DDP from the picker on that lane per PR of 2026-09-09
   * (UPS wire error 121213); leaving stale "DDP" behind would let the
   * form submit with an unsupported billing option and the backend
   * pre-flight would reject a hop later.
   */
  useEffect(() => {
    if (incoterms === 'DDP' && isUpsDdpDisallowedForTerritory(canon(carrier), recipientTerritory)) {
       
      setIncoterms('')
    }
  }, [carrier, recipientTerritory, incoterms, setIncoterms])

  /** Map a saved profile into the flat importer/broker shape (label-document keys). */
  const partiesFromProfile = (p: CustomsProfile): { importer: Party; broker: Party } => ({
    importer: {
      type: p.importerType || 'BUSINESS',
      name: p.importerName || '', contact: p.importerContact || '', phone: p.importerPhone || '',
      addressLine1: p.importerAddress1 || '', addressLine2: p.importerAddress2 || '',
      city: p.importerCity || '', state: p.importerState || '', postalCode: p.importerPostcode || '',
      countryCode: p.importerCountry || '', taxId: p.importerTaxId || '', taxIdType: p.importerTaxIdType || '',
      eori: p.importerEori || '', ioss: p.importerIoss || '', companyReg: p.importerCompanyReg || '',
      iec: p.importerIec || '', gstin: p.importerGstin || '',
    },
    broker: {
      name: p.brokerName || '', company: p.brokerCompany || '', countryCode: p.brokerCountry || '',
      addressLine1: p.brokerAddress1 || '', addressLine2: p.brokerAddress2 || '', phone: p.brokerPhone || '',
      city: p.brokerCity || '', state: p.brokerState || '', postalCode: p.brokerPostcode || '',
      brokerId: p.brokerId || '', license: p.brokerLicense || '',
    },
  })

  // What the card shows: the override wins; otherwise the resolved client profile.
  const activeParties = override ?? (importerProfile ? partiesFromProfile(importerProfile) : null)
  const vImp = activeParties?.importer
  const vBrk = activeParties?.broker
  const editorSeed = activeParties ?? { importer: { type: 'BUSINESS', countryCode: destCountry }, broker: {} }

  // A new destination or client re-resolves — drop any override tied to the old one.
  useEffect(() => {
     
    setOverride(null)
  }, [clientCode, destCountry, setOverride])

  // Sticky defaults: remember the last reason/incoterms/currency so
  // lane changes (CA → DE) and page returns don't drop the operator
  // back to "-- Select --". Submit-guards still enforce force-pick on
  // blank intl shipments.
  useEffect(() => writeSticky('ms:lastReason', reasonForExport), [reasonForExport])
  useEffect(() => writeSticky('ms:lastIncoterms', incoterms), [incoterms])
  useEffect(() => writeSticky('ms:lastCurrency', currency), [currency])
  useEffect(() => writeSticky('ms:lastDimUnit', dimUnit), [dimUnit])
  useEffect(() => writeSticky('ms:lastWeightUnit', weightUnit), [weightUnit])
  useEffect(() => writeSticky('ms:lastPickupType', pickupType), [pickupType])
  useEffect(() => writeSticky('ms:lastBulkAddCount', bulkAddCount), [bulkAddCount])

  /**
   * Sprint 31 — validate the ship-to address against the SELECTED carrier's
   * own database (UPS AVS / FedEx AV / DHL address-validate / SWSIM
   * CleanseAddress). Complements the platform-side check by confirming the
   * carrier can actually deliver the parcel + returning residential/commercial
   * classification.
   */
  const validateShipment = async () => {
    // PR #530 — zero shippable carriers: block early with a targeted
    // link to Settings instead of the generic "pick a carrier" toast.
    if (noCarriersAtAll) {
      showToast('Add and verify a carrier in Settings before validating.', 'No carriers connected')
      return
    }
    if (!carrier) {
      setShipmentValidationResult(null)
      setChecklistOpen(false)
      showToast('Pick a carrier first — the check is carrier-specific.', '1 field needs attention')
      return
    }
    // Required-when-null guard: block until every label field the
    // carrier needs has a pick. Fires here (Validate) AND in submit()
    // (Generate) so operators see the same message on both buttons.
    if (missingLabelFields.length > 0) {
      setSubmitAttempted(true)
      // The check didn't run on this form: an older result would mislead.
      setShipmentValidationResult(null)
      setChecklistOpen(false)
      showToast(
        missingLabelFields.includes('Duties payor account')
          ? `Enter the Duties payor account (the third party's ${canon(carrier) || 'carrier'} account) before validating.`
          : `Pick ${missingLabelFields.join(' + ')} before validating — the selected account has no saved default.`,
        `${missingLabelFields.length} field${missingLabelFields.length === 1 ? ' needs' : 's need'} attention`,
      )
      scrollToFirstError()
      return
    }
    // BUG-053 — the single-piece-too-many-boxes gate used to live AFTER
    // the formik.validateForm call. In a state where the form is red-
    // field-free but the box count exceeds the packaging cap, validate
    // ran to the carrier and returned mid-shaped results (or silently
    // did nothing when a formik check fired for an unrelated field).
    // Check it first so the operator always sees the actual reason.
    if (singlePieceOnlyWarning) {
      setSubmitAttempted(true)
      setShipmentValidationResult(null)
      setChecklistOpen(false)
      showToast(singlePieceOnlyWarning, 'Too many boxes for this packaging')
      return
    }
    // The same form rules Generate label enforces (lengths, phone digits,
    // HS format, insured ≤ declared…) — highlighted in red, as on Generate.
    // Busy from the click on: the form check itself takes a moment, and a click
    // that showed nothing meanwhile read as ignored.
    setSubmitAttempted(true)
    setCarrierValidating(true)
    let formErrors: string[]
    try {
      formErrors = flattenErrors(await formik.validateForm(formValues as unknown as ShipmentFormValues))
    } finally {
      setCarrierValidating(false)
    }
    if (formErrors.length > 0) {
      // The server check didn't run on this form: an older result would mislead.
      setShipmentValidationResult(null)
      setChecklistOpen(false)
      const more = formErrors.length - 1
      showToast(
        more > 0
          ? `${formErrors[0]} — and ${more} other field${more === 1 ? '' : 's'} highlighted in red.`
          : formErrors[0],
        `${formErrors.length} field${formErrors.length === 1 ? ' needs' : 's need'} attention`,
      )
      scrollToFirstError()
      return
    }
    if (pkgServiceWarning) {
      showToast(pkgServiceWarning, 'Incompatible package + service')
      return
    }
    if (singlePieceOnlyWarning) {
      showToast(singlePieceOnlyWarning, 'Too many boxes for this packaging')
      return
    }
    setCarrierValidating(true)
    try {
      const payload = buildLabelPayload()
      const res = await shipmentValidationService.validate(payload)
      const result = res.data ?? null
      setShipmentValidationResult(result)
      setValidatedAt(new Date())
      setChecklistOpen(true)
      setValidatedRequest(formSnapshotRef.current)
      // Phase 3 — a FAIL names its fields: show them under the inputs too.
      setServerFieldErrors(result?.overall === 'FAIL' ? fieldErrorsFromIssues(result.localErrors) : {})
      if (result?.overall === 'FAIL') scrollToFirstError()
    } catch (e) {
      notify.apiError(e, 'Shipment validation failed.')
    } finally {
      setCarrierValidating(false)
    }
  }

  /**
   * Sprint 38 — search the address book. Fires on every input change
   * (debounce-free — the endpoint caps at 25 hits and returns fast).
   * Empty / < 2 chars → clear suggestions.
   */
  const runRecipientSearch = (q: string) => {
    setRecipientSearch(q)
    setRecipientActive(-1)
    if (recipientSearchTimer.current) clearTimeout(recipientSearchTimer.current)
    if (!q || q.trim().length < 2) {
      recipientSearchSeq.current++          // drop any answer still in flight
      setRecipientSuggestions([])
      setRecipientNoMatch(false)
      setRecipientDropdownOpen(false)
      return
    }
    recipientSearchTimer.current = setTimeout(async () => {
      const seq = ++recipientSearchSeq.current
      try {
        const hits = await recipientBookService.search(q, clientCode || null)
        if (seq !== recipientSearchSeq.current) return   // a newer search has started
        setRecipientSuggestions(hits)
        setRecipientNoMatch(hits.length === 0)
        setRecipientDropdownOpen(true)
      } catch {
        if (seq !== recipientSearchSeq.current) return
        setRecipientSuggestions([])
        setRecipientNoMatch(false)
        setRecipientDropdownOpen(false)
      }
    }, 200)
  }

  /** Arrow keys move through the suggestions, Enter picks, Escape closes. */
  const onRecipientSearchKey = (e: React.KeyboardEvent<HTMLInputElement>) => {
    const n = recipientSuggestions.length
    if (e.key === 'Escape') {
      if (recipientDropdownOpen) {
        e.preventDefault()
        setRecipientDropdownOpen(false)
        setRecipientActive(-1)
      }
      return
    }
    if (n === 0) return
    if (e.key === 'ArrowDown') {
      e.preventDefault()
      setRecipientDropdownOpen(true)
      setRecipientActive((i) => (i + 1) % n)
    } else if (e.key === 'ArrowUp') {
      e.preventDefault()
      setRecipientDropdownOpen(true)
      setRecipientActive((i) => (i <= 0 ? n - 1 : i - 1))
    } else if (e.key === 'Enter' && recipientDropdownOpen && recipientActive >= 0) {
      e.preventDefault()
      applySavedRecipient(recipientSuggestions[recipientActive])
    }
  }

  /**
   * Apply a saved recipient. It REPLACES the whole Ship to block: a field the
   * saved address doesn't have is cleared, not kept. Merging used to leave the
   * previous person behind — pick Jane then Marco and Marco got Jane's email,
   * her "Suite 400" and her state, which could put the wrong contact on a label.
   */
  const applySavedRecipient = (r: SavedRecipient) => {
    const country = r.countryCode || 'US'
    setRecipient({
      name: r.name ?? '',
      company: r.company ?? '',
      phone: r.phone ?? '',
      email: r.email ?? '',
      addressLine1: r.addressLine1 ?? '',
      addressLine2: r.addressLine2 ?? '',
      addressLine3: r.addressLine3 ?? '',
      city: r.city ?? '',
      state: r.state ?? '',
      postalCode: r.postalCode ?? '',
      countryCode: country,
      residential: r.residential ?? false,
      // Dial code is always derived from the country (operator ask
      // 2026-09-12), never taken from the saved row.
      phoneCountryCode: dialCodeFor(country) || '',
    })
    // A picked address carries its own residential flag; forget any
    // earlier "ticked automatically" state from the previous recipient.
    setResidentialOrigin(r.residential == null ? undefined : 'manual')
    setRecipientSearch(r.name)
    setRecipientSuggestions([])
    setRecipientNoMatch(false)
    setRecipientActive(-1)
    setRecipientDropdownOpen(false)
    notify.success(`Loaded ${r.name} from the address book.`)
  }

  /**
   * Save the Ship to block to the address book — the only way an address gets
   * there from the app. Belongs to the chosen client (or is shared when no
   * client is picked). The server treats the same name + street + postal code
   * as the same entry, so a repeat save offers to update it instead.
   */
  const [savingToBook, setSavingToBook] = useState(false)
  const saveRecipientToBook = async () => {
    const r = recipient
    const missing = [
      !r.name?.trim() && 'name',
      !r.addressLine1?.trim() && 'street',
      !r.city?.trim() && 'city',
      !r.postalCode?.trim() && 'postal code',
    ].filter(Boolean)
    if (missing.length > 0) {
      notify.info({ title: 'Not saved yet', body: `Fill in the ${missing.join(', ')} first.` })
      return
    }
    const entry: SavedRecipient = {
      ownerCustomerNo: clientCode || null,
      name: r.name.trim(),
      company: r.company?.trim() || null,
      phone: r.phone?.trim() || null,
      email: r.email?.trim() || null,
      addressLine1: r.addressLine1.trim(),
      addressLine2: r.addressLine2?.trim() || null,
      addressLine3: r.addressLine3?.trim() || null,
      city: r.city.trim(),
      state: r.state?.trim() || null,
      postalCode: r.postalCode.trim(),
      countryCode: r.countryCode,
      residential: r.residential ?? null,
    }
    const owner = clientCode ? `for ${clientCode}` : 'as a shared address (every client can use it)'
    setSavingToBook(true)
    try {
      const res = await recipientBookService.save(entry)
      const saved = res.data
      if (!isDuplicateSave(res.message) || !saved?.id) {
        notify.success(`Saved ${entry.name} to the address book ${owner}.`)
        return
      }
      // Same person already saved: offer to bring it up to date, and say what
      // would change so nobody overwrites a good phone number by accident.
      const changed = (['company', 'phone', 'email', 'addressLine2', 'state', 'residential'] as const)
        .filter((k) => (saved[k] ?? '') !== (entry[k] ?? ''))
      if (changed.length === 0) {
        notify.info(`${entry.name} is already in the address book, with these details.`)
        return
      }
      const labels: Record<string, string> = {
        company: 'company', phone: 'phone', email: 'email', addressLine2: 'address line 2',
        state: 'state', residential: 'residential',
      }
      const ok = await notify.confirm(
        `${entry.name} at ${entry.addressLine1} is already in the address book. `
          + `Update its ${changed.map((k) => labels[k]).join(', ')} to what's on this form?`,
        { title: 'Already saved', confirmLabel: 'Update it', cancelLabel: 'Keep the saved one' },
      )
      if (!ok) return
      await recipientBookService.update(saved.id, entry)
      notify.success(`Updated ${entry.name} in the address book.`)
    } catch (e) {
      notify.apiError(e, 'Could not save the address.')
    } finally {
      setSavingToBook(false)
    }
  }



  // PR #558 — per-item weight auto-fill. Sums quantities across all rows
  // (min 1 to guard divide-by-zero) then spreads the shipment-level
  // package weight proportionally when a row's own weight is blank.
  // Operator override always wins. Defined BEFORE wizardPayload/other
  // useMemos that reference it — a `const` fn hoisted via TDZ crashes
  // the entire page render on first mount if used before declaration.
  const totalItemQty = items.reduce((sum, it) => sum + (Number(it.quantity) || 0), 0) || 1
  const pkgWeightNumeric = Number(weight) || 0
  // Apportion base is the WHOLE shipment (box 1 + every extra box) — using
  // box 1 alone spread 2 lb across the commodities of a 45 × 2 lb order.
  const shipmentWeightNumeric =
    pkgWeightNumeric + extraPackages.reduce((s, p) => s + (Number((p as { weight?: string | number }).weight) || 0), 0)
  const autoItemWeight = (it: ItemRow): number => {
    if (it.weight) return Number(it.weight) || 0
    const qty = Number(it.quantity) || 0
    if (shipmentWeightNumeric <= 0 || qty <= 0) return 0
    // 3-decimal precision matches BE fallback in buildManualIntlBlock.
    return Math.round((shipmentWeightNumeric * qty / totalItemQty) * 1000) / 1000
  }

  // Wizard payload = a snapshot of the current inline state in the shape
  // CustomsWizard expects (OrderCustomsPayload). Rebuilt each time the
  // wizard opens so it starts from whatever the user last typed inline.
  const wizardPayload = useMemo<OrderCustomsPayload>(() => ({
    items: items
      .filter((it) => it.description.trim() || it.hsCode.trim())
      .map<CustomsItem>((it) => ({
        description: it.description.trim(),
        hsCode: it.hsCode.trim() || null,
        countryOfOrigin: it.countryOfOrigin.trim().toUpperCase() || null,
        quantity: Number(it.quantity) || 1,
        unitValue: Number(it.unitValue) || 0,
        // PR #558 — per-item weight; auto-fill from pkg weight when blank.
        // Typed weights only. The auto-apportioned figure is shown as the
        // placeholder for transparency but NOT posted: persisting it made an
        // estimate look like a declared per-item weight (no "(est)" on the
        // invoice). The backend spreads the shipment weight for the carrier.
        weight: it.weight ? Number(it.weight) || null : null,
        sku: it.sku.trim() || null,
      })),
    incoterms,
    reasonForExport,
    currency,
    weightUnit,
    ftrExemption: ftrExemption || undefined,
    aesCitation: aesCitation || undefined,
    exportDeclarationReference: exportDeclarationReference || undefined,
  }), [items, incoterms, reasonForExport, currency, weightUnit, ftrExemption, aesCitation, exportDeclarationReference])

  /** Copy wizard-side state back into the inline form state so both stay in sync. */
  const acceptWizardPayload = (payload: OrderCustomsPayload) => {
    const rows: ItemRow[] = (payload.items ?? []).map((it) => ({
      description: it.description ?? '',
      sku: it.sku ?? '',
      hsCode: it.hsCode ?? '',
      countryOfOrigin: it.countryOfOrigin ?? '',
      quantity: String(it.quantity ?? 1),
      unitValue: it.unitValue != null ? String(it.unitValue) : '',
      boxSeq: '1', // wizard doesn't collect the box; default to box 1
      weight: it.weight != null ? String(it.weight) : '', // PR #558 — wizard doesn't collect weight yet; fallback fills.
    }))
    // Keep at least one row so the inline form isn't blank after a Save
    // with zero items entered in the wizard.
    setItems(rows.length ? rows : [blankItem()])
    if (payload.incoterms) setIncoterms(payload.incoterms)
    if (payload.reasonForExport) setReasonForExport(payload.reasonForExport)
    if (payload.currency) setCurrency(payload.currency)
    // EEI — accept either code exclusively; wizard already clears the
    // other side on pick, but re-assert here so wire state matches UI.
    if (payload.ftrExemption) { setFtrExemption(payload.ftrExemption); setAesCitation('') }
    else if (payload.aesCitation) { setAesCitation(payload.aesCitation); setFtrExemption('') }
    else { setFtrExemption(''); setAesCitation('') }
    // Generic export declaration reference — independent of the US
    // FTR/AES pair. Sync verbatim.
    setExportDeclarationReference(payload.exportDeclarationReference ?? '')
    if (payload.weightUnit && (payload.weightUnit === 'LB' || payload.weightUnit === 'KG')) {
      setWeightUnit(payload.weightUnit)
    }
    setWizardOpen(false)
    notify.success('Customs details saved to this shipment.')
  }

  const patchItem = (i: number, patch: Partial<ItemRow>) =>
    setItems((rows) => rows.map((r, idx) => (idx === i ? { ...r, ...patch } : r)))
  const [pasteOpen, setPasteOpen] = useState(false)
  const importPastedItems = (rows: PastedItem[]) => {
    setItems((cur) => {
      const kept = cur.filter((r) => r.description.trim() || r.hsCode.trim())
      return [...rows.map((r) => ({ ...blankItem(), ...r })), ...kept]
    })
    notify.success(`${rows.length} commodity line${rows.length === 1 ? '' : 's'} added.`)
    requestAnimationFrame(() => document.querySelector('[data-items-scroll]')?.scrollTo({ top: 0 }))
  }
  const addItem = () => {
    // BUG-013: append to end, not prepend — the operator sees the new
    // blank row below their last filled row and keeps typing downward.
    setItems((rows) => [...rows, blankItem()])
    // New rows are prepended; the virtualised list only paints the rows in
    // its window, so scroll to the top or the new row is invisible and the
    // click looks ignored (the stress test read that as a 16-row cap).
    requestAnimationFrame(() => document.querySelector('[data-items-scroll]')?.scrollTo({ top: 0 }))
  }
  const removeItem = (i: number) => setItems((rows) => (rows.length > 1 ? rows.filter((_, idx) => idx !== i) : rows))
  /** Put every line in box 1 (single-carton shipments, or a reset before re-assigning). */
  const assignAllToBox1 = () => {
    setItems((rows) => rows.map((r) => ({ ...r, boxSeq: '1' })))
    notify.success('Every line assigned to box 1.')
  }
  /** Split the lines into N consecutive runs, one per box, in the order they are listed. */
  const spreadItemsAcrossBoxes = () => {
    const boxes = 1 + extraPackages.length
    setItems((rows) => rows.map((r, i) => ({ ...r, boxSeq: String(Math.min(boxes, Math.floor((i * boxes) / rows.length) + 1)) })))
    notify.success(`Lines spread across ${boxes} boxes in order — adjust any line's Pkg # if needed.`)
  }
  const invoiceTotal = items.reduce((sum, it) => sum + (Number(it.quantity) || 0) * (Number(it.unitValue) || 0), 0)

  // PR #558 — for international shipments, keep the `declaredValue`
  // state (which drives Formik/Yup validation + the carrier payload)
  // in sync with the auto-computed invoiceTotal. Domestic shipments
  // are unchanged — operator's manual entry wins. The input is
  // rendered as readonly (see Field) so the operator can't drift from
  // this value; changing Items unit prices propagates automatically.
  // Guards against feedback loops: only writes when the string
  // representation actually differs.
  useEffect(() => {
    if (!isInternational) return
    const next = invoiceTotal > 0 ? invoiceTotal.toFixed(2) : ''
     
    if (next !== declaredValue) setDeclaredValue(next)
    // eslint-disable-next-line react-hooks/exhaustive-deps -- declaredValue read to prevent infinite loop; setDeclaredValue is stable
  }, [isInternational, invoiceTotal])

  // ── AI assist handlers (one per section, suggestion-only) ──────────────────
  const weightInLb = () => {
    const w = Number(weight) || 0
    return weightUnit === 'KG' ? Math.round(w * 2.20462 * 100) / 100 : w
  }

  /** Package & weight — recommend a package + weight from the item list. */
  const suggestPackagingAi = async () => {
    const usable = items.filter((it) => it.description.trim())
    if (!usable.length) return notify.error('Add at least one item description first.')
    setPkgBusy(true)
    try {
      const r = await aiService.suggestPackaging(
        usable.map((it) => ({ description: it.description.trim(), quantity: Number(it.quantity) || 1 })),
      )
      const filled: string[] = []
      if (r.weightLb) {
        setWeightUnit('LB')
        setWeight(String(r.weightLb))
        filled.push('weight')
      }
      if (r.lengthIn || r.widthIn || r.heightIn) {
        setDimUnit('IN')
        setPackageChoice(CUSTOM_PKG)
        if (r.lengthIn) setLength(String(r.lengthIn))
        if (r.widthIn) setWidth(String(r.widthIn))
        if (r.heightIn) setHeight(String(r.heightIn))
        filled.push('dimensions')
      }
      if (!filled.length) return notify.error('No packaging estimate was returned.')
      const why = r.rationale ? ` — ${r.rationale.replace(/\s*\.\s*$/, '')}` : ''
      notify.success(`Suggested ${filled.join(' + ')}${why}. Please review.`)
    } catch (err) {
      notify.apiError(err, 'Packaging suggestion failed.')
    } finally {
      setPkgBusy(false)
    }
  }

  /** Account & service — recommend a service + incoterm for the route. */
  const recommendServiceAi = async () => {
    if (!recipient.countryCode) return notify.error('Set the ship-to country first.')
    setSvcBusy(true)
    try {
      const r = await aiService.recommendService({
        fromCountry: sender.countryCode || undefined,
        toCountry: recipient.countryCode || undefined,
        weightLb: weightInLb() || undefined,
        available: servicesForCarrier.map((s) => s.name),
      })
      const applied: string[] = []
      const wanted = (r.serviceName || r.serviceCode || '').toLowerCase()
      const match = wanted ? servicesForCarrier.find((s) => s.name.toLowerCase() === wanted) : undefined
      if (match) {
        setServiceId(match.id)
        applied.push(`service “${match.name}”`)
      }
      if (r.incoterm === 'DDP' || r.incoterm === 'DAP') {
        setIncoterms(r.incoterm)
        applied.push(r.incoterm)
      }
      notify.success(
        `${applied.length ? `Applied ${applied.join(' + ')}. ` : ''}${r.rationale || `Recommended ${r.serviceName || r.serviceCode || 'a service'}`}`,
      )
    } catch (err) {
      notify.apiError(err, 'Service recommendation failed.')
    } finally {
      setSvcBusy(false)
    }
  }

  /** Shipment — pre-ship AI sanity review of the whole form. */
  const reviewShipmentAi = async () => {
    setReviewBusy(true)
    try {
      const pkgName =
        packageChoice === CUSTOM_PKG
          ? 'CUSTOM'
          : packagesForCarrier.find((p) => String(p.id) === packageChoice)?.name || undefined
      const r = await aiService.reviewShipment({
        fromCountry: sender.countryCode,
        toCountry: recipient.countryCode,
        weightLb: weightInLb() || undefined,
        lengthIn: Number(length) || undefined,
        widthIn: Number(width) || undefined,
        heightIn: Number(height) || undefined,
        packageCode: pkgName,
        incoterm: incoterms,
        items: isInternational
          ? items
              .filter((it) => it.description.trim())
              .map((it) => ({
                description: it.description.trim(),
                hsCode: it.hsCode || undefined,
                value: (Number(it.quantity) || 0) * (Number(it.unitValue) || 0),
              }))
          : [],
      })
      setReviewWarnings(r.warnings || [])
      if (!r.warnings?.length) notify.success('AI review: no issues found — good to ship.')
    } catch (err) {
      notify.apiError(err, 'AI review failed.')
    } finally {
      setReviewBusy(false)
    }
  }

  /** Flatten a (possibly nested/array) Formik error tree into a list of messages. */
  /** Every form-rule message, named by its field ("Ship to name: Max 35 characters"). */
  const flattenErrors = (node: unknown, path = ''): string[] => {
    if (!node) return []
    if (typeof node === 'string') {
      const where = fieldLabelFor(path)
      return [where && !/^Box \d+:/.test(node) ? `${where}: ${node.charAt(0).toLowerCase()}${node.slice(1)}` : node]
    }
    if (Array.isArray(node)) return node.flatMap((n, i) => flattenErrors(n, `${path}[${i}]`))
    return Object.entries(node as Record<string, unknown>).flatMap(([k, v]) => flattenErrors(v, path ? `${path}.${k}` : k))
  }

  // Commodity auto-split — invoked when the operator picks a strategy
  // in SplitShipmentModal. Re-submits the original payload with the
  // chosen strategy set; backend runs the N-way split. Same success/
  // failure handling as the initial submit (success = navigate to
  // /label/{orderNo}; error = toast).
  const resubmitWithSplit = async (strategy: SplitStrategy) => {
    if (!pendingSplitPayload) return
    const nextPayload: ManualShipmentPayload = { ...pendingSplitPayload, splitStrategy: strategy }
    setSplitPrompt(null)
    setPendingSplitPayload(null)
    setSubmitting(true)
    setQueuedInfo(null)
    const splitAttemptKey = newAttemptKey()
    rememberPendingAttempt(splitAttemptKey, fixOrderNo)
    try {
      const res = fixOrderNo
        ? await orderService.regenerateOrder(fixOrderNo, nextPayload, splitAttemptKey)
        : await orderService.generateManualLabel(nextPayload, splitAttemptKey)
      forgetPendingAttempt()
      const orderNo = res.data?.orderNo ?? null
      const status = res.data?.status
      // PR-G4 — under USPS_DIRECT the split's child labels went to the
      // 55/hr queue instead of being dispatched. Surface the queued
      // panel INSTEAD of navigating; the operator watches progress
      // in Data History (or via the MpsProgressCard for QUEUED_MPS).
      if (status === 'QUEUED' || status === 'QUEUED_MPS') {
        setQueuedInfo({
          orderNo,
          queueItemId: res.data?.queueItemId ?? null,
          isMps: status === 'QUEUED_MPS',
          mpsPieceCount: res.data?.mpsPieceCount ?? null,
        })
        notify.info({
          title: 'Queued for USPS',
          body:
            'Your split shipment is in the USPS Direct queue (~55 labels/hr platform cap). Track progress below or in Data History.',
        })
        return
      }
      notify.success(res.message || `Shipment split into ${res.data?.orderNo ? 'multiple' : 'N'} labels.`)
      navigate(orderNo ? `/label/${orderNo}` : '/orders')
    } catch (e) {
      settlePendingAttempt(e)
      const raw = e instanceof ApiError
        ? e.message
        : e instanceof Error
          ? e.message
          : 'Failed to generate split labels.'
      notify.error(raw)
    } finally {
      setSubmitting(false)
    }
  }

  /**
   * The exact request Generate label sends. Validate sends it too, so a
   * PASS is about the shipment that will actually be bought — every box,
   * the reference and the importer/broker override included.
   */
  const buildLabelPayload = (): ManualShipmentPayload => buildShipmentPayload({
    sender, recipient, isReturn, returnType, returnPickupRequested, rmaNumber, returnReason, originalOrderNo,
    reference, dtcLineId, fixOrderNo, fixChannel, note, ndsStdReplacementErpCode, carrier, accountNumber, accounts,
    serviceId, isCustomPkg, packageChoice, packagesForCarrier, customBoxes, length, width, height, dimUnit, weight,
    weightUnit, clientCode, warehouseCode, declaredValue, currency, dgBlock, signatureOption, insuredValue,
    customFieldValues, labelImageType, labelStockType, labelImageFormat, pickupType, extraPackages, isInternational,
    items, reasonForExport, incoterms, clearanceOption, dutiesAccount, ftrExemption, aesCitation,
    exportDeclarationReference, override, ndsQueue, ndsActiveIdx, clients,
  })

  // Every value the label request is built from — when it differs from the
  // one taken at the last check, the form changed since.
  const formSnapshot = JSON.stringify([
    sender, recipient, isReturn, returnType, rmaNumber, returnReason, originalOrderNo, reference, carrier, accountNumber, serviceId,
    packageChoice, length, width, height, dimUnit, weight, weightUnit, clientCode, warehouseCode,
    declaredValue, currency, dgBlock, signatureOption, insuredValue, labelImageType, labelStockType,
    labelImageFormat, pickupType, extraPackages, items, reasonForExport, incoterms, clearanceOption,
    dutiesAccount, ftrExemption, aesCitation, exportDeclarationReference, override,
  ])
  const validationStale = !!shipmentValidationResult && !!validatedRequest && formSnapshot !== validatedRequest
  // Generate label unlocks only after this exact form passed Validate
  // (Passed or To review). A failed check, or any edit since, locks it again.
  const validationGate: string | null = !shipmentValidationResult
    ? 'Validate the shipment first.'
    : shipmentValidationResult.overall === 'FAIL'
      ? 'Fix the issues in the shipment check, then validate again.'
      : validationStale
        ? 'The form changed since the check — validate again.'
        : null
  // Leaving with work on the form asks first; reload / closing the tab gets
  // the browser's own "Leave site?" prompt.
  const formHasWork = !!(recipient.name?.trim() || recipient.addressLine1?.trim() || reference.trim()
    || items.some((it) => it.description.trim()) || extraPackages.length > 0)
  useEffect(() => {
    if (!formHasWork || submitting) return
    const warn = (e: BeforeUnloadEvent) => { e.preventDefault(); e.returnValue = '' }
    window.addEventListener('beforeunload', warn)
    return () => window.removeEventListener('beforeunload', warn)
  }, [formHasWork, submitting])

  // BUG-014: the sticky Validate/Generate footer is ~96 px above the viewport
  // bottom; the default toast position (bottom-4 = 1rem) sits directly over
  // the buttons. Lift the toast stack above the footer while this page is
  // mounted and restore on unmount so other pages keep the default.
  useEffect(() => {
    document.documentElement.style.setProperty('--toast-bottom', '7rem')
    return () => {
      document.documentElement.style.removeProperty('--toast-bottom')
    }
  }, [])

  const leaveForm = async () => {
    if (formHasWork && !(await notify.confirm(
      "This shipment hasn't been created yet. Leave and lose what you've entered?",
      { title: 'Leave this shipment?', confirmLabel: 'Leave', cancelLabel: 'Stay' },
    ))) return
    navigate('/orders')
  }
  const formSnapshotRef = useRef('')
  useEffect(() => { formSnapshotRef.current = formSnapshot }, [formSnapshot])

  const submit = async () => {
    if (validationGate) {
      showToast(validationGate, 'Validate before generating')
      return
    }
    // Yup + Formik gate — validate the mirrored form values before anything else.
    setSubmitAttempted(true)
    // PR #530 — zero shippable carriers: block early with a
    // Settings-link toast rather than the yup / label-guard errors
    // that would fire on downstream blank fields (carrier === '',
    // no label defaults, etc.).
    if (noCarriersAtAll) {
      showToast(
        'Add + verify a carrier account in Settings before generating a label.',
        'No carriers connected',
      )
      return
    }
    // Required-when-null guard runs BEFORE yup so we surface a
    // targeted toast; yup doesn't know about the label / shipping-
    // purpose fields (they're not in the form schema).
    if (missingLabelFields.length > 0) {
      showToast(
        missingLabelFields.includes('Duties payor account')
          ? `Enter the Duties payor account (the third party's ${canon(carrier) || 'carrier'} account).`
          : `Pick ${missingLabelFields.join(' + ')} — no saved default for this client / account.`,
        `${missingLabelFields.length} field${missingLabelFields.length === 1 ? ' needs' : 's need'} attention`,
      )
      scrollToFirstError()
      return
    }
    const errs = await formik.validateForm(formValues as unknown as ShipmentFormValues)
    const msgs = flattenErrors(errs)
    if (msgs.length > 0) {
      const more = msgs.length - 1
      // Lightweight toast (not a blocking modal) — the field-level red messages
      // below each input are the primary guidance.
      showToast(
        more > 0
          ? `${msgs[0]} — and ${more} other field${more === 1 ? '' : 's'} highlighted in red.`
          : msgs[0],
        `${msgs.length} field${msgs.length === 1 ? ' needs' : 's need'} attention`,
      )
      // Bring the first invalid field into view.
      scrollToFirstError()
      return
    }

    // BUG-067 — Returns Analytics was 100% "Unknown" because the reason
    // picker was optional and operators left it blank. Make it required
    // when isReturn so every new return rolls up into a real bucket.
    // The picker options match CarrierServiceImpl.RETURN_REASON_CODES.
    if (isReturn && !returnReason) {
      showToast(
        'Pick a return reason — it drives the Returns Analytics breakdown. '
          + 'Options: Wrong item, Defective, No longer needed, Size, Other.',
        'Return reason required',
      )
      return
    }
    // Packaging ↔ service compatibility gate (carrier would otherwise 400).
    if (pkgServiceWarning) {
      showToast(pkgServiceWarning, 'Incompatible package + service')
      return
    }
    if (singlePieceOnlyWarning) {
      showToast(singlePieceOnlyWarning, 'Too many boxes for this packaging')
      return
    }

    const payload = buildLabelPayload()

    setSubmitting(true)
    setQueuedInfo(null)
    setServerFieldErrors({})
    setClearedFixKeys(new Set())
    // Phase 3 — one key per attempt, remembered until the request settles so a
    // reload mid-purchase can recover the outcome instead of buying twice.
    const attemptKey = newAttemptKey()
    rememberPendingAttempt(attemptKey, fixOrderNo)
    try {
      const res = fixOrderNo
        ? await orderService.regenerateOrder(fixOrderNo, payload, attemptKey, shipmentValidationResult?.fingerprint)
        : await orderService.generateManualLabel(payload, attemptKey, shipmentValidationResult?.fingerprint)
      forgetPendingAttempt()
      const orderNo = res.data?.orderNo
      // Sprint 43 — persist custom field values against the new order.
      // Best-effort: never block the success navigation on this call.
      if (orderNo && Object.keys(customFieldValues).length > 0) {
        try {
          await customFieldService.upsertValues(orderNo, customFieldValues, clientCode || null)
        } catch (cfErr) {
          notify.error(
            cfErr instanceof Error
              ? `Label generated but custom fields failed to save: ${cfErr.message}`
              : 'Label generated but custom fields failed to save.',
          )
        }
      }
      // PR-G4 — USPS_DIRECT routes labels through the 55/hr platform
      // queue. status='QUEUED' (single) / 'QUEUED_MPS' (multi-piece)
      // means the label was NOT dispatched sync; there's no
      // trackingNumber yet and navigating to /label/{n} would show an
      // empty label. Surface the queued panel inline instead.
      const status = res.data?.status
      if (status === 'QUEUED' || status === 'QUEUED_MPS') {
        setQueuedInfo({
          orderNo: orderNo ?? null,
          queueItemId: res.data?.queueItemId ?? null,
          isMps: status === 'QUEUED_MPS',
          mpsPieceCount: res.data?.mpsPieceCount ?? null,
        })
        notify.info({
          title: 'Queued for USPS',
          body:
            'Your label is in the USPS Direct queue (~55 labels/hr platform cap). Estimated wait time and progress are shown below.',
        })
        return
      }
      notify.success(res.message || 'Shipment label generated.')
      if (orderNo && dtcLine) await linkDtcLine(orderNo)
      // (a) Auto-advance to the next queued NDS shipment. When the operator
      // scanned multiple orders, drop the just-saved chip from the queue and
      // load the next one in-place instead of navigating away. Empty queue
      // → normal navigate.
      if (ndsQueue.length > 1 && ndsActiveIdx >= 0) {
        const rest = ndsQueue.filter((_, i) => i !== ndsActiveIdx)
        const nextIdx = Math.min(ndsActiveIdx, rest.length - 1)
        setNdsQueue(rest)
        setNdsActiveIdx(nextIdx)
        applyNdsPrefill(rest[nextIdx])
        notify.info(rest.length === 1
          ? 'Loaded the last queued shipment.'
          : `${rest.length} queued shipments remaining — loaded the next one.`)
        return
      }
      navigate(orderNo ? `/label/${orderNo}` : '/orders')
    } catch (e) {
      // A reload mid-purchase aborts the request; only a server answer
      // settles the attempt, otherwise the next mount looks it up.
      settlePendingAttempt(e)
      // Phase 3 — the server named the field: show it under the input.
      if (e instanceof ApiError && e.status === 422 && typeof e.payload?.errors?.field === 'string') {
        setServerFieldErrors(fieldErrorsFromIssues([e.payload.errors]))
        scrollToFirstError()
      }
      // Commodity auto-split — 422 SPLIT_REQUIRED. Open the modal with
      // the strategy options instead of showing an error toast. Operator
      // picks; picker calls resubmit with the strategy populated.
      if (e instanceof ApiError && e.errorCode === 'SPLIT_REQUIRED'
          && e.payload?.data) {
        setSplitPrompt(e.payload.data as SplitRequiredPayload)
        setPendingSplitPayload(payload)
        return
      }
      // PR-G4 — 422 INTL_MPS_UNSUPPORTED (USPS_DIRECT rejects
      // international multi-piece at enqueue-time — v3 API has no
      // batched intl endpoint). Surface the actionable backend message
      // as a persistent error toast instead of the generic error path.
      if (e instanceof ApiError && e.errorCode === 'INTL_MPS_UNSUPPORTED') {
        notify.error({
          title: 'International MPS not supported on USPS Direct',
          body:
            e.message ||
            'USPS Direct does not accept international multi-piece shipments. Split into single-piece labels or pick another carrier.',
        })
        return
      }
      const raw = e instanceof ApiError
        ? e.message
        : e instanceof Error
          ? e.message
          : 'Failed to generate the label.'
      // Backend's IntlShipmentValidator concatenates every gap into a single
      // message with a stable prefix. Parse it back into a structured
      // notify.error so the user sees a title + bullet list instead of a
      // wall of text.
      const parsed = parseIntlValidationMessage(raw)
      if (parsed) {
        notify.error({ title: parsed.title, body: parsed.body })
      } else if (/rejected the (manual )?shipment|createShipment|HTTP \d{3}/i.test(raw)) {
        // Carrier rejection — the backend message carries the raw connector
        // payload ("FEDEX createShipment HTTP 400: {json}"). Humanize it for
        // the toast (same treatment the orders list gives the Failure column)
        // and keep the payload in the console for debugging. Preserve the
        // "(saved as order N)" locator so the operator can find the row.
        console.debug('[manual-label] carrier rejection:', raw)
        const savedAs = raw.match(/\(saved as order \d+\)/)?.[0]
        notify.error({
          title: 'The carrier rejected this shipment',
          body: summarizeCarrierError(raw) + (savedAs ? ` ${savedAs}` : ''),
        })
      } else {
        notify.error(raw)
      }
    } finally {
      setSubmitting(false)
    }
  }

  // PR #530 — replaced the full-page "No shippable carrier yet" empty
  // state with an inline banner + force-block on submit, so operators
  // can lay out the shipment (addresses, package, items) while
  // Settings is missing a carrier. Two cases:
  //   - tenant-wide: zero carriers across all clients + platform.
  //   - client-scoped: client picked has no own carriers assigned,
  //     but platform (verified) accounts exist as a fallback. Soft
  //     warning; shipping is still allowed via platform.
  const noCarriersAtAll = !loading && !anyShippableCarrier
  const clientHasNoOwnCarriers = !loading && !!clientCode
    && clientCarriers.length === 0 && platformCarriers.length > 0

  /**
   * True when the operator has picked a service that requires residential
   * (FedEx GROUND_HOME_DELIVERY) but the recipient's residential flag
   * is unticked. Drives:
   *   1. An inline warning banner near the residential checkbox
   *   2. The Submit button's disabled state
   * Both surfaces exist so operators see the constraint in context AND
   * can't submit a shipment doomed to fail at the carrier. Backend
   * boundary guard in CarrierServiceImpl catches API callers who
   * bypass the FE, so this is UX polish rather than the only line of
   * defense.
   */
  const selectedServiceCode = serviceId !== ''
    ? services.find((s) => s.id === Number(serviceId))?.serviceCode
    : undefined
  const residentialConflict = selectedServiceCode === 'GROUND_HOME_DELIVERY'
    && !recipient.residential

  return {
    CURRENCIES, CUSTOM_PKG, acceptWizardPayload, accountNumber, accountsForCarrier, activateQueued, activeParties, addBoxBlockedMessage, addItem, addrErrors, aesCitation, anyShippableCarrier, applyClient, applySavedRecipient, assignAllToBox1, autoItemWeight, blankExtraPackage, bulkAddCount, canAddMoreBoxes, canEstimateLandedCost, carrier, carrierLabelMap, carrierOptions, carrierValidating, checklistOpen, clearFixKey, clearanceOption, clientCarriers, clientCode, clientHasNoOwnCarriers, clientWarehouses, clients, compatiblePresetIdsForService, currency, currentBoxCount, customBoxes, customFieldValues, declaredValue, destAllowed, destCountry, destRules, dgBlock, dgWizardOpen, dimUnit, dtcBatch, dtcLine, dtcLineId, duplicateBox, dutiesAccount, editorSeed, errAt, expandedBoxes, exportDeclarationReference, extraPackages, fixError, fixItemError, fixLoading, fixOrderNo, fixVoided, ftrExemption, height, importPastedItems, importerProfile, incoterms, insuredValue, invoiceTotal, isCustomPkg, isInternational, isRecipientUsTerritory, isReturn, itemErrAt, items, joinParts, labelImageFormat, labelImageType, labelStockType, landedCostOpen, landedCostRequest, leaveForm, loading, maxPiecesContext, mode, navigate, ndsActiveIdx, ndsBanner, ndsLoading, ndsQueue, ndsScan, noCarriersAtAll, note, onRecipientSearchKey, onScanSubmit, originalOrderNo, override, overrideEditorOpen, packageChoice, packagesForCarrier, pasteOpen, patchItem, pickupType, pkgBusy, pkgServiceWarning, platformCarriers, queuedInfo, reasonForExport, recipient, recipientActive, recipientDropdownOpen, recipientEffectiveCountry, recipientNoMatch, recipientSearch, recipientSuggestions, recipientTerritory, recipientTerritoryEarly, recommendServiceAi, reference, removeBox, removeItem, removeQueued, residentialConflict, residentialOrigin, resubmitWithSplit, returnEmailMissing, returnPickupRequested, returnReason, returnType, reviewBusy, reviewShipmentAi, reviewWarnings, rmaNumber, runRecipientSearch, saveRecipientToBook, savingToBook, sender, senderTerritoryEarly, serviceId, services, servicesForCarrier, setAccountNumber, setAesCitation, setBulkAddCount, setCarrier, setChecklistOpen, setClearanceOption, setCurrency, setCustomFieldValues, setDeclaredValue, setDgBlock, setDgWizardOpen, setDimUnit, setDimUnitTouched, setDutiesAccount, setExportDeclarationReference, setExtraPackages, setFtrExemption, setHeight, setIncoterms, setInsuredValue, setLabelImageFormat, setLabelImageType, setLabelStockType, setLandedCostOpen, setLength, setNdsBanner, setNdsScan, setNote, setOriginalOrderNo, setOverride, setOverrideEditorOpen, setPackageChoice, setPasteOpen, setPendingSplitPayload, setPickupType, setPickupTypeTouched, setReasonForExport, setRecipient, setRecipientActive, setRecipientDropdownOpen, setReference, setResidentialOrigin, setReturnPickupRequested, setReturnReason, setReturnType, setReviewWarnings, setRmaNumber, setSender, setServiceId, setSignatureOption, setSplitPrompt, setToast, setWarehouseCode, setWeight, setWeightUnit, setWeightUnitTouched, setWidth, setWizardOpen, shipmentValidationResult, signatureOption, singlePieceOnlyWarning, splitPrompt, spreadItemsAcrossBoxes, submit, submitAttempted, submitting, suggestPackagingAi, svcBusy, switchMode, toast, toggleBox, upsReturnMultiPkgNote, vBrk, vImp, validateShipment, validatedAt, validationGate, validationStale, warehouseCode, weight, weightOverPresetMaxWarning, weightUnit, width, wizardOpen, wizardPayload,
    length, }
}
