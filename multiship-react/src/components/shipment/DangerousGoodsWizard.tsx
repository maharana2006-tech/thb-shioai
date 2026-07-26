import { useMemo, useState } from 'react'
import {
  FiAlertTriangle,
  FiCheckCircle,
  FiPlus,
  FiSearch,
  FiTrash2,
  FiX,
} from 'react-icons/fi'
import {
  dgService,
  type DangerousCommodity,
  type DangerousGoodsBlock,
  type UnNumberEntry,
} from '../../api/dgService'

/**
 * Dangerous goods declaration wizard. Full-screen modal in the
 * NewShipmentPage flow — mirrors the CustomsWizard scaffolding from
 * Sprint 8 (parent-owned value + onChange, onComplete on save,
 * onCancel to dismiss).
 *
 * <p>Regulation set + emergency contact + signatory are declared once
 * for the whole shipment; the commodities section lets the operator
 * add multiple lines with UN number autocomplete backed by the
 * curated {@code /api/v1/dg/un/search} endpoint.
 *
 * <p>Client-side validation checks the fields the backend also checks
 * so submit doesn't need a round-trip to know the block is complete.
 * Server errors from the actual generate-label call surface as toast
 * (owned by the parent, not the wizard).
 */
export interface DangerousGoodsWizardProps {
  value: DangerousGoodsBlock | null
  onChange: (next: DangerousGoodsBlock) => void
  onComplete: (payload: DangerousGoodsBlock) => void
  onCancel: () => void
}

const REGULATION_SETS = [
  { code: 'IATA', label: 'IATA — International air', hint: 'IATA Dangerous Goods Regulations. Air freight.' },
  { code: 'ADR', label: 'ADR — European road', hint: 'Accord européen. EU / UK road.' },
  { code: 'DOT', label: 'DOT — US road / ground', hint: 'US DOT 49 CFR. US road / ground.' },
] as const

const PACKING_GROUPS = ['I', 'II', 'III'] as const
const QUANTITY_UNITS = ['KG', 'G', 'L', 'ML', 'PCS'] as const
const HAZARD_CLASSES = ['1', '2.1', '2.2', '2.3', '3', '4.1', '4.2', '4.3',
  '5.1', '5.2', '6.1', '6.2', '7', '8', '9'] as const

function blankCommodity(): DangerousCommodity {
  return {
    unNumber: '',
    properShippingName: '',
    hazardClass: '',
    packingGroup: null,
    quantity: 0,
    quantityUnit: 'KG',
    packageCount: 1,
    limitedQuantity: false,
  }
}

function blankBlock(): DangerousGoodsBlock {
  return {
    regulationSet: 'IATA',
    accessibility: 'INACCESSIBLE',
    emergencyContactName: '',
    emergencyContactPhone: '',
    signatoryName: '',
    signatoryTitle: '',
    commodities: [blankCommodity()],
  }
}

