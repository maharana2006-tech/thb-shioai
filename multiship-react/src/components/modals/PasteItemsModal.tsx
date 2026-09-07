import { useMemo, useRef, useState } from 'react'
import { FiClipboard, FiX } from 'react-icons/fi'
import { useFocusTrap } from '../../hooks/useFocusTrap'
import { useModalDismiss } from '../../hooks/useModalDismiss'

/** One parsed commodity line — strings, matching the form's ItemRow shape. */
export interface PastedItem {
  description: string
  sku: string
  hsCode: string
  countryOfOrigin: string
  quantity: string
  unitValue: string
  weight: string
}

interface PasteItemsModalProps {
  onImport: (rows: PastedItem[]) => void
  onClose: () => void
}

const COLUMNS = ['description', 'sku', 'hsCode', 'countryOfOrigin', 'quantity', 'unitValue', 'weight'] as const

/** Header row detection: any cell naming a known column. */
const HEADER_RE = /^(description|desc|sku|hs ?code|hs|origin|country|qty|quantity|unit ?(value|price)|price|weight|wt)$/i

/**
 * Parse spreadsheet text (tab-separated from Excel/Sheets, or CSV) into
 * commodity rows. Column order is fixed and stated in the dialog:
 * description, SKU, HS code, origin, qty, unit value, weight. Trailing
 * columns may be omitted; a header row is skipped.
 */
