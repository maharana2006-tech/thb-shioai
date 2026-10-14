/**
 * One timestamp format across the app: "Sep 1, 2026, 4:20 PM". Explicit en-US
 * because a bare toLocaleString() renders DD/MM/YYYY on en-IN (and other)
 * locales, which read inconsistently next to the rest of the UI.
 */
export function formatDateTime(iso: string | null | undefined): string {
  if (!iso) return '—'
  try {
    // BUG-119 — backend serialises LocalDateTime as 'YYYY-MM-DDTHH:mm:ss.…'
    // with NO zone designator. JS parses that as LOCAL time, which showed
    // every row 5.5h behind on IST browsers. Treat a Z-less timestamp as
    // UTC (append 'Z') so it localises correctly. Values already carrying
    // a zone ('Z' / '±HH:MM') pass through unchanged.
    const s = String(iso).trim()
    const hasZone = /[Zz]$|[+-]\d{2}:?\d{2}$/.test(s)
    return new Date(hasZone ? s : `${s}Z`).toLocaleString('en-US', {
      month: 'short', day: 'numeric', year: 'numeric', hour: 'numeric', minute: '2-digit',
    })
  } catch {
    return iso
  }
}
