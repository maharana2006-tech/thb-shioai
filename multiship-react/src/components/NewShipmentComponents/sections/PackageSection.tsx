import type { ExtraPackage } from '../../../shipment/useManualShipment'
import { FiZap, FiPackage, FiPlus, FiTrash2, FiAlertTriangle, FiCopy, FiAlertCircle } from 'react-icons/fi'
import { usTerritoryBannerHint } from '../../../utils/usTerritoryServices'
import { Field, SectionCard, inputCls } from '../_shared'


import type { NewShipmentModel } from '../useNewShipmentModel'

export function PackageSection({ m }: { m: NewShipmentModel }) {
  const {
    CUSTOM_PKG, addBoxBlockedMessage, blankExtraPackage, bulkAddCount, canAddMoreBoxes, carrier, carrierLabelMap, clearFixKey, compatiblePresetIdsForService, currency, currentBoxCount, customBoxes, declaredValue, dimUnit, duplicateBox, errAt, expandedBoxes, extraPackages, height, insuredValue, invoiceTotal, isCustomPkg, isInternational, isRecipientUsTerritory, length, maxPiecesContext, packageChoice, packagesForCarrier, pkgBusy, pkgServiceWarning, recipientTerritory, removeBox, serviceId, setBulkAddCount, setDeclaredValue, setDimUnit, setDimUnitTouched, setExtraPackages, setHeight, setInsuredValue, setLength, setPackageChoice, setSignatureOption, setWeight, setWeightUnit, setWeightUnitTouched, setWidth, signatureOption, singlePieceOnlyWarning, suggestPackagingAi, toggleBox, upsReturnMultiPkgNote, weight, weightOverPresetMaxWarning, weightUnit, width,
  } = m

  return (

            <SectionCard
              icon={<FiPackage className="h-3.5 w-3.5" />}
              id="sec-packages"
                title="Package & weight"
              wrapHeader
              badge={
                <div className="flex flex-1 flex-wrap items-center justify-end gap-2">
                  {/* BUG-032 — Suggest draws its recommendation from the
                      customs items list, which only exists on international
                      shipments. On domestic the button was visible but
                      guaranteed to 422 ("Add at least one item"); hide it. */}
                  {isInternational ? (
                  <button
                    type="button"
                    onClick={() => void suggestPackagingAi()}
                    disabled={pkgBusy}
                    title="Suggest packaging & weight from your items"
                    className="inline-flex items-center gap-1 rounded-lg border border-dashed border-[#cdbf9f] bg-white px-2.5 py-1 text-[10px] font-bold uppercase tracking-[0.08em] text-[#5a4526] transition hover:border-[#412d15] hover:bg-[#faf7f0] disabled:opacity-50"
                  >
                    {pkgBusy ? (
                      <span className="inline-block h-3 w-3 animate-spin rounded-full border-2 border-[#cdbf9f] border-t-[#412d15]" />
                    ) : (
                      <FiZap className="h-3 w-3" />
                    )}
                    Suggest
                  </button>
                  ) : null}

                  {/* Weight + dims unit toggles grouped in one compact pill */}
                  <div className="inline-flex items-center gap-1.5 rounded-lg border border-[#e3d9c4] bg-white px-1.5 py-1">
                    <span className="text-[8.5px] font-bold uppercase tracking-[0.1em] text-[#a1906d]">Wt</span>
                    <div className="inline-flex overflow-hidden rounded-md bg-[#faf7f0]">
                      {(['LB', 'KG'] as const).map((u) => (
                        <button
                          key={u}
                          type="button"
                          onClick={() => { setWeightUnit(u); setWeightUnitTouched(true) }}
                          className={`px-2 py-0.5 text-[11px] font-semibold transition ${weightUnit === u ? 'bg-[#1f150c] text-[#f4eede]' : 'text-[#5a4526] hover:bg-[#efe7d4]'}`}
                        >
                          {u.toLowerCase()}
                        </button>
                      ))}
                    </div>
                    <span className="h-4 w-px bg-[#e3d9c4]" />
                    <span className="text-[8.5px] font-bold uppercase tracking-[0.1em] text-[#a1906d]">Dim</span>
                    <div className="inline-flex overflow-hidden rounded-md bg-[#faf7f0]">
                      {(['IN', 'CM'] as const).map((u) => (
                        <button
                          key={u}
                          type="button"
                          onClick={() => { setDimUnit(u); setDimUnitTouched(true) }}
                          className={`px-2 py-0.5 text-[11px] font-semibold transition ${dimUnit === u ? 'bg-[#1f150c] text-[#f4eede]' : 'text-[#5a4526] hover:bg-[#efe7d4]'}`}
                        >
                          {u.toLowerCase()}
                        </button>
                      ))}
                    </div>
                  </div>
                </div>
              }
            >
              <div className="space-y-3">
                <div className="grid grid-cols-2 gap-3">
                  <Field label="Packaging" required className="col-span-2" error={errAt('package')}
                         hint={compatiblePresetIdsForService?.size === 0 && serviceId
                           ? `No carrier packaging is linked to this service — link it in Settings → Shipping Catalog, or pick "Custom package…".`
                           : undefined}>
                    <select className={inputCls} value={packageChoice} onChange={(e) => { setPackageChoice(e.target.value); clearFixKey('package') }}>
                      {packagesForCarrier.length ? (
                        <optgroup label={`${carrierLabelMap[carrier] || carrier} packaging`}>
                          {packagesForCarrier.map((p) => (
                            <option key={p.id} value={String(p.id)}>{p.name}</option>
                          ))}
                        </optgroup>
                      ) : null}
                      {customBoxes.length ? (
                        <optgroup label="Your boxes">
                          {customBoxes.map((p) => (
                            <option key={p.id} value={String(p.id)}>{p.name}</option>
                          ))}
                        </optgroup>
                      ) : null}
                      <option value={CUSTOM_PKG}>Custom package…</option>
                    </select>
                  </Field>
                  <Field label={`Weight (${weightUnit.toLowerCase()})`} required error={errAt('weight') || weightOverPresetMaxWarning}>
                    <input className={inputCls} type="number" min="0" step="0.1" value={weight} onChange={(e) => { setWeight(e.target.value); clearFixKey('weight') }} placeholder="2.5" />
                  </Field>
                  <Field
                    label={`Declared value (${currency})${isInternational ? ' — auto' : ''}`}
                    error={errAt('declaredValue')}
                    hint={isInternational
                      ? 'Computed from Items: sum of quantity × unit value.'
                      : undefined}
                  >
                    {isInternational ? (
                      // PR #558 — for international, Declared Value is the
                      // sum(qty × unit price) across items. Made readonly
                      // so the operator can't drift from the customs total
                      // sent to the carrier. Empty items → red hint below.
                      <>
                        <input
                          className={`${inputCls} !bg-[#faf7f0] !text-[#5a4526] cursor-not-allowed`}
                          type="text"
                          readOnly
                          value={invoiceTotal > 0 ? invoiceTotal.toFixed(2) : '—'}
                          title="Auto-computed from Items — edit the Items section to change this value"
                          tabIndex={-1}
                        />
                        {invoiceTotal <= 0 ? (
                          <p className="mt-1 px-1 text-[11px] font-semibold text-rose-600">
                            Fill in Items unit prices to enable this shipment.
                          </p>
                        ) : null}
                      </>
                    ) : (
                      <input className={inputCls} type="number" min="0" step="0.01" value={declaredValue} onChange={(e) => { setDeclaredValue(e.target.value); clearFixKey('declaredValue') }} placeholder="100.00" />
                    )}
                  </Field>
                </div>
                {pkgServiceWarning ? (
                  <div className="mt-2 flex items-start gap-2 rounded-lg border border-amber-300 bg-amber-50 px-3 py-2 text-[12px] text-amber-800">
                    <FiAlertTriangle className="mt-0.5 h-3.5 w-3.5 shrink-0" />
                    <span>{pkgServiceWarning}</span>
                  </div>
                ) : null}
                {singlePieceOnlyWarning ? (
                  <div className="mt-2 flex items-start gap-2 rounded-lg border border-amber-300 bg-amber-50 px-3 py-2 text-[12px] text-amber-800">
                    <FiAlertTriangle className="mt-0.5 h-3.5 w-3.5 shrink-0" />
                    <span>{singlePieceOnlyWarning}</span>
                  </div>
                ) : null}
                {isRecipientUsTerritory ? (
                  <div className="mt-2 flex items-start gap-2 rounded-lg border border-sky-300 bg-sky-50 px-3 py-2 text-[12px] text-sky-800">
                    <FiAlertTriangle className="mt-0.5 h-3.5 w-3.5 shrink-0" />
                    <span>
                      {`Shipping to ${recipientTerritory}. FedEx + UPS treat this US territory as a separate country — ${usTerritoryBannerHint(recipientTerritory)}. The label will show ${recipientTerritory} as the country.`}
                    </span>
                  </div>
                ) : null}
                {/* Sprint 35 — signature at delivery + insured value.
                    NONE = carrier default (usually no signature domestic,
                    indirect on air); ADULT = 21+ ID required (higher fee).
                    Insured value beyond the carrier's free $100 tier
                    incurs an insurance surcharge on UPS/FedEx; USPS +
                    DHL treat it as a separate rider. */}
                <div className="grid grid-cols-2 gap-3">
                  <Field label="Signature at delivery"
                         hint="Adult signature = 21+ ID; higher fee">
                    <select className={inputCls}
                            value={signatureOption}
                            onChange={(e) => setSignatureOption(e.target.value as typeof signatureOption)}>
                      <option value="NONE">Carrier default</option>
                      <option value="INDIRECT">Indirect (anyone at address)</option>
                      <option value="DIRECT">Direct (someone at address)</option>
                      <option value="ADULT">Adult signature (21+)</option>
                    </select>
                  </Field>
                  <Field label={`Insured value (${currency})`}
                         error={errAt('insuredValue')}
                         hint="Beyond the carrier's free $100 tier">
                    <input className={inputCls} type="number" min="0" step="0.01"
                           value={insuredValue}
                           onChange={(e) => setInsuredValue(e.target.value)}
                           placeholder="0.00" />
                  </Field>
                </div>
                {/* UPS/DHL/USPS Label format moved to the compact top
                    "Shipment" row (rendered next to FedEx's Label Type
                    + Label Size). Removed from this section to avoid
                    two controls bound to the same labelImageFormat. */}
                {isCustomPkg ? (
                  <div className="grid grid-cols-3 gap-3">
                    <Field label={`Length (${dimUnit.toLowerCase()})`} required error={errAt('length')}>
                      <input className={inputCls} type="number" min="0" step="0.1" value={length} onChange={(e) => setLength(e.target.value)} placeholder="e.g. 12" />
                    </Field>
                    <Field label={`Width (${dimUnit.toLowerCase()})`} required error={errAt('width')}>
                      <input className={inputCls} type="number" min="0" step="0.1" value={width} onChange={(e) => setWidth(e.target.value)} placeholder="e.g. 9" />
                    </Field>
                    <Field label={`Height/depth (${dimUnit.toLowerCase()})`} required error={errAt('height')}>
                      <input className={inputCls} type="number" min="0" step="0.1" value={height} onChange={(e) => setHeight(e.target.value)} placeholder="e.g. 4" />
                    </Field>
                  </div>
                ) : null}

                {/* Sprint 29 — additional boxes. First box uses the fields
                    above; extra rows collect per-box weight + dims. A filled
                    box collapses to a one-line summary (click to edit). */}
                {extraPackages.map((p, idx) => {
                  const filled = !!(p.weight && p.length && p.width && p.height)
                  // Inline errors without the "Box N:" prefix the notice uses.
                  const boxErr = (f: string) => errAt(`extraPackages[${idx}].${f}`)?.replace(/^Box \d+: /, '')
                  const boxHasError = ['weight', 'length', 'width', 'height'].some((f) => !!boxErr(f))
                  // A box with something to fix stays open, so the field can be seen.
                  const expanded = expandedBoxes.has(idx) || !filled || boxHasError
                  const needsDims = !p.packageType && isCustomPkg
                  const summary = `${p.weight || '—'} ${weightUnit.toLowerCase()} · ${p.length || '—'}×${p.width || '—'}×${p.height || '—'} ${dimUnit.toLowerCase()}`
                  return (
                  <div key={idx} className="rounded-xl border border-dashed border-[#e3d9c4] bg-[#faf7f0]/60 p-3">
                    <div className="mb-2 flex items-center justify-between gap-2">
                      <button type="button" onClick={() => toggleBox(idx)} aria-expanded={expanded} className="flex min-w-0 items-center gap-2 text-left">
                        <p className="text-[11px] font-bold uppercase tracking-[0.14em] text-[#6b5c42]">
                          Box {idx + 2}
                        </p>
                        {!expanded ? <span className="truncate text-[11.5px] text-[#5a4526]">{summary}</span> : null}
                      </button>
                      <div className="flex shrink-0 items-center gap-1">
                        <button
                          type="button"
                          onClick={() => duplicateBox(idx)}
                          disabled={!canAddMoreBoxes}
                          title={canAddMoreBoxes ? 'Add a box with the same weight and dimensions' : (addBoxBlockedMessage || 'Add a box with the same weight and dimensions')}
                          className="inline-flex h-9 items-center gap-1 rounded-lg border border-[#e3d9c4] bg-white px-2 text-[11px] font-semibold text-[#5a4526] hover:bg-[#faf7f0] disabled:cursor-not-allowed disabled:opacity-40 disabled:hover:bg-white"
                        >
                          <FiCopy className="h-3.5 w-3.5" /> Duplicate
                        </button>
                        {filled ? (
                          <button type="button" onClick={() => toggleBox(idx)} className="inline-flex h-9 items-center rounded-lg border border-[#e3d9c4] bg-white px-2 text-[11px] font-semibold text-[#5a4526] hover:bg-[#faf7f0]">
                            {expanded ? 'Collapse' : 'Edit'}
                          </button>
                        ) : null}
                        <button
                          type="button"
                          onClick={() => removeBox(idx)}
                          aria-label={`Remove box ${idx + 2}`}
                          className="inline-flex h-9 w-9 items-center justify-center rounded-lg border border-[#e3d9c4] bg-white text-[#6b5c42] hover:bg-rose-50 hover:text-rose-600"
                        >
                          <FiTrash2 className="h-3.5 w-3.5" />
                        </button>
                      </div>
                    </div>
                    {expanded ? (<>
                    <div className="grid grid-cols-2 gap-2">
                      <Field label={`Weight (${weightUnit.toLowerCase()})`} required error={boxErr('weight')}>
                        <input
                          className={inputCls}
                          type="number" min="0" step="0.1"
                          value={p.weight}
                          onChange={(e) => setExtraPackages((cur) =>
                            cur.map((x, i) => (i === idx ? { ...x, weight: e.target.value } : x)))}
                          placeholder="2.5"
                        />
                      </Field>
                      <Field label={`Declared value (${currency})`}>
                        <input
                          className={inputCls}
                          type="number" min="0" step="0.01"
                          value={p.declaredValue}
                          onChange={(e) => setExtraPackages((cur) =>
                            cur.map((x, i) => (i === idx ? { ...x, declaredValue: e.target.value } : x)))}
                          placeholder="100.00"
                        />
                      </Field>
                    </div>
                    <div className="mt-2 grid grid-cols-3 gap-2">
                      <Field label={`L (${dimUnit.toLowerCase()})`} required={needsDims} error={boxErr('length')}>
                        <input
                          className={inputCls}
                          type="number" min="0" step="0.1"
                          value={p.length}
                          onChange={(e) => setExtraPackages((cur) =>
                            cur.map((x, i) => (i === idx ? { ...x, length: e.target.value } : x)))}
                        />
                      </Field>
                      <Field label={`W (${dimUnit.toLowerCase()})`} required={needsDims} error={boxErr('width')}>
                        <input
                          className={inputCls}
                          type="number" min="0" step="0.1"
                          value={p.width}
                          onChange={(e) => setExtraPackages((cur) =>
                            cur.map((x, i) => (i === idx ? { ...x, width: e.target.value } : x)))}
                        />
                      </Field>
                      <Field label={`H (${dimUnit.toLowerCase()})`} required={needsDims} error={boxErr('height')}>
                        <input
                          className={inputCls}
                          type="number" min="0" step="0.1"
                          value={p.height}
                          onChange={(e) => setExtraPackages((cur) =>
                            cur.map((x, i) => (i === idx ? { ...x, height: e.target.value } : x)))}
                        />
                      </Field>
                    </div>
                    </>) : null}
                  </div>
                  )
                })}
                <div className="flex flex-wrap items-center gap-2">
                  <button
                    type="button"
                    onClick={() => setExtraPackages((cur) => [...cur, blankExtraPackage()])}
                    disabled={!canAddMoreBoxes}
                    title={canAddMoreBoxes ? undefined : (addBoxBlockedMessage || undefined)}
                    className="inline-flex h-9 items-center gap-1.5 rounded-lg border border-dashed border-[#e3d9c4] bg-white px-2.5 text-[11px] font-semibold text-[#5a4526] hover:bg-[#faf7f0] disabled:cursor-not-allowed disabled:opacity-40 disabled:hover:bg-white"
                  >
                    <FiPlus className="h-3 w-3" />
                    Add another box
                    <span className="ml-1 rounded-full bg-[#f4eede] px-1.5 py-0.5 text-[10px] font-bold tabular-nums text-[#5a4526]" title="Boxes on this shipment">
                      {1 + extraPackages.length}
                    </span>
                  </button>
                  <span className={`inline-flex h-9 items-center gap-1 rounded-lg border border-dashed border-[#e3d9c4] bg-white px-2 text-[11px] font-semibold text-[#5a4526] ${canAddMoreBoxes ? '' : 'opacity-40'}`}>
                    Add
                    <input
                      type="number" min="1" max="200"
                      value={bulkAddCount}
                      onChange={(e) => setBulkAddCount(e.target.value)}
                      disabled={!canAddMoreBoxes}
                      aria-label="Number of boxes to add"
                      className="w-12 rounded border border-[#e3d9c4] px-1 py-0.5 text-center text-[11px] tabular-nums disabled:cursor-not-allowed"
                    />
                    <button
                      type="button"
                      disabled={!canAddMoreBoxes}
                      title={canAddMoreBoxes
                        ? "Adds boxes copying the last box's weight and dimensions (box 1's when there is no extra box yet)"
                        : (addBoxBlockedMessage || undefined)}
                      onClick={() => {
                        if (!canAddMoreBoxes) return
                        const roomLeft = maxPiecesContext.cap != null
                          ? Math.max(0, maxPiecesContext.cap - currentBoxCount)
                          : 200
                        const n = Math.max(1, Math.min(200, Math.min(roomLeft, Number(bulkAddCount) || 1)))
                        const last = extraPackages[extraPackages.length - 1]
                        // BUG-006: when no previous extra and a CARRIER
                        // preset is active, top-level length/width/height are
                        // blank (isCustomPkg=false). Read effective dims from
                        // the selected preset so each cloned box carries the
                        // preset's L/W/H instead of empty strings.
                        const preset = isCustomPkg
                          ? null
                          : packagesForCarrier.find((p) => String(p.id) === packageChoice)
                        const effLength = isCustomPkg
                          ? length
                          : (preset?.length != null ? String(preset.length) : '')
                        const effWidth = isCustomPkg
                          ? width
                          : (preset?.width != null ? String(preset.width) : '')
                        const effHeight = isCustomPkg
                          ? height
                          : (preset?.height != null ? String(preset.height) : '')
                        const effPackageType = isCustomPkg
                          ? ''
                          : (preset?.carrierPackageCode || '')
                        const t: ExtraPackage = last && last.weight
                          ? { ...last }
                          : { ...blankExtraPackage(), weight,
                              length: effLength, width: effWidth, height: effHeight,
                              packageType: effPackageType }
                        setExtraPackages((cur) => [...cur, ...Array.from({ length: n }, () => ({ ...t }))])
                      }}
                      className="rounded px-1 hover:bg-[#faf7f0] disabled:cursor-not-allowed disabled:hover:bg-transparent"
                    >
                      boxes like the last
                    </button>
                  </span>
                  {upsReturnMultiPkgNote ? (
                    <span
                      title="UPS's Ship API allows only one package per return-label call, so we submit N single-box requests behind the scenes and stitch them into one order with N per-piece tracking numbers."
                      className="inline-flex h-9 items-center gap-1.5 rounded-lg border border-sky-200 bg-sky-50 px-2.5 text-[11px] font-semibold text-sky-800"
                    >
                      <FiAlertCircle className="h-3 w-3" />
                      UPS returns: one label per box. We'll generate {1 + extraPackages.length} return labels — a separate tracking number for each box.
                    </span>
                  ) : null}
                  {extraPackages.length > 0 ? (
                    <button
                      type="button"
                      title="Copy box 1's weight and dimensions onto every extra box"
                      onClick={() => {
                        // Read EFFECTIVE box-1 dims — top-level state is
                        // only populated when "Custom" is picked. For a
                        // preset-selected box 1 (e.g. THB-PACKAGE), fall
                        // back to the preset's own L/W/H so the applied
                        // row isn't blank. packageType also carries over
                        // so the operator doesn't have to re-select it on
                        // every box.
                        const preset = isCustomPkg
                          ? null
                          : [...packagesForCarrier, ...customBoxes].find((pk) => String(pk.id) === packageChoice)
                        const effLength = isCustomPkg
                          ? length
                          : (preset?.length != null ? String(preset.length) : '')
                        const effWidth = isCustomPkg
                          ? width
                          : (preset?.width != null ? String(preset.width) : '')
                        const effHeight = isCustomPkg
                          ? height
                          : (preset?.height != null ? String(preset.height) : '')
                        const effPackageType = isCustomPkg
                          ? ''
                          : (preset?.carrierPackageCode || '')
                        setExtraPackages((cur) => cur.map((p) => ({
                          ...p,
                          weight,
                          length: effLength,
                          width: effWidth,
                          height: effHeight,
                          packageType: effPackageType,
                        })))
                      }}
                      className="inline-flex h-9 items-center rounded-lg border border-dashed border-[#e3d9c4] bg-white px-2.5 text-[11px] font-semibold text-[#5a4526] hover:bg-[#faf7f0]"
                    >
                      Apply box 1 to all
                    </button>
                  ) : null}
                </div>
              </div>
              </SectionCard>

  )
}
