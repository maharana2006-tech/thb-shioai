/**
 * PR-F1 Agent-2 — human-readable duration formatting used by the
 * USPS Direct queue depth pill + the bulk-label modal's warning
 * banner. Extracted into its own file so the badge component can
 * export only the component (react-refresh Fast Refresh
 * requirement) while both the badge and the modal share the same
 * formatting rules.
 *
 * <p>Rules:
 *  - {@code < 60s}  → "under a minute"
 *  - {@code < 60m}  → "N min"
 *  - {@code < 24h}  → "N h M min" (drops the minutes when zero)
 *  - else            → "N h" (day-scale is unusual for this queue but graceful)
 *  - negative / NaN → "unknown"
 */
export function formatQueueDuration(seconds: number): string {
  if (!Number.isFinite(seconds) || seconds < 0) return 'unknown'
  if (seconds < 60) return 'under a minute'
  const totalMinutes = Math.round(seconds / 60)
  if (totalMinutes < 60) return `${totalMinutes} min`
  const hours = Math.floor(totalMinutes / 60)
  const minutes = totalMinutes % 60
  if (minutes === 0) return `${hours} h`
  return `${hours} h ${minutes} min`
}
