import { useMemo, useState, type ReactNode } from 'react'
import {
  FiCheckCircle,
  FiChevronLeft,
  FiChevronRight,
  FiFileText,
  FiGlobe,
  FiInfo,
  FiPackage,
  FiPlus,
  FiTrash2,
} from 'react-icons/fi'
import type { CustomsItem, OrderCustomsPayload } from '../../api/customsService'
import { checkHsCode, hsCodeHint } from '../../utils/hsCode'

/**
 * Four-step customs wizard for the label creation flow. Sprint-1 scaffold —
 * the fields it collects match the backend {@link IntlShipmentBlockDTO}
 * populated in CarrierServiceImpl.buildShipmentRequest; carrier-specific
 * wiring lands in Sprint 2/3/4 per carrier.
 *
 * <p>Wizard vs. modal rationale: the single-page customs form was dense to
 * the point of hiding required fields (HS code, country of origin, incoterms
 * all lived under a collapsed accordion). A wizard forces sequencing —
 * commodities before duties, duties before review — and gives us a canonical
 * place to put per-carrier hints inline (UPS Paperless Invoice, FedEx ETD,
 * USPS CN22/CN23), which we'll wire up as each carrier's connector lands.
 *
 * <p>The wizard is stateless-container by design: parent owns the
 * `OrderCustomsPayload` and passes it in; every step's edits fire onChange
 * with the next payload. That way saving to /orders/{n}/customs stays a
 * one-liner in the parent and the wizard doesn't need to know about our
 * apiClient.
 */

export interface CustomsWizardProps {
  /** The carrier the shipment will use — drives per-carrier hint copy. */
  carrierCode?: string | null
  /** ISO-3166 alpha-2. Determines domestic vs. intl and the wizard's visibility. */
  originCountry?: string | null
  destinationCountry?: string | null
  /** Customs payload — parent-owned, mutated via onChange. */
  value: OrderCustomsPayload
  onChange: (next: OrderCustomsPayload) => void
  /** Fires when the user hits Save on step 4. Parent persists via customsService.upsertCustoms. */
  onComplete: (payload: OrderCustomsPayload) => void
  /** Cancel button — parent decides whether to close a modal or route back. */
  onCancel: () => void
}

const INCOTERMS = [
  { code: 'DDP', label: 'DDP — Delivered Duty Paid', hint: 'Sender pays duties + taxes.' },
  { code: 'DAP', label: 'DAP — Delivered At Place', hint: 'Recipient pays duties + acts as importer.' },
  { code: 'DDU', label: 'DDU — Delivered Duty Unpaid', hint: 'Recipient pays duties only.' },
] as const

const REASONS_FOR_EXPORT = [
  { code: 'SALE', label: 'Sale' },
  { code: 'GIFT', label: 'Gift' },
  { code: 'SAMPLE', label: 'Sample / Commercial sample' },
  { code: 'RETURN', label: 'Returned goods' },
  { code: 'REPAIR', label: 'Goods for repair' },
  { code: 'DOCUMENTS', label: 'Documents' },
] as const

const CURRENCIES = ['USD', 'EUR', 'GBP', 'CAD', 'INR', 'AUD', 'SGD', 'JPY', 'CNY', 'AED'] as const

const WEIGHT_UNITS = ['LB', 'KG'] as const

/**
 * Per-carrier hint blocks. When a carrier is known, the review step renders
 * the matching hint so operators can proactively enable paperless / ETD in
 * their carrier portal before the first cross-border label.
 */
