import { FiUsers, FiX } from 'react-icons/fi'
import { canon } from '../../../utils/carrierCanon'
import { SHIPPING_PURPOSES, FTR_EXEMPTIONS, EEI_THRESHOLD_USD, pickupTypesForCarrier, labelFormatsForCarrier } from '../../../utils/customsOptions'
import { Field, SectionCard, inputCls } from '../_shared'


import type { NewShipmentModel } from '../useNewShipmentModel'

export function ShipmentSection({ m }: { m: NewShipmentModel }) {
  const {
    CURRENCIES, activateQueued, aesCitation, anyShippableCarrier, applyClient, carrier, carrierLabelMap, carrierOptions, clientCarriers, clientCode, clientWarehouses, clients, currency, declaredValue, errAt, exportDeclarationReference, ftrExemption, isInternational, isReturn, labelImageFormat, labelImageType, labelStockType, ndsActiveIdx, ndsBanner, ndsLoading, ndsQueue, ndsScan, onScanSubmit, pickupType, platformCarriers, reasonForExport, recipientEffectiveCountry, reference, removeQueued, sender, setAesCitation, setCarrier, setCurrency, setExportDeclarationReference, setFtrExemption, setLabelImageFormat, setLabelImageType, setLabelStockType, setNdsBanner, setNdsScan, setPickupType, setPickupTypeTouched, setReasonForExport, setReference, setWarehouseCode, submitAttempted, warehouseCode,
  } = m

  return (

            <SectionCard
              icon={<FiUsers className="h-3.5 w-3.5" />}
              id="sec-shipment"
                title="Shipment"
            >
              <div
                className="flex flex-wrap items-end gap-3 [&>*]:min-w-[160px] [&>*]:flex-1"
                title="Choosing a client fills its ship-from and auto-selects its default carrier account. Reason & currency remember your last choice."
              >
                {/* NDS scan — .X<containerId> or .Y<batchId>. Enter fires the
                    lookup; matches USB scanners that auto-append CR/LF. Fills
                    client + recipient + packages + notify + CI items. */}
                <Field label="Scan (NDS)"
                       hint={ndsQueue.length > 1
                         ? undefined
                         : clientCode
                           ? 'One or many — separate multiple containers by comma or space.'
                           : 'Pick a client below to enable scanning.'}>
                  <input
                    className={inputCls}
                    value={ndsScan}
                    onChange={(e) => setNdsScan(e.target.value)}
                    onKeyDown={(e) => {
                      if (e.key === 'Enter') {
                        e.preventDefault()
                        onScanSubmit()
                      }
                    }}
                    placeholder={clientCode
                      ? '.X<containerId>, .X<c2>… or .Y<batchId>'
                      : 'Pick a client first'}
                    disabled={ndsLoading || !clientCode}
                    aria-busy={ndsLoading}
                  />
                  {ndsQueue.length > 1 ? (
                    <div className="mt-1.5" aria-label="Queued shipments">
                      <div className="flex items-center gap-1.5">
                        <span className="text-[11px] font-bold uppercase tracking-[0.1em] text-slate-500 whitespace-nowrap">
                          Queued · {ndsQueue.length}
                        </span>
                        {/* (b) horizontal scroll handles arbitrarily long queues
                            without a portal / drawer — scanner ops can whip
                            through 50+ chips with the trackpad. */}
                        <div className="flex-1 min-w-0 overflow-x-auto">
                          <div className="flex items-center gap-1.5 pb-0.5">
                            {ndsQueue.map((p, i) => {
                              const label = p.orders?.[0]?.orderNo != null
                                ? `Order ${p.orders[0].orderNo}` : p.scannedValue
                              const sub = p.recipient?.name ?? p.recipient?.attn ?? p.clientCode
                              const active = i === ndsActiveIdx
                              // (c) per-chip tint by status — WARNING amber, BLOCKED rose,
                              // OK slate. Active chip stays dark to signal "loaded".
                              const inactiveTint =
                                p.status === 'BLOCKED' ? 'bg-white text-rose-700 ring-rose-300 hover:bg-rose-50'
                                : p.status === 'WARNING' ? 'bg-white text-amber-700 ring-amber-300 hover:bg-amber-50'
                                : 'bg-white text-slate-700 ring-slate-300 hover:bg-slate-50'
                              return (
                                <span key={`${p.scannedValue}:${i}`}
                                      className={`inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-[11.5px] ring-1 whitespace-nowrap ${
                                        active
                                          ? 'bg-[#1f150c] text-[#f4eede] ring-[#1f150c]'
                                          : `${inactiveTint} cursor-pointer`
                                      }`}
                                      onClick={active ? undefined : () => activateQueued(i)}
                                      title={active ? 'Currently loaded'
                                        : `Click to swap in ${label}${sub ? ` (${sub})` : ''}${p.status !== 'OK' ? ` — ${p.status}` : ''}`}>
                                  <span className="font-semibold">{label}</span>
                                  {sub ? <span className={active ? 'opacity-70' : 'opacity-80'}>· {sub}</span> : null}
                                  <button
                                    type="button"
                                    className={`ml-0.5 rounded-full px-1 leading-none ${
                                      active ? 'hover:bg-[#412d15]' : 'hover:bg-black/10'
                                    }`}
                                    onClick={(e) => { e.stopPropagation(); removeQueued(i) }}
                                    aria-label={`Remove ${label} from queue`}
                                    title="Remove from queue"
                                  >×</button>
                                </span>
                              )
                            })}
                          </div>
                        </div>
                      </div>
                    </div>
                  ) : null}
                </Field>
                {/* Client stays REQUIRED (validation under the field) — dev's
                    layout change and this branch's required-client rule merge. */}
                <Field label="Client" required error={errAt('clientCode')}>
                  <select className={inputCls} value={clientCode} onChange={(e) => applyClient(e.target.value)}>
                    <option value="">Select a client…</option>
                    {clients.map((c) => (
                      <option key={c.clientCode} value={c.clientCode}>
                        {c.clientCode} — {c.name}
                      </option>
                    ))}
                  </select>
                </Field>
                <Field label={`Ship from${clientWarehouses.length ? ` · ${clientWarehouses.length}` : ''}`}>
                  <select
                    className={inputCls}
                    value={warehouseCode}
                    onChange={(e) => setWarehouseCode(e.target.value)}
                    disabled={!clientCode || clientWarehouses.length === 0}
                  >
                    <option value="">
                      {clientCode && clientWarehouses.length === 0 ? 'None attached' : 'Manual sender'}
                    </option>
                    {clientWarehouses.map((cw) => (
                      <option key={cw.id} value={cw.warehouse?.code ?? ''}>
                        {cw.warehouse?.code} — {cw.warehouse?.name}
                        {cw.isDefault ? ' ★' : ''}
                      </option>
                    ))}
                  </select>
                </Field>
                <Field label="Carrier" required error={errAt('carrier')}>
                  <select className={inputCls} value={carrier} onChange={(e) => setCarrier(e.target.value)}>
                    {/* No verified PLATFORM account + no client picked yet →
                        the pools are empty although client accounts exist.
                        Say what unlocks the list instead of rendering blank. */}
                    {carrierOptions.length === 0 ? (
                      <option value="">
                        {anyShippableCarrier ? 'Select a client to see its carriers…' : 'No carriers available'}
                      </option>
                    ) : null}
                    {clientCarriers.length > 0 ? (
                      <optgroup label={`${clientCode || 'Client'} accounts`}>
                        {clientCarriers.map((c) => (
                          <option key={c} value={c}>{carrierLabelMap[c] || c}</option>
                        ))}
                      </optgroup>
                    ) : null}
                    {platformCarriers.length > 0 ? (
                      <optgroup label="Platform (verified)">
                        {platformCarriers.map((c) => (
                          <option key={c} value={c}>{carrierLabelMap[c] || c}</option>
                        ))}
                      </optgroup>
                    ) : null}
                  </select>
                </Field>
                <Field label="Reference / PO">
                  <input className={inputCls} value={reference} onChange={(e) => setReference(e.target.value)} placeholder="PO-12345" maxLength={80} />
                </Field>
                {isInternational ? (
                  <Field label="Reason of export" required
                         error={submitAttempted && !reasonForExport ? 'Required — pick a reason.' : errAt('reasonForExport')}>
                    <select className={inputCls} value={reasonForExport} onChange={(e) => setReasonForExport(e.target.value)}>
                      <option value="">-- Select --</option>
                      {SHIPPING_PURPOSES.map((p) => (
                        <option key={p.value} value={p.value}>{p.label}</option>
                      ))}
                    </select>
                  </Field>
                ) : null}
                {/* US Export EEI — FTR §30.37 exemption OR AES ITN. Shown
                    for ALL US-origin international exports including Canada
                    (BUG-005: FedEx explicitly requires FTR Exemption /
                    AES ITN on CA when Export declaration ref is empty;
                    the §30.36 bilateral is NOT auto-applied by every
                    carrier). FTR_EXEMPTIONS already carries NO_EEI_30_36
                    "export to Canada" so the operator picks it. Empty on
                    shipments where the rule doesn't
                    apply keeps the form terse. Mutually-exclusive picker:
                    filling one disables the other so the wire payload can
                    never carry both.
                    2026-09-09 — gate now uses recipientEffectiveCountry
                    (territory state → territory ISO) so US → PR/VI/GU/
                    AS/MP/UM lanes DO render the FTR/AES inputs. Operator
                    compliance stance is PR/VI ALSO require EEI filing
                    above $2,500 — fields need to be visible any time
                    an EEI could apply so an operator can enter an ITN
                    preemptively. Fields aren't hard-required under
                    threshold; the field-level error above still gates on
                    the $2,500 mark. */}
                {isInternational
                    && (sender.countryCode ?? '').trim().toUpperCase() === 'US'
                    && !['US', ''].includes(recipientEffectiveCountry) ? (
                  <>
                    <Field label="FTR exemption"
                           error={submitAttempted
                                  && (currency ?? 'USD').toUpperCase() === 'USD'
                                  && Number(declaredValue || 0) >= EEI_THRESHOLD_USD
                                  && !ftrExemption && !aesCitation
                             ? `Required at ≥ $${EEI_THRESHOLD_USD.toLocaleString()} — pick one or paste an AES ITN.`
                             : undefined}>
                      <select className={inputCls}
                              value={ftrExemption}
                              disabled={!!aesCitation}
                              onChange={(e) => {
                                setFtrExemption(e.target.value)
                                if (e.target.value) setAesCitation('')
                              }}>
                        <option value="">-- None (using AES) --</option>
                        {FTR_EXEMPTIONS.map((ex) => (
                          <option key={ex.value} value={ex.value}>{ex.label}</option>
                        ))}
                      </select>
                    </Field>
                    <Field label="AES ITN (Census)"
                           error={undefined}>
                      <input className={inputCls}
                             type="text"
                             placeholder="e.g. X20260101123456"
                             value={aesCitation}
                             disabled={!!ftrExemption}
                             onChange={(e) => {
                               setAesCitation(e.target.value)
                               if (e.target.value) setFtrExemption('')
                             }} />
                    </Field>
                  </>
                ) : null}
                {/* Generic export declaration reference — shows on ANY
                    international shipment. Complementary to the US
                    FTR/AES fields above; used for non-US origins whose
                    export regime issues a reference (CA B13A / GB CDS /
                    EU MRN / AU EDN / JP declaration / IN SB). Backend
                    WARNs (not blocks) on high-value intl without any of
                    the three refs populated. */}
                {isInternational ? (
                  <Field label="Export declaration ref"
                         error={undefined}>
                    <input className={inputCls}
                           type="text"
                           placeholder="CA B13A / GB CDS / EU MRN / AU EDN / JP / IN SB — non-US origins"
                           value={exportDeclarationReference}
                           onChange={(e) => setExportDeclarationReference(e.target.value)} />
                  </Field>
                ) : null}
                <Field label="Currency" error={errAt('currency')}>
                  <select className={inputCls} value={currency} onChange={(e) => setCurrency(e.target.value)}>
                    {CURRENCIES.map((c) => (
                      <option key={c} value={c}>{c}</option>
                    ))}
                  </select>
                </Field>
                {/* Per-shipment label spec — prefilled from the picked
                    carrier account (matchedAccount above). Required
                    when the account has no saved default so operators
                    can't silently ship on the backend hardcoded
                    fallback. FedEx renders Label Type + Label Size;
                    UPS/DHL/USPS render Label Type only (they don't
                    have a stock-type concept). */}
                {canon(carrier) === 'FEDEX' ? (
                  <>
                    <Field label="Label Type" required
                           error={submitAttempted && !labelImageType ? 'Required — pick a label type.' : undefined}>
                      <select className={inputCls}
                              value={labelImageType}
                              onChange={(e) => setLabelImageType(e.target.value)}>
                        <option value="">-- Select --</option>
                        {/* BUG-057 R6 — the FEDEX branch previously
                            hardcoded five options with its own wording.
                            The order form's vocabulary must come from
                            Settings > Carrier Dropdowns (same DB registry
                            UPS/DHL/USPS already read), so renaming a
                            FEDEX row there or adding/removing a code
                            flows through here without a code change.
                            Bootstrap fallback (LABEL_FORMAT_BY_CARRIER)
                            kicks in only when the DB is empty — a
                            first-boot cushion, not a parallel source. */}
                        {labelFormatsForCarrier('FEDEX').map((opt) => (
                          <option key={opt.value} value={opt.value}>{opt.label}</option>
                        ))}
                      </select>
                    </Field>
                    <Field label="Label Size" required
                           error={submitAttempted && !labelStockType ? 'Required — pick a label size.' : undefined}>
                      <select className={inputCls}
                              value={labelStockType}
                              onChange={(e) => setLabelStockType(e.target.value)}>
                        <option value="">-- Select --</option>
                        <option value="PAPER_4X6">Paper 4x6</option>
                        <option value="PAPER_4X6.75">Paper 4x6.75</option>
                        <option value="PAPER_4X8">Paper 4x8</option>
                        <option value="PAPER_4X9">Paper 4x9</option>
                        <option value="PAPER_7X4.75">Paper 7x4.75</option>
                        <option value="PAPER_LETTER">Paper letter</option>
                        <option value="STOCK_4X6">Thermal stock 4x6</option>
                        <option value="STOCK_4X6.75">Thermal stock 4x6.75</option>
                        <option value="STOCK_4X8">Thermal stock 4x8</option>
                        <option value="STOCK_4X9_LEADING_DOC_TAB">Thermal stock 4x9 (doc tab)</option>
                      </select>
                    </Field>
                    {/* FDX-H1 — Pickup Type. Hidden in Return mode
                        because the connector always emits
                        CONTACT_FEDEX_TO_SCHEDULE for isReturn=true;
                        showing the field would imply the operator's
                        pick matters when it doesn't. */}
                    {!isReturn ? (
                      <Field label="Pickup Type">
                        <select className={inputCls}
                                value={pickupType}
                                onChange={(e) => { setPickupType(e.target.value); setPickupTypeTouched(true) }}>
                          {/* V120 — options come from carrier_pickup_type
                              (via pickupTypesForCarrier). Bootstrap mirrors
                              the pre-V120 hardcoded set when DB is empty. */}
                          {pickupTypesForCarrier('FEDEX').map((opt) => (
                            <option key={opt.value} value={opt.value}>{opt.label}</option>
                          ))}
                        </select>
                      </Field>
                    ) : null}
                  </>
                ) : null}
                {['UPS', 'DHL', 'USPS'].includes(canon(carrier)) ? (
                  <Field label="Label Type" required
                         error={submitAttempted && !labelImageFormat ? 'Required — pick a label type.' : undefined}>
                    <select className={inputCls}
                            value={labelImageFormat}
                            onChange={(e) => setLabelImageFormat(e.target.value)}>
                      <option value="">-- Select --</option>
                      {/* V120 — options come from carrier_label_format (via
                          labelFormatsForCarrier). Bootstrap in customsOptions.ts
                          mirrors the pre-V120 per-carrier hardcoded sets. */}
                      {labelFormatsForCarrier(canon(carrier)).map((opt) => (
                        <option key={opt.value} value={opt.value}>{opt.label}</option>
                      ))}
                    </select>
                  </Field>
                ) : null}
              </div>
              {/* NDS lookup result banner. Color-coded by status; lists the
                  backend `messages` verbatim so operators see WARNING/BLOCKED
                  reasons (HLD ship method, unmapped shipvia, missing weight). */}
              {ndsBanner ? (
                <div
                  role="status"
                  className={`mt-3 rounded-md border px-3 py-2 text-xs ${
                    ndsBanner.status === 'OK'
                      ? 'border-emerald-300 bg-emerald-50 text-emerald-900'
                      : ndsBanner.status === 'WARNING'
                      ? 'border-amber-300 bg-amber-50 text-amber-900'
                      : 'border-rose-300 bg-rose-50 text-rose-900'
                  }`}
                >
                  <div className="flex items-start justify-between gap-2">
                    <div className="flex-1">
                      <div className="font-medium">
                        NDS lookup {ndsBanner.status === 'OK'
                          ? 'succeeded'
                          : ndsBanner.status === 'WARNING'
                          ? 'succeeded with warnings'
                          : 'blocked'}
                      </div>
                      {ndsBanner.text ? <div className="mt-0.5">{ndsBanner.text}</div> : null}
                      {ndsBanner.messages.length > 0 ? (
                        <ul className="mt-1 list-inside list-disc space-y-0.5">
                          {ndsBanner.messages.map((m, i) => (
                            <li key={i}>{m.text}</li>
                          ))}
                        </ul>
                      ) : null}
                    </div>
                    <button
                      type="button"
                      className="text-current opacity-60 hover:opacity-100"
                      onClick={() => setNdsBanner(null)}
                      aria-label="Dismiss NDS lookup result"
                    >
                      <FiX className="h-4 w-4" />
                    </button>
                  </div>
                </div>
              ) : null}
            </SectionCard>

  )
}
