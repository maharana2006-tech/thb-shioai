/**
 * F5-B2 — Importer / Broker draft step (create mode). A scoped-down
 * subset of ClientCustomsProfile — enough for a first-time onboarding
 * profile. The full field surface is on the settings page.
 *
 * <p>Extracted from ClientEditorPage. Owns no async state — the
 * `filled` toggle + BUSINESS/RECEIVER radio + form fields all live in
 * the parent's `draft` prop so a browser refresh doesn't lose them
 * (the wizard persists to localStorage as one blob).
 */
import type { Dispatch, SetStateAction } from 'react'
import { Field, inputBaseClass, inputOk } from './_shared'
import type { ImporterBrokerDraft } from './_types'

export interface ImporterBrokerDraftStepProps {
  draft: ImporterBrokerDraft
  setDraft: Dispatch<SetStateAction<ImporterBrokerDraft>>
}

export function ImporterBrokerDraftStep({
  draft,
  setDraft,
}: ImporterBrokerDraftStepProps) {
  const update = <K extends keyof ImporterBrokerDraft>(k: K, v: ImporterBrokerDraft[K]) =>
    setDraft((cur) => ({ ...cur, [k]: v }))

  return (
    <div className="px-4 py-3 space-y-3">
      <label className="flex items-start gap-2 rounded-2xl border border-slate-200 bg-slate-50/60 p-3">
        <input
          type="checkbox"
          checked={draft.filled}
          onChange={(e) => update('filled', e.target.checked)}
          className="mt-0.5 h-4 w-4 rounded border-slate-300 text-slate-950 focus:ring-slate-300"
        />
        <span className="flex-1">
          <span className="block text-[12.5px] font-semibold text-slate-950">
            Fill Importer / Broker now
          </span>
          <span className="mt-0.5 block text-[11px] leading-4 text-slate-500">
            Optional. Captures one primary importer profile for customs on international shipments.
            Unchecked = skip this step; you can add profiles later from Settings → Importer/Broker.
          </span>
        </span>
      </label>

      {draft.filled ? (
        <div className="rounded-2xl border border-slate-200 bg-white p-4 space-y-4">
          {/* Importer type */}
          <div>
            <p className="mb-1.5 text-[10px] font-bold uppercase tracking-[0.14em] text-slate-400">
              Importer type
            </p>
            <div className="flex flex-wrap gap-2">
              {(['BUSINESS', 'RECEIVER'] as const).map((v) => (
                <label
                  key={v}
                  className={`inline-flex cursor-pointer items-center gap-2 rounded-xl border px-3 py-2 text-[12px] font-semibold transition ${
                    draft.importerType === v
                      ? 'border-[#412d15] bg-[#412d15]/5 text-[#412d15]'
                      : 'border-slate-200 bg-white text-slate-600 hover:bg-slate-50'
                  }`}
                >
                  <input
                    type="radio"
                    name="importerType"
                    value={v}
                    checked={draft.importerType === v}
                    onChange={() => update('importerType', v)}
                    className="sr-only"
                  />
                  {v === 'BUSINESS' ? 'BUSINESS · fixed importer (DDP)' : 'RECEIVER · consignee is IOR (DAP)'}
                </label>
              ))}
            </div>
            <p className="mt-1 text-[10.5px] text-slate-500">
              BUSINESS: this client's own importer identity is on file. RECEIVER: no importer of record — the destination consignee is the IOR (DAP terms).
            </p>
          </div>

          {/* Importer identity — only relevant for BUSINESS */}
          {draft.importerType === 'BUSINESS' ? (
            <div className="grid grid-cols-1 gap-2.5 sm:grid-cols-2">
              <Field label="Importer name" required>
                <input value={draft.importerName} onChange={(e) => update('importerName', e.target.value)} maxLength={200} className={`${inputBaseClass} ${inputOk}`} />
              </Field>
              <Field label="Country (ISO-2)" required>
                <input value={draft.importerCountry} onChange={(e) => update('importerCountry', e.target.value.toUpperCase())} maxLength={2} className={`${inputBaseClass} ${inputOk} uppercase font-semibold`} />
              </Field>
              <Field label="Address line 1" required>
                <input value={draft.importerAddress1} onChange={(e) => update('importerAddress1', e.target.value)} maxLength={255} className={`${inputBaseClass} ${inputOk}`} />
              </Field>
              <Field label="Address line 2">
                <input value={draft.importerAddress2} onChange={(e) => update('importerAddress2', e.target.value)} maxLength={255} className={`${inputBaseClass} ${inputOk}`} />
              </Field>
              <Field label="City" required>
                <input value={draft.importerCity} onChange={(e) => update('importerCity', e.target.value)} maxLength={120} className={`${inputBaseClass} ${inputOk}`} />
              </Field>
              <Field label="State / region">
                <input value={draft.importerState} onChange={(e) => update('importerState', e.target.value)} maxLength={120} className={`${inputBaseClass} ${inputOk}`} />
              </Field>
              <Field label="Postal code" required>
                <input value={draft.importerPostcode} onChange={(e) => update('importerPostcode', e.target.value)} maxLength={20} className={`${inputBaseClass} ${inputOk}`} />
              </Field>
              <Field label="Phone">
                <input value={draft.importerPhone} onChange={(e) => update('importerPhone', e.target.value)} type="tel" inputMode="tel" maxLength={50} className={`${inputBaseClass} ${inputOk}`} />
              </Field>
              <Field label="Tax ID (EIN / EORI / GSTIN / …)" hint="Optional but usually required by the destination customs authority.">
                <input value={draft.importerTaxId} onChange={(e) => update('importerTaxId', e.target.value)} maxLength={60} className={`${inputBaseClass} ${inputOk}`} />
              </Field>
              <Field label="Tax ID type" hint="EIN, EORI, GSTIN, IEC, IOSS …">
                <input value={draft.importerTaxIdType} onChange={(e) => update('importerTaxIdType', e.target.value.toUpperCase())} maxLength={20} className={`${inputBaseClass} ${inputOk} uppercase`} />
              </Field>
            </div>
          ) : null}

          {/* Shipment defaults */}
          <div>
            <p className="mb-1.5 text-[10px] font-bold uppercase tracking-[0.14em] text-slate-400">
              Shipment defaults (optional)
            </p>
            <div className="grid grid-cols-1 gap-2.5 sm:grid-cols-2">
              <Field label="Incoterms" hint="DDP · DAP · DDU · EXW · CIF · FOB">
                <select value={draft.incoterms} onChange={(e) => update('incoterms', e.target.value)} className={`${inputBaseClass} ${inputOk}`}>
                  <option value="">— carrier default —</option>
                  {['DDP', 'DAP', 'DDU', 'EXW', 'CIF', 'FOB', 'DPU', 'CPT', 'CIP', 'FCA', 'FAS', 'CFR'].map((c) => (
                    <option key={c} value={c}>{c}</option>
                  ))}
                </select>
              </Field>
              <Field label="Reason for export">
                <select value={draft.reasonForExport} onChange={(e) => update('reasonForExport', e.target.value)} className={`${inputBaseClass} ${inputOk}`}>
                  <option value="">— carrier default —</option>
                  <option value="SALE">SALE — commercial sale</option>
                  <option value="GIFT">GIFT</option>
                  <option value="SAMPLE">SAMPLE</option>
                  <option value="RETURN">RETURN</option>
                  <option value="REPAIR">REPAIR</option>
                  <option value="DOCUMENTS">DOCUMENTS</option>
                </select>
              </Field>
            </div>
          </div>

          {/* Broker (optional) */}
          <div>
            <p className="mb-1.5 text-[10px] font-bold uppercase tracking-[0.14em] text-slate-400">
              Broker (optional)
            </p>
            <div className="grid grid-cols-1 gap-2.5 sm:grid-cols-2">
              <Field label="Broker name">
                <input value={draft.brokerName} onChange={(e) => update('brokerName', e.target.value)} maxLength={200} className={`${inputBaseClass} ${inputOk}`} />
              </Field>
              <Field label="Broker phone">
                <input value={draft.brokerPhone} onChange={(e) => update('brokerPhone', e.target.value)} type="tel" inputMode="tel" maxLength={50} className={`${inputBaseClass} ${inputOk}`} />
              </Field>
            </div>
          </div>
        </div>
      ) : null}
    </div>
  )
}
