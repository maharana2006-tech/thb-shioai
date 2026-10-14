import { describe, expect, it } from 'vitest'
import { parseServerTs, relativeTime } from './relativeTime'

/** A zoneless backend time as the server writes it: local wall-clock, no offset. */
const localIso = (d: Date) => {
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}T${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}

describe('relativeTime', () => {
  it('reads a zoneless server time as local (BUG-026)', () => {
    const tenMinutesAgo = new Date(Date.now() - 10 * 60_000)
    expect(relativeTime(localIso(tenMinutesAgo))).toMatch(/10/)
  })

  it('still honours an explicit zone', () => {
    expect(relativeTime(new Date(Date.now() - 3 * 3_600_000).toISOString())).toMatch(/3/)
  })

  it('leaves the UTC parser for the columns stored in UTC', () => {
    expect(parseServerTs('2026-10-14T10:15:34')?.toISOString()).toBe('2026-10-14T10:15:34.000Z')
  })
})
