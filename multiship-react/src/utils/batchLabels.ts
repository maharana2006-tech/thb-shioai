import type { OrderImportRow } from '../api/orderImportService'

/** The distinct orders behind these rows whose label is live (generated, not voided). */
export function liveOrdersOf(rows: OrderImportRow[]): number[] {
  const out = new Set<number>()
  for (const r of rows) {
    if (r.generatedOrderNo != null && (r.generatedStatus ?? '').toUpperCase() === 'GENERATED') out.add(r.generatedOrderNo)
  }
  return Array.from(out)
}
