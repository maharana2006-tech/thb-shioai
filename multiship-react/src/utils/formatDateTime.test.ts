import { describe, expect, it } from 'vitest'
import { localDateIso } from './formatDateTime'

describe('localDateIso', () => {
  it('is the local calendar date, not the UTC one', () => {
    const lateEvening = new Date(2026, 9, 14, 23, 30) // 14 Oct, 23:30 local
    expect(localDateIso(lateEvening)).toBe('2026-10-14')
  })
})