const CARRIER_HINTS: Record<string, { title: string; body: string; docsUrl: string }> = {
  UPS: {
    title: 'UPS Paperless Invoice',
    body: 'Enable Paperless Invoice on your UPS account to have the commercial invoice transmitted electronically — no printed copy attached to the parcel.',
    docsUrl: 'https://www.ups.com/us/en/business-solutions/business-tips/us-import-export/paperless-invoice-guide.page',
  },
  FEDEX: {
    title: 'FedEx Electronic Trade Documents (ETD)',
    body: "Enrol in FedEx ETD (via your account manager) to upload the commercial invoice electronically. Without ETD, FedEx expects three printed invoice copies attached to each parcel.",
    docsUrl: 'https://www.fedex.com/en-us/shipping/international/electronic-trade-documents.html',
  },
  USPS: {
    title: 'USPS Customs Forms (CN22 / CN23)',
    body: 'Stamps.com auto-prints CN22 (goods ≤ $400) or CN23 (goods > $400 or Priority Mail Intl) on the label PDF once customs data is present. No extra step for operators.',
    docsUrl: 'https://about.usps.com/postal-bulletin/2019/pb22515/html/updt_003.htm',
  },
}

type StepId = 'shipment-type' | 'commodities' | 'duties' | 'review'

const STEPS: Array<{ id: StepId; label: string; icon: ReactNode }> = [
  { id: 'shipment-type', label: 'Shipment type', icon: <FiGlobe /> },
  { id: 'commodities', label: 'Commodities', icon: <FiPackage /> },
  { id: 'duties', label: 'Duties & terms', icon: <FiFileText /> },
  { id: 'review', label: 'Review', icon: <FiCheckCircle /> },
]

