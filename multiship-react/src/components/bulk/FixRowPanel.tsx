import { useEffect, useMemo, useState } from 'react'
import { FiAlertTriangle, FiX } from 'react-icons/fi'
import type { OrderImportRow } from '../../api/orderImportService'
import { shippingConfigService, type ShipViaCode } from '../../api/shippingConfigService'
import { DH_COLUMNS, bucketRowErrors, fieldLabel, type DhColumn } from '../batchGrid'
import { STATE_CODE_OPTIONS } from '../../utils/stateCodes'

/** The import fields, grouped the way an order reads. orderRef stays out: it decides the grouping. */
const SECTIONS: { title: string; keys: string[] }[] = [
  { title: 'Recipient', keys: ['recipientName', 'recipientCompany', 'recipientPhone', 'recipientEmail'] },
  { title: 'Address', keys: ['addressLine1', 'addressLine2', 'city', 'state', 'postalCode', 'countryCode'] },
  { title: 'Shipping', keys: ['clientCode', 'billTo', 'warehouseCode', 'carrierCode', 'accountNumber', 'serviceType', 'packageType', 'reference'] },
  { title: 'Package', keys: ['weight', 'weightUnit', 'length', 'width', 'height', 'dimUnit'] },
  { title: 'Items & customs', keys: ['itemDescription', 'itemSku', 'itemQuantity', 'itemUnitValue', 'currency', 'incoterms', 'hsCode', 'countryOfOrigin'] },
]
const COL: Record<string, DhColumn> = Object.fromEntries(DH_COLUMNS.map((c) => [c.key, c]))
const UNMAPPED = /serviceType '([^']+)' is (?:not mapped|mapped, but not)/

const labelOf = (key: string) => fieldLabel(COL[key] ?? { key })

/** What the operator edits: the client's ship via code, not the carrier code a rule resolved it to. */
const draftOf = (row: OrderImportRow): Record<string, string> => {
  const raw = row as unknown as Record<string, unknown>
  const d: Record<string, string> = {}
  for (const s of SECTIONS) for (const k of s.keys) d[k] = raw[k] == null ? '' : String(raw[k])
  d.serviceType = row.shipViaCode ?? d.serviceType
  return d
}

/**
 * Fix one import row in place: its errors first, each next to its field, then
 * every field of the order grouped as it reads. Save re-validates on the
 * server; the page closes the panel once the row comes back clean.
 */
