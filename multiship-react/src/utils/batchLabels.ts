import type { OrderImportRow } from '../api/orderImportService'

export interface LabelCounts { generated: number; pending: number; voided: number; failed: number }

/** Where a batch's labels stand, counted from its rows (the batch page). */
export function labelCountsOf(rows: OrderImportRow[]): LabelCounts {
  const c: LabelCounts = { generated: 0, pending: 0, voided: 0, failed: 0 }
  for (const r of rows) {
    const st = (r.generatedStatus ?? '').toUpperCase()
    if (st === 'GENERATED' || st === 'QUEUED_USPS') c.generated++
    else if (st === 'VOIDED') c.voided++
    else if (st === 'FAILED') c.failed++
    else c.pending++
  }
  return c
}

/**
 * Whether this row's order has a commercial invoice: only an international
 * shipment does. ponytail: "international" = destination outside the US, the
 * same rule the row's invoice icon uses; a client shipping from another
 * country would need its ship-from country here.
 */
export function hasCommercialInvoice(r: Pick<OrderImportRow, 'countryCode'>): boolean {
  const c = (r.countryCode ?? '').trim().toUpperCase()
  return c !== '' && c !== 'US'
}

/** The distinct orders behind these rows whose label is live (generated, not voided). */
export function liveOrdersOf(rows: OrderImportRow[]): number[] {
  const out = new Set<number>()
  for (const r of rows) {
    if (r.generatedOrderNo != null && (r.generatedStatus ?? '').toUpperCase() === 'GENERATED') out.add(r.generatedOrderNo)
  }
  return Array.from(out)
}