export function parsePastedItems(text: string): { rows: PastedItem[]; skipped: number } {
  const lines = text.split(/\r?\n/).map((l) => l.trimEnd()).filter((l) => l.trim().length > 0)
  const tab = lines.some((l) => l.includes('\t'))
  const split = (l: string) =>
    tab
      ? l.split('\t')
      : (l.match(/("([^"]|"")*"|[^,]*)(,|$)/g) || []).map((c) => c.replace(/,$/, '').replace(/^"|"$/g, '').replace(/""/g, '"')).filter((_, i, a) => i < a.length - 1 || _ !== '')
  const rows: PastedItem[] = []
  let skipped = 0
  lines.forEach((line, i) => {
    const cells = split(line).map((c) => c.trim())
    if (i === 0 && cells.some((c) => HEADER_RE.test(c))) return
    if (!cells[0]) { skipped++; return }
    const row = {} as Record<(typeof COLUMNS)[number], string>
    COLUMNS.forEach((key, idx) => { row[key] = cells[idx] ?? '' })
    row.countryOfOrigin = row.countryOfOrigin.toUpperCase()
    row.quantity = row.quantity || '1'
    rows.push(row)
  })
  return { rows, skipped }
}

/**
 * Bulk commodity entry — paste rows straight from a spreadsheet instead of
 * typing 100+ lines one field at a time (the single biggest time cost in
 * the 45-package / 120-line stress test).
 */
export default function PasteItemsModal({ onImport, onClose }: PasteItemsModalProps) {
  const dialogRef = useRef<HTMLDivElement>(null)
  const [text, setText] = useState('')
  useFocusTrap(true, dialogRef)
  useModalDismiss(true, dialogRef, onClose)
  const parsed = useMemo(() => parsePastedItems(text), [text])

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-slate-950/50 p-4 backdrop-blur-sm"
      role="dialog"
      aria-modal="true"
      aria-label="Paste commodity lines from a spreadsheet"
      onClick={onClose}
    >
      <div
        ref={dialogRef}
        className="flex max-h-[90vh] w-full max-w-2xl flex-col overflow-hidden rounded-2xl border border-slate-200 bg-white shadow-[0_30px_80px_rgba(15,23,42,0.35)]"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex items-start justify-between gap-3 border-b border-slate-100 px-5 py-4">
          <div>
            <p className="text-[11px] font-semibold uppercase tracking-[0.16em] text-[#412d15]">Bulk entry</p>
            <h3 className="mt-1 text-base font-semibold text-[#1f150c]">Paste commodity lines</h3>
            <p className="mt-1 text-xs leading-5 text-slate-500">
              Copy rows from Excel or Sheets and paste them here. Columns, in order:
              <span className="font-mono"> description · SKU · HS code · origin · qty · unit value · weight</span>.
              Trailing columns may be left out; a header row is ignored.
            </p>
          </div>
          <button type="button" onClick={onClose} className="rounded-lg border border-slate-200 bg-white p-2 text-slate-500 hover:bg-slate-50" aria-label="Close">
            <FiX className="h-4 w-4" />
          </button>
        </div>
        <div className="min-h-0 flex-1 overflow-y-auto px-5 py-4">
          <textarea
            autoFocus
            value={text}
            onChange={(e) => setText(e.target.value)}
            spellCheck={false}
            placeholder={'Leather wallets\tSKU-1\t4202.31.00\tUS\t6\t28.00\t0.30\nCanvas belts\tSKU-2\t4203.30.10\tMX\t4\t12.50\t0.30'}
            className="h-56 w-full resize-y rounded-xl border border-[#e3d9c4] bg-[#fdfbf6] p-3 font-mono text-[12px] leading-5 text-[#1f150c] focus:outline-none focus:ring-2 focus:ring-[#412d15]/20"
          />
          {parsed.rows.length ? (
            <div className="mt-3 overflow-x-auto rounded-xl border border-[#e3d9c4]">
              <table className="w-full text-[11.5px]">
                <thead className="bg-[#faf7f0] text-[10px] uppercase tracking-[0.1em] text-[#6b5c42]">
                  <tr>{['Description', 'SKU', 'HS', 'Origin', 'Qty', 'Unit', 'Wt'].map((h) => <th key={h} className="px-2 py-1 text-left font-bold">{h}</th>)}</tr>
                </thead>
                <tbody>
                  {parsed.rows.slice(0, 5).map((r, i) => (
                    <tr key={i} className="border-t border-[#f2ecdf]">
                      <td className="px-2 py-1">{r.description}</td><td className="px-2 py-1 font-mono">{r.sku}</td>
                      <td className="px-2 py-1 font-mono">{r.hsCode}</td><td className="px-2 py-1 font-mono">{r.countryOfOrigin}</td>
                      <td className="px-2 py-1 tabular-nums">{r.quantity}</td><td className="px-2 py-1 tabular-nums">{r.unitValue}</td>
                      <td className="px-2 py-1 tabular-nums">{r.weight}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
              {parsed.rows.length > 5 ? <p className="px-2 py-1 text-[11px] text-slate-500">… and {parsed.rows.length - 5} more</p> : null}
            </div>
          ) : null}
        </div>
        <div className="flex items-center justify-between gap-2 border-t border-slate-100 px-5 py-3">
          <p className="text-[12px] text-slate-500">
            {parsed.rows.length} line{parsed.rows.length === 1 ? '' : 's'} ready
            {parsed.skipped ? ` · ${parsed.skipped} skipped (no description)` : ''}
          </p>
          <div className="flex gap-2">
            <button type="button" onClick={onClose} className="rounded-xl border border-[#e3d9c4] bg-white px-3.5 py-2 text-[13px] font-semibold text-[#5a4526] hover:bg-[#faf7f0]">Cancel</button>
            <button
              type="button"
              disabled={!parsed.rows.length}
              onClick={() => { onImport(parsed.rows); onClose() }}
              className="inline-flex items-center gap-1.5 rounded-xl bg-[#1f150c] px-4 py-2 text-[13px] font-semibold text-[#f4eede] hover:bg-[#412d15] disabled:cursor-not-allowed disabled:bg-[#dcd4c4]"
            >
              <FiClipboard className="h-3.5 w-3.5" /> Add {parsed.rows.length || ''} line{parsed.rows.length === 1 ? '' : 's'}
            </button>
          </div>
        </div>
      </div>
    </div>
  )
}
