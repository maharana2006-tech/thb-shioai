/**
 * F5-B — Review & Submit step of the client-editor wizard, plus its
 * per-section SummaryCard. Extracted from ClientEditorPage. Emits a
 * per-section pill (READY / SKIPPED / NEEDS FIX) driven entirely by
 * props — the parent owns the stepComplete / stepBlockers logic.
 */
import type { ReactNode } from 'react'
import { FiChevronRight } from 'react-icons/fi'
import type { ClientUpsertPayload } from '../../api/clientService'
import { formatCarrierName } from '../../utils/carrierUtils'
import type {
  CarrierAccountDraft,
  ImporterBrokerDraft,
  MappingRuleDraft,
  StepKey,
} from './_types'

export interface SummaryStepProps {
  form: ClientUpsertPayload
  selectedShipFromWarehouseId: number | null
  shipFromWarehouseLabel: string | null
  carrierDrafts: CarrierAccountDraft[]
  mappingDrafts: MappingRuleDraft[]
  importerBrokerDraft: ImporterBrokerDraft
  stepComplete: (k: StepKey) => boolean
  stepBlockers: (k: StepKey) => string[]
  jumpTo: (k: StepKey) => void
}

export function SummaryStep({
  form,
  selectedShipFromWarehouseId,
  shipFromWarehouseLabel,
  carrierDrafts,
  mappingDrafts,
  importerBrokerDraft,
  stepComplete,
  stepBlockers,
  jumpTo,
}: SummaryStepProps) {
  const identityBody = (
    <ul className="space-y-0.5 text-[11.5px] leading-4 text-slate-700">
      <li><span className="font-mono font-semibold">{form.clientCode || '—'}</span> · {form.name || <em className="text-slate-400">no name</em>}</li>
      {form.email ? <li>{form.email}</li> : null}
      {form.phone ? <li>{form.phone}</li> : null}
      <li className="text-slate-500">
        Defaults: {[form.defaultCurrency, form.defaultWeightUnit, form.defaultDimUnit, form.defaultOriginCountry, form.timezone]
          .filter(Boolean).join(' · ') || <em>none set</em>}
      </li>
    </ul>
  )

  const shipFromBody = (
    <ul className="space-y-0.5 text-[11.5px] leading-4 text-slate-700">
      <li className="font-semibold">{shipFromWarehouseLabel || <em className="text-slate-400">no warehouse picked</em>}</li>
      {form.shipFrom ? (
        <>
          {form.shipFrom.name ? <li>{form.shipFrom.name}</li> : null}
          <li>
            {form.shipFrom.line1 || <em className="text-slate-400">no street</em>}
            {form.shipFrom.line2 ? `, ${form.shipFrom.line2}` : ''}
          </li>
          <li>
            {[form.shipFrom.city, form.shipFrom.state, form.shipFrom.zip].filter(Boolean).join(', ') || <em className="text-slate-400">no city / state / zip</em>}
            {form.shipFrom.country ? ` · ${form.shipFrom.country}` : ''}
          </li>
        </>
      ) : null}
    </ul>
  )

  const returnBody = form.returnSameAsShipFrom ? (
    <p className="text-[11.5px] text-slate-700">Mirrors Ship From address.</p>
  ) : form.returnAddress ? (
    <ul className="space-y-0.5 text-[11.5px] leading-4 text-slate-700">
      {form.returnAddress.name ? <li>{form.returnAddress.name}</li> : null}
      <li>{form.returnAddress.line1 || <em className="text-slate-400">no street</em>}{form.returnAddress.line2 ? `, ${form.returnAddress.line2}` : ''}</li>
      <li>{[form.returnAddress.city, form.returnAddress.state, form.returnAddress.zip].filter(Boolean).join(', ')}{form.returnAddress.country ? ` · ${form.returnAddress.country}` : ''}</li>
    </ul>
  ) : (
    <p className="text-[11.5px] italic text-slate-500">no return address set</p>
  )

  const carriersBody = carrierDrafts.length ? (
    <ul className="space-y-0.5 text-[11.5px] leading-4 text-slate-700">
      {carrierDrafts.slice(0, 5).map((d) => (
        <li key={d.id}>
          {formatCarrierName(d.carrierCode)} · {d.accountNumber}
          {d.clientDefault ? <span className="ml-1 text-[10px] font-semibold text-[#412d15]">(default)</span> : null}
        </li>
      ))}
      {carrierDrafts.length > 5 ? <li className="italic text-slate-500">+ {carrierDrafts.length - 5} more…</li> : null}
    </ul>
  ) : (
    <p className="text-[11.5px] italic text-slate-500">no carrier accounts staged</p>
  )

  const mappingBody = mappingDrafts.length ? (
    <ul className="space-y-0.5 text-[11.5px] leading-4 text-slate-700">
      {mappingDrafts.slice(0, 5).map((d) => (
        <li key={d.id}>
          <span className="rounded bg-[#1f150c] px-1.5 py-0.5 font-mono text-[10px] text-[#e1dcc9]">{d.shipviaCd}</span>
          {' → service #'}{d.serviceId}
        </li>
      ))}
      {mappingDrafts.length > 5 ? <li className="italic text-slate-500">+ {mappingDrafts.length - 5} more…</li> : null}
    </ul>
  ) : (
    <p className="text-[11.5px] italic text-slate-500">no mappings staged</p>
  )

  const importerBody = importerBrokerDraft.filled ? (
    <ul className="space-y-0.5 text-[11.5px] leading-4 text-slate-700">
      <li>
        <span className="rounded bg-slate-100 px-1.5 py-0.5 text-[10px] font-semibold text-slate-700">{importerBrokerDraft.importerType}</span>
        {' '}{importerBrokerDraft.importerName || <em className="text-slate-400">no importer name</em>}
      </li>
      {importerBrokerDraft.importerType === 'BUSINESS' ? (
        <>
          <li>{[importerBrokerDraft.importerAddress1, importerBrokerDraft.importerCity, importerBrokerDraft.importerPostcode, importerBrokerDraft.importerCountry].filter(Boolean).join(', ')}</li>
          {importerBrokerDraft.importerTaxId ? <li>Tax ID: {importerBrokerDraft.importerTaxIdType || '?'}/{importerBrokerDraft.importerTaxId}</li> : null}
        </>
      ) : null}
      {importerBrokerDraft.incoterms || importerBrokerDraft.reasonForExport ? (
        <li className="text-slate-500">Defaults: {[importerBrokerDraft.incoterms, importerBrokerDraft.reasonForExport].filter(Boolean).join(' · ')}</li>
      ) : null}
      {importerBrokerDraft.brokerName ? <li>Broker: {importerBrokerDraft.brokerName}{importerBrokerDraft.brokerPhone ? ` · ${importerBrokerDraft.brokerPhone}` : ''}</li> : null}
    </ul>
  ) : (
    <p className="text-[11.5px] italic text-slate-500">skipped — add profiles later from Settings → Importer/Broker</p>
  )

  return (
    <div className="px-4 py-3 space-y-3">
      <div className="rounded-2xl border border-slate-200 bg-slate-50/60 p-3">
        <p className="text-[12.5px] font-semibold text-slate-950">Review & submit</p>
        <p className="mt-0.5 text-[11px] leading-4 text-slate-500">
          Everything below will be created on Submit. Green sections are ready; red sections must be fixed. Amber sections are optional-and-empty — Submit is still allowed.
        </p>
      </div>

      <SummaryCard
        title="Identity"
        stepKey="identity"
        status={stepComplete('identity') ? 'ok' : 'block'}
        blockers={stepBlockers('identity')}
        jumpTo={jumpTo}
      >
        {identityBody}
      </SummaryCard>

      <SummaryCard
        title="Ship From"
        stepKey="shipFrom"
        status={(stepComplete('shipFrom') && selectedShipFromWarehouseId != null) ? 'ok' : 'block'}
        blockers={stepBlockers('shipFrom')}
        jumpTo={jumpTo}
      >
        {shipFromBody}
      </SummaryCard>

      <SummaryCard
        title="Return address"
        stepKey="return"
        status={stepComplete('return') ? 'ok' : 'block'}
        blockers={stepBlockers('return')}
        jumpTo={jumpTo}
      >
        {returnBody}
      </SummaryCard>

      <SummaryCard
        title={`Carrier accounts (${carrierDrafts.length})`}
        stepKey="carriers"
        status={stepComplete('carriers') ? 'ok' : 'block'}
        blockers={stepBlockers('carriers')}
        jumpTo={jumpTo}
      >
        {carriersBody}
      </SummaryCard>

      <SummaryCard
        title={`Shipping mappings (${mappingDrafts.length})`}
        stepKey="mapping"
        status={stepComplete('mapping') ? 'ok' : 'block'}
        blockers={stepBlockers('mapping')}
        jumpTo={jumpTo}
      >
        {mappingBody}
      </SummaryCard>

      <SummaryCard
        title="Importer / Broker"
        stepKey="importerBroker"
        status={
          !importerBrokerDraft.filled
            ? 'warn'  // amber: intentionally skipped (still valid)
            : stepComplete('importerBroker') ? 'ok' : 'block'
        }
        blockers={stepBlockers('importerBroker')}
        jumpTo={jumpTo}
      >
        {importerBody}
      </SummaryCard>
    </div>
  )
}

