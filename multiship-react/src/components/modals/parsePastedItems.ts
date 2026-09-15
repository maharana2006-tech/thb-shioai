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