export default function DangerousGoodsWizard({
  value,
  onChange,
  onComplete,
  onCancel,
}: DangerousGoodsWizardProps) {
  const current = value ?? blankBlock()

  const errors = useMemo(() => validate(current), [current])
  const canSave = errors.length === 0

  const update = (patch: Partial<DangerousGoodsBlock>) =>
    onChange({ ...current, ...patch })

  const patchCommodity = (idx: number, patch: Partial<DangerousCommodity>) =>
    onChange({
      ...current,
      commodities: current.commodities.map((c, i) => (i === idx ? { ...c, ...patch } : c)),
    })

  const addCommodity = () =>
    onChange({ ...current, commodities: [...current.commodities, blankCommodity()] })

  const removeCommodity = (idx: number) =>
    onChange({
      ...current,
      commodities: current.commodities.filter((_, i) => i !== idx),
    })

  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-label="Dangerous goods declaration"
      className="fixed inset-0 z-50 flex items-center justify-center bg-slate-950/45 p-4"
      onClick={onCancel}
    >
      <div
        className="flex h-[min(760px,92vh)] w-full max-w-[820px] flex-col overflow-hidden rounded-2xl border border-slate-200 bg-white shadow-[0_30px_80px_rgba(15,23,42,0.35)]"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex items-start justify-between gap-3 border-b border-slate-100 px-5 py-4">
          <div>
            <p className="inline-flex items-center gap-1 text-[10.5px] font-bold uppercase tracking-[0.16em] text-amber-700">
              <FiAlertTriangle className="h-3 w-3" /> Dangerous goods
            </p>
            <h3 className="mt-1 text-[15px] font-semibold text-slate-950">
              Declare a hazmat shipment
            </h3>
            <p className="mt-1 text-[11.5px] text-slate-500">
              Fill the regulation, 24/7 emergency contact and signatory. Add every commodity
              — carriers reject the shipment if any UN number is missing.
            </p>
          </div>
          <button
            type="button"
            onClick={onCancel}
            aria-label="Close"
            className="inline-flex h-8 w-8 items-center justify-center rounded-lg border border-slate-200 bg-white text-slate-500 transition hover:bg-slate-50"
          >
            <FiX className="h-3.5 w-3.5" />
          </button>
        </div>

        <div className="flex-1 space-y-4 overflow-y-auto px-5 py-4">
          {/* Regulation set */}
          <Section title="Regulation set">
            <div className="grid grid-cols-3 gap-2">
              {REGULATION_SETS.map((r) => (
                <button
                  key={r.code}
                  type="button"
                  onClick={() => update({ regulationSet: r.code })}
                  className={`rounded-xl border p-2.5 text-left transition ${
                    current.regulationSet === r.code
                      ? 'border-slate-950 bg-slate-50 ring-1 ring-slate-950'
                      : 'border-slate-200 hover:bg-slate-50'
                  }`}
                >
                  <p className="text-[12px] font-semibold text-slate-950">{r.label}</p>
                  <p className="mt-0.5 text-[10.5px] text-slate-500">{r.hint}</p>
                </button>
              ))}
            </div>
          </Section>

          {/* Accessibility + cargo aircraft */}
          <Section title="Accessibility">
            <div className="flex flex-wrap items-center gap-3">
              <div className="inline-flex rounded-lg border border-slate-200 bg-white p-0.5">
                {(['ACCESSIBLE', 'INACCESSIBLE'] as const).map((a) => (
                  <button
                    key={a}
                    type="button"
                    onClick={() => update({ accessibility: a })}
                    className={`rounded-md px-2.5 py-1 text-[11px] font-semibold transition ${
                      current.accessibility === a
                        ? 'bg-slate-950 text-white'
                        : 'text-slate-600 hover:bg-slate-50'
                    }`}
                  >
                    {a === 'ACCESSIBLE' ? 'Accessible' : 'Inaccessible'}
                  </button>
                ))}
              </div>
              <label className="inline-flex items-center gap-1.5 text-[11.5px] text-slate-700">
                <input
                  type="checkbox"
                  checked={Boolean(current.cargoAircraftOnly)}
                  onChange={(e) => update({ cargoAircraftOnly: e.target.checked })}
                  className="h-3.5 w-3.5"
                />
                Cargo aircraft only
              </label>
            </div>
          </Section>

          {/* Emergency contact + signatory */}
          <Section title="Emergency contact (24/7)">
            <div className="grid grid-cols-2 gap-2">
              <Field label="Contact name" required>
                <input
                  className={inputCls}
                  value={current.emergencyContactName}
                  onChange={(e) => update({ emergencyContactName: e.target.value })}
                  placeholder="Chem Response Ltd"
                />
              </Field>
              <Field label="Contact phone" required>
                <input
                  className={inputCls}
                  value={current.emergencyContactPhone}
                  onChange={(e) => update({ emergencyContactPhone: e.target.value })}
                  placeholder="+1-800-424-9300"
                />
              </Field>
              <Field label="Response contract (optional)">
                <input
                  className={inputCls}
                  value={current.emergencyResponseContract ?? ''}
                  onChange={(e) => update({ emergencyResponseContract: e.target.value })}
                  placeholder="CHEMTREC / NCEC contract #"
                />
              </Field>
            </div>
          </Section>

          <Section title="Signatory">
            <div className="grid grid-cols-2 gap-2">
              <Field label="Signatory name" required>
                <input
                  className={inputCls}
                  value={current.signatoryName}
                  onChange={(e) => update({ signatoryName: e.target.value })}
                  placeholder="Jane Doe"
                />
              </Field>
              <Field label="Title">
                <input
                  className={inputCls}
                  value={current.signatoryTitle ?? ''}
                  onChange={(e) => update({ signatoryTitle: e.target.value })}
                  placeholder="Compliance Officer"
                />
              </Field>
            </div>
          </Section>

          {/* Commodities */}
          <Section title={`Commodities (${current.commodities.length})`}>
            <div className="space-y-2">
              {current.commodities.map((c, idx) => (
                <CommodityRow
                  key={idx}
                  value={c}
                  onChange={(patch) => patchCommodity(idx, patch)}
                  onRemove={current.commodities.length > 1 ? () => removeCommodity(idx) : null}
                />
              ))}
              <button
                type="button"
                onClick={addCommodity}
                className="inline-flex items-center gap-1.5 rounded-lg border border-dashed border-slate-300 bg-white px-2.5 py-1.5 text-[11px] font-semibold text-slate-600 hover:bg-slate-50"
              >
                <FiPlus className="h-3 w-3" />
                Add commodity
              </button>
            </div>
          </Section>

          {errors.length > 0 ? (
            <div className="rounded-xl border border-amber-200 bg-amber-50 px-3 py-2 text-[11.5px] text-amber-800">
              <p className="font-semibold">Fix before saving:</p>
              <ul className="mt-1 list-inside list-disc space-y-0.5">
                {errors.map((e, i) => (
                  <li key={i}>{e}</li>
                ))}
              </ul>
            </div>
          ) : null}
        </div>

        <div className="flex items-center justify-between gap-2 border-t border-slate-100 px-5 py-3">
          <p className="text-[10.5px] text-slate-500">
            {canSave ? (
              <span className="inline-flex items-center gap-1 text-emerald-700">
                <FiCheckCircle className="h-3 w-3" /> Ready to attach to the shipment.
              </span>
            ) : (
              `${errors.length} issue(s) to fix.`
            )}
          </p>
          <div className="flex items-center gap-2">
            <button
              type="button"
              onClick={onCancel}
              className="inline-flex items-center rounded-lg border border-slate-200 bg-white px-3 py-1.5 text-[12px] font-semibold text-slate-700 hover:bg-slate-50"
            >
              Cancel
            </button>
            <button
              type="button"
              disabled={!canSave}
              onClick={() => onComplete(current)}
              className="inline-flex items-center rounded-lg bg-slate-950 px-3 py-1.5 text-[12px] font-semibold text-white transition hover:bg-slate-800 disabled:opacity-40"
            >
              Attach to shipment
            </button>
          </div>
        </div>
      </div>
    </div>
  )
}