export default function CustomsWizard({
  carrierCode,
  originCountry,
  destinationCountry,
  value,
  onChange,
  onComplete,
  onCancel,
}: CustomsWizardProps) {
  const [stepId, setStepId] = useState<StepId>('shipment-type')
  const stepIdx = STEPS.findIndex((s) => s.id === stepId)

  const isInternational = useMemo(() => {
    if (!originCountry || !destinationCountry) return true
    return originCountry.trim().toUpperCase() !== destinationCountry.trim().toUpperCase()
  }, [originCountry, destinationCountry])

  const carrierHint = carrierCode
    ? CARRIER_HINTS[carrierCode.trim().toUpperCase()]
    : undefined

  const invoiceTotal = useMemo(
    () =>
      (value.items ?? []).reduce(
        (sum, item) => sum + (item.quantity || 0) * (item.unitValue || 0),
        0,
      ),
    [value.items],
  )

  const goto = (id: StepId) => setStepId(id)
  const next = () => {
    if (stepIdx < STEPS.length - 1) goto(STEPS[stepIdx + 1].id)
  }
  const back = () => {
    if (stepIdx > 0) goto(STEPS[stepIdx - 1].id)
  }

  const patch = (delta: Partial<OrderCustomsPayload>) => onChange({ ...value, ...delta })

  const updateItem = (index: number, delta: Partial<CustomsItem>) => {
    const items = (value.items ?? []).slice()
    items[index] = { ...items[index], ...delta }
    onChange({ ...value, items })
  }

  const addItem = () =>
    onChange({
      ...value,
      items: [
        ...(value.items ?? []),
        { description: '', quantity: 1, unitValue: 0, hsCode: '', countryOfOrigin: '' },
      ],
    })

  const removeItem = (index: number) => {
    const items = (value.items ?? []).slice()
    items.splice(index, 1)
    onChange({ ...value, items })
  }

  const canProceed: boolean = (() => {
    if (stepId === 'commodities') {
      return (value.items ?? []).length > 0
        && (value.items ?? []).every((i) => i.description?.trim() && i.quantity > 0)
    }
    if (stepId === 'duties') {
      return Boolean(value.incoterms) && Boolean(value.currency)
    }
    return true
  })()

  return (
    <div className="flex h-full flex-col">
      {/* stepper */}
      <div className="flex items-center gap-2 border-b border-slate-200 px-4 py-3">
        {STEPS.map((s, idx) => (
          <div key={s.id} className="flex items-center gap-2">
            <button
              type="button"
              onClick={() => goto(s.id)}
              className={`flex items-center gap-1.5 rounded-lg px-2.5 py-1 text-[11.5px] font-semibold transition ${
                idx === stepIdx
                  ? 'bg-[#1f150c] text-white'
                  : idx < stepIdx
                    ? 'text-emerald-700 hover:bg-emerald-50'
                    : 'text-slate-400 cursor-not-allowed'
              }`}
              disabled={idx > stepIdx}
            >
              <span className="h-3.5 w-3.5">{s.icon}</span>
              {s.label}
            </button>
            {idx < STEPS.length - 1 ? (
              <FiChevronRight className="h-3 w-3 text-slate-300" />
            ) : null}
          </div>
        ))}
      </div>

      {/* body */}
      <div className="flex-1 overflow-y-auto px-4 py-4">
        {stepId === 'shipment-type' ? (
          <ShipmentTypeStep
            originCountry={originCountry}
            destinationCountry={destinationCountry}
            isInternational={isInternational}
          />
        ) : null}
        {stepId === 'commodities' ? (
          <CommoditiesStep
            items={value.items ?? []}
            onUpdate={updateItem}
            onAdd={addItem}
            onRemove={removeItem}
            invoiceTotal={invoiceTotal}
            currency={value.currency ?? 'USD'}
          />
        ) : null}
        {stepId === 'duties' ? (
          <DutiesStep value={value} onPatch={patch} />
        ) : null}
        {stepId === 'review' ? (
          <ReviewStep
            value={value}
            invoiceTotal={invoiceTotal}
            carrierHint={carrierHint}
          />
        ) : null}
      </div>

      {/* footer */}
      <div className="flex items-center justify-between border-t border-slate-200 px-4 py-3">
        <button
          type="button"
          onClick={onCancel}
          className="rounded-lg border border-slate-200 bg-white px-3 py-1.5 text-[12px] font-semibold text-slate-600 hover:bg-slate-50"
        >
          Cancel
        </button>
        <div className="flex items-center gap-2">
          {stepIdx > 0 ? (
            <button
              type="button"
              onClick={back}
              className="inline-flex items-center gap-1 rounded-lg border border-slate-200 bg-white px-3 py-1.5 text-[12px] font-semibold text-slate-600 hover:bg-slate-50"
            >
              <FiChevronLeft className="h-3.5 w-3.5" />
              Back
            </button>
          ) : null}
          {stepId !== 'review' ? (
            <button
              type="button"
              onClick={next}
              disabled={!canProceed}
              className="inline-flex items-center gap-1 rounded-lg bg-[#1f150c] px-3 py-1.5 text-[12px] font-semibold text-white transition hover:bg-[#412d15] disabled:cursor-not-allowed disabled:bg-slate-300"
            >
              Next
              <FiChevronRight className="h-3.5 w-3.5" />
            </button>
          ) : (
            <button
              type="button"
              onClick={() => onComplete(value)}
              className="inline-flex items-center gap-1 rounded-lg bg-emerald-600 px-3 py-1.5 text-[12px] font-semibold text-white transition hover:bg-emerald-700"
            >
              <FiCheckCircle className="h-3.5 w-3.5" />
              Save customs
            </button>
          )}
        </div>
      </div>
    </div>
  )
}

/* ---------------- Step 1: shipment type ---------------- */
function ShipmentTypeStep({
  originCountry,
  destinationCountry,
  isInternational,
}: {
  originCountry?: string | null
  destinationCountry?: string | null
  isInternational: boolean
}) {
  return (
    <div className="space-y-3">
      <h3 className="text-[15px] font-semibold text-slate-950">Shipment type</h3>
      <div className="rounded-2xl border border-slate-200 bg-slate-50 p-4">
        <p className="text-[12px] text-slate-600">
          Ships from{' '}
          <span className="font-semibold text-slate-900">{originCountry ?? '—'}</span> to{' '}
          <span className="font-semibold text-slate-900">{destinationCountry ?? '—'}</span>.
        </p>
        <p className="mt-3 text-[13px] font-semibold text-slate-950">
          {isInternational ? '🌐 International shipment' : '🏠 Domestic shipment'}
        </p>
        {isInternational ? (
          <p className="mt-1 text-[11.5px] leading-4 text-slate-500">
            Customs declaration required. The next steps collect commodities, duties, and incoterms
            — the wizard will merge this with the client's customs profile (importer of record, broker,
            tax IDs) at label time.
          </p>
        ) : (
          <p className="mt-1 text-[11.5px] leading-4 text-slate-500">
            No customs declaration needed. You can skip this wizard.
          </p>
        )}
      </div>
    </div>
  )
}

