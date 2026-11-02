import type { RowData } from '@tanstack/react-table'
import type { ColumnDef } from '../components/workspace/AdvancedDataTable'

/**
 * BUG-066 / 067 / 076 — several screens exported only the rows on the
 * current page. One loop fetches every page (the Orders list's pattern)
 * and one matrix builder turns column definitions into CSV rows, so a
 * page adds an `onExport` instead of its own copy of either.
 */
export interface PageLike<T> {
  content?: T[] | null
  totalPages?: number | null
  totalElements?: number | null
}

export const EXPORT_ROW_CAP = 50_000

export async function fetchAllPages<T>(
  fetchPage: (page: number, size: number) => Promise<PageLike<T> | null | undefined>,
  opts: { size?: number; cap?: number; onProgress?: (collected: number, total: number) => void } = {},
): Promise<T[]> {
  const size = opts.size ?? 500
  const cap = opts.cap ?? EXPORT_ROW_CAP
  const out: T[] = []
  for (let page = 0; ; page++) {
    const res = await fetchPage(page, size)
    const batch = res?.content ?? []
    out.push(...batch)
    const total = res?.totalElements ?? out.length
    opts.onProgress?.(out.length, total)
    const lastPage = res?.totalPages != null ? page >= res.totalPages - 1 : batch.length < size
    if (lastPage || batch.length === 0 || out.length >= cap) break
  }
  return out.slice(0, cap)
}

export interface ExportColMeta<T> {
  exportable?: boolean
  exportAlways?: boolean
  exportValue?: (row: T) => unknown
  headerLabel?: string
}

/** Header + one line per row, honouring the visible columns and each column's export meta. */
export function columnsToCsvMatrix<T extends RowData>(
  columns: ColumnDef<T, unknown>[],
  rows: T[],
  visibleIds?: string[],
  extraLeading: { header: string; value: (row: T) => unknown }[] = [],
): string[][] {
  const visible = new Set(visibleIds ?? [])
  const chosen = columns.filter((col) => {
    const meta = col.meta as ExportColMeta<T> | undefined
    if (meta?.exportable === false) return false
    const id = col.id ?? (col as { accessorKey?: string }).accessorKey
    if (!id) return false
    if (meta?.exportAlways) return true
    return visible.size === 0 || visible.has(String(id))
  })
  const header = [
    ...extraLeading.map((e) => e.header),
    ...chosen.map((col) => {
      const meta = col.meta as ExportColMeta<T> | undefined
      if (meta?.headerLabel) return meta.headerLabel
      return typeof col.header === 'string' ? col.header : String(col.id ?? '')
    }),
  ]
  const matrix: string[][] = [header]
  rows.forEach((row, index) => {
    matrix.push([
      ...extraLeading.map((e) => cell(e.value(row))),
      ...chosen.map((col) => {
        const meta = col.meta as ExportColMeta<T> | undefined
        if (meta?.exportValue) return cell(meta.exportValue(row))
        const key = (col as { accessorKey?: string }).accessorKey
        if (key) return cell((row as Record<string, unknown>)[key])
        const fn = (col as { accessorFn?: (r: T, i: number) => unknown }).accessorFn
        return fn ? cell(fn(row, index)) : ''
      }),
    ])
  })
  return matrix
}

const cell = (v: unknown): string => (v == null ? '' : String(v))
