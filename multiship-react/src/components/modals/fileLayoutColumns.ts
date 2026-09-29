import type { FileColumn } from '../../api/orderImportService'

/**
 * The upload grid's columns for a file: in the file's own order and names when it
 * isn't in our field layout (CLIENT_ID, ATTENTION, … for the client layout), else
 * null (the grid's standard columns). Columns with no field of ours are left out —
 * they show among the file's extra columns, as before.
 */
export function fileLayoutColumns<C extends { key: string }>(
  fileColumns: FileColumn[] | null | undefined,
  byKey: Record<string, C>,
): (C & { name: string })[] | null {
  if (!fileColumns?.length) return null
  const ourLayout = fileColumns.every((c) => !c.field || c.field.toLowerCase() === c.name.trim().toLowerCase())
  if (ourLayout) return null
  const seen = new Set<string>()
  const cols: (C & { name: string })[] = []
  for (const c of fileColumns) {
    const base = c.field ? byKey[c.field] : undefined
    if (!base || seen.has(base.key)) continue
    seen.add(base.key)
    cols.push({ ...base, name: c.name })
  }
  return cols.length ? cols : null
}