/* -------------------------- Commodity row -------------------------- */

function CommodityRow({
  value,
  onChange,
  onRemove,
}: {
  value: DangerousCommodity
  onChange: (patch: Partial<DangerousCommodity>) => void
  onRemove: (() => void) | null
}) {
  const [suggestions, setSuggestions] = useState<UnNumberEntry[]>([])
  const [open, setOpen] = useState(false)

  const runSearch = async (q: string) => {
    const hits = await dgService.search(q)
    setSuggestions(hits)
    setOpen(hits.length > 0)
  }

  const pick = (entry: UnNumberEntry) => {
    onChange({
      unNumber: entry.unNumber,
      properShippingName: entry.properShippingName,
      hazardClass: entry.hazardClass,
      packingGroup: entry.defaultPackingGroup ?? value.packingGroup ?? null,
    })
    setOpen(false)
  }

  return (
    <div className="rounded-xl border border-slate-200 bg-slate-50/40 p-3">
      <div className="flex items-start justify-between gap-2">
        <div className="min-w-0 flex-1">
          <div className="grid grid-cols-2 gap-2">
            <div className="relative">
              <Field label="UN number" required>
                <div className="relative">
                  <input
                    className={inputCls}
                    value={value.unNumber}
                    onChange={(e) => {
                      const next = e.target.value.toUpperCase()
                      onChange({ unNumber: next })
                      void runSearch(next)
                    }}
                    onFocus={() => value.unNumber && void runSearch(value.unNumber)}
                    onBlur={() => setTimeout(() => setOpen(false), 150)}
                    placeholder="UN3480"
                  />
                  <FiSearch className="pointer-events-none absolute right-2 top-1/2 h-3 w-3 -translate-y-1/2 text-slate-400" />
                </div>
              </Field>
              {open ? (
                <ul className="absolute z-10 mt-0.5 max-h-56 w-full overflow-y-auto rounded-lg border border-slate-200 bg-white shadow-lg">
                  {suggestions.map((s) => (
                    <li key={s.unNumber}>
                      <button
                        type="button"
                        onMouseDown={(e) => e.preventDefault()}
                        onClick={() => pick(s)}
                        className="flex w-full items-start justify-between gap-2 px-2.5 py-1.5 text-left text-[11.5px] hover:bg-slate-50"
                      >
                        <div>
                          <p className="font-mono font-semibold text-slate-950">{s.unNumber}</p>
                          <p className="text-slate-600">{s.properShippingName}</p>
                        </div>
                        <span className="whitespace-nowrap rounded-full bg-slate-100 px-1.5 py-0.5 text-[9.5px] font-semibold text-slate-500">
                          class {s.hazardClass}
                        </span>
                      </button>
                    </li>
                  ))}
                </ul>
              ) : null}
            </div>
            <Field label="Proper shipping name" required>
              <input
                className={inputCls}
                value={value.properShippingName}
                onChange={(e) => onChange({ properShippingName: e.target.value })}
                placeholder="Lithium ion batteries"
              />
            </Field>
            <Field label="Hazard class" required>
              <select
                className={inputCls}
                value={value.hazardClass}
                onChange={(e) => onChange({ hazardClass: e.target.value })}
              >
                <option value="">—</option>
                {HAZARD_CLASSES.map((c) => (
                  <option key={c} value={c}>
                    {c}
                  </option>
                ))}
              </select>
            </Field>
            <Field label="Packing group">
              <select
                className={inputCls}
                value={value.packingGroup ?? ''}
                onChange={(e) => onChange({ packingGroup: e.target.value || null })}
              >
                <option value="">— (Class 1 / 7)</option>
                {PACKING_GROUPS.map((g) => (
                  <option key={g} value={g}>
                    {g}
                  </option>
                ))}
              </select>
            </Field>
            <Field label="Quantity" required>
              <input
                className={inputCls}
                type="number"
                min="0"
                step="0.01"
                value={value.quantity || ''}
                onChange={(e) => onChange({ quantity: Number(e.target.value) })}
              />
            </Field>
            <Field label="Unit">
              <select
                className={inputCls}
                value={value.quantityUnit}
                onChange={(e) => onChange({ quantityUnit: e.target.value })}
              >
                {QUANTITY_UNITS.map((u) => (
                  <option key={u} value={u}>
                    {u}
                  </option>
                ))}
              </select>
            </Field>
          </div>
          <label className="mt-2 inline-flex items-center gap-1.5 text-[10.5px] text-slate-600">
            <input
              type="checkbox"
              checked={Boolean(value.limitedQuantity)}
              onChange={(e) => onChange({ limitedQuantity: e.target.checked })}
              className="h-3 w-3"
            />
            Ships under Limited Quantity rules
          </label>
        </div>
        {onRemove ? (
          <button
            type="button"
            onClick={onRemove}
            aria-label="Remove commodity"
            className="inline-flex h-7 w-7 shrink-0 items-center justify-center rounded-lg border border-slate-200 bg-white text-slate-500 hover:bg-rose-50 hover:text-rose-600"
          >
            <FiTrash2 className="h-3 w-3" />
          </button>
        ) : null}
      </div>
    </div>
  )
}