export interface SummaryCardProps {
  title: string
  stepKey: StepKey
  status: 'ok' | 'warn' | 'block'
  blockers: string[]
  jumpTo: (k: StepKey) => void
  children: ReactNode
}

export function SummaryCard({
  title,
  stepKey,
  status,
  blockers,
  jumpTo,
  children,
}: SummaryCardProps) {
  const tone =
    status === 'ok'
      ? { bar: 'border-emerald-200 bg-emerald-50/40', pill: 'bg-emerald-100 text-emerald-800', label: 'READY' }
      : status === 'warn'
        ? { bar: 'border-amber-200 bg-amber-50/40', pill: 'bg-amber-100 text-amber-800', label: 'SKIPPED' }
        : { bar: 'border-rose-300 bg-rose-50/40', pill: 'bg-rose-100 text-rose-800', label: 'NEEDS FIX' }

  return (
    <div className={`rounded-2xl border ${tone.bar} p-3`}>
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <p className="flex items-center gap-2 text-[12px] font-semibold text-slate-950">
            {title}
            <span className={`rounded-full ${tone.pill} px-1.5 py-0.5 text-[9.5px] font-bold uppercase tracking-wide`}>
              {tone.label}
            </span>
          </p>
          <div className="mt-1.5">
            {children}
          </div>
          {status === 'block' && blockers.length ? (
            <ul className="mt-1.5 list-disc space-y-0.5 pl-4 text-[10.5px] font-semibold text-rose-700">
              {blockers.map((b, i) => <li key={i}>{b}</li>)}
            </ul>
          ) : null}
        </div>
        <button
          type="button"
          onClick={() => jumpTo(stepKey)}
          className="inline-flex shrink-0 items-center gap-1 rounded-lg border border-slate-200 bg-white px-2.5 py-1 text-[11px] font-semibold text-slate-700 transition hover:border-[#412d15] hover:bg-[#faf7f0] hover:text-[#412d15]"
        >
          {status === 'block' ? 'Fix' : 'Edit'} <FiChevronRight className="h-3 w-3" />
        </button>
      </div>
    </div>
  )
}