/* ---------------- Step 2: commodities ---------------- */
function CommoditiesStep({
  items,
  onUpdate,
  onAdd,
  onRemove,
  invoiceTotal,
  currency,
}: {
  items: CustomsItem[]
  onUpdate: (index: number, delta: Partial<CustomsItem>) => void
  onAdd: () => void
  onRemove: (index: number) => void
  invoiceTotal: number
  currency: string
}) {
  return (
    <div className="space-y-3">
      <div className="flex items-center justify-between">
        <h3 className="text-[15px] font-semibold text-slate-950">Commodities</h3>
        <button
          type="button"
          onClick={onAdd}
          className="inline-flex items-center gap-1 rounded-lg border border-slate-200 bg-white px-2.5 py-1 text-[11.5px] font-semibold text-slate-700 hover:bg-slate-50"
        >
          <FiPlus className="h-3 w-3" />
          Add line
        </button>
      </div>

      {items.length === 0 ? (
        <div className="rounded-xl border border-dashed border-slate-300 bg-slate-50 p-6 text-center">
          <p className="text-[12px] text-slate-500">No commodity lines yet.</p>
          <button
            type="button"
            onClick={onAdd}
            className="mt-2 inline-flex items-center gap-1 rounded-lg bg-[#1f150c] px-3 py-1.5 text-[11.5px] font-semibold text-white hover:bg-[#412d15]"
          >
            <FiPlus className="h-3 w-3" />
            Add first line
          </button>
        </div>
      ) : (
        <div className="space-y-2">
          {items.map((item, idx) => {
            const hsStatus = checkHsCode(item.hsCode)
            const hsHint = hsCodeHint(hsStatus)
            const hsBorder = hsStatus === 'malformed' ? 'border-amber-400' : 'border-slate-200'
            return (
            <div
              key={idx}
              className="rounded-xl border border-slate-200 bg-white p-2.5 space-y-1.5"
            >
              <div className="grid grid-cols-12 gap-2">
              <input
                className="col-span-4 rounded-lg border border-slate-200 bg-slate-50 px-2.5 py-1.5 text-[12px]"
                placeholder="Description"
                value={item.description}
                onChange={(e) => onUpdate(idx, { description: e.target.value })}
              />
              <input
                className={`col-span-2 rounded-lg border bg-slate-50 px-2.5 py-1.5 text-[12px] ${hsBorder}`}
                placeholder="HS code"
                value={item.hsCode ?? ''}
                onChange={(e) => onUpdate(idx, { hsCode: e.target.value })}
              />
              <input
                className="col-span-2 rounded-lg border border-slate-200 bg-slate-50 px-2.5 py-1.5 text-[12px]"
                placeholder="Origin (US)"
                maxLength={2}
                value={item.countryOfOrigin ?? ''}
                onChange={(e) => onUpdate(idx, { countryOfOrigin: e.target.value.toUpperCase() })}
              />
              <input
                type="number"
                className="col-span-1 rounded-lg border border-slate-200 bg-slate-50 px-2.5 py-1.5 text-right text-[12px] tabular-nums"
                min={1}
                value={item.quantity}
                onChange={(e) => onUpdate(idx, { quantity: Number(e.target.value) })}
              />
              <input
                type="number"
                step="0.01"
                className="col-span-2 rounded-lg border border-slate-200 bg-slate-50 px-2.5 py-1.5 text-right text-[12px] tabular-nums"
                placeholder="Unit value"
                value={item.unitValue ?? 0}
                onChange={(e) => onUpdate(idx, { unitValue: Number(e.target.value) })}
              />
              <button
                type="button"
                onClick={() => onRemove(idx)}
                aria-label="Remove line"
                className="col-span-1 flex items-center justify-center rounded-lg text-slate-400 transition hover:bg-rose-50 hover:text-rose-600"
              >
                <FiTrash2 className="h-3.5 w-3.5" />
              </button>
              </div>
              {hsHint ? (
                <p className="text-[10.5px] leading-4 text-amber-700">
                  <span className="font-semibold">HS code · </span>{hsHint}
                </p>
              ) : null}
            </div>
            )
          })}
        </div>
      )}

      {items.length > 0 ? (
        <div className="flex items-center justify-end gap-2 rounded-xl border border-slate-200 bg-slate-50 px-3 py-2 text-[11.5px] font-semibold text-slate-600">
          <span>Invoice total:</span>
          <span className="tabular-nums text-slate-950">
            {invoiceTotal.toFixed(2)} {currency}
          </span>
        </div>
      ) : null}
    </div>
  )
}

