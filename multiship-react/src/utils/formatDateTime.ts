/**
 * One timestamp format across the app: "Sep 1, 2026, 4:20 PM". Explicit en-US
 * because a bare toLocaleString() renders DD/MM/YYYY on en-IN (and other)
 * locales, which read inconsistently next to the rest of the UI.
 *
 * NOTE: parses the string as-is. Most backend LocalDateTime values are already
 * server-local; only a few columns are stored in UTC — those must use
 * `fmtServerTs` (relativeTime.ts) instead, not this. A blanket UTC assumption
 * here shifted the local timestamps +5½h, so it is deliberately plain.
 */
export function formatDateTime(iso: string | null | undefined): string {
  if (!iso) return '—'
  try {
    return new Date(iso).toLocaleString('en-US', {
      month: 'short', day: 'numeric', year: 'numeric', hour: 'numeric', minute: '2-digit',
    })
  } catch {
    return iso
  }
}
