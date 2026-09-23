const rtf = new Intl.RelativeTimeFormat('en', { style: 'narrow' })

/** "just now" · "5m ago" · "3h ago" · "2d ago" — null when the timestamp is unset or unreadable. */
export const relativeTime = (iso?: string | null): string | null => {
  if (!iso) return null
  const secs = Math.round((Date.now() - new Date(iso).getTime()) / 1000)
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
