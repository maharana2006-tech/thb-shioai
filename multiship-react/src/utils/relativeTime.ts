const rtf = new Intl.RelativeTimeFormat('en', { style: 'narrow' })

/**
 * BUG-119 — the Java backend serialises LocalDateTime without a 'Z'
 * suffix (e.g. "2026-10-13T14:39:54.294427"), which JavaScript parses
 * as LOCAL time. Operators in IST saw timestamps 5½ hours behind
 * reality on Mail, Carrier API Log, Email Delivery Log and Writeback
 * Journal.
 *
 * Treat a Z-less ISO as UTC by appending Z before `new Date(...)`.
 * Values already carrying an offset ('Z', '+05:30', '-04') pass
 * through unchanged.
 *
 * Only for columns the backend stores in UTC (carrier API log, mail
 * providers). Everything else is server-local: use formatDateTime /
 * relativeTime.
 */
export const parseServerTs = (iso?: string | null): Date | null => {
  if (!iso) return null
  const s = String(iso).trim()
  // Already has a zone designator (Z or ±HH[:MM]) — trust it.
  const hasZone = /[Zz]$|[+-]\d{2}:?\d{2}$/.test(s)
  const d = new Date(hasZone ? s : `${s}Z`)
  return Number.isNaN(d.getTime()) ? null : d
}

/** Operator-facing timestamp formatter — assumes UTC when no zone is
 *  attached. Pass through null-ish values as empty string. */
export const fmtServerTs = (iso?: string | null): string => {
  const d = parseServerTs(iso)
  return d ? d.toLocaleString() : ''
}

/**
 * "just now" · "5m ago" · "3h ago" · "2d ago" — null when the timestamp is unset or unreadable.
 * Reads a zoneless value as server-local, like formatDateTime: that is how almost
 * every backend LocalDateTime is stored (BUG-026 — reading them as UTC put a label
 * generated a minute ago 5½h in the future, so it said "just now" for hours).
 * The few UTC-stored columns (carrier API log, mail) use parseServerTs directly.
 */
export const relativeTime = (iso?: string | null): string | null => {
  if (!iso) return null
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return null
  const secs = Math.round((Date.now() - d.getTime()) / 1000)
  if (Number.isNaN(secs)) return null
  if (secs < 60) return 'just now'
  const mins = Math.round(secs / 60)
  if (mins < 60) return rtf.format(-mins, 'minute')
  const hrs = Math.round(mins / 60)
  if (hrs < 24) return rtf.format(-hrs, 'hour')
  return rtf.format(-Math.round(hrs / 24), 'day')
}

/** "45s" · "2m 03s" · "1h 04m" — how long something took or has been running. */
export const formatDuration = (ms: number): string | null => {
  if (!Number.isFinite(ms) || ms < 0) return null
  const secs = Math.round(ms / 1000)
  if (secs < 60) return `${secs}s`
  const mins = Math.floor(secs / 60)
  if (mins < 60) return `${mins}m ${String(secs % 60).padStart(2, '0')}s`
  return `${Math.floor(mins / 60)}h ${String(mins % 60).padStart(2, '0')}m`
}