export default function FixRowPanel({
  row,
  saving,
  canMap,
  onSave,
  onMap,
  onClose,
}: {
  row: OrderImportRow
  saving: boolean
  /** May add a Shipping Service Mapping rule for an unmapped code. */
  canMap: boolean
  onSave: (edited: OrderImportRow) => void
  onMap: (code: string) => void
  onClose: () => void
}) {
  const initial = useMemo(() => draftOf(row), [row])
  const [draft, setDraft] = useState(initial)
  // A save or a new mapping comes back as a fresh row: start again from it.
  const [seen, setSeen] = useState(row)
  if (seen !== row) {
    setSeen(row)
    setDraft(initial)
  }

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose() }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])

  const client = (row.clientCode ?? '').trim().toUpperCase() || null
  const [codes, setCodes] = useState<ShipViaCode[] | null>(null)
  useEffect(() => {
    let alive = true
    shippingConfigService.shipViaCodes(client)
      .then((res) => { if (alive) setCodes((res.data ?? []).filter((c) => c.enabled)) })
      .catch(() => { if (alive) setCodes(null) })
    return () => { alive = false }
  }, [client])

  const { byField, rowLevel } = useMemo(() => bucketRowErrors(row.errors ?? []), [row.errors])
  const problems = Object.entries(byField).flatMap(([k, msgs]) => msgs.map((m) => ({ key: k, text: m })))
  const unmapped = (byField.serviceType ?? []).map((m) => m.match(UNMAPPED)).find(Boolean)?.[1] ?? null
  const dirty = Object.keys(draft).some((k) => draft[k] !== initial[k])

  const save = () => {
    const edited = { ...row } as unknown as Record<string, unknown>
    for (const k of Object.keys(draft)) {
      if (draft[k] === initial[k]) continue // untouched fields keep their stored value
      const v = draft[k].trim()
      const col = COL[k]
      edited[k] = col?.numeric ? (v === '' ? null : Number(v)) : col?.upper ? v.toUpperCase() : v
    }
    onSave(edited as unknown as OrderImportRow)
  }

  const field = (key: string) => {
    const errs = byField[key] ?? []
    const bad = errs.length > 0
    const set = (v: string) => setDraft((d) => ({ ...d, [key]: v }))
    const box = `w-full rounded-lg border bg-white px-2.5 py-1.5 text-[12.5px] text-[#1f150c] outline-none transition focus:ring-2 ${
      bad ? 'border-rose-300 focus:ring-rose-100' : 'border-[#e3d9c4] focus:border-[#cdbf9f] focus:ring-[#f4eede]'} ${COL[key]?.mono ? 'font-mono' : ''}`
    const id = `fix-${row.rowNumber}-${key}`
    const states = key === 'state' ? STATE_CODE_OPTIONS[(draft.countryCode || '').trim().toUpperCase()] : undefined
    let input: React.ReactNode
    if (key === 'serviceType' && codes && codes.length > 0) {
      const known = codes.some((c) => c.code.toUpperCase() === draft.serviceType.toUpperCase())
      input = (
        <select id={id} value={draft.serviceType} onChange={(e) => set(e.target.value)} className={box}>
          {!known ? <option value={draft.serviceType}>{draft.serviceType || '—'} (not mapped)</option> : null}
          {codes.map((c) => (
            <option key={`${c.code}-${c.clientCode ?? '*'}-${c.destination ?? ''}`} value={c.code}>
              {c.code} — {c.serviceName}{c.destination ? ` (${c.destination} only)` : ''}
            </option>
          ))}
        </select>
      )
    } else if (states) {
      const known = states.some((s) => s.code === draft.state.toUpperCase())
      input = (
        <select id={id} value={known ? draft.state.toUpperCase() : draft.state} onChange={(e) => set(e.target.value)} className={box}>
          {!known ? <option value={draft.state}>{draft.state || '—'}{draft.state ? ' (not valid)' : ''}</option> : null}
          {states.map((s) => <option key={s.code} value={s.code}>{s.code} — {s.label}</option>)}
        </select>
      )
    } else {
      input = (
        <input id={id} value={draft[key]} onChange={(e) => set(e.target.value)} inputMode={COL[key]?.numeric ? 'decimal' : undefined}
          type={COL[key]?.numeric ? 'number' : 'text'} step="any" className={box} />
      )
    }
    return (
      <div key={key} className={key === 'addressLine1' || key === 'itemDescription' || key === 'recipientName' ? 'sm:col-span-2' : ''}>
        <label htmlFor={id} className={`mb-0.5 block text-[10.5px] font-semibold uppercase tracking-[0.06em] ${bad ? 'text-rose-700' : 'text-[#8a7a5c]'}`}>
          {labelOf(key)}
        </label>
        {input}
        {errs.map((m) => <p key={m} className="mt-0.5 text-[11px] leading-snug text-rose-700">{m}</p>)}
        {key === 'serviceType' && unmapped && canMap ? (
          <button type="button" onClick={() => onMap(unmapped)}
            className="mt-1 rounded-md border border-[#e3d9c4] bg-white px-2 py-0.5 text-[11px] font-semibold text-[#5a4526] hover:bg-[#faf7f0]">
            Map {unmapped}…
          </button>
        ) : null}
      </div>
    )
  }

  return (
    <div className="fixed inset-0 z-50 flex justify-end bg-[#1f150c]/35" onClick={onClose}>
      <aside
        role="dialog"
        aria-modal="true"
        aria-label={`Fix row ${row.rowNumber}`}
        onClick={(e) => e.stopPropagation()}
        className="flex h-full w-full max-w-[460px] flex-col bg-[#fcfaf5] shadow-[-18px_0_50px_rgba(31,21,12,0.25)]"
      >
        <header className="flex items-start gap-3 border-b border-[#e3d9c4] bg-white px-4 py-3">
          <div className="min-w-0 flex-1">
            <h2 className="text-[15px] font-semibold text-[#1f150c]">Fix row {row.rowNumber}</h2>
            <p className="truncate text-[11.5px] text-[#6b5c42]">
              {[row.orderRef && `Order ${row.orderRef}`, client, row.recipientName].filter(Boolean).join(' · ')}
            </p>
          </div>
          <button type="button" onClick={onClose} aria-label="Close" className="rounded-lg p-1.5 text-[#5a4526] hover:bg-[#f4eede]">
            <FiX className="h-4 w-4" />
          </button>
        </header>

        <div className="flex-1 space-y-3 overflow-y-auto px-4 py-3">
          {problems.length + rowLevel.length > 0 ? (
            <section className="rounded-xl border border-rose-200 bg-rose-50 px-3 py-2" aria-label="Problems">
              <p className="mb-1 flex items-center gap-1.5 text-[12px] font-semibold text-rose-800">
                <FiAlertTriangle className="h-3.5 w-3.5" />
                {problems.length + rowLevel.length} to fix before this row can be labelled
              </p>
              <ul className="space-y-0.5 text-[11.5px] text-rose-800">
                {problems.map((p) => (
                  <li key={p.key + p.text}>
                    <button type="button" className="text-left hover:underline" onClick={() => document.getElementById(`fix-${row.rowNumber}-${p.key}`)?.focus()}>
                      <b>{labelOf(p.key)}:</b> {p.text}
                    </button>
                  </li>
                ))}
                {rowLevel.map((m) => <li key={m}>{m}</li>)}
              </ul>
            </section>
          ) : (
            <p className="rounded-xl border border-emerald-200 bg-emerald-50 px-3 py-2 text-[12px] font-semibold text-emerald-800">No errors on this row.</p>
          )}

          {SECTIONS.map((s) => {
            const hasErr = s.keys.some((k) => byField[k]?.length)
            return (
              <details key={s.title} open={hasErr || undefined} className="rounded-xl border border-[#e3d9c4] bg-white">
                <summary className={`cursor-pointer select-none px-3 py-2 text-[12px] font-semibold ${hasErr ? 'text-rose-800' : 'text-[#412d15]'}`}>
                  {s.title}{hasErr ? ' · needs fixing' : ''}
                </summary>
                <div className="grid grid-cols-1 gap-2.5 px-3 pb-3 sm:grid-cols-2">{s.keys.map(field)}</div>
              </details>
            )
          })}
        </div>

        <footer className="flex items-center justify-end gap-2 border-t border-[#e3d9c4] bg-white px-4 py-3">
          <button type="button" onClick={onClose} className="rounded-lg border border-[#e3d9c4] bg-white px-3 py-1.5 text-[12px] font-semibold text-[#5a4526] hover:bg-[#faf7f0]">
            Cancel
          </button>
          <button type="button" onClick={save} disabled={!dirty || saving}
            className="inline-flex items-center gap-1.5 rounded-lg bg-[#1f150c] px-3.5 py-1.5 text-[12px] font-semibold text-[#f4eede] hover:bg-[#412d15] disabled:cursor-not-allowed disabled:opacity-40">
            {saving ? <span className="inline-block h-3 w-3 animate-spin rounded-full border-2 border-[#f4eede]/40 border-t-[#f4eede]" /> : null}
            {saving ? 'Checking…' : 'Save & re-check'}
          </button>
        </footer>
      </aside>
    </div>
  )
}
