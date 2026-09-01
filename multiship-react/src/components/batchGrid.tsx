/* eslint-disable react-refresh/only-export-components */
// This file intentionally co-locates the shared cell component (GridCell)
// with its column model + error-bucketing helper. Splitting into two
// files just to appease react-refresh would touch every import site of
// EDIT_FIELD_KEYS / bucketRowErrors / DH_COLUMNS with no runtime benefit
// (HMR fast-refresh on a spreadsheet cell component isn't a common
// dev workflow here).
import { useEffect, useRef, useState } from 'react'

/**
 * Shared primitives for the import/API batch spreadsheet grid — the editable
 * cell, the column model, and the error→field bucketing. Extracted so both
 * the Import-history view (DataHistoryPage) and the API section (ApiBatchList)
 * can reuse them without a circular import.
 */

// Field keys that map a validation message to a specific cell so the offending
// input can go red; messages with no field prefix (group-level customs rules)
// stay row-level.
export const EDIT_FIELD_KEYS = [
  'orderRef', 'clientCode', 'billTo', 'warehouseCode',
  'recipientName', 'recipientCompany', 'recipientPhone', 'recipientEmail',
  'addressLine1', 'addressLine2', 'city', 'state', 'postalCode', 'countryCode',
  'carrierCode', 'accountNumber', 'serviceType', 'packageType',
  'weight', 'weightUnit', 'currency', 'reference',
  'itemDescription', 'itemSku', 'itemQuantity', 'itemUnitValue',
  'hsCode', 'countryOfOrigin',
] as const

/** Split a row's error strings into per-field buckets + row-level leftovers. */
export function bucketRowErrors(errors: string[]): {
  byField: Record<string, string[]>
  rowLevel: string[]
} {
  const byField: Record<string, string[]> = {}
  const rowLevel: string[] = []
  for (const msg of errors) {
    const first = msg.split(/[\s']/, 1)[0]
    if ((EDIT_FIELD_KEYS as readonly string[]).includes(first)) {
      ;(byField[first] ??= []).push(msg)
    } else {
      rowLevel.push(msg)
    }
  }
  return { byField, rowLevel }
}

/** Column model for the batch spreadsheet grid — every import field, in
 *  template order. `numeric`/`upper` shape how an edited value is written
 *  back; `w` is the cell input min-width. */
export type DhColumn = { key: string; mono?: boolean; upper?: boolean; numeric?: boolean; w: string }
export const DH_COLUMNS: DhColumn[] = [
  { key: 'orderRef', mono: true, w: 'w-24' },
  { key: 'clientCode', mono: true, upper: true, w: 'w-24' },
  { key: 'billTo', mono: true, upper: true, w: 'w-24' },
  { key: 'warehouseCode', mono: true, upper: true, w: 'w-24' },
  { key: 'recipientName', w: 'w-40' },
  { key: 'recipientCompany', w: 'w-40' },
  { key: 'recipientPhone', w: 'w-28' },
  { key: 'recipientEmail', w: 'w-44' },
  { key: 'addressLine1', w: 'w-48' },
  { key: 'addressLine2', w: 'w-40' },
  { key: 'city', w: 'w-32' },
  { key: 'state', mono: true, upper: true, w: 'w-16' },
  { key: 'postalCode', mono: true, w: 'w-24' },
  { key: 'countryCode', mono: true, upper: true, w: 'w-16' },
  { key: 'carrierCode', mono: true, upper: true, w: 'w-24' },
  { key: 'accountNumber', mono: true, w: 'w-32' },
  { key: 'serviceType', mono: true, w: 'w-28' },
  { key: 'packageType', mono: true, w: 'w-24' },
  { key: 'weight', numeric: true, w: 'w-16' },
  { key: 'weightUnit', mono: true, upper: true, w: 'w-16' },
  { key: 'currency', mono: true, upper: true, w: 'w-16' },
  { key: 'reference', mono: true, w: 'w-28' },
  { key: 'itemDescription', w: 'w-48' },
  { key: 'itemSku', mono: true, w: 'w-24' },
  { key: 'itemQuantity', numeric: true, w: 'w-16' },
  { key: 'itemUnitValue', numeric: true, w: 'w-20' },
  { key: 'hsCode', mono: true, w: 'w-24' },
  { key: 'countryOfOrigin', mono: true, upper: true, w: 'w-16' },
]

/**
 * A read-only grid cell that becomes an input on click. Shows the value as
 * plain text (red + tooltip when the field failed validation); clicking opens
 * an inline editor that commits on blur or Enter, cancels on Escape. Generated
 * rows are read-only. The commit fires once on exit, not per keystroke.
 */
export function GridCell({
  value,
  onCommit,
  bad = false,
  mono = false,
  errors,
  readOnly = false,
}: {
  value: string
  onCommit: (v: string) => void
  bad?: boolean
  mono?: boolean
  errors?: string[]
  readOnly?: boolean
}) {
  const [editing, setEditing] = useState(false)
  const [draft, setDraft] = useState(value)
  const inputRef = useRef<HTMLInputElement>(null)
  useEffect(() => {
    if (editing) { inputRef.current?.focus(); inputRef.current?.select() }
  }, [editing])
  const begin = () => { if (readOnly) return; setDraft(value); setEditing(true) }
  const commit = () => { setEditing(false); if (draft !== value) onCommit(draft) }
  const cancel = () => { setEditing(false); setDraft(value) }
  if (editing) {
    return (
      <input
        ref={inputRef}
        value={draft}
        onChange={(e) => setDraft(e.target.value)}
        onBlur={commit}
        // Select-all on focus, deterministically — the effect's select() can
        // race the mount and leave the caret at position 0, so typing INSERTED
        // before the old value ("FEDEX" over "UPS" → "FEDEXUPS"). onFocus fires
        // after the browser settles focus, so the old value is always replaced
        // by the first keystroke.
        onFocus={(e) => e.currentTarget.select()}
        onKeyDown={(e) => {
          if (e.key === 'Enter') { e.preventDefault(); commit() }
          else if (e.key === 'Escape') { e.preventDefault(); cancel() }
          // Keep ⌘/Ctrl+A inside the editor (browser-native select-all) —
          // don't let ancestor key handlers see it.
          else if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'a') { e.stopPropagation() }
        }}
        className={`w-full rounded-[5px] border border-[#412d15] bg-white px-1.5 py-0.5 text-[10.5px] text-[#1f150c] outline-none ring-1 ring-[#412d15] ${mono ? 'font-mono' : ''}`}
      />
    )
  }
  const tooltip = bad && errors && errors.length > 0 ? errors.join('\n') : value || undefined
  return (
    <button
      type="button"
      onClick={begin}
      title={tooltip}
      className={`block w-full truncate rounded-[5px] px-1.5 py-0.5 text-left text-[10.5px] transition ${mono ? 'font-mono' : ''} ${
        readOnly ? 'cursor-default text-[#8a7959]'
          : bad ? 'cursor-text bg-rose-50 text-rose-800 ring-1 ring-inset ring-rose-300 hover:ring-rose-400'
          : 'cursor-text text-[#3f3527] hover:bg-[#efe7d4]'
      }`}
    >
      {value || <span className="text-[#cdbf9f]">—</span>}
    </button>
  )
}
