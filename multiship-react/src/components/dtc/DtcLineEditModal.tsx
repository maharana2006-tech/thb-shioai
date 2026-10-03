import { useEffect, useRef, useState } from 'react'
import { FiLink, FiX } from 'react-icons/fi'
import { dtcService, type DtcLineEdit, type DtcOrder } from '../../api/dtcService'
import { useFocusTrap } from '../../hooks/useFocusTrap'
import { notify } from '../../utils/notify'

/**
 * Correct a D2C line that failed before any label order existed (bad address, wrong
 * ship-via, no weight…) — or link it to an order already labelled by hand. Only what
 * changed is sent; a cleared box clears the field. The next Generate builds from this.
 */

type TextKey = 'shipName' | 'shipAttn' | 'shipAddr1' | 'shipAddr2' | 'shipAddr3' | 'shipToCity'
  | 'shipToState' | 'shipToZip' | 'shipToCountryCode' | 'phone' | 'email' | 'goodsDesc' | 'shipViaCode'

const TEXT_FIELDS: { key: TextKey; label: string; wide?: boolean; placeholder?: string }[] = [
  { key: 'shipName', label: 'Name' },
  { key: 'shipAttn', label: 'Attention / company' },
  { key: 'shipAddr1', label: 'Address line 1', wide: true },
  { key: 'shipAddr2', label: 'Address line 2' },
  { key: 'shipAddr3', label: 'Address line 3' },
  { key: 'shipToCity', label: 'City' },
  { key: 'shipToState', label: 'State' },
  { key: 'shipToZip', label: 'Postal code' },
  { key: 'shipToCountryCode', label: 'Country', placeholder: 'US' },
  { key: 'phone', label: 'Phone' },
  { key: 'email', label: 'Email' },
  { key: 'goodsDesc', label: 'Goods description', wide: true },
  { key: 'shipViaCode', label: 'Ship-via code' },
]

const INPUT = 'w-full rounded-lg border border-[#e3d9c4] bg-white px-2.5 py-1.5 text-[12.5px] text-[#1f150c] outline-none transition focus:border-[#412d15] focus:ring-4 focus:ring-[#f0e9d8]'
const LABEL = 'mb-1 block text-[10.5px] font-semibold uppercase tracking-[0.06em] text-[#8a7a5a]'

const toNumber = (v: string): number | null => (v.trim() === '' ? null : Number(v))