/* ---------------- Step 3: duties + incoterms ---------------- */
function DutiesStep({
  value,
  onPatch,
}: {
  value: OrderCustomsPayload
  onPatch: (delta: Partial<OrderCustomsPayload>) => void
}) {
  return (
    <div className="space-y-4">
      <h3 className="text-[15px] font-semibold text-slate-950">Duties & terms</h3>

      <div>
        <label className="mb-1 block text-[10.5px] font-bold uppercase tracking-[0.14em] text-slate-400">
          Incoterms
        </label>
        <div className="grid grid-cols-3 gap-2">
          {INCOTERMS.map((opt) => (
            <button
              key={opt.code}
              type="button"
              onClick={() => onPatch({ incoterms: opt.code })}
              className={`rounded-xl border p-2.5 text-left transition ${
                value.incoterms === opt.code
                  ? 'border-[#1f150c] bg-white text-[#1f150c] shadow-sm'
                  : 'border-slate-200 bg-slate-50 text-slate-600 hover:bg-slate-100'
              }`}
            >
              <div className="text-[11.5px] font-semibold">{opt.label}</div>
              <div className="mt-1 text-[10.5px] text-slate-500">{opt.hint}</div>
            </button>
          ))}
        </div>
      </div>

      <div className="grid grid-cols-2 gap-3">
        <div>
          <label className="mb-1 block text-[10.5px] font-bold uppercase tracking-[0.14em] text-slate-400">
            Reason for export
          </label>
          <select
            value={value.reasonForExport ?? ''}
            onChange={(e) => onPatch({ reasonForExport: e.target.value })}
            className="w-full rounded-xl border border-slate-200 bg-slate-50 px-3 py-2 text-[12.5px]"
          >
            <option value="">Select…</option>
            {REASONS_FOR_EXPORT.map((r) => (
              <option key={r.code} value={r.code}>
                {r.label}
              </option>
            ))}
          </select>
        </div>
        <div>
          <label className="mb-1 block text-[10.5px] font-bold uppercase tracking-[0.14em] text-slate-400">
            Invoice currency
          </label>
          <select
            value={value.currency ?? 'USD'}
            onChange={(e) => onPatch({ currency: e.target.value })}
            className="w-full rounded-xl border border-slate-200 bg-slate-50 px-3 py-2 text-[12.5px]"
          >
            {CURRENCIES.map((c) => (
              <option key={c} value={c}>
                {c}
              </option>
            ))}
          </select>
        </div>
        <div>
          <label className="mb-1 block text-[10.5px] font-bold uppercase tracking-[0.14em] text-slate-400">
            Weight unit
          </label>
          <select
            value={value.weightUnit ?? 'LB'}
            onChange={(e) => onPatch({ weightUnit: e.target.value })}
            className="w-full rounded-xl border border-slate-200 bg-slate-50 px-3 py-2 text-[12.5px]"
          >
            {WEIGHT_UNITS.map((u) => (
              <option key={u} value={u}>
                {u}
              </option>
            ))}
          </select>
        </div>
      </div>

      <div>
        <label className="mb-1 block text-[10.5px] font-bold uppercase tracking-[0.14em] text-slate-400">
          Notes (optional)
        </label>
        <textarea
          rows={2}
          value={value.notes ?? ''}
          onChange={(e) => onPatch({ notes: e.target.value })}
          className="w-full rounded-xl border border-slate-200 bg-slate-50 px-3 py-2 text-[12.5px]"
          placeholder="Optional operator notes attached to the declaration."
        />
      </div>
    </div>
  )
}

