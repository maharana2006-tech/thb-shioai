/**
 * PR-F4 Agent-2 — pure formatting helpers shared by the USPS Direct
 * dashboard panels.
 *
 * <p>Extracted into its own file (per the PR-F1 {@code uspsQueueFormat.ts}
 * precedent) because react-refresh Fast Refresh requires component
 * files to export only components. Panels re-export test-visible
 * helpers from here so unit tests can assert boundary conditions
 * without spinning up React.
 */

/**
 * Traffic-light coloring for the quota utilization bar.
 * <ul>
 *   <li>&lt; 60% — green (comfortable headroom)</li>
 *   <li>60–85% — amber (pay attention)</li>
 *   <li>≥ 85% — red (throttling risk)</li>
 * </ul>
 *
 * <p>NaN / infinite input falls to the green branch — the panel would
 * rather under-report severity than block the render on a bad payload.
 */
export function utilizationColor(percent: number): string {
  if (!Number.isFinite(percent) || percent < 60) return 'bg-emerald-500'
  if (percent < 85) return 'bg-amber-500'
  return 'bg-rose-500'
}

/**
 * Currency-aware formatter. Falls back to a bare number + code when
 * {@code Intl} can't parse the currency (unusual but possible for
 * synthetic test fixtures like "XXX" or hand-typed configuration).
 */
export function formatCurrency(amount: number, currency: string): string {
  try {
    return new Intl.NumberFormat(undefined, {
      style: 'currency',
      currency,
      maximumFractionDigits: 2,
    }).format(amount)
  } catch {
    return `${amount.toFixed(2)} ${currency}`
  }
}