export default function DtcLineEditModal({ line, onClose, onSaved }: {
  line: DtcOrder
  onClose: () => void
  onSaved: () => void
}) {
  const dialogRef = useRef<HTMLDivElement>(null)
  useFocusTrap(true, dialogRef)
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose() }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])

  const [text, setText] = useState<Record<TextKey, string>>(() =>
    Object.fromEntries(TEXT_FIELDS.map((f) => [f.key, line[f.key] ?? ''])) as Record<TextKey, string>)
  const [weight, setWeight] = useState(line.weight != null ? String(line.weight) : '')
  const [unitValue, setUnitValue] = useState(line.unitValue != null ? String(line.unitValue) : '')
  const [adopt, setAdopt] = useState('')
  const [saving, setSaving] = useState<'edit' | 'adopt' | null>(null)

  /** Only the fields that changed — the server treats a missing field as "keep". */
  const changes = (): DtcLineEdit => {
    const body: DtcLineEdit = {}
    for (const f of TEXT_FIELDS) {
      if (text[f.key].trim() !== (line[f.key] ?? '').trim()) body[f.key] = text[f.key].trim()
    }
    const w = toNumber(weight)
    if (w !== null && w !== line.weight) body.weight = w
    const v = toNumber(unitValue)
    if (v !== null && v !== line.unitValue) body.unitValue = v
    return body
  }

  const send = async (kind: 'edit' | 'adopt', body: DtcLineEdit, done: string) => {
    setSaving(kind)
    try {
      await dtcService.editLine(String(line.batchId), line.id, line.tenantId, body)
      notify.success(done)
      onSaved()
    } catch (e) {
      notify.apiError(e, kind === 'adopt' ? 'Could not link the order.' : 'Could not save the line.')
    } finally {
      setSaving(null)
    }
  }

  const save = () => {
    const body = changes()
    if (Object.keys(body).length === 0) { onClose(); return }
    if (body.weight !== undefined && !(Number(body.weight) > 0)) { notify.info('Weight must be more than 0.'); return }
    void send('edit', body, `Line ${line.toteNumber ?? line.id} updated — the next Generate uses the new details.`)
  }

  const link = () => {
    const n = Number(adopt.trim().replace(/^#/, ''))
    if (!Number.isInteger(n) || n <= 0) { notify.info('Enter the order number, e.g. 906982.'); return }
    void send('adopt', { adoptOrderNo: n }, `Line ${line.toteNumber ?? line.id} linked to order ${n}.`)
  }

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-slate-950/45 p-4 backdrop-blur-sm"
      role="dialog"
      aria-modal="true"
      aria-label={`Edit shipment line ${line.toteNumber ?? ''}`}
      onClick={onClose}
    >
      <div
        ref={dialogRef}
        className="flex max-h-[90vh] w-full max-w-2xl flex-col overflow-hidden rounded-2xl border border-slate-200 bg-white shadow-[0_30px_80px_rgba(15,23,42,0.35)]"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex items-start justify-between gap-3 border-b border-slate-100 px-5 py-4">
          <div>
            <h3 className="text-[15px] font-semibold tracking-tight text-[#1f150c]">Edit line — Tote {line.toteNumber ?? '—'}</h3>
            <p className="mt-0.5 text-[12px] text-[#6b5c42]">Client {line.tenantId} · batch {line.batchId} · the next Generate builds the label from these details</p>
            {line.generatedMessage ? (
              <p className="mt-1.5 max-w-xl break-words text-[11.5px] text-red-700" title={line.generatedMessage}>{line.generatedMessage}</p>
            ) : null}
          </div>
          <button type="button" onClick={onClose} aria-label="Close" className="rounded-lg border border-[#e3d9c4] bg-white p-1.5 text-[#5a4526] transition hover:bg-[#faf7f0]">
            <FiX className="h-4 w-4" />
          </button>
        </div>

        <div className="grid gap-3 overflow-y-auto px-5 py-4 sm:grid-cols-2">
          {TEXT_FIELDS.map((f) => (
            <label key={f.key} className={`block ${f.wide ? 'sm:col-span-2' : ''}`}>
              <span className={LABEL}>{f.label}</span>
              <input
                value={text[f.key]}
                placeholder={f.placeholder}
                onChange={(e) => setText((t) => ({ ...t, [f.key]: e.target.value }))}
                className={INPUT}
              />
            </label>
          ))}
          <label className="block">
            <span className={LABEL}>Weight (lb)</span>
            <input type="number" min={0} step="0.01" value={weight} onChange={(e) => setWeight(e.target.value)} className={INPUT} />
          </label>
          <label className="block">
            <span className={LABEL}>Declared value (USD)</span>
            <input type="number" min={0} step="0.01" value={unitValue} onChange={(e) => setUnitValue(e.target.value)} className={INPUT} />
          </label>

          <div className="rounded-xl border border-dashed border-[#e3d9c4] bg-[#fcfaf5] p-3 sm:col-span-2">
            <p className="text-[12px] font-semibold text-[#1f150c]">Already labelled it by hand?</p>
            <p className="mt-0.5 text-[11px] text-[#8a7a5a]">Link the order from the manual shipment form instead — the line takes that order's label and won't be bought again.</p>
            <div className="mt-2 flex items-center gap-2">
              <input value={adopt} onChange={(e) => setAdopt(e.target.value)} placeholder="Order #, e.g. 906982" aria-label="Order number to link" className={`${INPUT} max-w-[14rem]`} />
              <button
                type="button"
                onClick={link}
                disabled={!!saving || !adopt.trim()}
                className="inline-flex items-center gap-1 rounded-lg border border-[#e3d9c4] bg-white px-2.5 py-1.5 text-[12px] font-semibold text-[#5a4526] transition hover:bg-[#faf7f0] disabled:cursor-not-allowed disabled:opacity-50"
              >
                <FiLink className="h-3.5 w-3.5" /> {saving === 'adopt' ? 'Linking…' : 'Link order'}
              </button>
            </div>
          </div>
        </div>

        <div className="flex items-center justify-end gap-2 border-t border-slate-100 bg-[#fcfaf5] px-5 py-3">
          <button type="button" onClick={onClose} className="rounded-lg border border-[#e3d9c4] bg-white px-3 py-1.5 text-[12px] font-semibold text-[#5a4526] transition hover:bg-[#faf7f0]">Cancel</button>
          <button
            type="button"
            onClick={save}
            disabled={!!saving}
            className="rounded-lg bg-[#1f150c] px-3.5 py-1.5 text-[12px] font-semibold text-[#f4eede] shadow-sm transition hover:bg-[#412d15] disabled:cursor-not-allowed disabled:opacity-60"
          >
            {saving === 'edit' ? 'Saving…' : 'Save line'}
          </button>
        </div>
      </div>
    </div>
  )
}
