import { useEffect, useState, useRef } from 'react'
import { useModalDismiss } from '../hooks/useModalDismiss'
import MpsProgressCard from './orders/MpsProgressCard'
import { notify } from '../utils/notify'
import { FiZap, FiArrowRight, FiArrowLeft, FiTruck, FiPackage, FiRotateCcw, FiCheckCircle, FiAlertTriangle, FiX } from 'react-icons/fi'
import { orderService } from '../api/orderService'
import { SplitShipmentModal } from './modals/SplitShipmentModal'
import { summarizeCarrierError } from '../utils/carrierErrorMap'
import ShipmentPartiesOverrideModal from './modals/ShipmentPartiesOverrideModal'
import CustomFieldsSection from './shared/CustomFieldsSection'
import CustomsWizard from './shipment/CustomsWizard'
import DangerousGoodsWizard from './shipment/DangerousGoodsWizard'
import LandedCostModal from './shipment/LandedCostModal'
import { postalCodeOptionalFor } from '../utils/countryFormats'
import { canon } from '../utils/carrierCanon'
import { SectionRail } from './NewShipmentComponents/_shared'
import ValidationChecklist from './ValidationChecklist'


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

import { useNewShipmentModel } from './NewShipmentComponents/useNewShipmentModel'
import { ShipmentSection } from './NewShipmentComponents/sections/ShipmentSection'
import { AddressesSection } from './NewShipmentComponents/sections/AddressesSection'
import { ServiceSection } from './NewShipmentComponents/sections/ServiceSection'
import { PackageSection } from './NewShipmentComponents/sections/PackageSection'
import { CustomsSection } from './NewShipmentComponents/sections/CustomsSection'
import { ItemsSection } from './NewShipmentComponents/sections/ItemsSection'
import type { ExtraPackage, NewShipmentItemRow } from '../shipment/useManualShipment'
export type { ExtraPackage, NewShipmentItemRow }