/* ---------------- Step 4: review ---------------- */
function ReviewStep({
  value,
  invoiceTotal,
  carrierHint,
}: {
  value: OrderCustomsPayload
  invoiceTotal: number
  carrierHint?: { title: string; body: string; docsUrl: string }
}) {
  return (
    <div className="space-y-4">
      <h3 className="text-[15px] font-semibold text-slate-950">Review</h3>

      <div className="grid grid-cols-2 gap-3 rounded-2xl border border-slate-200 bg-slate-50 p-3">
        <Kv label="Incoterms">{value.incoterms ?? '—'}</Kv>
        <Kv label="Reason">{value.reasonForExport ?? '—'}</Kv>
        <Kv label="Currency">{value.currency ?? '—'}</Kv>
        <Kv label="Weight unit">{value.weightUnit ?? 'LB'}</Kv>
      </div>

      <div>
        <div className="mb-1 text-[10.5px] font-bold uppercase tracking-[0.14em] text-slate-400">
          Commodities ({(value.items ?? []).length})
        </div>
        <div className="rounded-2xl border border-slate-200 bg-white">
          {(value.items ?? []).map((item, idx) => (
            <div
              key={idx}
              className="grid grid-cols-6 gap-2 border-b border-slate-100 px-3 py-2 text-[11.5px] last:border-b-0"
            >
              <div className="col-span-3 font-semibold text-slate-950">
                {item.description || '—'}
              </div>
              <div className="text-slate-500">HS {item.hsCode || '—'}</div>
              <div className="text-slate-500">×{item.quantity}</div>
              <div className="text-right tabular-nums text-slate-700">
                {((item.quantity || 0) * (item.unitValue || 0)).toFixed(2)}
              </div>
            </div>
          ))}
          <div className="flex items-center justify-end gap-2 px-3 py-2 text-[11.5px] font-semibold">
            <span className="text-slate-500">Total:</span>
            <span className="tabular-nums text-slate-950">
              {invoiceTotal.toFixed(2)} {value.currency ?? ''}
            </span>
          </div>
        </div>
      </div>

      {carrierHint ? (
        <div className="rounded-2xl border border-sky-200 bg-sky-50 p-3">
          <div className="flex items-start gap-2">
            <FiInfo className="mt-0.5 h-4 w-4 shrink-0 text-sky-600" />
            <div>
              <div className="text-[12px] font-semibold text-sky-900">{carrierHint.title}</div>
              <p className="mt-1 text-[11.5px] leading-4 text-sky-800">{carrierHint.body}</p>
              <a
                href={carrierHint.docsUrl}
                target="_blank"
                rel="noreferrer"
                className="mt-1 inline-block text-[11px] font-semibold text-sky-700 underline hover:text-sky-900"
              >
                Docs →
              </a>
            </div>
          </div>
        </div>
      ) : null}
    </div>
  )
}

function Kv({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div>
      <div className="text-[10px] font-bold uppercase tracking-[0.14em] text-slate-400">
        {label}
      </div>
      <div className="mt-0.5 text-[12.5px] font-semibold text-slate-950">{children}</div>
    </div>
  )
}