/* -------------------------- Layout helpers -------------------------- */

const inputCls =
  'w-full rounded-lg border border-slate-200 bg-white px-2.5 py-1.5 text-[12px] text-slate-950 outline-none focus:border-slate-950 focus:ring-1 focus:ring-slate-950'

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <section>
      <h4 className="mb-1.5 text-[10.5px] font-bold uppercase tracking-[0.14em] text-slate-500">
        {title}
      </h4>
      {children}
    </section>
  )
}

function Field({
  label,
  required,
  children,
}: {
  label: string
  required?: boolean
  children: React.ReactNode
}) {
  return (
    <label className="block">
      <span className="mb-0.5 block text-[10.5px] font-semibold text-slate-600">
        {label} {required ? <span className="text-rose-500">*</span> : null}
      </span>
      {children}
    </label>
  )
}

/* -------------------------- Validation -------------------------- */

const UN_NUMBER_RE = /^UN\d{4}$/
const HAZARD_CLASS_RE = /^[1-9](\.\d{1,3})?$/

function validate(block: DangerousGoodsBlock): string[] {
  const out: string[] = []
  if (!block.emergencyContactName?.trim() || !block.emergencyContactPhone?.trim()) {
    out.push('24/7 emergency contact name + phone are required.')
  }
  if (!block.signatoryName?.trim()) {
    out.push('Legal signatory name is required.')
  }
  if (!block.commodities?.length) {
    out.push('Add at least one commodity.')
  }
  block.commodities.forEach((c, i) => {
    const label = `Commodity #${i + 1}`
    if (!UN_NUMBER_RE.test((c.unNumber || '').trim().toUpperCase())) {
      out.push(`${label}: UN number must match UN\\d{4} (e.g. UN3480).`)
    }
    if (!c.properShippingName?.trim()) {
      out.push(`${label}: proper shipping name is required.`)
    }
    if (!HAZARD_CLASS_RE.test((c.hazardClass || '').trim())) {
      out.push(`${label}: hazard class must be 1-9, optionally a subclass like 4.1.`)
    }
    if (!c.quantity || c.quantity <= 0) {
      out.push(`${label}: quantity must be > 0.`)
    }
  })
  return out
}