export default function NewShipmentPage() {
  const m = useNewShipmentModel()
  // BUG-042 — tab title: "New return · Multiship" / "Fix order
  // #900046 · Multiship" / "New shipment · Multiship".
  const pageTitle = m.fixOrderNo != null
    ? `Fix order #${m.fixOrderNo}`
    : m.isReturn ? 'New return' : 'New shipment'
  useEffect(() => {
    const prev = document.title
    document.title = `${pageTitle} · Multiship`
    return () => { document.title = prev }
  }, [pageTitle])
  // BUG-040 — inline error for "Return of #" when the typed order
  // doesn't resolve. Field-local UX concern — operator sees on blur.
  const [originalOrderNoError, setOriginalOrderNoError] = useState<string | null>(null)
  const {
    acceptWizardPayload, accountNumber, activeParties, carrier, carrierValidating, checklistOpen, clientCode, clientHasNoOwnCarriers, customFieldValues, destCountry, dgBlock, dgWizardOpen, dtcBatch, dtcLine, dtcLineId, editorSeed, fixError, fixLoading, fixOrderNo, fixVoided, incoterms, isInternational, isReturn, items, landedCostOpen, landedCostRequest, leaveForm, loading, mode, navigate, noCarriersAtAll, originalOrderNo, overrideEditorOpen, queuedInfo, recipient, residentialConflict, resubmitWithSplit, returnEmailMissing, returnPickupRequested, returnReason, returnType, reviewBusy, reviewShipmentAi, reviewWarnings, rmaNumber, sender, serviceId, setChecklistOpen, setCustomFieldValues, setDgBlock, setDgWizardOpen, setLandedCostOpen, setOriginalOrderNo, setOverride, setOverrideEditorOpen, setPendingSplitPayload, setReturnPickupRequested, setReturnReason, setReturnType, setReviewWarnings, setRmaNumber, setSender, setSplitPrompt, setToast, setWizardOpen, shipmentValidationResult, splitPrompt, submit, submitAttempted, submitting, switchMode, toast, validateShipment, validatedAt, validationGate, validationStale, weight, wizardOpen, wizardPayload,
  } = m
  const wizardDialogRef = useRef<HTMLDivElement>(null)
  const requestClose = useModalDismiss(wizardOpen, wizardDialogRef, () => setWizardOpen(false))
  return (
    <div className="pb-6">
      {/* Validation toast — non-blocking; auto-dismisses. */}
      {toast ? (
        <div
          key={toast.id}
          role="alert"
          className="fixed right-4 top-4 z-[100] w-[min(92vw,360px)] animate-[msToastIn_0.25s_ease-out] overflow-hidden rounded-2xl border border-rose-200 bg-white shadow-[0_12px_40px_-12px_rgba(159,18,57,0.35)]"
        >
          <div className="flex items-start gap-3 p-3.5">
            <span className="mt-0.5 inline-flex h-8 w-8 shrink-0 items-center justify-center rounded-xl bg-rose-50 text-rose-600 ring-1 ring-rose-100">
              <FiAlertTriangle className="h-4 w-4" />
            </span>
            <div className="min-w-0 flex-1">
              <p className="text-[12.5px] font-bold text-[#1f150c]">{toast.title}</p>
              <p className="mt-0.5 text-[12px] leading-snug text-[#5a4526]">{toast.body}</p>
            </div>
            <button
              type="button"
              onClick={() => setToast(null)}
              aria-label="Dismiss"
              className="-mr-1 -mt-1 shrink-0 rounded-lg p-1 text-[#b6a684] transition hover:bg-[#faf7f0] hover:text-[#5a4526]"
            >
              <FiX className="h-3.5 w-3.5" />
            </button>
          </div>
          <div className="h-1 w-full origin-left animate-[msToastBar_6s_linear_forwards] bg-rose-400/70" />
        </div>
      ) : null}
      <div className="w-full space-y-4">
        {/* PR-G4 — USPS_DIRECT queued-response panel. Only rendered
            when submit() got back status='QUEUED' or 'QUEUED_MPS'; the
            operator sees an inline "your label is in the USPS queue"
            state instead of being navigated to /label/{n} (which would
            show an empty label until the queue processor drains).
            MpsProgressCard is mounted for QUEUED_MPS so per-piece
            aggregate progress ticks in place. */}
        {queuedInfo ? (
          <section
            role="status"
            aria-live="polite"
            data-testid="usps-direct-queued-panel"
            className="rounded-2xl border border-amber-200 bg-amber-50 p-4 shadow-sm"
          >
            <div className="flex items-start gap-3">
              <span className="mt-0.5 inline-flex h-8 w-8 shrink-0 items-center justify-center rounded-xl bg-amber-100 text-amber-700">
                <FiPackage className="h-4 w-4" aria-hidden="true" />
              </span>
              <div className="min-w-0 flex-1">
                <p className="text-[13px] font-bold text-amber-900">
                  Queued for USPS Direct
                </p>
                <p className="mt-0.5 text-[12px] text-amber-800">
                  {queuedInfo.isMps
                    ? `This ${queuedInfo.mpsPieceCount ?? 'multi'}-piece shipment is queued for the USPS Direct 55/hr platform cap. Progress updates in place below; the label opens once the queue processor drains every piece.`
                    : 'Your label is in the USPS Direct queue (~55 labels/hr platform cap). Estimated wait shows in the queue badge; the label opens once the queue processor drains this item.'}
                </p>
                <p className="mt-1 text-[11.5px] text-amber-700">
                  {queuedInfo.queueItemId != null
                    ? `Queue item #${queuedInfo.queueItemId}`
                    : 'Queue item registered.'}
                  {queuedInfo.orderNo != null ? ` · Order #${queuedInfo.orderNo}` : ''}
                </p>
              </div>
              <div className="flex shrink-0 items-center gap-2">
                {queuedInfo.orderNo != null ? (
                  <button
                    type="button"
                    onClick={() => navigate(`/label/${queuedInfo.orderNo}`)}
                    data-testid="usps-direct-queued-open-order"
                    className="inline-flex items-center rounded-xl border border-amber-300 bg-white px-3 py-1.5 text-[12px] font-semibold text-amber-900 shadow-sm transition hover:border-amber-400 hover:bg-amber-100"
                  >
                    Open order
                  </button>
                ) : null}
                <button
                  type="button"
                  onClick={() => navigate('/data-history')}
                  data-testid="usps-direct-queued-open-history"
                  className="inline-flex items-center rounded-xl border border-amber-300 bg-white px-3 py-1.5 text-[12px] font-semibold text-amber-900 shadow-sm transition hover:border-amber-400 hover:bg-amber-100"
                >
                  Open Data History
                </button>
              </div>
            </div>
            {queuedInfo.isMps ? (
              <div className="mt-3" data-testid="usps-direct-queued-mps-slot">
                <MpsProgressCard orderNo={queuedInfo.orderNo} />
              </div>
            ) : null}
          </section>
        ) : null}
        {loading ? (
          <section className="rounded-2xl border border-slate-200 bg-white p-10 text-center text-sm text-[#6b5c42] shadow-sm">
            Loading carriers, services and packaging…
          </section>
        ) : (
          <>
            {/* PR #530 — No-carrier banners. Tenant-wide (red, blocks
                Generate) vs client-scoped (amber, soft warning; can
                still ship via a platform carrier). */}
            {noCarriersAtAll ? (
              <section role="alert" className="rounded-2xl border border-rose-300 bg-rose-50 p-4 shadow-sm">
                <div className="flex items-start gap-3">
                  <span className="mt-0.5 inline-flex h-8 w-8 shrink-0 items-center justify-center rounded-xl bg-rose-100 text-rose-700">
                    <FiAlertTriangle className="h-4 w-4" />
                  </span>
                  <div className="min-w-0 flex-1">
                    <p className="text-[13px] font-bold text-rose-900">No carriers connected in this workspace</p>
                    <p className="mt-0.5 text-[12px] text-rose-800">
                      A carrier needs an active account <em>and</em> synced live services before you can generate a label. Fill in the shipment details below, then add + verify a carrier in Settings.
                    </p>
                  </div>
                  <button
                    type="button"
                    onClick={() => navigate('/settings/carriers')}
                    className="shrink-0 rounded-xl border border-rose-300 bg-white px-3 py-1.5 text-[12px] font-semibold text-rose-900 shadow-sm transition hover:border-rose-400 hover:bg-rose-100"
                  >
                    Add carrier account
                  </button>
                </div>
              </section>
            ) : clientHasNoOwnCarriers ? (
              <section role="status" className="rounded-2xl border border-amber-300 bg-amber-50 p-4 shadow-sm">
                <div className="flex items-start gap-3">
                  <span className="mt-0.5 inline-flex h-8 w-8 shrink-0 items-center justify-center rounded-xl bg-amber-100 text-amber-700">
                    <FiAlertTriangle className="h-4 w-4" />
                  </span>
                  <div className="min-w-0 flex-1">
                    <p className="text-[13px] font-bold text-amber-900">
                      Client {clientCode} has no carriers assigned
                    </p>
                    <p className="mt-0.5 text-[12px] text-amber-800">
                      Shipping via a platform (verified) carrier. Pick a different client or assign a carrier account to {clientCode} in Settings.
                    </p>
                  </div>
                  <button
                    type="button"
                    onClick={() => navigate('/settings/carriers')}
                    className="shrink-0 rounded-xl border border-amber-300 bg-white px-3 py-1.5 text-[12px] font-semibold text-amber-900 shadow-sm transition hover:border-amber-400 hover:bg-amber-100"
                  >
                    Assign carrier account
                  </button>
                </div>
              </section>
            ) : null}

            {/* ── Shipment / Return toggle ── */}
            <div className="flex flex-wrap items-center justify-between gap-3">
              <div className="flex items-center gap-3">
                <div className="inline-flex items-center gap-1 rounded-2xl border border-[#e3d9c4] bg-white p-1 shadow-sm">
                  {(['SHIPMENT', 'RETURN'] as const).map((m) => (
                    <button
                      key={m}
                      type="button"
                      onClick={() => switchMode(m)}
                      className={`inline-flex items-center justify-center gap-1.5 rounded-xl px-4 py-2 text-[12.5px] font-semibold transition ${
                        mode === m ? 'bg-[#1f150c] text-[#f4eede] shadow-sm' : 'text-[#5a4526] hover:bg-[#faf7f0]'
                      }`}
                    >
                      {m === 'SHIPMENT' ? <FiTruck className="h-3.5 w-3.5" /> : <FiRotateCcw className="h-3.5 w-3.5" />}
                      {m === 'SHIPMENT' ? 'Shipment' : 'Return'}
                    </button>
                  ))}
                </div>
                {isReturn ? (
                  <div className="flex flex-wrap items-center gap-3">
                    <span className="text-[12px] text-[#6b5c42]">
                      Reverse label — the customer ships back to your address. Billed to your account.
                    </span>
                    {/* Return delivery type — PRINT | EMAIL. Threaded as
                        ManualShipmentRequest.returnType and picked up by
                        each carrier connector (UPS ReturnService.Code 8/9,
                        FedEx returnedShipmentDetail.returnType). */}
                    <div className="inline-flex items-center gap-1 rounded-xl border border-[#e3d9c4] bg-white p-1 shadow-sm">
                      {(['PRINT', 'EMAIL'] as const).map((rt) => (
                        <button
                          key={rt}
                          type="button"
                          onClick={() => setReturnType(rt)}
                          title={rt === 'PRINT'
                            ? 'Carrier returns the label PDF to us — we print or forward it.'
                            : 'Carrier emails the label directly to the customer.'}
                          className={`inline-flex items-center gap-1 rounded-lg px-2.5 py-1 text-[11.5px] font-semibold transition ${
                            returnType === rt
                              ? 'bg-[#1f150c] text-[#f4eede]'
                              : 'text-[#5a4526] hover:bg-[#faf7f0]'
                          }`}
                        >
                          {rt === 'PRINT' ? 'Print' : 'Email to customer'}
                        </button>
                      ))}
                    </div>
                    {/* Returns F8 — DHL-only pickup toggle. DHL Express
                        historically forced pickup.isRequested=true on every
                        return; some returns are drop-off at a ServicePoint. */}
                    {canon(carrier) === 'DHL' ? (
                      <label className="inline-flex cursor-pointer items-center gap-1.5 rounded-xl border border-[#e3d9c4] bg-white px-2.5 py-1.5 text-[11.5px] font-semibold text-[#5a4526] shadow-sm">
                        <input
                          type="checkbox"
                          checked={returnPickupRequested}
                          onChange={(e) => setReturnPickupRequested(e.target.checked)}
                          className="h-3.5 w-3.5"
                        />
                        Request DHL pickup
                      </label>
                    ) : null}
                    {/* Returns F10 — operator-issued RMA. Free-form,
                        capped at the DB column width. Blank is fine. */}
                    <label className="inline-flex cursor-text items-center gap-1.5 rounded-xl border border-[#e3d9c4] bg-white px-2.5 py-1 text-[11.5px] font-semibold text-[#5a4526] shadow-sm">
                      <span className="whitespace-nowrap">RMA #</span>
                      <input
                        type="text"
                        value={rmaNumber}
                        onChange={(e) => setRmaNumber(e.target.value.slice(0, 60))}
                        placeholder="optional"
                        maxLength={60}
                        className="w-28 border-0 bg-transparent p-0 text-[11.5px] font-semibold text-[#1f150c] placeholder:font-normal placeholder:text-[#a08f6c] focus:outline-none focus:ring-0"
                      />
                    </label>
                    {/* Returns F12 — original order no this is a return of.
                        Server validates existence; a typo surfaces inline
                        on blur (BUG-040). */}
                    <label className={`inline-flex cursor-text items-center gap-1.5 rounded-xl border bg-white px-2.5 py-1 text-[11.5px] font-semibold shadow-sm ${
                      originalOrderNoError
                        ? 'border-rose-300 text-rose-800'
                        : 'border-[#e3d9c4] text-[#5a4526]'
                    }`}>
                      <span className="whitespace-nowrap">Return of #</span>
                      <input
                        type="number"
                        value={originalOrderNo}
                        inputMode="numeric"
                        // BUG-042 — <input type="number"> accepts "e",
                        // "+", "-", "." which make no sense for an
                        // order number. Block at keydown.
                        onKeyDown={(e) => {
                          if (['e', 'E', '+', '-', '.'].includes(e.key)) e.preventDefault()
                        }}
                        // BUG-040 — clear the inline error on any edit;
                        // operator's new typing supersedes the stale 404.
                        onChange={(e) => {
                          setOriginalOrderNo(e.target.value)
                          if (originalOrderNoError) setOriginalOrderNoError(null)
                        }}
                        // R2-BUG-017 — fetch the original order on blur and
                        // backfill the customer block (sender in return mode)
                        // from its recipient. Operator typed the number; we
                        // save them the retype. BUG-040 — on 404, show inline
                        // red error instead of silently dropping the digit;
                        // operator must fix it before Validate / Generate.
                        onBlur={async (e) => {
                          const v = e.target.value.trim()
                          if (!v) { setOriginalOrderNoError(null); return }
                          if (!Number.isFinite(Number(v)) || Number(v) <= 0) {
                            setOriginalOrderNoError('Enter a valid order #.')
                            return
                          }
                          try {
                            const res = await orderService.getOrderWithLines(Number(v))
                            const o = res.data?.order
                            if (!o) {
                              setOriginalOrderNoError(`Order #${v} wasn't found. Fix the number or clear this field.`)
                              return
                            }
                            setOriginalOrderNoError(null)
                            setSender((cur) => ({
                              ...cur,
                              name:         o.shipName ?? cur.name,
                              company:      o.shipAttn ?? cur.company,
                              addressLine1: o.shipAddr1 ?? cur.addressLine1,
                              addressLine2: o.shipAddr2 ?? cur.addressLine2,
                              city:         o.shiptoCity ?? cur.city,
                              state:        o.shiptoState ?? cur.state,
                              postalCode:   o.shiptoZip ?? cur.postalCode,
                              countryCode:  o.shiptoCountryCd ?? cur.countryCode,
                              phone:        o.phone ?? cur.phone,
                            }))
                          } catch {
                            setOriginalOrderNoError(`Order #${v} wasn't found. Fix the number or clear this field.`)
                          }
                        }}
                        placeholder="optional"
                        min={1}
                        aria-invalid={originalOrderNoError ? true : undefined}
                        aria-describedby={originalOrderNoError ? 'original-order-no-error' : undefined}
                        className="w-24 border-0 bg-transparent p-0 text-[11.5px] font-semibold text-[#1f150c] placeholder:font-normal placeholder:text-[#a08f6c] focus:outline-none focus:ring-0"
                      />
                    </label>
                    {originalOrderNoError ? (
                      <span id="original-order-no-error" className="basis-full text-[11px] font-medium text-rose-700">
                        {originalOrderNoError}
                      </span>
                    ) : null}
                    {/* Returns F11 — canonical reason picklist. Codes must
                        match CarrierServiceImpl.RETURN_REASON_CODES. */}
                    {/* BUG-067 — reason is required on return submit now
                        (handleGenerateLabel guards). Flag the asterisk
                        + an inline red note when the submit guard would
                        fire, matching the pattern on other required
                        fields across the form. */}
                    <label className={`inline-flex cursor-pointer items-center gap-1.5 rounded-xl border bg-white px-2.5 py-1 text-[11.5px] font-semibold shadow-sm ${
                      submitAttempted && !returnReason
                        ? 'border-rose-300 text-rose-800'
                        : 'border-[#e3d9c4] text-[#5a4526]'
                    }`}>
                      <span className="whitespace-nowrap">Reason<span className="text-rose-600">*</span></span>
                      <select
                        value={returnReason}
                        onChange={(e) => setReturnReason(e.target.value)}
                        className="border-0 bg-transparent p-0 pr-5 text-[11.5px] font-semibold text-[#1f150c] focus:outline-none focus:ring-0"
                      >
                        <option value="">—</option>
                        <option value="WRONG_ITEM">Wrong item</option>
                        <option value="DEFECTIVE">Defective</option>
                        <option value="NO_LONGER_NEEDED">No longer needed</option>
                        <option value="SIZE">Size</option>
                        <option value="OTHER">Other</option>
                      </select>
                    </label>
                  </div>
                ) : null}
              </div>
              <button
                type="button"
                onClick={() => void leaveForm()}
                className="inline-flex items-center gap-1.5 rounded-xl border border-[#e3d9c4] bg-white px-3 py-2 text-[12.5px] font-semibold text-[#5a4526] transition hover:border-[#cdbf9f] hover:bg-[#faf7f0]"
              >
                <FiArrowLeft className="h-3.5 w-3.5" />
                Back to orders
              </button>
            </div>

            {/* Return-mode gate: carriers (UPS especially) reject a return
                label without a customer email. Show why the Generate button
                is disabled and where to fix it, right next to the mode
                toggle so the operator finds it immediately. */}
            {returnEmailMissing ? (
              <div className="rounded-xl border border-amber-300 bg-amber-50 px-4 py-3">
                <div className="flex items-start gap-2.5">
                  <FiAlertTriangle className="mt-0.5 h-4 w-4 shrink-0 text-amber-600" />
                  <div className="min-w-0">
                    <p className="text-[13px] font-semibold text-amber-900">
                      Customer email required for return labels
                    </p>
                    <p className="mt-0.5 text-[12px] text-amber-800">
                      Enter the customer's email on the <span className="font-semibold">Return from · customer</span> block below.
                      Without it, UPS rejects the label with 9120145 &ldquo;Missing label delivery information&rdquo;;
                      other carriers behave similarly.
                    </p>
                  </div>
                </div>
              </div>
            ) : null}

            {/* Fix-a-failed-order banner: the order's data is pre-filled below;
                correct what the carrier rejected and re-generate in place. */}
            {fixOrderNo && fixVoided ? (
              <div className="rounded-xl border border-amber-300 bg-amber-50 px-4 py-3">
                <div className="flex items-start gap-2.5">
                  <FiAlertTriangle className="mt-0.5 h-4 w-4 shrink-0 text-amber-600" />
                  <div className="min-w-0">
                    <p className="text-[13px] font-semibold text-amber-900">
                      Reissuing label for order #{fixOrderNo}{fixLoading ? ' — loading…' : ''}
                    </p>
                    <p className="mt-0.5 text-[12px] text-amber-800">
                      The previous label was voided. Adjust anything below and regenerate — the order keeps its number and the
                      voided label stays on its history.
                    </p>
                  </div>
                </div>
              </div>
            ) : dtcLineId ? (
              <div className="rounded-xl border border-rose-300 bg-rose-50 px-4 py-3">
                <div className="flex items-start gap-2.5">
                  <FiAlertTriangle className="mt-0.5 h-4 w-4 shrink-0 text-rose-600" />
                  <div className="min-w-0">
                    <p className="text-[13px] font-semibold text-rose-800">
                      Fixing D2C line — batch {dtcBatch}{dtcLine?.toteNumber ? `, tote ${dtcLine.toteNumber}` : ''}{dtcLine?.orderNo != null ? `, order ${Number(dtcLine.orderNo)}` : ''}{dtcLine ? '' : ' — loading…'}
                    </p>
                    <p className="mt-0.5 break-words text-[12px] text-rose-700" title={dtcLine?.generatedMessage ?? undefined}>
                      {dtcLine?.generatedMessage ? `Label run failed: ${summarizeCarrierError(dtcLine.generatedMessage)}. ` : ''}
                      Correct the details below and generate — the label is linked back to the line.
                    </p>
                  </div>
                </div>
              </div>
            ) : fixOrderNo ? (
              <div className="rounded-xl border border-rose-300 bg-rose-50 px-4 py-3">
                <div className="flex items-start gap-2.5">
                  <FiAlertTriangle className="mt-0.5 h-4 w-4 shrink-0 text-rose-600" />
                  <div className="min-w-0">
                    <p className="text-[13px] font-semibold text-rose-800">
                      Fixing failed order #{fixOrderNo}{fixLoading ? ' — loading…' : ''}
                    </p>
                    {fixError ? (
                      <p className="mt-0.5 break-words text-[12px] text-rose-700" title={fixError}>
                        Carrier rejected it: {summarizeCarrierError(fixError)}
                      </p>
                    ) : (
                      <p className="mt-0.5 text-[12px] text-rose-700">
                        Correct the details below and regenerate — it keeps the same order number.
                      </p>
                    )}
                  </div>
                </div>
              </div>
            ) : null}

            <SectionRail
              sections={[
                { id: 'sec-shipment', label: 'Shipment', done: !!clientCode },
                { id: 'sec-addresses', label: 'Addresses', done: !!(sender.name && sender.addressLine1 && (sender.postalCode || postalCodeOptionalFor(sender.countryCode)) && recipient.name && recipient.addressLine1 && (recipient.postalCode || postalCodeOptionalFor(recipient.countryCode))) },
                { id: 'sec-service', label: 'Service', done: !!carrier && (serviceId !== '' || !!accountNumber) },
                { id: 'sec-packages', label: 'Packages', done: Number(weight) > 0 },
                { id: 'sec-customs', label: 'Customs', done: !!incoterms && !!activeParties, show: isInternational },
                { id: 'sec-items', label: 'Items', done: items.some((it) => it.description.trim() && Number(it.unitValue) > 0), show: isInternational },
              ]}
            />
            {/* ── Top: client · reason of export · currency ──
                Compact single-row layout for both Shipment + Return
                modes: flex-wrap row so fields sit inline on lg+ and
                only wrap on narrower viewports. Note text moved to
                the title's hover tooltip so the section takes one
                horizontal band. */}
            <ShipmentSection m={m} />

            {/* ── Addresses + Carrier + Package (compact 3-col band) ── */}
            <div className="grid grid-cols-1 gap-4 lg:grid-cols-2 xl:grid-cols-3">
              <AddressesSection m={m} />
              <ServiceSection m={m} />

            {/* ── Package & weight ── */}
            <PackageSection m={m} />
            </div>

            {/* ── Customs · international only ── */}
            <CustomsSection m={m} />

            {/* ── Items · commercial invoice (international only) ── */}
            <ItemsSection m={m} />
          </>
        )}
      </div>

      {/* Sprint 43 — Custom fields (tenant-defined metadata). Panel
       *  self-hides when the tenant has no applicable fields. */}
      {!loading ? (
        <CustomFieldsSection
          tenantId={clientCode || null}
          values={customFieldValues}
          onChange={setCustomFieldValues}
          compact
        />
      ) : null}

      {/* sticky footer action bar — stays within the content column.
          `!mt-6` beats the parent space-y-4 so there's a clear gap between the
          form cards and the action bar. */}
      {!loading ? (
        <div className="sticky bottom-4 z-30 !mt-6 space-y-2">
          {shipmentValidationResult && checklistOpen ? (
            <ValidationChecklist
              // A new check is a fresh panel: rows with issues open again.
              key={validatedAt?.getTime() ?? 0}
              result={shipmentValidationResult}
              checkedAt={validatedAt}
              stale={validationStale}
              busy={carrierValidating}
              onRevalidate={() => void validateShipment()}
              onClose={() => setChecklistOpen(false)}
            />
          ) : null}
          {reviewWarnings ? (
            <div className="rounded-2xl border border-[#e3d9c4] bg-white p-3.5 shadow-[0_18px_50px_rgba(31,21,12,0.14)]">
              <div className="mb-2 flex items-center justify-between">
                <span className="inline-flex items-center gap-1.5 text-[11px] font-bold uppercase tracking-[0.12em] text-[#5a4526]">
                  <FiZap className="h-3.5 w-3.5" /> AI pre-ship review
                </span>
                <button type="button" onClick={() => setReviewWarnings(null)} className="text-[11px] font-semibold text-[#6b5c42] hover:text-[#412d15]">
                  Dismiss
                </button>
              </div>
              {reviewWarnings.length === 0 ? (
                <p className="flex items-center gap-1.5 text-[12.5px] text-emerald-700">
                  <FiCheckCircle className="h-4 w-4" /> No issues found — good to ship.
                </p>
              ) : (
                <ul className="space-y-1.5">
                  {reviewWarnings.map((w, idx) => {
                    const tone =
                      w.severity === 'high'
                        ? 'bg-rose-50 text-rose-700 ring-rose-200'
                        : w.severity === 'medium'
                          ? 'bg-amber-50 text-amber-700 ring-amber-200'
                          : 'bg-slate-50 text-slate-600 ring-slate-200'
                    return (
                      <li key={idx} className="flex items-start gap-2 text-[12.5px] text-[#3f3527]">
                        <span className={`mt-0.5 inline-flex shrink-0 rounded-full px-1.5 py-0.5 text-[9px] font-bold uppercase tracking-wide ring-1 ${tone}`}>
                          {w.severity}
                        </span>
                        <span>
                          {w.field ? <span className="font-semibold">{w.field}: </span> : null}
                          {w.message}
                        </span>
                      </li>
                    )
                  })}
                </ul>
              )}
            </div>
          ) : null}
          <div className="flex items-center justify-between gap-3 rounded-2xl border border-[#e3d9c4] bg-white px-3 py-2.5 shadow-[0_18px_50px_rgba(31,21,12,0.16)] sm:px-5 sm:py-3">
            <span className="hidden text-[11.5px] text-[#6b5c42] sm:block">
              {validationGate ? (
                <span className="font-semibold text-[#5a4526]">
                  {validationGate} Generate label unlocks after a successful check.
                  {shipmentValidationResult && !checklistOpen ? (
                    <button type="button" onClick={() => setChecklistOpen(true)}
                      className="ml-1.5 font-semibold underline underline-offset-2 hover:text-[#1f150c]">
                      Show the check
                    </button>
                  ) : null}
                </span>
              ) : (
                <>
                  The label is purchased immediately on the selected account.
                  {isInternational ? ' Commercial invoice included for this cross-border lane.' : ''}
                </>
              )}
            </span>
            {/* Phones: a 2 × 2 grid, so the bar stays two rows tall. */}
            <div className="grid w-full grid-cols-2 gap-2 sm:flex sm:w-auto sm:flex-wrap sm:items-center sm:justify-end [&>button]:justify-center [&>button]:whitespace-nowrap max-sm:[&>button]:px-2">
              <button
                type="button"
                onClick={() => void reviewShipmentAi()}
                disabled={reviewBusy}
                className="inline-flex items-center gap-1.5 rounded-xl border border-[#cdbf9f] bg-white px-3.5 py-2 text-[12.5px] font-semibold text-[#5a4526] transition hover:border-[#412d15] hover:bg-[#faf7f0] disabled:opacity-50"
              >
                {reviewBusy ? (
                  <span className="inline-block h-3.5 w-3.5 animate-spin rounded-full border-2 border-[#cdbf9f] border-t-[#412d15]" />
                ) : (
                  <FiZap className="h-3.5 w-3.5" />
                )}
                AI review
              </button>
              <button
                type="button"
                onClick={() => void leaveForm()}
                className="rounded-xl border border-[#e3d9c4] bg-white px-3.5 py-2 text-[12.5px] font-semibold text-[#5a4526] transition hover:border-[#cdbf9f] hover:bg-[#faf7f0]"
              >
                Cancel
              </button>
              {/* Validate sits right beside Generate label: check, then buy. */}
              <button
                type="button"
                onClick={() => void validateShipment()}
                disabled={carrierValidating || !carrier || noCarriersAtAll}
                title={carrier
                  ? 'Check the whole shipment — addresses, package, service and price, customs and the carrier — without buying a label.'
                  : 'Pick a carrier first'}
                data-testid="validate-shipment-btn"
                className="inline-flex items-center gap-1.5 rounded-xl border border-[#1f150c] bg-white px-3.5 py-2 text-[12.5px] font-semibold text-[#1f150c] transition hover:bg-[#faf7f0] disabled:cursor-not-allowed disabled:opacity-40"
              >
                {carrierValidating ? (
                  <span className="inline-block h-3.5 w-3.5 animate-spin rounded-full border-2 border-[#cdbf9f] border-t-[#1f150c]" />
                ) : (
                  <FiCheckCircle className="h-3.5 w-3.5" />
                )}
                Validate shipment
              </button>
              <button
                type="button"
                onClick={() => void submit()}
                disabled={submitting || noCarriersAtAll || residentialConflict || returnEmailMissing || !!validationGate || !!originalOrderNoError}
                title={validationGate ? `${validationGate} Generate label unlocks after a successful check.` : (residentialConflict
                  ? 'FedEx Home Delivery requires the recipient to be marked as residential. Tick the checkbox on the recipient block, or pick a different service.'
                  : returnEmailMissing
                    ? 'Return labels need the customer email on the sender block — carriers (UPS especially) reject return labels without it (UPS error 9120145 "Missing label delivery information").'
                    : undefined)}
                className="inline-flex items-center gap-1.5 rounded-xl bg-[#1f150c] px-4 py-2 text-[12.5px] font-semibold text-[#f4eede] shadow-sm transition hover:bg-[#412d15] disabled:cursor-not-allowed disabled:bg-[#dcd4c4] disabled:text-white disabled:shadow-none"
              >
                {submitting ? (
                  <>
                    <span className="inline-block h-3.5 w-3.5 animate-spin rounded-full border-2 border-[#f4eede]/40 border-t-[#f4eede]" />
                    Generating…
                  </>
                ) : (
                  <>
                    <FiZap className="h-3.5 w-3.5" />
                    {/* BUG-042 — in return+Email mode the carrier
                        emails the label to the customer; the
                        operator isn't pulling a printable file.
                        Rephrase the CTA to match the actual action. */}
                    {fixOrderNo
                      ? (fixVoided ? 'Regenerate label' : 'Fix & regenerate')
                      : isReturn
                        ? (returnType === 'EMAIL' ? 'Email return label' : 'Generate return label')
                        : 'Generate label'}
                    <FiArrowRight className="h-3.5 w-3.5" />
                  </>
                )}
              </button>
            </div>
          </div>
        </div>
      ) : null}

      {dgWizardOpen ? (
        <DangerousGoodsWizard
          value={dgBlock}
          onChange={(next) => setDgBlock(next)}
          onComplete={(next) => {
            setDgBlock(next)
            setDgWizardOpen(false)
            notify.success(
              `Dangerous goods block attached · ${next.commodities.length} commodity(ies).`,
            )
          }}
          onCancel={() => setDgWizardOpen(false)}
        />
      ) : null}

      {landedCostOpen ? (
        <LandedCostModal
          request={landedCostRequest}
          onClose={() => setLandedCostOpen(false)}
        />
      ) : null}

      {overrideEditorOpen ? (
        <ShipmentPartiesOverrideModal
          importer={editorSeed.importer}
          broker={editorSeed.broker}
          destCountry={destCountry}
          // BUG-021 — pass the ship-to so Type=Receiver can autofill.
          recipient={{
            name: recipient.name ?? '',
            phone: recipient.phone ?? '',
            email: recipient.email ?? '',
            addressLine1: recipient.addressLine1 ?? '',
            addressLine2: recipient.addressLine2 ?? '',
            city: recipient.city ?? '',
            state: recipient.state ?? '',
            postalCode: recipient.postalCode ?? '',
            countryCode: recipient.countryCode ?? '',
          }}
          onClose={() => setOverrideEditorOpen(false)}
          onSave={(importer, broker) => {
            setOverride({ importer, broker })
            setOverrideEditorOpen(false)
          }}
        />
      ) : null}

      {/* Customs wizard — full-screen modal overlay. Owns its own scroll +
          keyboard trap; the parent form stays mounted so state is preserved
          if the user cancels. */}
      {wizardOpen ? (
        <div ref={wizardDialogRef}
          role="dialog"
          aria-modal="true"
          aria-label="Customs declaration wizard"
          className="fixed inset-0 z-50 flex items-center justify-center bg-slate-950/45 p-4"
        >
          <div className="flex h-[min(720px,90vh)] w-full max-w-[720px] flex-col overflow-hidden rounded-2xl border border-slate-200 bg-white shadow-[0_30px_80px_rgba(15,23,42,0.35)]">
            <CustomsWizard
              carrierCode={carrier}
              originCountry={sender.countryCode}
              destinationCountry={recipient.countryCode}
              value={wizardPayload}
              onChange={() => {
                // No-op: the wizard renders from wizardPayload but writes
                // back only on Complete. We don't mirror in-progress edits
                // to the inline form because the user might cancel.
              }}
              onComplete={acceptWizardPayload}
              onCancel={() => void requestClose()}
            />
          </div>
        </div>
      ) : null}

      {splitPrompt ? (
        <SplitShipmentModal
          payload={splitPrompt}
          onPick={resubmitWithSplit}
          onCancel={() => {
            setSplitPrompt(null)
            setPendingSplitPayload(null)
          }}
        />
      ) : null}
    </div>
  )
}

/**
 * Sprint 31 — inline banner for carrier-side address validation results.
 * Colour by matchLevel; when the carrier returned a suggested address,
 * show the diff + an Apply button that overwrites the recipient block.
 */

/* ---------------- Sprint 48 audit fix — virtualized CI items list ---------------- */

/** Module-scoped mirror of the local ItemRow type so the virtualizer's
 *  memoized row component can be declared outside the main function
 *  (memo needs a stable reference across parent re-renders). */
